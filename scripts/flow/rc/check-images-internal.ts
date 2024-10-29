#!/usr/bin/env -S deno run --allow-run --allow-net --allow-write
import * as yaml from "jsr:@std/yaml@1.0.5";
import { parseArgs } from "jsr:@std/cli@1.0.6";

const platforms = [
  "linux/amd64",
  "linux/arm64",
];

const imageMap: Record<string, GitRepoImageMap> = {
  comunity: {
    "pingcap/tidb": {
      "pingcap/tidb/images/tidb-server": "qa/tidb",
      "pingcap/tidb/images/br": "qa/br",
      "pingcap/tidb/images/dumpling": "qa/dumpling",
      "pingcap/tidb/images/tidb-lightning": "qa/tidb-lightning",
    },
    "pingcap/tiflash": {
      "pingcap/tiflash/image": "qa/tiflash",
    },
    "pingcap/tiflow": {
      "pingcap/tiflow/images/cdc": "qa/ticdc",
      "pingcap/tiflow/images/dm": "qa/dm",
    },
    "pingcap/tidb-binlog": {
      "pingcap/tidb-binlog/image": "qa/tidb-binlog",
    },
    "tikv/pd": {
      "tikv/pd/image": "qa/pd",
    },
    "tikv/tikv": {
      "tikv/tikv/image": "qa/tikv",
    },
    "pingcap/monitoring": {
      "pingcap/monitoring/image": "qa/tidb-monitor-initializer",
    },
    "pingcap/ng-monitoring": {
      "pingcap/ng-monitoring/image": "qa/ng-monitoring",
    },
  },
  enterprise: {
    "pingcap/tidb": {
      "pingcap/tidb/images/tidb-server": "qa/tidb-enterprise",
    },
    "pingcap/tiflash": {
      "pingcap/tiflash/image": "qa/tiflash-enterprise",
    },
    "tikv/pd": {
      "tikv/pd/image": "qa/pd-enterprise",
    },
    "tikv/tikv": {
      "tikv/tikv/image": "qa/tikv-enterprise",
    },
  },
};

interface CliParams {
  oci_registry: string;
  version: string;
  branch: string;
  save_to: string;
}

interface ImageInfo {
  error?: string;
  ok?: boolean;
  oci?: {
    [key: string]: OciMetadata;
  };
  src_oci?: {
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

type GitRepoImageMap = Record<string, Record<string, string>>;

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

    // deep compare src_oci with oci
    if (info.src_oci) {
      if (JSON.stringify(info.oci) !== JSON.stringify(info.src_oci)) {
        info.ok = false;
        info.error = (info.error || "") + "\n" +
          "- src_oci not same with oci.";
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

async function gatheringGithubGitSha(repo: string, branch: string) {
  const res = await fetch(
    `https://api.github.com/repos/${repo}/commits/${branch}`,
    {
      headers: {
        Accept: "application/vnd.github.v3+json",
      },
    },
  );
  const { sha } = await res.json();
  console.info(`got github git sha of ${repo}@${branch}: ${sha}`);
  return sha || "";
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
) {
  const results = {} as Record<string, ImageInfo>;
  for (const [gitRepo, map] of Object.entries(mm)) {
    // tidb-binlog is deprecated since v8.4.0, skip it.
    if (version >= "v8.4.0" && gitRepo === "pingcap/tidb-binlog") {
      continue;
    }

    console.group(gitRepo);
    const gitSha = await gatheringGithubGitSha(gitRepo, branch);

    for (const [src, dst] of Object.entries(map)) {
      const srcInfos = await getMultiArchImageInfo(
        `${oci_registry}/${src}:${version}`,
      );
      const qaInfos = await getMultiArchImageInfo(
        `${oci_registry}/${dst}:${version}`,
      );
      const info = {
        oci: qaInfos,
        src_oci: srcInfos,
        git_sha: gitSha,
      } as ImageInfo;
      results[dst] = info;
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
    oci_registry = "hub.pingcap.net",
    save_to = "results.yaml",
  }: CliParams,
) {
  checkTools();
  const totalResults = {} as Record<string, Record<string, ImageInfo>>;
  const totalFailedPkgs = {} as Record<string, string[]>;

  {
    console.group("check community images:");
    const { results, failedPkgs } = await checkImages(
      branch,
      version,
      oci_registry,
      imageMap.comunity,
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
}

// parase cli params with `CliParams` and pass to main
const args = parseArgs(Deno.args) as CliParams;
await main(args);
