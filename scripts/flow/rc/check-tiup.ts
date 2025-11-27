#!/usr/bin/env -S deno run --allow-run --allow-net --allow-write
import * as yaml from "jsr:@std/yaml@1.0.5";
import { parseArgs } from "jsr:@std/cli@1.0.6";
import { Octokit } from "https://esm.sh/octokit@4.0.2?dts";

const platforms = [
  "darwin/amd64",
  "darwin/arm64",
  "linux/amd64",
  "linux/arm64",
];

const OCI2Tiup: Record<string, string[]> = {
  "tikv/pd/package": ["pd", "pd-recover"],
  "tikv/tikv/package": ["tikv"],
  "pingcap/ctl/package": ["ctl"],
  "pingcap/tidb-binlog/package": [
    "pump",
    "drainer",
  ],
  "pingcap/tidb-dashboard/package": ["tidb-dashboard"],
  "pingcap/tidb/package": [
    "br",
    "tidb",
    "tidb-lightning",
    "dumpling",
  ],
  "pingcap/tiflash/package": ["tiflash"],
  "pingcap/tiflow/package": [
    "cdc",
    "dm-master",
    "dm-worker",
    "dmctl",
  ],
  "pingcap/ticdc/package": [
    "cdc",
  ],
  "pingcap/ng-monitoring/package": [],
  "pingcap/monitoring/package": [
    "grafana",
    "prometheus",
  ],
};

interface CliParams {
  tiup_mirror: string;
  oci_registry: string;
  version: string;
  branch: string;
  save_to: string;
  github_token: string;
}

interface TiupPkgInfo {
  platforms?: string[];
  published?: string;
  error?: string;
  ok: boolean;
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
  tiup: Record<string, TiupPkgInfo>;
}

function validate(info: TiupPkgInfo) {
  // assert all git sha are same in oci files.
  if (info.oci) {
    // unique the gitSha
    if (new Set(Object.values(info.oci).map((i) => i.git_sha)).size !== 1) {
      info.ok = false;
      info.error = (info.error || "") + "\n" +
        "- git sha not same in oci files.";
    }
    // assert the oci.git_sha is all same with git_sha
    if (info.git_sha) {
      if (Object.values(info.oci).some((g) => g.git_sha !== info.git_sha)) {
        info.ok = false;
        info.error = (info.error || "") + "\n" +
          "- git sha not same with git branch.";
      }
    }
    // assert .published are late than .oci.published.
    if (info.published) {
      const publisheds = Object.values(info.oci).map((i) => i.published);
      if (!publisheds.some((p) => new Date(p) < new Date(info.published!))) {
        info.ok = false;
        info.error = (info.error || "") + "\n" +
          "- tiup pkg published time not later than oci published time.";
      }
    }
  }
}

async function gatherTiupPkgInfo(
  name: string,
  version: string,
): Promise<TiupPkgInfo> {
  console.info("gathering tiup pkg:", name, version);
  const command = new Deno.Command("tiup", { args: ["list", name] });
  const { code, stdout } = await command.output();
  if (code !== 0) {
    return { ok: false, error: "pkg not found" };
  }
  // implement bash grep -E "^$VERSION\b\s+"
  const lines = new TextDecoder().decode(stdout).split("\n")
    .map((line) => line.trim())
    .filter((line) => line.split(/\s+/)[0] === version);
  if (lines.length !== 1) {
    return { ok: false, error: "version not found" };
  }

  const publishedTime = lines[0].split(/\s+/)[1];
  const platforms = lines[0].split(/\s+/).pop()!.split(",").sort();
  if (platforms.every((arch) => platforms.includes(arch))) {
    return { ok: true, published: publishedTime, platforms };
  } else {
    return {
      ok: false,
      platforms,
      error: "not full platforms published",
    };
  }
}

async function gatheringOciMetadata(ociArtifact: string) {
  const command = new Deno.Command("oras", {
    args: ["manifest", "fetch-config", ociArtifact],
  });
  const { code, stdout, stderr } = await command.output();
  if (code !== 0) {
    console.error(new TextDecoder().decode(stderr));
    return;
  }
  const config = JSON.parse(new TextDecoder().decode(stdout));
  const gitSha = config["net.pingcap.tibuild.git-sha"];

  const command2 = new Deno.Command("oras", {
    args: ["manifest", "fetch", ociArtifact],
  });
  const { code: code2, stdout: stdout2 } = await command2.output();
  if (code2 !== 0) {
    console.error(new TextDecoder().decode(stdout2));
    return;
  }
  const manifest = JSON.parse(new TextDecoder().decode(stdout2));
  const publishedTime =
    manifest.annotations["org.opencontainers.image.created"];

  const meta = { git_sha: gitSha, published: publishedTime } as OciMetadata;
  console.info("got OCI metadata of", ociArtifact, meta);
  return meta;
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
  const command = new Deno.Command("tiup", { args: ["--version"] });
  const { code } = command.outputSync();
  if (code !== 0) {
    throw new Error("tiup not found in PATH");
  }

  const command2 = new Deno.Command("oras", { args: ["version"] });
  const { code: code2 } = command2.outputSync();
  if (code2 !== 0) {
    throw new Error("oras not found in PATH");
  }
}

async function main(
  {
    tiup_mirror,
    version,
    branch,
    github_token,
    oci_registry = "hub.pingcap.net",
    save_to = "results.yaml",
  }: CliParams,
) {
  // check tools
  checkTools();

  const command = new Deno.Command("tiup", {
    args: ["mirror", "set", tiup_mirror],
  });
  const { code } = await command.output();
  if (code !== 0) {
    throw new Error(`tiup mirror set ${tiup_mirror} failed`);
  }

  const results = { tiup: {} } as Results;
  const ghClient = new Octokit({ auth: github_token });
  for (const [ociRepo, pkgs] of Object.entries(OCI2Tiup)) {
    // tidb-binlog is deprecated since v8.4.0, skip it.
    if (version >= "v8.4.0" && ociRepo === "pingcap/tidb-binlog/package") {
      continue;
    }
    // ticdc is initilized since v8.5.4
    if (version <= "v8.5.4" && ociRepo === "pingcap/ticdc/package") {
      continue;
    }

    console.group(ociRepo);
    const ociInfos = {} as Record<string, OciMetadata>;
    for (const platform of platforms) {
      const ociArtifact = `${oci_registry}/${ociRepo}:${version}_${
        platform.replaceAll("/", "_")
      }`;
      const metadata = await gatheringOciMetadata(ociArtifact);
      if (metadata) {
        ociInfos[platform] = metadata;
      }
    }

    const gitRepo = ociRepo.split("/").slice(0, -1).join("/");
    const gitSha = gitRepo === "pingcap/ctl"
      ? undefined
      : await gatheringGithubGitSha(ghClient, gitRepo, branch);

    for (const pkg of pkgs) {
      const result = await gatherTiupPkgInfo(pkg, version);
      result.oci = ociInfos;
      if (gitSha) result.git_sha = gitSha;
      validate(result);
      results.tiup[pkg] = result;
    }
    console.groupEnd();
  }

  console.group("Validation Results");
  const failedPkgs = [] as string[];
  for (const [pkg, result] of Object.entries(results.tiup)) {
    if (!result.ok) {
      console.error("❌", pkg, result.error);
      failedPkgs.push(pkg);
    }
  }
  console.groupEnd();

  // write the results to a yaml file.
  await Deno.writeTextFile(save_to, yaml.stringify(results));
  if (failedPkgs.length > 0) {
    throw new Error(`some packages check failed: ${failedPkgs.join(", ")}`);
  }

  console.info("🏅🏅🏅 check success!");
  Deno.exit(0);
}

if (import.meta.main) {
  const args = parseArgs(Deno.args) as CliParams;
  await main(args);
}
