#!/usr/bin/env -S deno run --allow-read --allow-write --allow-net
import * as yaml from "jsr:@std/yaml@1.0.5";
import { parseArgs } from "jsr:@std/cli@1.0.9";
import { retryAsync } from "https://deno.land/x/retry@v2.0.0/mod.ts";

interface Rule {
  description?: string;
  tags_regex: string[];
  dest_repositories: string[];
  constant_tags?: string[];
  tag_regex_replace?: string;
}

interface Config {
  [sourceRepo: string]: Rule[];
}

function getDeliveryTargetImages(imageUrlWithTag: string, config: Config) {
  const [imageUrl, tag] = imageUrlWithTag.split(":");
  const ret: string[] = [];

  // Retrieve rules for the source repository from the YAML config
  (config[imageUrl] || [])
    .filter((r) => r.tags_regex.some((regex) => new RegExp(regex).test(tag)))
    .forEach((rule) => {
      const {
        dest_repositories,
        constant_tags = [],
        tag_regex_replace = "",
        tags_regex = [],
      } = rule;

      for (const destRepo of dest_repositories) {
        ret.push(`${destRepo}:${tag}`);
        for (const constTag of constant_tags) {
          ret.push(`${destRepo}:${constTag}`);
        }
        if (tag_regex_replace != "") {
          const converted = tag.replace(
            new RegExp(tags_regex[0]),
            tag_regex_replace,
          );
          ret.push(`${destRepo}:${converted}`);
        }
      }
    });

  return ret;
}

async function main(
  srcImagesFile: string,
  configFileOrUrl: string,
  outFile: string,
) {
  // Read source image list
  const srcImages = yaml.parse(
    await Deno.readTextFile(srcImagesFile),
  ) as string[];

  // Read delivery config
  let yamlContent: string;
  // if yamlFile is a url format, fetch it.
  if (configFileOrUrl.startsWith("http")) {
    const url = new URL(configFileOrUrl);
    const response = await retryAsync(
      async () => await fetch(url),
      { delay: 1000, maxTry: 5 },
    );
    const text = await response.text();
    yamlContent = text;
  } else {
    yamlContent = await Deno.readTextFile(configFileOrUrl);
  }

  const config = yaml.parse(yamlContent) as { image_copy_rules: Config };
  const dstImages = srcImages.map((srcImage) =>
    getDeliveryTargetImages(srcImage, config.image_copy_rules)
  ).flat();

  // Save results.
  const contentStr = yaml.stringify(dstImages);
  await Deno.writeTextFile(outFile, contentStr, {
    create: true,
    append: false,
  });
  console.info("✅ target image urls are saved in ", outFile);
}

// Parse command-line arguments
const {
  src_url_list,
  config = "./packages/delivery.yaml",
  save_to = "./delivery-target-images.yaml",
} = parseArgs(Deno.args);

// Example usage
await main(src_url_list, config, save_to);
Deno.exit(0);
