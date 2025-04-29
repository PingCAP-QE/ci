import { Octokit } from "https://esm.sh/@octokit/rest@21.1.1";

// get commits of a file in a repository with octokit package
async function getCommits(
  octokit: Octokit,
  owner: string,
  repo: string,
  path: string,
  sha: string,
  since?: string,
): Promise<string[]> {
  const response = await octokit.repos.listCommits({
    owner,
    repo,
    path,
    sha,
    since,
  });

  // Return the SHA or another identifier from filtered commits
  return response.data.map((commit) => commit.sha);
}

// get pr number from commit
async function getPrNumberFromCommit(
  octokit: Octokit,
  owner: string,
  repo: string,
  commitSha: string,
): Promise<number | null> {
  const response = await octokit.repos.listPullRequestsAssociatedWithCommit({
    owner,
    repo,
    commit_sha: commitSha,
  });

  const prs = response.data;

  // Return the PR number or null if not found
  return prs.length > 0 ? prs[0].number : null;
}

// get the comments of a pull request sent by a user.
async function getApproversOfPullRequest(
  octokit: Octokit,
  owner: string,
  repo: string,
  prNumber: number,
  sender: string,
): Promise<string[]> {
  const response = await octokit.issues.listComments({
    owner,
    repo,
    issue_number: prNumber,
  });

  const comments = response.data;
  const approvalNotification = comments.findLast((comment) =>
    comment.user?.login === sender &&
    comment.body?.includes("[APPROVALNOTIFIER] This PR is **APPROVED**")
  );

  if (!approvalNotification) {
    return [];
  }

  // parse the approvalNotification.body to get the approvers
  // collect the approvers fron the line in the comment, the line like "This pull-request has been approved by: ......"
  // This pull-request has been approved by: *<a href="https://github.com/pingcap/tidb/pull/60909#pullrequestreview-2801227371" title="Approved">bb7133</a>*, *<a href="https://github.com/pingcap/tidb/pull/60909#pullrequestreview-2801699521" title="Approved">lance6716</a>*
  const approverElements = approvalNotification.body?.match(
    /This pull-request has been approved by: (.*)/,
  )?.[1].split(", ") || [];

  // extract the github id from the html <a> element,  the element like:
  // *<a href="https://github.com/pingcap/tidb/pull/60909#pullrequestreview-2801227371" title="Approved">bb7133</a>*
  const approvers: string[] = [];
  approverElements.forEach((element) => {
    const match = element.match(
      /<a href="https:\/\/github\.com\/([^/]+)\/[^"]+" title="Approved">([^<]+)<\/a>/,
    );
    if (match) {
      approvers.push(match[2]);
    }
  });

  return approvers;
}

async function reportForRepo(
  octokit: Octokit,
  owner: string,
  repo: string,
  branch: string,
  files: string[],
  since?: string,
) {
  const commits = new Set<string>();
  for (const file of files) {
    console.group(`parse commits that changed file: ${file}`);
    const fileCommits = await getCommits(
      octokit,
      owner,
      repo,
      file,
      branch,
      since,
    );
    fileCommits.forEach((commit) => {
      commits.add(commit);
    });
    if (fileCommits.length > 0) {
      console.log(
        `parsed ${fileCommits.length} commits: ${
          Array.from(fileCommits).join(", ")
        }`,
      );
    }
    console.groupEnd();
  }

  const pullNumbers = new Set<number>();
  for (const commit of commits) {
    console.group(`parsing pull request that make commit: ${commit}`);
    const num = await getPrNumberFromCommit(octokit, owner, repo, commit);
    if (num) {
      console.log(`parsed pull request number: ${num}`);
      pullNumbers.add(num);
    }
    console.groupEnd();
  }

  const results: Record<number, string[]> = {};
  for (const pullNumber of pullNumbers) {
    console.group(`parsing approvers of pull request: ${pullNumber}`);
    const approvers = await getApproversOfPullRequest(
      octokit,
      owner,
      repo,
      pullNumber,
      "ti-chi-bot[bot]",
    );
    console.log(`parsed approvers: ${approvers.join(", ")}`);
    results[pullNumber] = approvers;
    console.groupEnd();
  }

  return results;
}

async function main() {
  const token = Deno.env.get("GITHUB_TOKEN");
  const octokit = new Octokit({ auth: token! });

  const branch = "release-8.5";
  const since = "2025-01-17";

  const filesMap = {
    "pingcap/tidb": [
      "session/bootstrap.go",
      "sessionctx/variable/sysvar.go",
      "sessionctx/variable/session.go",
      "sessionctx/variable/tidb_vars.go",
      "config/config.go",
      "config/config.toml.example",
      "lightning/tidb-lightning.toml",
      "pkg/lightning/config/",
      "parser/parser.y",
    ],
    "pingcap/tiflow": [
      "pkg/cmd/util/changefeed.toml",
      "cdc/api/v2",
      "dm/master/config.go",
      "dm/worker/config.go",
      "dm/config/task.go",
      "dm/config/source_config.go",
    ],
    "pingcap/tiflash": [
      "dbms/src/Interpreters/Settings.h",
    ],
    // "pingcap/ticdc": [
    //   "pkg/config",
    // ]
    "tikv/tikv": [
      "components/batch-system/src/config.rs",
      "components/pd_client/src/config.rs",
      "components/sst_importer/src/config.rs",
      "components/raftstore/src/store/worker/split_config.rs",
      "components/raftstore/src/coprocessor/config.rs",
      "components/encryption/src/config.rs",
      "etc/config-template.toml",
      "src/coprocessor_v2/config.rs",
      "src/storage/config.rs",
      "src/server/gc_worker/config.rs",
      "src/server/lock_manager/config.rs",
      "src/server/config.rs",
      "src/config/mod.rs",
      "components/cdc/src/config.rs",
    ],
    "tikv/pd": [
      "server/config/config.go",
      "server/config/service_middleware_config.go",
      "pkg/encryption/config.go",
      "pkg/schedule/config/config.go",
      "pkg/schedule/config/store_config.go",
      "client/resource_group/controller/config.go",
      "client/tlsutil/tlsconfig.go",
      "pkg/mcs/scheduling/server/config/config.go",
      "pkg/mcs/tso/server/config.go",
      "pkg/mcs/resourcemanager/server/config.go",
      "pkg/schedule/schedulers/hot_region_config.go",
      "conf/config.toml",
    ],
  };

  for (const [repo, files] of Object.entries(filesMap)) {
    console.group(`report for ${repo}:`);
    const results = await reportForRepo(
      octokit,
      repo.split("/")[0],
      repo.split("/")[1],
      branch,
      files,
      since,
    );

    // if the results is empty, then return.
    if (Object.keys(results).length > 0) {
      console.group(`📑 associated pull requests:`);
      for (const pr in results) {
        console.log(`- ${pr}: approved by: ${results[pr].join(", ")}`);
      }
      console.groupEnd();
    }
    console.groupEnd();
    console.groupEnd();
  }

  console.log("🎉🎉🎉 All analysis finished!");
}

if (import.meta.main) {
  await main();
  Deno.exit(0);
}
