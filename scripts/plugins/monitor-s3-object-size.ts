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

/**
 * CLI args
 * --path="path to monitor in s3"
 * --threshold-mb="size threshold in MB"
 * --feishu-webhook="feishu webhook url"
 * --feishu-secret="feishu webhook secret" (optional)
 */
await main();

async function main() {
  const args = parse(Deno.args);
  const path = args["path"] || "";
  const thresholdMB = args["threshold-mb"] || 100;
  const thresholdBytes = thresholdMB * 1024 * 1024;

  // 飞书配置
  const feishuConfig: FeishuConfig | undefined = args["feishu-webhook"]
    ? {
      webhook: args["feishu-webhook"] as string,
      secret: args["feishu-secret"] as string | undefined,
    }
    : undefined;

  const bucket = getBucket();
  await monitorObjectSizes(bucket, path, thresholdBytes, feishuConfig);
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
) {
  const objects: ObjectSizeInfo[] = [];
  
  console.log("\n=== Scan Configuration ===");
  console.log(`Scanning objects in path: "${prefix}"`);
  console.log(`Size threshold: ${formatSize(thresholdBytes)}`);
  
  try {
    // 测试bucket访问
    console.log("\n=== Testing Bucket Access ===");
    const testList = await bucket.listObjects({ prefix, maxKeys: 1 });
    console.log(`Test list result: ${JSON.stringify(testList, null, 2)}`);
    
    // 收集所有对象信息
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

  // 分离超过和未超过阈值的对象
  const oversizedObjects = objects.filter((obj) => obj.size > thresholdBytes);
  const normalObjects = objects.filter((obj) => obj.size <= thresholdBytes);

  // 按大小降序排序
  oversizedObjects.sort((a, b) => b.size - a.size);
  normalObjects.sort((a, b) => b.size - a.size);

  // 输出统计信息
  console.log("\n=== Summary ===");
  console.log(`Total objects: ${objects.length}`);
  console.log(`Oversized objects: ${oversizedObjects.length}`);
  console.log(`Normal sized objects: ${normalObjects.length}`);

  // 输出超过阈值的对象
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

  // 输出正常大小的对象
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

  // 如果有超大文件并且配置了飞书webhook，发送告警
  if (oversizedObjects.length > 0 && feishuConfig) {
    await sendFeishuAlert(oversizedObjects, thresholdBytes, prefix, feishuConfig);
  }
}

async function sendFeishuAlert(
  oversizedObjects: ObjectSizeInfo[],
  thresholdBytes: number,
  prefix: string,
  feishuConfig: FeishuConfig,
) {
  console.log("\n=== Sending Feishu Alert ===");

  const timestamp = Math.floor(Date.now() / 1000);
  let sign = "";

  if (feishuConfig.secret) {
    // 计算签名
    const signString = `${timestamp}\n${feishuConfig.secret}`;
    const encoder = new TextEncoder();
    const data = encoder.encode(signString);
    const hash = await crypto.subtle.digest("SHA-256", data);
    sign = base64Encode(hash);
  }

  // 构建消息内容
  const content = {
    msg_type: "post",
    content: {
      post: {
        zh_cn: {
          title: "🚨 S3存储大小告警",
          content: [
            [
              {
                tag: "text",
                text: `检测到以下文件超过大小阈值 ${formatSize(thresholdBytes)}:\n`,
              },
            ],
            [
              {
                tag: "text",
                text: `📁 扫描路径: ${prefix}\n`,
              },
            ],
            [
              {
                tag: "text",
                text: `📊 超大文件数量: ${oversizedObjects.length}\n\n`,
              },
            ],
            ...oversizedObjects.map((obj) => [
              {
                tag: "text",
                text: `📄 ${obj.key}\n大小: ${formatSize(obj.size)}\n修改时间: ${
                  obj.lastModified.toISOString()
                }\n\n`,
              },
            ]),
          ],
        },
      },
    },
  };

  try {
    const url = new URL(feishuConfig.webhook);
    if (feishuConfig.secret) {
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
        `Failed to send Feishu alert: ${response.status} ${response.statusText}`,
      );
    }

    const responseData = await response.json();
    console.log("Feishu alert sent successfully:", responseData);
  } catch (error) {
    console.error("Error sending Feishu alert:", error);
  }
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
