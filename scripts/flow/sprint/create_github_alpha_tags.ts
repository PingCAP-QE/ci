import { Octokit } from "https://esm.sh/octokit@4.0.2?dts";
import { parseArgs } from "jsr:@std/cli@^1.0.1";
// import { parse, parseRange, satisfies } from "jsr:@std/semver@^1.0.3"; // FIXME: https://github.com/denoland/std/issues/6303

interface RepoInfo {
    owner: string;
    repo: string;
    product_name: string;
    tag_githash?: string;
    version?: string;
    release_msg?: string;
    branch_name?: string;
}

interface CliParams {
    token: string;
    alpha_version: string;
    latest_version: string;

    dryrun?: boolean;
}

const REPO_LIST: RepoInfo[] = [
    // {
    //     owner: "<test-org>",
    //     repo: "<test-repo>",
    //     product_name: "Test",
    //     branch_name: "main",
    // }, // repo for test.
    { owner: "tikv", repo: "tikv", product_name: "TiKV" },
    { owner: "tikv", repo: "pd", product_name: "PD" },
    { owner: "pingcap", repo: "tidb", product_name: "TiDB" },
    { owner: "pingcap", repo: "tiflash", product_name: "TiFlash" },
    { owner: "pingcap", repo: "tidb-binlog", product_name: "Binlog" },
    { owner: "pingcap", repo: "tiflow", product_name: "TiFlow" },
    { owner: "pingcap", repo: "ticdc", product_name: "TiCDC" },
    {
        owner: "pingcap",
        repo: "ng-monitoring",
        product_name: "NG-Monitoring",
        branch_name: "main",
    },
    {
        owner: "pingcap",
        repo: "tidb-dashboard",
        product_name: "TiDB-Dashboard",
    },
    { owner: "pingcap", repo: "monitoring", product_name: "Monitoring" },
];

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

    // "ticdc" repo will release from from 9.0.0, it's a new repo for new CDC component.
    if (version < "v9.0.0") {
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
                prerelease: true,
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

async function main(args: CliParams) {
    const octokit = new Octokit({
        auth: args.token,
        userAgent: "PingCAP Release v1.0.0",
    });
    // check the version.

    const repos = await fillRepoTagInfo(
        REPO_LIST,
        args.alpha_version,
        octokit,
    );

    console.log("will create alpha tag on repos:", repos);
    console.group("Check repo commit status");
    for (const repo of repos) {
        await checkRepoCommitStatus(repo, args.latest_version, octokit);
    }
    console.groupEnd();

    console.group("Create pre release");
    for (const repo of repos) {
        await createRelease(repo, octokit, args.dryrun);
    }
    console.groupEnd();
}

// parase cli params with `CliParams` and pass to main
const args = parseArgs(Deno.args) as CliParams;
await main(args);
Deno.exit(0);
