import { Octokit } from "https://esm.sh/octokit@4.0.2?dts";
import { parseArgs } from "jsr:@std/cli@^1.0.1/parse-args";
import { parse } from "jsr:@std/semver";

interface RepoInfo {
    owner: string;
    repo: string;
    tag_githash?: string;
    version?: string;
    branch_name?: string;
}

interface CliParams {
    token: string;
    version: string;
    dryrun?: boolean;
}

const REPO_LIST: RepoInfo[] = [
    { owner: "PingCAP-QE", repo: "tidb-test" },
    { owner: "pingcap-inc", repo: "enterprise-plugin" },
];

async function fillRepoTagInfo(
    repos: RepoInfo[],
    version: string,
    octokit: Octokit,
) {
    const v = parse(version);
    const releaseBranch = `release-${v.major}.${v.minor}`;
    console.dir({ version, releaseBranch });

    return await Promise.all(
        repos.map(async (repo) => {
            const info = {
                ...repo,
                version,
            } as RepoInfo;
            info.branch_name ||= releaseBranch;
            info.tag_githash = await lastCommitShaOfBranch(info, octokit);
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

async function createPatchReleaseBranch(
    repo: RepoInfo,
    octokit: Octokit,
    dryrun = false,
) {
    const newBranchName = repo.version?.replace("v", "release-");
    const createFunc = async () => {
        if (!dryrun) {
            await octokit.rest.git.createRef({
                owner: repo.owner,
                repo: repo.repo,
                ref: `refs/heads/${newBranchName}`,
                sha: repo.tag_githash!,
            });
        }
        console.log(
            `✅ Branch ${newBranchName} created successfully for ${repo.owner}/${repo.repo} based on commit: ${repo.tag_githash}`,
        );
    };

    console.group("Create branch: ", newBranchName);
    if (dryrun) console.info("🐒 dryrun enabled");

    const branchRef = await octokit.rest.git.getRef({
        owner: repo.owner,
        repo: repo.repo,
        ref: `heads/${newBranchName!}`,
    }).catch((error) => {
        console.error(error);
        if (error.status !== 404) throw error;
        console.info(
            `🚀 No branch ${newBranchName} found for ${repo.owner}/${repo.repo}.`,
        );
        return null;
    });

    if (branchRef) {
        console.warn(
            `🎯 Branch ${newBranchName} existed in ${repo.owner}/${repo.repo}.`,
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
        await createPatchReleaseBranch(repo, octokit, args.dryrun);
    }
}

// parase cli params with `CliParams` and pass to main
const args = parseArgs(Deno.args) as CliParams;
await main(args);
Deno.exit(0);

// Usage:
// deno run --allow-net scripts/flow/ga/create-patch-release-branches.ts --token $GITHUB_TOKEN --version 6.6.0 [--dryrun]
