import * as tar from "https://deno.land/std@0.153.0/archive/tar.ts";
import { parse } from "https://deno.land/std@0.153.0/flags/mod.ts";
import { ensureDir } from "https://deno.land/std@0.153.0/fs/ensure_dir.ts";
import { ensureFile } from "https://deno.land/std@0.153.0/fs/ensure_file.ts";
import { walk } from "https://deno.land/std@0.153.0/fs/walk.ts";
import {
  copy,
  readableStreamFromReader,
  readerFromStreamReader,
} from "https://deno.land/std@0.153.0/streams/conversion.ts";
import { S3, S3Bucket, S3Object } from "https://deno.land/x/s3@0.5.0/mod.ts";
import { ClientOptions } from "https://deno.land/x/s3_lite_client@0.2.0/client.ts";
import { ServerError } from "https://deno.land/x/s3_lite_client@0.2.0/errors.ts";
import { S3Client } from "https://deno.land/x/s3_lite_client@0.2.0/mod.ts";
import * as transform from "https://deno.land/x/transform@v0.4.0/mod.ts";

const DEFAULT_KEEP_COUNT = 0;
const UPLOAD_PART_BYTES = 64 * 1024 * 1024;
/**
 * CLI args
 * --op=backup/restore
 * --path="backup from or restore to dir path", default pwd
 * --key="a-key-to-save-or-restore"
 * --key-prefix="key prefixs to restore", optional
 * --filter="filter to backup" , default all.
 * --keep-count="10", default DEFAULT_KEEP_COUNT.
 */
await main();

// ---------------------implement-------------------
async function main() {
  const args = parse(Deno.args);

  if ("op" in args) {
    const path = args["path"] || ".";
    const op = args["op"];
    const key = args["key"];
    const keyPrefix = args["key-prefix"];

    const bucket = getBucket();
    switch (op) {
      case "restore":
        await restoreToDir(bucket, path, key!, args["key-prefix"]);
        break;
      case "remove":
        await bucket.deleteObject(key!);
        console.log(`deleted ${key}`)
        break;
      case "shrink":
        {
          const keepSize = args["keep-size-g"] as number;
          await shrinkBucketToSize(
            bucket,
            keepSize * 1000 * 1000 * 1000,
            keyPrefix,
          );
        }
        break;
      case "backup":
        {
          let keepCount = DEFAULT_KEEP_COUNT;
          if (typeof (args["keep-count"]) === "number") {
            keepCount = args["keep-count"];
          }
          await save(bucket, path, key!, args["filter"], keyPrefix, keepCount)
            .then(() => console.log("backup succeed."))
            .catch((e) => {
              console.error(e);
              throw e;
            });
        }
        break;
      default:
        throw new Error(`not supported operation: ${args["op"]}`);
    }
  } else {
    throw new Error("op and key args are required");
  }
}

function getBucket() {
  const bucketName = Deno.env.get("BUCKET_NAME")!;
  // Create a S3 instance.
  const port = Number(Deno.env.get("BUCKET_PORT") || "80");
  let endpointURL = `http://${Deno.env.get("BUCKET_HOST")!}`;
  if (port != 80) {
    endpointURL += `:${port}`;
  }

  const s3 = new S3({
    accessKeyID: Deno.env.get("AWS_ACCESS_KEY_ID")!,
    secretKey: Deno.env.get("AWS_SECRET_ACCESS_KEY")!,
    region: Deno.env.get("BUCKET_REGION") || "ci",
    endpointURL,
  });

  return s3.getBucket(bucketName);
}

function getBucketForBigUpload() {
  const params: ClientOptions = {
    endPoint: Deno.env.get("BUCKET_HOST")!,
    region: Deno.env.get("BUCKET_REGION") || "ci",
    accessKey: Deno.env.get("AWS_ACCESS_KEY_ID")!,
    secretKey: Deno.env.get("AWS_SECRET_ACCESS_KEY")!,
    bucket: Deno.env.get("BUCKET_NAME")!,
    useSSL: false,
  };

  const port = Number(Deno.env.get("BUCKET_PORT") || "80");
  if (port != 80) params.port = port;

  return new S3Client(params);
}

async function save(
  bucket: S3Bucket,
  putBucket: S3Client,
  path: string,
  key: string,
  filter?: string,
  cleanPrefix?: string,
  cleanKeepCount?: number,
) {
  const ret = await bucket.headObject(key);
  if (ret) {
    console.debug("object existed, skip");
    return key;
  }

  // first clean space, then push new one.
  if (cleanPrefix && cleanKeepCount) {
    await cleanOld(bucket, cleanPrefix, cleanKeepCount);
  }

  const cwd = Deno.cwd();
  Deno.chdir(path);
  const stream = await newBackupReadableStream(filter);

  await putBucket.putObject(key, stream, {
    metadata: {
      contentType: "application/x-tar",
      contentEncoding: "gzip",
      cacheControl: "public, no-transform",
    },
    partSize: UPLOAD_PART_BYTES,
  })
    .catch((e: ServerError) => {
      const { code, statusCode, cause, bucketName, key, resource } = e;
      console.error({ code, statusCode, cause, bucketName, key, resource });
      throw e
    })
    .finally(() => Deno.chdir(cwd));

  const newRet = await bucket.headObject(key).catch((e: ServerError) => {
    const { code, statusCode, cause, bucketName, key, resource } = e;
    console.error({ code, statusCode, cause, bucketName, key, resource });
    console.trace(e);
    throw e
  });
  console.debug(`uploaded item:`);
  console.debug(newRet)
}

async function newBackupReadableStream(filter?: string) {
  const { GzEncoder } = transform.Transformers;
  const to = new tar.Tar();
  const matchRegs = filter ? [new RegExp(filter)] : undefined;

  for await (const entry of walk("./", { match: matchRegs })) {
    if (!entry.isFile) {
      continue;
    }

    await to.append(entry.path, { filePath: entry.path });
  }

  const reader = transform.pipeline(to.getReader(), new GzEncoder());
  return readableStreamFromReader(reader);
}

async function restoreToDir(
  bucket: S3Bucket,
  path: string,
  key: string,
  keyPrefix?: string,
) {
  await Deno.mkdir(path, { recursive: true });
  const cwd = Deno.cwd();

  Deno.chdir(path);
  await restore(bucket, key, keyPrefix).finally(() => Deno.chdir(cwd));
}

async function restore(
  bucket: S3Bucket,
  key: string,
  keyPrefix?: string,
) {
  const restoreKey = await getRestoreKey(bucket, key, keyPrefix);
  if (!restoreKey) {
    console.log("cache missed");
    return;
  }

  console.log(`will restore from key: ${restoreKey}`);
  const ret = await bucket.getObject(restoreKey);
  if (!ret) {
    console.error(`get content failed for key: ${restoreKey}`);
    return;
  }

  const denoReader = readerFromStreamReader(ret.body.getReader());
  const untar = new tar.Untar(denoReader);

  for await (const entry of untar) {
    if (entry.type === "directory") {
      await ensureDir(entry.fileName);
      continue;
    }

    await ensureFile(entry.fileName);
    const file = await Deno.open(entry.fileName, { write: true });
    // <entry> is a reader.
    await copy(entry, file);
  }
}

async function getRestoreKey(
  bucket: S3Bucket,
  key: string,
  keyPrefix?: string,
) {
  const ret = await bucket.headObject(key);
  if (ret) {
    console.debug("key existed");
    return key;
  }
  console.debug("key not existed");

  // restore from other objects.
  if (!keyPrefix) {
    return;
  }

  const orderedList = await listObjectsByModifiedTime(bucket, keyPrefix);
  if (orderedList) {
    return orderedList[0].key;
  } else {
    console.log("none objects to restore");
  }
}

async function listObjectsByModifiedTime(bucket: S3Bucket, prefix: string) {
  const list = await bucket.listObjects({ prefix });
  if (!list || list.keyCount === 0) {
    return;
  }

  // sort by modified time, first is latest modified item.
  return list.contents?.sort((a: S3Object, b: S3Object) => {
    return (a.lastModified! === b.lastModified!)
      ? 0
      : (a.lastModified! > b.lastModified! ? -1 : 1);
  })!;
}

async function cleanOld(
  bucket: S3Bucket,
  keyPrefix: string,
  keepCount: number,
) {
  if (keepCount <= 0) {
    console.debug("skip to clean because keep count set <= 0");
    return;
  }

  const list = await listObjectsByModifiedTime(bucket, keyPrefix);
  if (!list) {
    console.log(`none objects founds by prefix: ${keyPrefix}`);
    return;
  }

  if (list.length > keepCount) {
    for await (const obj of list.slice(keepCount)) {
      console.debug(`deleting ${obj.key!}`);
      await bucket.deleteObject(obj.key!);
    }
  }
}

async function shrinkBucketToSize(
  bucket: S3Bucket,
  keepSize: number,
  keyPrefix = "",
) {
  if (keepSize <= 0) {
    console.debug("skip to clean because keep size set <= 0");
    return;
  }

  const list = await listObjectsByModifiedTime(bucket, keyPrefix);
  if (!list) {
    console.log(`none objects founds by prefix ${keyPrefix}`);
    return;
  }

  let toDeletePos = -1;
  let size = 0;

  for (let i = 0; i < list.length; i++) {
    const obj = list[i];
    console.debug(`${obj.key} => modified: ${obj.lastModified}, size: ${obj.size}bytes`);

    if (obj.size) {
      size += obj.size;
    }

    if (size >= keepSize) {
      toDeletePos = i;
      break;
    }
  }

  if (toDeletePos <= 0) {
    console.info(`no need to shrink, total size with prefix "${keyPrefix}" is ${size} bytes.`);
    return;
  }

  for (const obj of list.slice(toDeletePos)) {
    console.debug(`deleting key: ${obj.key}, modified: ${obj.lastModified}`);
    await bucket.deleteObject(obj.key!);
  }

  console.log(`deleted ${list.length - toDeletePos} objects.`);
}
