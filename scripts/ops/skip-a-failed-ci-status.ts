// steps:
// 1. get the latest commit of a github PR.
// 2. call api to set the commit status.
// write it with deno script

import { parseArgs } from "jsr:@std/cli@^1.0.1/parse-args";
import { Octokit } from "npm:octokit@3.1.0";

interface cliArgs {
    github_private_token: string;
    pr_url: string;
    check_name: string;
}

function parsePRUrl(
    prUrl: string,
): { owner: string; repo: string; pull_number: number } {
    const url = new URL(prUrl);
    const [, owner, repo, , pull_number] = url.pathname.split("/");
    return { owner, repo, pull_number: Number(pull_number) };
}

async function main({ github_private_token, pr_url, check_name }: cliArgs) {
    const octokit = new Octokit({ auth: github_private_token });
    // parse owner, repo and pull number from pr_url:

    const { owner, repo, pull_number } = parsePRUrl(pr_url);
    console.dir({ owner, repo, pull_number });
    const { data } = await octokit.rest.pulls.get({ owner, repo, pull_number });

    if (data.mergeable_state === "CONFLICTING") {
        console.error(
            `PR ${pull_number} is conflicted. Please resolve it manually.`,
        );
        Deno.exit(1);
    }

    // get the newest commit sha or the PR head sha
    const { data: commits } = await octokit.rest.pulls.listCommits({
        owner,
        repo,
        pull_number,
    });
    const commitSha = commits.pop()!.sha;
    console.log("last pr commit: ", commitSha);

    // set the commit status to the latest commit with "check_name"
    await octokit.rest.repos.createCommitStatus({
        owner,
        repo,
        sha: commitSha,
        state: "success",
        context: check_name,
        description: "Skiped by CI bot, no need to run it to save time",
    });
    console.info("✅ set commit status success");
}

const args = parseArgs(Deno.args) as cliArgs;
await main(args);
