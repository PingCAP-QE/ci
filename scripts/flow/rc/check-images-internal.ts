#!/usr/bin/env -S deno run --allow-run --allow-net --allow-write
import * as yaml from "jsr:@std/yaml@1.0.5";
import { parseArgs } from "jsr:@std/cli@1.0.6";
import { Octokit } from "https://esm.sh/octokit@4.0.2?dts";
import {
  greaterOrEqual,
  lessThanOrEqual,
  parse as parseSemver,
} from "jsr:@std/semver@^1.0.4";

const platforms = [
  "linux/amd64",
  "linux/arm64",
];

const imageMap: Record<string, GitRepoImageMap> = {
  comunity: {
    "tikv/pd": ["tikv/pd/image"],
    "tikv/tikv": ["tikv/tikv/image"],
    "pingcap/tidb": [
      "pingcap/tidb/images/tidb-server",
      "pingcap/tidb/images/br",
      "pingcap/tidb/images/dumpling",
      "pingcap/tidb/images/tidb-lightning",
    ],
    "pingcap/tiflash": ["pingcap/tiflash/image"],
    "pingcap/tiflow": [
      "pingcap/tiflow/images/cdc",
      "pingcap/tiflow/images/dm",
    ],
    "pingcap/ticdc": ["pingcap/ticdc/image"],
    "pingcap/tidb-binlog": ["pingcap/tidb-binlog/image"],
    "pingcap/monitoring": ["pingcap/monitoring/image"],
    "pingcap/ng-monitoring": ["pingcap/ng-monitoring/image"],
  },
  enterprise: {
    "tikv/pd": ["tikv/pd/image"],
    "tikv/tikv": ["tikv/tikv/image"],
    "pingcap/tidb": ["pingcap/tidb/images/tidb-server"],
    "pingcap/tiflash": ["pingcap/tiflash/image"],
  },
};

interface CliParams {
  oci_registry: string;
  version: string;
  branch: string;
  github_token: string;
  save_to: string;
}

interface ImageInfo {
  error?: string;
  ok?: boolean;
  oci?: {
    [key: string]: OciMetadata;
  };
  git_sha?: string;
}

interface OciMetadata {
  git_sha: string;
  published: string;
}

interface Results {
  images: {
    [key: string]: Record<string, ImageInfo>;
  };
}

type GitRepoImageMap = Record<string, string[]>;

/**
 * Normalize version string for semver parsing.
 * Removes 'v' prefix and '-enterprise' suffix if present.
 */
function normalizeVersion(version: string): string {
  return version.replace(/^v/, "").replace(/-enterprise$/, "");
}

/**
 * Compare two version strings using semantic versioning.
 * Returns true if version >= threshold.
 */
function versionGreaterOrEqual(version: string, threshold: string): boolean {
  const v = parseSemver(normalizeVersion(version));
  const t = parseSemver(normalizeVersion(threshold));
  return greaterOrEqual(v, t);
}

/**
 * Compare two version strings using semantic versioning.
 * Returns true if version <= threshold.
 */
function versionLessThanOrEqual(version: string, threshold: string): boolean {
  const v = parseSemver(normalizeVersion(version));
  const t = parseSemver(normalizeVersion(threshold));
  return lessThanOrEqual(v, t);
}

function validate(info: ImageInfo) {
  info.ok = true;
  // assert all git sha are same in oci files.
  if (info.oci) {
    // unique the gitSha
    if (new Set(Object.values(info.oci).map((i) => i.git_sha)).size !== 1) {
      info.ok = false;
      info.error = (info.error || "") + "\n" +
        "- git sha not same in platforms.";
    }
    // assert the oci.git_sha is all same with git_sha
    if (info.git_sha) {
      if (Object.values(info.oci).some((g) => g.git_sha !== info.git_sha)) {
        info.ok = false;
        info.error = (info.error || "") + "\n" +
          "- git sha not same with git branch.";
      }
    }
  }
}

async function gatherOciMetadata(image: string, platform: string) {
  const command = new Deno.Command("oras", {
    args: ["manifest", "fetch-config", image, "--platform", platform],
  });
  const { code, stdout, stderr } = await command.output();
  if (code !== 0) {
    console.error(new TextDecoder().decode(stderr));
    return;
  }
  const config = JSON.parse(new TextDecoder().decode(stdout));
  const publishedTime = config["created"];
  const gitSha = config["config"]["Labels"]["net.pingcap.tibuild.git-sha"];

  const meta = { git_sha: gitSha, published: publishedTime } as OciMetadata;
  console.info("got OCI metadata of", image, platform, meta);
  return meta;
}

async function getMultiArchImageInfo(image: string) {
  const ociInfos = {} as Record<string, OciMetadata>;
  for (const platform of platforms) {
    const metadata = await gatherOciMetadata(image, platform);
    if (metadata) {
      ociInfos[platform] = metadata;
    }
  }
  return ociInfos;
}

async function gatheringGithubGitSha(
  ghClient: Octokit,
  fullRepo: string,
  branch: string,
) {
  const [owner, repo] = fullRepo.split("/", 2);
  const sha = await Promise.race([
    ghClient.rest.repos.getCommit({ owner, repo, ref: branch }).then((res) =>
      res.data.sha
    ),
    new Promise<string>((_, reject) =>
      setTimeout(() => reject(new Error("Timeout after 5 seconds")), 5000)
    ),
  ]).catch(() => "");
  console.info(`got github git sha of ${fullRepo}@${branch}: ${sha}`);
  return sha;
}

function checkTools() {
  const command = new Deno.Command("oras", { args: ["version"] });
  const { code } = command.outputSync();
  if (code !== 0) {
    throw new Error("oras not found in PATH");
  }
}

async function checkImages(
  branch: string,
  version: string,
  oci_registry: string,
  mm: GitRepoImageMap,
  ghClient: Octokit,
) {
  const results = {} as Record<string, ImageInfo>;
  for (const [gitRepo, map] of Object.entries(mm)) {
    // tidb-binlog is deprecated since v8.4.0, skip it.
    if (versionGreaterOrEqual(version, "v8.4.0") && gitRepo === "pingcap/tidb-binlog") {
      continue;
    }
    // ticdc is initilized since v8.5.4
    if (versionLessThanOrEqual(version, "v8.5.3") && gitRepo === "pingcap/ticdc") {
      continue;
    }

    console.group(gitRepo);
    const gitSha = await gatheringGithubGitSha(ghClient, gitRepo, branch);

    for (const src of map) {
      if (versionGreaterOrEqual(version, "v8.5.4") && src === "pingcap/tiflow/images/cdc") {
        continue;
      }
      const imageUrl = `${oci_registry}/${src}:${version}`;
      const infos = await getMultiArchImageInfo(imageUrl);
      const info = {
        oci: infos,
        image_url: imageUrl,
        git_sha: gitSha,
      } as ImageInfo;
      results[src] = info;
      validate(info);
    }

    console.groupEnd();
  }

  const failedPkgs = [] as string[];
  for (const [pkg, result] of Object.entries(results)) {
    if (!result.ok) {
      console.error("❌", pkg, result.error);
      failedPkgs.push(pkg);
    }
  }
  return { results, failedPkgs };
}

async function main(
  {
    version,
    branch,
    github_token,
    oci_registry = "hub.pingcap.net",
    save_to = "results.yaml",
  }: CliParams,
) {
  checkTools();
  const totalResults = {} as Record<string, Record<string, ImageInfo>>;
  const totalFailedPkgs = {} as Record<string, string[]>;
  const ghClient = new Octokit({ auth: github_token });

  {
    console.group("check community images:");
    const { results, failedPkgs } = await checkImages(
      branch,
      version,
      oci_registry,
      imageMap.comunity,
      ghClient,
    );
    console.groupEnd();
    totalResults["community"] = results;
    totalFailedPkgs["community"] = failedPkgs;
  }

  {
    console.group("check enterprise images:");
    const { results, failedPkgs } = await checkImages(
      branch,
      `${version}-enterprise`,
      oci_registry,
      imageMap.enterprise,
      ghClient,
    );
    console.groupEnd();
    totalResults["enterprise"] = results;
    totalFailedPkgs["enterprise"] = failedPkgs;
  }

  // write the results to a yaml file.
  await Deno.writeTextFile(save_to, yaml.stringify(totalResults));

  if (totalFailedPkgs["community"].length > 0) {
    throw new Error(
      `some community images check failed: ${
        totalFailedPkgs["community"].join(", ")
      }`,
    );
  }
  if (totalFailedPkgs["enterprise"].length > 0) {
    throw new Error(
      `some enterprise images check failed: ${
        totalFailedPkgs["enterprise"].join(", ")
      }`,
    );
  }

  console.info("🏅🏅🏅 check success!");
  Deno.exit(0);
}

if (import.meta.main) {
  const args = parseArgs(Deno.args) as CliParams;
  await main(args);
}
