import { Octokit } from "https://esm.sh/octokit@4.1.3?dts";
import { parseArgs } from "jsr:@std/cli/parse-args";

async function listBranches(
  octokit: Octokit,
  owner: string,
  repo: string,
  pattern: RegExp,
) {
  const results = await octokit.paginate(octokit.rest.repos.listBranches, {
    owner: owner,
    repo: repo,
    per_page: 100,
  });

  return results.filter((branch) => pattern.test(branch.name));
}

async function cleanBranch(
  octokit: Octokit,
  branchName: string,
  base: { owner: string; repo: string },
  head: { owner: string; repo: string },
) {
  // find the opened PRs that have created with the head branch on the base repo.
  const openedPulls = await octokit.rest.pulls.list({
    owner: base.owner,
    repo: base.repo,
    head: `${head.owner}:${branchName}`,
    state: "open",
    per_page: 100,
  });

  // return if there are opened PRs
  if (openedPulls.data.length > 0) {
    console.group(`Branch ${branchName} has opened PRs, skipping deletion.`);
    openedPulls.data.forEach((pull) => console.log(pull.html_url));
    console.groupEnd();
    return false;
  }

  await octokit.rest.git.deleteRef({
    owner: head.owner,
    repo: head.repo,
    ref: `heads/${branchName}`,
  });
  console.log(`Branch ${branchName} deleted successfully.`);
  return true;
}

async function cleanBranches(
  octokit: Octokit,
  base: { owner: string; repo: string },
  head: { owner: string; repo: string },
  pattern: RegExp,
) {
  const branches = await listBranches(
    octokit,
    head.owner,
    head.repo,
    pattern,
  );
  console.log("branch count:", branches.length);

  const ret = { deleted: 0, keep: 0 };
  for (const b of branches) {
    const deleted = await cleanBranch(octokit, b.name, base, head);
    if (deleted) {
      ret.deleted++;
    } else {
      ret.keep++;
    }
  }

  return ret;
}

async function main() {
  const flags = parseArgs(Deno.args, { string: ["token"] });
  const octokit = new Octokit({ auth: flags.token });
  const pattern = /^cherry-pick-[0-9]+-to-release-.+/;

  // delete for ti-chi-bot/tidb
  console.group("clean branches for ti-chi-bot/tidb");
  const tidbRet = await cleanBranches(
    octokit,
    { owner: "pingcap", repo: "tidb" },
    { owner: "ti-chi-bot", repo: "tidb" },
    pattern,
  );
  console.log("clean result for tidb:", tidbRet);
  console.groupEnd();

  // delete for ti-chi-bot/tiflash
  console.group("clean branches for ti-chi-bot/tiflash");
  const tiflashRet = await cleanBranches(
    octokit,
    { owner: "pingcap", repo: "tiflash" },
    { owner: "ti-chi-bot", repo: "tiflash" },
    pattern,
  );
  console.log("clean result for tiflash:", tiflashRet);
  console.groupEnd();

  // delete for ti-chi-bot/tiflow
  console.group("clean branches for ti-chi-bot/tiflow");
  const tiflowRet = await cleanBranches(
    octokit,
    { owner: "pingcap", repo: "tiflow" },
    { owner: "ti-chi-bot", repo: "tiflow" },
    pattern,
  );
  console.log("clean result for tiflow:", tiflowRet);
  console.groupEnd();

  // delete for ti-chi-bot/tikv
  console.group("clean branches for ti-chi-bot/tikv");
  const tikvRet = await cleanBranches(
    octokit,
    { owner: "tikv", repo: "tikv" },
    { owner: "ti-chi-bot", repo: "tikv" },
    pattern,
  );
  console.log("clean result for tikv:", tikvRet);
  console.groupEnd();

  // delete for ti-chi-bot/pd
  console.group("clean branches for ti-chi-bot/pd");
  const pdRet = await cleanBranches(
    octokit,
    { owner: "tikv", repo: "pd" },
    { owner: "ti-chi-bot", repo: "pd" },
    pattern,
  );
  console.log("clean result for pd:", pdRet);
  console.groupEnd();
}

// Run the application
if (import.meta.main) {
  await main();
  Deno.exit(0);
}
