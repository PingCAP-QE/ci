import { Octokit } from "https://esm.sh/octokit@4.0.2?dts";
import { parseArgs } from "jsr:@std/cli@^1.0.1";
import {
  format,
  maxSatisfying,
  parse,
  parseRange,
  type SemVer,
} from "jsr:@std/semver@^1.0.3"; // FIXME: https://github.com/denoland/std/issues/6303

type alphaVersion = `v{number}.{number}.{number}-alpha`;
interface RepoInfo {
  owner: string;
  repo: string;
  tag_githash?: string;
  version?: string;
  release_msg?: string;
  branch_name?: string;
}

interface CliParams {
  token: string;
  version: string;
  dryrun?: boolean;
  pushEventUrl?: string;
}

async function fillRepoTagInfo(
  repos: RepoInfo[],
  version: string,
  octokit: Octokit,
) {
  const branch = `master`;

  // "tidb-binlog" repo stops to release since 8.4.0, but the history release branches is still there and keep releasing patches.
  if (version >= "v8.4.0") {
    console.log("tidb-binlog repo is removed from 8.4.0, skip it.");
    repos = repos.filter((r) => r.repo !== "tidb-binlog");
  }

  // "ticdc" repo will release from from 8.5.4, it's a new repo for new CDC component.
  if (version < "v8.5.4") {
    repos = repos.filter((r) => r.repo !== "ticdc");
  }

  return await Promise.all(
    repos.map(async (repo) => {
      const info = {
        ...repo,
        version,
      } as RepoInfo;
      info.branch_name ||= branch;
      info.tag_githash = await lastCommitShaOfBranch(info, octokit);
      info.release_msg ||=
        `Alpha tag for next release, please do not use it in production.`;

      return info;
    }),
  );
}

async function lastCommitShaOfBranch(
  { owner, repo, ...rest }: RepoInfo,
  octokit: Octokit,
) {
  try {
    const res = await octokit.rest.repos.listCommits({
      owner,
      repo,
      sha: rest.branch_name,
      per_page: 1,
    });
    return res.data[0].sha;
  } catch {
    return "";
  }
}

async function createTag(repo: RepoInfo, octokit: Octokit, dryrun = false) {
  const createFunc = async () => {
    if (!dryrun) {
      await octokit.rest.git.createRef({
        owner: repo.owner,
        repo: repo.repo,
        ref: `refs/tags/${repo.version!}`,
        sha: repo.tag_githash!,
      });
    }
    console.log(
      `✅ Tag ${repo.version} created successfully for ${repo.owner}/${repo.repo} based on commit: ${repo.tag_githash}`,
    );
  };
  console.group(`Create tag on for ${repo.repo}`, repo.version);
  if (dryrun) console.info("🐒 dryrun enabled");
  const gitRef = await octokit.rest.git.getRef({
    owner: repo.owner,
    repo: repo.repo,
    ref: `tags/${repo.version!}`, // format: tags/<tag> or heads/<branch>
  }).catch((error) => {
    if (error.status !== 404) throw error;
    console.info(
      `🚀 No tag ${repo.version} found for ${repo.owner}/${repo.repo}.`,
    );
    return null;
  });
  if (gitRef) {
    console.warn(
      `🎯 Tag ${repo.version} existed in ${repo.owner}/${repo.repo}.`,
    );
  } else {
    await createFunc();
  }

  console.groupEnd();
}

/*
 * Check if the repo has new commits since the last release.
 * If all repos are up to date, return true, else throw an error.
 */
async function checkRepoCommitStatus(
  repo: RepoInfo,
  latestTag: string,
  octokit: Octokit,
) {
  const tagCommit = await lastCommitShaOfBranch({
    ...repo,
    branch_name: latestTag,
  }, octokit);
  if (tagCommit !== repo.tag_githash) {
    console.log(`✅ Repo ${repo.owner}/${repo.repo} is up to date.`);
    return true;
  } else {
    throw new Error(
      `❌ Repo ${repo.owner}/${repo.repo} is not up to date. the latest tag is ${tagCommit}, same as the branch commit ${repo.tag_githash}`,
    );
  }
}

async function emitBranchPushEvent(
  repo: RepoInfo,
  sinkerUrl: string,
  octokit: Octokit,
  dryrun = false,
) {
  const eventPayload = await generatePushEventPayload(
    repo.owner,
    repo.repo,
    repo.branch_name!,
    octokit,
  );

  const fetchInit = {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-GitHub-Event": "push",
    },
    body: JSON.stringify(eventPayload),
  };
  console.log(fetchInit);
  console.log(fetchInit.body);
  if (dryrun) {
    console.log(
      "🐒 dryrun mode enabled, i will skip sending event to ",
      sinkerUrl,
    );
    return;
  }

  const ret = await fetch(sinkerUrl, fetchInit);
  if (ret.ok) {
    console.log(
      `✅ Emitted branch push event for ${repo.owner}/${repo.repo}@${repo.branch_name}`,
    );
    console.log(await ret.text());
  } else {
    throw new Error(
      `❌ Emit branch push event failed for ${repo.owner}/${repo.repo}@${repo.branch_name}`,
      { cause: ret.statusText },
    );
  }
}

async function generatePushEventPayload(
  owner: string,
  repo: string,
  branch: string,
  client: Octokit,
) {
  const gitUrl = `https://github.com/${owner}/${repo}.git`;
  const ref = `refs/heads/${branch}`;
  return {
    before: "0000000000000000000000000000000000000000",
    after: (await client.rest.repos.getCommit({ owner, repo, ref }))
      .data.sha,
    ref,
    repository: {
      name: repo,
      full_name: `${owner}/${repo}`,
      owner: { login: owner },
      clone_url: gitUrl,
    },
  };
}

async function getLatestReleasedVersion(repo: RepoInfo, octokit: Octokit) {
  const releases = await octokit.paginate(octokit.rest.repos.listReleases, {
    owner: repo.owner,
    repo: repo.repo,
    per_page: 100,
  });
  const versionTags = releases.map((r) => r.tag_name).filter((t) =>
    t.match(/^v\d+\.\d+\.\d+$/)
  ).map(parse) as SemVer[];

  // find the maximum version tag in the list which lower then repo.version.
  const destVer = parse(repo.version!);
  destVer.prerelease = [];
  const range = parseRange(`< ${format(destVer)}`);
  const ret = maxSatisfying(versionTags, range);
  if (!ret) {
    console.warn(
      `no latest GA release found for ${repo.owner}/${repo.repo}`,
    );
    return undefined;
  }
  return "v" + format(ret);
}

async function main(args: CliParams, repos: RepoInfo[]) {
  const octokit = new Octokit({
    auth: args.token,
    userAgent: "PingCAP Release v1.0.0",
  });
  // check the version.

  const repoInfos = await fillRepoTagInfo(
    repos,
    args.version,
    octokit,
  );

  console.group("Check repo commit status");
  for (const repo of repoInfos) {
    const lastVersion = await getLatestReleasedVersion(repo, octokit);
    console.log(
      `🎯 the latest released version of ${repo.repo} is ${lastVersion}`,
    );
    if (lastVersion) {
      await checkRepoCommitStatus(repo, lastVersion, octokit);
    }
  }
  console.groupEnd();

  console.group("Create tag");
  for (const repo of repoInfos) {
    await createTag(repo, octokit, args.dryrun);
  }
  console.groupEnd();

  if (args.pushEventUrl) {
    console.group("Emit branch push events");
    for (const repo of repoInfos) {
      await emitBranchPushEvent(
        repo,
        args.pushEventUrl,
        octokit,
        args.dryrun,
      );
    }
    console.groupEnd();
  }
}

// parase cli params with `CliParams` and pass to main
const args = parseArgs(Deno.args) as CliParams;
await main(args, [
  // {
  //     owner: "PingCAP-QE",
  //     repo: "ci",
  //     product_name: "Test",
  //     branch_name: "main",
  // }, // repo for test.
  { owner: "tikv", repo: "tikv" },
  { owner: "tikv", repo: "pd" },
  { owner: "pingcap", repo: "tidb" },
  { owner: "pingcap", repo: "tidb-binlog" },
  { owner: "pingcap", repo: "tiflash" },
  { owner: "pingcap", repo: "tiflow" },
  { owner: "pingcap", repo: "ticdc" },
  { owner: "pingcap", repo: "ng-monitoring", branch_name: "main" },
  { owner: "pingcap", repo: "tidb-dashboard" },
  { owner: "pingcap", repo: "monitoring" },
]);
Deno.exit(0);
