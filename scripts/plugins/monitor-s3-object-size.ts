import { S3, S3Bucket, S3Object } from "https://deno.land/x/s3@0.5.0/mod.ts";
import { parse } from "https://deno.land/std@0.153.0/flags/mod.ts";
import { encode as base64Encode } from "https://deno.land/std@0.153.0/encoding/base64.ts";

interface ObjectSizeInfo {
  key: string;
  size: number;
  lastModified: Date;
}

interface FeishuConfig {
  webhook: string;
  secret?: string;
}

interface CleanupConfig {
  enabled: boolean;
  dryRun: boolean;  // Dry run mode, no actual deletion
  minAge?: number;  // Minimum file age (days), optional
}

/**
 * CLI args
 * --path="path to monitor in s3"
 * --threshold-mb="size threshold in MB"
 * --feishu-webhook="feishu webhook url"
 * --feishu-secret="feishu webhook secret" (optional)
 * --cleanup  Enable automatic cleanup (disabled by default)
 * --dry-run  Cleanup dry run mode, no actual deletion (disabled by default)
 * --min-age  Minimum file age for cleanup in days (disabled by default)
 */
await main();

async function main() {
  const args = parse(Deno.args);
  const path = args["path"] || "";
  const thresholdMB = args["threshold-mb"] || 100;
  const thresholdBytes = thresholdMB * 1024 * 1024;

  // Feishu configuration
  const feishuConfig: FeishuConfig | undefined = args["feishu-webhook"]
    ? {
      webhook: args["feishu-webhook"] as string,
      secret: args["feishu-secret"] as string | undefined,
    }
    : undefined;

  // Cleanup configuration
  const cleanupConfig: CleanupConfig = {
    enabled: args["cleanup"] === true,
    dryRun: args["dry-run"] === true,
    minAge: args["min-age"] ? Number(args["min-age"]) : undefined,
  };

  const bucket = getBucket();
  await monitorObjectSizes(bucket, path, thresholdBytes, feishuConfig, cleanupConfig);
}

function getBucket() {
  const bucketName = Deno.env.get("BUCKET_NAME")!;
  const endpoint = Deno.env.get("BUCKET_ENDPOINT")!;
  const region = Deno.env.get("BUCKET_REGION") || "ci";

  const s3 = new S3({
    accessKeyID: Deno.env.get("AWS_ACCESS_KEY_ID")!,
    secretKey: Deno.env.get("AWS_SECRET_ACCESS_KEY")!,
    region: region,
    endpointURL: endpoint,
  });

  return s3.getBucket(bucketName);
}

async function monitorObjectSizes(
  bucket: S3Bucket,
  prefix: string,
  thresholdBytes: number,
  feishuConfig?: FeishuConfig,
  cleanupConfig?: CleanupConfig,
) {
  const objects: ObjectSizeInfo[] = [];

  console.log("\n=== Scan Configuration ===");
  console.log(`Scanning objects in path: "${prefix}"`);
  console.log(`Size threshold: ${formatSize(thresholdBytes)}`);

  try {
    // Test bucket access
    console.log("\n=== Testing Bucket Access ===");
    const testList = await bucket.listObjects({ prefix, maxKeys: 1 });
    console.log(`Test list result: ${JSON.stringify(testList, null, 2)}`);

    // Collect all object information
    console.log("\n=== Starting Object Scan ===");
    for await (const obj of bucket.listAllObjects({ prefix, batchSize: 1000 })) {
      console.log(`Found object: ${obj.key} (${obj.size} bytes)`);
      if (obj.size !== undefined && obj.key && obj.lastModified) {
        objects.push({
          key: obj.key,
          size: obj.size,
          lastModified: obj.lastModified,
        });
      } else {
        console.log(`Skipped object due to missing properties:`, obj);
      }
    }
  } catch (error) {
    console.error("Error during bucket operations:", error);
    throw error;
  }

  // Separate objects that exceed and don't exceed the threshold
  const oversizedObjects = objects.filter((obj) => obj.size > thresholdBytes);
  const normalObjects = objects.filter((obj) => obj.size <= thresholdBytes);

  // Sort by size in descending order
  oversizedObjects.sort((a, b) => b.size - a.size);
  normalObjects.sort((a, b) => b.size - a.size);

  // Output summary information
  console.log("\n=== Summary ===");
  console.log(`Total objects: ${objects.length}`);
  console.log(`Oversized objects: ${oversizedObjects.length}`);
  console.log(`Normal sized objects: ${normalObjects.length}`);

  // Output oversized objects
  if (oversizedObjects.length > 0) {
    console.log("\n=== Oversized Objects ===");
    for (const obj of oversizedObjects) {
      console.log(
        `[WARNING] ${obj.key}\n\tSize: ${formatSize(obj.size)}\n\tLast Modified: ${
          obj.lastModified.toISOString()
        }`,
      );
    }
  }

  // Output normal sized objects
  if (normalObjects.length > 0) {
    console.log("\n=== Normal Sized Objects ===");
    for (const obj of normalObjects) {
      console.log(
        `${obj.key}\n\tSize: ${formatSize(obj.size)}\n\tLast Modified: ${
          obj.lastModified.toISOString()
        }`,
      );
    }
  }

  let cleanupResults: Array<{ key: string; success: boolean; error?: string }> = [];

  // If cleanup is enabled, process oversized objects
  if (cleanupConfig?.enabled && oversizedObjects.length > 0) {
    cleanupResults = await cleanupOversizedObjects(bucket, oversizedObjects, cleanupConfig);
  }

  // If a Feishu webhook is configured, send a comprehensive report
  if (feishuConfig && oversizedObjects.length > 0) {
    await sendFeishuReport(
      oversizedObjects,
      thresholdBytes,
      prefix,
      cleanupConfig,
      cleanupResults,
      feishuConfig
    );
  }
}

async function sendFeishuReport(
  oversizedObjects: ObjectSizeInfo[],
  thresholdBytes: number,
  prefix: string,
  cleanupConfig?: CleanupConfig,
  cleanupResults?: Array<{ key: string; success: boolean; error?: string }>,
  feishuConfig?: FeishuConfig,
) {
  console.log("\n=== Sending Feishu Report ===");

  const timestamp = Math.floor(Date.now() / 1000);
  let sign = "";

  if (feishuConfig?.secret) {
    const signString = `${timestamp}\n${feishuConfig.secret}`;
    const encoder = new TextEncoder();
    const data = encoder.encode(signString);
    const hash = await crypto.subtle.digest("SHA-256", data);
    sign = base64Encode(hash);
  }

  // Build message content
  const content = {
    msg_type: "post",
    content: {
      post: {
        zh_cn: {
          title: `🚨 S3 storage monitoring${cleanupConfig?.enabled ? ' and cleanup' : ''} report`,
          content: [
            // Monitoring information
            [
              {
                tag: "text",
                text: `The following files have exceeded the size threshold ${formatSize(thresholdBytes)}:\n`,
              },
            ],
            [
              {
                tag: "text",
                text: `📁 Scan path: ${prefix}\n`,
              },
            ],
            [
              {
                tag: "text",
                text: `📊 Number of oversized files: ${oversizedObjects.length}\n\n`,
              },
            ],
            // Cleanup configuration information (if enabled)
            ...(cleanupConfig?.enabled ? [
              [
                {
                  tag: "text",
                  text: `🧹 Cleanup configuration:\n` +
                    `Mode: ${cleanupConfig.dryRun ? "Dry run mode" : "Active"}\n` +
                    `${cleanupConfig.minAge ? `Minimum file age: ${cleanupConfig.minAge} days\n` : ''}\n`,
                },
              ],
            ] : []),
            // File list
            ...oversizedObjects.map((obj) => {
              const cleanupResult = cleanupResults?.find(r => r.key === obj.key);
              return [
                {
                  tag: "text",
                  text: `📄 ${obj.key}\n` +
                    `Size: ${formatSize(obj.size)}\n` +
                    `Last Modified: ${obj.lastModified.toISOString()}\n` +
                    (cleanupResult ?
                      `Cleanup status: ${cleanupResult.success ? '✅ Cleaned' : `❌ Cleanup failed (${cleanupResult.error})`}\n`
                      : '') +
                    `\n`,
                },
              ];
            }),
          ],
        },
      },
    },
  };

  try {
    const url = new URL(feishuConfig!.webhook);
    if (feishuConfig?.secret) {
      url.searchParams.set("timestamp", timestamp.toString());
      url.searchParams.set("sign", sign);
    }

    const response = await fetch(url.toString(), {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(content),
    });

    if (!response.ok) {
      throw new Error(
        `Failed to send Feishu report: ${response.status} ${response.statusText}`,
      );
    }

    const responseData = await response.json();
    console.log("Feishu report sent successfully:", responseData);
  } catch (error) {
    console.error("Error sending Feishu report:", error);
  }
}

async function cleanupOversizedObjects(
  bucket: S3Bucket,
  objects: ObjectSizeInfo[],
  config: CleanupConfig,
) {
  console.log("\n=== Cleanup Process ===");
  console.log(`Mode: ${config.dryRun ? "Dry Run (no actual deletion)" : "Active"}`);
  if (config.minAge) {
    console.log(`Minimum age requirement: ${config.minAge} days`);
  }

  const now = new Date();
  let deletedCount = 0;
  let skippedCount = 0;
  const deletionResults: Array<{ key: string; success: boolean; error?: string }> = [];

  for (const obj of objects) {
    console.log(`\nProcessing: ${obj.key}`);

    // Check file age
    if (config.minAge) {
      const ageInDays = (now.getTime() - obj.lastModified.getTime()) / (1000 * 60 * 60 * 24);
      if (ageInDays < config.minAge) {
        console.log(`Skipped: File age (${ageInDays.toFixed(1)} days) is less than minimum requirement (${config.minAge} days)`);
        skippedCount++;
        continue;
      }
    }

    try {
      if (!config.dryRun) {
        await bucket.deleteObject(obj.key);
        console.log(`Deleted: ${obj.key}`);
        deletionResults.push({ key: obj.key, success: true });
      } else {
        console.log(`[DRY RUN] Would delete: ${obj.key}`);
        deletionResults.push({ key: obj.key, success: true });
      }
      deletedCount++;
    } catch (error) {
      console.error(`Failed to delete ${obj.key}:`, error);
      deletionResults.push({ key: obj.key, success: false, error: error.message });
    }
  }

  console.log("\n=== Cleanup Summary ===");
  console.log(`Total processed: ${objects.length}`);
  console.log(`Deleted: ${deletedCount}`);
  console.log(`Skipped: ${skippedCount}`);

  return deletionResults;
}

function formatSize(bytes: number): string {
  const units = ["B", "KB", "MB", "GB", "TB"];
  let size = bytes;
  let unitIndex = 0;

  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }

  return `${size.toFixed(2)} ${units[unitIndex]}`;
}
