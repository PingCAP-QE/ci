#!/usr/bin/env deno run --allow-net --allow-env --allow-write

const token = Deno.env.get("GITHUB_TOKEN");
if (!token) {
  console.error("GITHUB_TOKEN is not set.");
  Deno.exit(1);
}

const headers = { "Authorization": `token ${token}` };

function parseReleaseVersion(branch: string): number[] | null {
  const prefix = "release-";
  if (!branch.startsWith(prefix)) return null;
  const versionPart = branch.substring(prefix.length).split("-", 1)[0];
  if (!versionPart) return null;
  const parts = versionPart.split(".").map((p) => {
    const n = parseInt(p, 10);
    return isNaN(n) ? NaN : n;
  });
  if (parts.length === 0 || parts.includes(NaN)) return null;
  return parts;
}

function isBranchBeforeRelease(
  branch: string,
  targetVersion: number[],
): boolean {
  const version = parseReleaseVersion(branch);
  if (!version) return false;
  const maxLen = Math.max(version.length, targetVersion.length);
  const paddedVersion = version.slice();
  while (paddedVersion.length < maxLen) paddedVersion.push(0);
  const paddedTarget = targetVersion.slice();
  while (paddedTarget.length < maxLen) paddedTarget.push(0);
  for (let i = 0; i < maxLen; i++) {
    if (paddedVersion[i] < paddedTarget[i]) return true;
    if (paddedVersion[i] > paddedTarget[i]) return false;
  }
  return false;
}

async function getLatestCommitHash(
  repo: string,
  branch: string,
): Promise<string | null> {
  const url = `https://api.github.com/repos/${repo}/commits/${branch}`;
  const resp = await fetch(url, { headers });
  if (resp.ok) {
    const data = await resp.json();
    return data.sha as string;
  } else {
    console.error(`Error fetching ${repo} branch ${branch}: ${resp.status}`);
    return null;
  }
}

async function main(branch: string, version: string): Promise<void> {
  const TICDC_REPO_CHANGE_VERSION = [8, 5];
  const ticdcRepo = isBranchBeforeRelease(branch, TICDC_REPO_CHANGE_VERSION)
    ? "pingcap/tiflow"
    : "pingcap/ticdc";

  const repos: Record<string, string> = {
    // "binlog": "pingcap/tidb-binlog",
    "br": "pingcap/tidb",
    "tidb": "pingcap/tidb",
    "tikv": "tikv/tikv",
    "pd": "tikv/pd",
    "tiflash": "pingcap/tiflash",
    "ticdc": ticdcRepo,
    "dm": "pingcap/tiflow",
    "dumpling": "pingcap/tidb",
    "tidb-dashboard": "pingcap/tidb-dashboard",
    "lightning": "pingcap/tidb",
    "ng-monitoring": "pingcap/ng-monitoring",
    "enterprise-plugin": "pingcap-inc/enterprise-plugin",
  };

  const output: { docker_images: any[]; tiup_packages: any[] } = {
    docker_images: [],
    tiup_packages: [],
  };

  for (const [name, repo] of Object.entries(repos)) {
    const commitHash = await getLatestCommitHash(repo, branch);
    if (commitHash) {
      const entry = { name, version, commit_hash: commitHash };
      output.docker_images.push(entry);
      output.tiup_packages.push(entry); // same entry for tiup_packages
    }
  }

  const filename = `components-${version}.json`;
  await Deno.writeTextFile(filename, JSON.stringify(output, null, 2));
}

if (import.meta.main) {
  const args = Deno.args;
  if (args.length !== 2) {
    console.error("Usage: create_components_json.ts <branch> <version>");
    Deno.exit(1);
  }
  const [branch, version] = args;
  await main(branch, version);
}
