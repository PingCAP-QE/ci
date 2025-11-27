import { Octokit } from "https://esm.sh/octokit@4.0.2?dts";
import { parseArgs } from "jsr:@std/cli@^1.0.1";
import {
  format,
  maxSatisfying,
  parse,
  parseRange,
  satisfies,
  type SemVer,
} from "jsr:@std/semver@^1.0.4";

interface RepoInfo {
  owner: string;
  repo: string;
  product_name: string;
  tag_githash?: string;
  version?: string;
  skip_issues_in_release_msg?: boolean;
  release_msg?: string;
  branch_name?: string;
}

interface CliParams {
  token: string;
  version: string;
  dryrun?: boolean;
}

const REPO_LIST: RepoInfo[] = [
  {
    owner: "tikv",
    repo: "tikv",
    product_name: "TiKV",
  },
  {
    owner: "tikv",
    repo: "pd",
    product_name: "PD",
  },
  {
    owner: "pingcap",
    repo: "tidb",
    product_name: "TiDB",
  },
  {
    owner: "pingcap",
    repo: "tiflash",
    product_name: "TiFlash",
  },
  {
    owner: "pingcap",
    repo: "tidb-binlog",
    product_name: "Binlog",
    skip_issues_in_release_msg: true,
  },
  {
    owner: "pingcap",
    repo: "tidb-tools",
    product_name: "TiDB-Tools",
    branch_name: "master", // always use master to release.
  },
  {
    owner: "pingcap",
    repo: "tiflow",
    product_name: "TiFlow",
  },
  {
    owner: "pingcap",
    repo: "ticdc",
    product_name: "TiCDC",
  },
  {
    owner: "pingcap",
    repo: "ng-monitoring",
    product_name: "NG-Monitoring",
  },
  {
    owner: "pingcap",
    repo: "tidb-dashboard",
    product_name: "TiDB-Dashboard",
  },
  {
    owner: "pingcap",
    repo: "monitoring",
    product_name: "Monitoring",
    skip_issues_in_release_msg: true,
  },
];

async function fillRepoTagInfo(
  repos: RepoInfo[],
  version: string,
  octokit: Octokit,
) {
  const v = parse(version);
  const isNewBetaVer = satisfies(v, parseRange(">=9.0.0-0")) && v.patch == 0 &&
    v.prerelease?.[0] == "beta" && v.prerelease?.[1];
  const releaseBranch = isNewBetaVer
    ? `release-${v.major}.${v.minor}-beta.${v.prerelease![1]}`
    : `release-${v.major}.${v.minor}`;

  console.log("default create release on branch: ", releaseBranch);

  v.prerelease = undefined; // delete the pre release version for comparing.
  // "tidb-binlog" repo stops to release since 8.4.0, but the history release branches is still there and keep releasing patches.
  const binlogRemovedRange = parseRange(">=8.4.0-0");
  if (satisfies(v, binlogRemovedRange)) {
    repos = repos.filter((r) => r.repo !== "tidb-binlog");
  }

  // "ticdc" repo will release from 8.5.4, it's a new repo for new CDC component.
  const ticdcStartedRange = parseRange(">=8.5.4-0");
  if (!satisfies(v, ticdcStartedRange)) {
    repos = repos.filter((r) => r.repo !== "ticdc");
  }

  // "tidb-tools" repo will be excluded from release since 9.0.0, its code migrated to `tiflow` repo.
  const tidbToolsRemovedRange = parseRange(">=9.0.0-0");
  if (satisfies(v, tidbToolsRemovedRange)) {
    repos = repos.filter((r) => r.repo !== "tidb-tools");
  }

  return await Promise.all(
    repos.map(async (repo) => {
      const info = {
        ...repo,
        version,
      } as RepoInfo;
      info.branch_name ||= releaseBranch;
      info.tag_githash = await lastCommitShaOfBranch(info, octokit);
      // console.log("repo done:", info.repo);
      info.release_msg = await releaseNote(info, octokit);

      return info;
    }),
  );
}

async function lastCommitShaOfBranch(
  { owner, repo, ...rest }: RepoInfo,
  octokit: Octokit,
) {
  const res = await octokit.rest.repos.listCommits({
    owner,
    repo,
    sha: rest.branch_name,
    per_page: 1,
  });
  return res.data[0].sha;
}

async function previousRelease(repo: RepoInfo, octokit: Octokit) {
  const releases = await octokit.paginate(octokit.rest.repos.listReleases, {
    owner: repo.owner,
    repo: repo.repo,
    per_page: 100,
  });
  const versionTags = releases.map((r) => r.tag_name).filter((t) =>
    t.match(/^v\d+\.\d+\.\d+$/)
  ).map(parse) as SemVer[];

  // find the maximum version tag in the list which lower then repo.version.
  const range = parseRange(`< ${repo.version!}`);
  const ret = maxSatisfying(versionTags, range);
  if (!ret) {
    console.warn(`no previous release found for ${repo.repo}`);
    return undefined;
  }
  return "v" + format(ret);
}

async function releaseNote(repo: RepoInfo, octokit: Octokit) {
  const previousTag = await previousRelease(repo, octokit);
  if (!previousTag) {
    return "";
  }
  console.info(
    `previous release of ${repo.product_name}@${repo.version}: ${previousTag}`,
  );
  const { data: { commits } } = await octokit.rest.repos
    .compareCommitsWithBasehead({
      owner: repo.owner,
      repo: repo.repo,
      basehead: `${previousTag}...${repo.branch_name}`,
    });

  const fixedIssues = commits
    .filter((c) => c.commit.message.match(/close\s+\S*#\d+/))
    .map((c) => c.commit.message.match(/close\s+(\S*#\d+)/)![1]);
  const releaseDescription =
    `For new features, improvements, and bug fixes released in ${repo.version} for ${repo.product_name}, see [TiDB ${repo.version} release notes](https://docs.pingcap.com/tidb/stable/release-${
      repo.version?.slice(1)
    }).`;
  if (!repo.skip_issues_in_release_msg) {
    return releaseDescription.concat(
      "\n\n",
      "See the difference from the issue perspective:\n<details>\n\n",
      fixedIssues.map((i) => `- ${i}`).join("\n"),
      "\n\n</details>",
    );
  } else {
    return releaseDescription;
  }
}

async function createRelease(repo: RepoInfo, octokit: Octokit, dryrun = false) {
  const releaseName = `${repo.product_name} ${repo.version}`;
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
    if (!dryrun) {
      await octokit.rest.repos.createRelease({
        owner: repo.owner,
        repo: repo.repo,
        name: releaseName,
        tag_name: repo.version!,
        body: repo.release_msg,
      });
    }
    console.info(
      `✅ Release ${repo.version} created successfully for ${repo.owner}/${repo.repo} with release message: \n${repo.release_msg}`,
    );
  };
  console.group("Create release: ", releaseName);
  if (dryrun) console.info("🐒 dryrun enabled");
  const releaseTag = await octokit.rest.repos.getReleaseByTag({
    owner: repo.owner,
    repo: repo.repo,
    tag: repo.version!,
  }).catch((error) => {
    if (error.status !== 404) throw error;
    console.info(
      `🚀 No release tag ${repo.version} found for ${repo.owner}/${repo.repo}.`,
    );
    return null;
  });
  if (releaseTag) {
    console.warn(
      `🎯 Release ${repo.version} existed in ${repo.owner}/${repo.repo}.`,
    );
  } else {
    await createFunc();
  }

  console.groupEnd();
}

async function main(args: CliParams) {
  const octokit = new Octokit({
    auth: args.token,
    userAgent: "PingCAP Release v1.0.0",
  });

  new Octokit();

  const repos = await fillRepoTagInfo(
    REPO_LIST,
    args.version,
    octokit,
  );

  for (const repo of repos) {
    await createRelease(repo, octokit, args.dryrun);
  }
}

// parase cli params with `CliParams` and pass to main
const args = parseArgs(Deno.args) as CliParams;
await main(args);
Deno.exit(0);
