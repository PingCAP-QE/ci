import { S3, S3Bucket, S3Object } from "https://deno.land/x/s3@0.5.0/mod.ts";
import { parse } from "https://deno.land/std@0.153.0/flags/mod.ts";
import * as tar from "https://deno.land/std@0.153.0/archive/tar.ts";
import { ensureFile } from "https://deno.land/std@0.153.0/fs/ensure_file.ts";
import { ensureDir } from "https://deno.land/std@0.153.0/fs/ensure_dir.ts";
import { copy } from "https://deno.land/std@0.153.0/streams/conversion.ts";
import { readerFromStreamReader } from "https://deno.land/std@0.153.0/streams/conversion.ts";

/**
 * CLI args
 * --key-prefix
 */
await main();

// ---------------------implement-------------------

async function main() {
  const bucket = getBucket();
  await restoreToDir(
    bucket,
    "white",
    "git/pingcap/enterprise-plugin/rev-xxxx",
    ["git/pingcap/enterprise-plugin/rev-"],
  );
}

function getBucket() {
  const bucketName = Deno.env.get("BUCKET_NAME")!;
  // Create a S3 instance.
  const s3 = new S3({
    accessKeyID: Deno.env.get("AWS_ACCESS_KEY_ID")!,
    secretKey: Deno.env.get("AWS_SECRET_ACCESS_KEY")!,
    region: Deno.env.get("BUCKET_REGION") || "ci",
    endpointURL: `http://${Deno.env.get("BUCKET_HOST")}:${
      Deno.env.get("BUCKET_PORT")
    }`!,
  });

  return s3.getBucket(bucketName);
}

function save(bucket: S3Bucket, path: string, key: string, filter?: string) {
  // tar file
}

async function restoreToDir(
  bucket: S3Bucket,
  path: string,
  key: string,
  restoreKeys?: string[],
) {
  await Deno.mkdir(path, { recursive: true });
  const cwd = Deno.cwd();

  Deno.chdir(path);
  await restore(bucket, key, restoreKeys).finally(() => Deno.chdir(cwd));
}

async function restore(
  bucket: S3Bucket,
  key: string,
  restoreKeys?: string[],
) {
  const restoreKey = await getRestoreKey(bucket, key, restoreKeys);
  console.debug(restoreKey);

  if (!restoreKey) {
    console.log("cache missed");
    return;
  }

  console.debug(`key: ${restoreKey}`);
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

    const { fileName, type } = entry;
    console.log(type, fileName);
  }
}

async function getRestoreKey(
  bucket: S3Bucket,
  key: string,
  restoreKeys?: string[],
) {
  const ret = await bucket.headObject(key);
  if (ret) {
    console.debug("key hit");
    console.debug(ret);
    return;
  }
  console.debug("key miss");

  // restore from other objects.
  if (!restoreKeys) {
    return;
  }

  const list = await bucket.listObjects({
    prefix: restoreKeys?.[0],
  });
  console.debug(list);

  if (!list || list.keyCount === 0) {
    console.log("no other objects");
    return;
  }

  // sort by modified time, first is latest modified item.
  const orderedList = list.contents?.sort((a: S3Object, b: S3Object) => {
    return (a.lastModified! === b.lastModified!)
      ? 0
      : (a.lastModified! > b.lastModified! ? -1 : 1);
  })!;

  console.debug(orderedList);
  return orderedList[0].key;
}
