import * as flags from "https://deno.land/std@0.190.0/flags/mod.ts";
import { Octokit, RequestError } from "npm:/octokit@3.1.0";

const DELAY_SECONDS_BEFORE_CREATE_PR = 5;
const DELAY_SECONDS_BEFORE_DEAL_PR = 5;
// All the updates for the same base branch share one stable source branch, so
// every run force-updates that branch instead of opening yet another PR.
const BOT_BRANCH_PREFIX = "bot/update-submodule";

interface cliArgs {
  owner: string;
  repository: string;

  base_ref: string;
  sub_owner: string;
  sub_repository: string;
  sub_ref: string;
  path: string;
  github_private_token: string;
  draft: boolean;
  add_labels: string[]; // labels to add in post dealing.
}

function headBranchName(baseRef: string) {
  return `${BOT_BRANCH_PREFIX}-${baseRef}`;
}

function newCommitMsg(submodulePath: string) {
  return `[SKIP-CI] update submodule ${submodulePath}

skip-checks: true
`;
}

function newPRDescription(
  submodulePath: string,
  subOwner: string,
  subRepository: string,
  subRef: string,
  subSha: string,
) {
  return `
### What problem does this PR solve?

Problem Summary: update submodule \`${submodulePath}\` to \`${subOwner}/${subRepository}@${subRef}\` (\`${subSha}\`).

This pull request is force-updated on every run of the submodule auto
updating job, so its source branch and diff always point at the latest
upstream commit.

### What changed and how does it work?

See code changes.

### Check List

Tests <!-- At least one of them must be included. -->

- [x] Unit test
- [ ] Integration test
- [ ] Manual test (add detailed scripts or steps below)
- [ ] No need to test
  > - [ ] I checked and no code files have been changed.
  > <!-- Or your custom  "No need to test" reasons -->

Side effects

- [ ] Performance regression: Consumes more CPU
- [ ] Performance regression: Consumes more Memory
- [ ] Breaking backward compatibility

Documentation

- [ ] Affects user behaviors
- [ ] Contains syntax changes
- [ ] Contains variable changes
- [ ] Contains experimental features
- [ ] Changes MySQL compatibility

### Release note

<!-- compatibility change, improvement, bugfix, and new feature need a release note -->

Please refer to [Release Notes Language Style Guide](https://pingcap.github.io/tidb-dev-guide/contribute-to-tidb/release-notes-style-guide.html) to write a quality release note.

\`\`\`release-note
None
\`\`\`

`;
}

function isHttpStatus(error: unknown, status: number) {
  if (error instanceof RequestError) {
    return error.status === status;
  }
  return typeof error === "object" && error !== null && "status" in error &&
    (error as { status?: number }).status === status;
}

async function getBranchSha(
  octokit: Octokit,
  owner: string,
  repo: string,
  branch: string,
) {
  const { data } = await octokit.rest.git.getRef({
    owner,
    repo,
    ref: `heads/${branch}`,
  });
  return data.object.sha;
}

// Returns the commit hash the submodule currently points at on the given ref,
// or `undefined` when the path does not exist (or is not a submodule) yet.
async function getSubmoduleSha(
  octokit: Octokit,
  owner: string,
  repo: string,
  ref: string,
  path: string,
): Promise<string | undefined> {
  try {
    const { data } = await octokit.rest.repos.getContent({
      owner,
      repo,
      ref,
      path,
    });
    if (Array.isArray(data)) {
      return undefined;
    }
    return data.type === "submodule" ? data.sha : undefined;
  } catch (error) {
    if (isHttpStatus(error, 404)) {
      return undefined;
    }
    throw error;
  }
}

async function listOpenPRs(
  octokit: Octokit,
  owner: string,
  repo: string,
  headBranch: string,
  baseRef: string,
) {
  const { data } = await octokit.rest.pulls.list({
    owner,
    repo,
    state: "open",
    head: `${owner}:${headBranch}`,
    ...(baseRef ? { base: baseRef } : {}),
  });
  return data;
}

// Create the source branch, or force-update it when it already exists.
async function upsertBranch(
  octokit: Octokit,
  owner: string,
  repo: string,
  branch: string,
  sha: string,
) {
  try {
    await octokit.rest.git.createRef({
      owner,
      repo,
      ref: `refs/heads/${branch}`,
      sha,
    });
    return;
  } catch (error) {
    if (!isHttpStatus(error, 422)) {
      throw error;
    }
  }

  await octokit.rest.git.updateRef({
    owner,
    repo,
    ref: `heads/${branch}`,
    sha,
    force: true,
  });
}

async function postDealPR(
  octokit: Octokit,
  owner: string,
  repo: string,
  prNumber: number,
  toAddLabels: string[],
) {
  // add "/release-note-none" comment.
  await octokit.rest.issues.createComment({
    owner,
    repo,
    issue_number: prNumber,
    body: "/release-note-none",
  }).catch((error: unknown) => console.error("Error creating comment:", error));

  if (toAddLabels) {
    await octokit.rest.issues.addLabels({
      owner,
      repo,
      issue_number: prNumber,
      labels: toAddLabels,
    }).catch((error: unknown) => console.error("Error add labels:", error));
  }
}

// The submodule is already up to date, so the auto updating PR (if any) is a
// no-op. Close it and drop its source branch to keep the repository clean.
async function closeStalePRs(
  octokit: Octokit,
  owner: string,
  repo: string,
  headBranch: string,
  prs: Awaited<ReturnType<typeof listOpenPRs>>,
  path: string,
  subSha: string,
) {
  if (prs.length === 0) {
    return;
  }

  for (const pr of prs) {
    await octokit.rest.issues.createComment({
      owner,
      repo,
      issue_number: pr.number,
      body:
        `Closing this pull request because submodule \`${path}\` is already up to date at \`${subSha}\`.`,
    }).catch((error: unknown) =>
      console.error("Error creating comment:", error)
    );

    await octokit.rest.pulls.update({
      owner,
      repo,
      pull_number: pr.number,
      state: "closed",
    }).catch((error: unknown) => console.error("Error closing PR:", error));

    console.info(`🧹 Closed stale pull request: ${pr.html_url}`);
  }

  await octokit.rest.git.deleteRef({
    owner,
    repo,
    ref: `heads/${headBranch}`,
  }).catch((error: unknown) =>
    console.warn(`⚠️ Failed to delete branch ${headBranch}:`, error)
  );
}

// Ref: https://stackoverflow.com/questions/45789854/how-to-update-a-submodule-to-a-specified-commit-via-github-rest-api
async function updateSubmodule(
  octokit: Octokit,
  owner: string,
  repository: string,
  baseRef: string,
  subOwner: string,
  subRepository: string,
  subRef: string,
  path: string,
  draft: boolean,
  addLabels: string[],
) {
  console.debug("-----");
  console.debug({
    owner,
    repository,
    baseRef,
    subOwner,
    subRepository,
    subRef,
    path,
  });

  const headBranch = headBranchName(baseRef);
  console.info(
    `ℹ️ Updating ${owner}/${repository}@${baseRef} submodule ${path} via ${headBranch} ...`,
  );

  // Get target branch's git commit SHA.
  const baseSha = await getBranchSha(octokit, owner, repository, baseRef);
  // Get git commit SHA of submodule repo you want to update to.
  const subSha = await getBranchSha(
    octokit,
    subOwner,
    subRepository,
    subRef,
  );
  const existingPRs = await listOpenPRs(
    octokit,
    owner,
    repository,
    headBranch,
    baseRef,
  );
  const currentSubSha = await getSubmoduleSha(
    octokit,
    owner,
    repository,
    baseRef,
    path,
  );

  if (currentSubSha === subSha) {
    console.info(
      `✅ Submodule ${path} in ${owner}/${repository}@${baseRef} already points at ${subSha}, nothing to do.`,
    );
    await closeStalePRs(
      octokit,
      owner,
      repository,
      headBranch,
      existingPRs,
      path,
      subSha,
    );
    return;
  }

  // Create a git tree that updates the submodule reference:
  const { data: baseCommit } = await octokit.rest.git.getCommit({
    owner,
    repo: repository,
    commit_sha: baseSha,
  });
  const { data: treeData } = await octokit.rest.git.createTree({
    owner,
    repo: repository,
    base_tree: baseCommit.tree.sha,
    tree: [{
      path,
      sha: subSha,
      mode: "160000",
      type: "commit",
    }],
  });

  // Create the update commit on top of the latest base branch head.
  console.debug("Create commit...");
  const { data: newCommitData } = await octokit.rest.git.createCommit({
    owner,
    repo: repository,
    message: newCommitMsg(path),
    tree: treeData.sha,
    parents: [baseSha],
  });

  // Create or force-update the source branch to point at the new commit.
  console.debug("Upsert ref...");
  await upsertBranch(
    octokit,
    owner,
    repository,
    headBranch,
    newCommitData.sha,
  );

  // Delay for a few seconds, give some time to github to deal the new data.
  await new Promise((resolve) =>
    setTimeout(resolve, DELAY_SECONDS_BEFORE_CREATE_PR * 1000)
  );

  // Reuse the existing pull request if there is one: force-updating the source
  // branch above already refreshed it, so no new pull request is needed.
  if (existingPRs.length > 0) {
    const pr = existingPRs[0];
    await octokit.rest.pulls.update({
      owner,
      repo: repository,
      pull_number: pr.number,
      title: `${path}: update submodule`,
      body: newPRDescription(path, subOwner, subRepository, subRef, subSha),
    }).catch((error: unknown) =>
      console.warn("⚠️ Failed to refresh pull request:", error)
    );
    console.info(
      `✅ Force-updated source branch ${headBranch}, reused pull request: ${pr.html_url}`,
    );
    return;
  }

  console.debug("🫧 Creating pull request...");
  const { data: pr } = await octokit.rest.pulls.create({
    owner,
    repo: repository,
    title: `${path}: update submodule`,
    body: newPRDescription(path, subOwner, subRepository, subRef, subSha),
    head: headBranch,
    base: baseRef,
    draft,
  });
  console.info(
    `✅ Pull request created for repo ${owner}/${repository}: ${pr.html_url}`,
  );

  // Post deal the pull request.
  console.info(
    `🫧 Post dealing for pull request: ${owner}/${repository}/${pr.number} ...`,
  );

  // Wait a moment, let's other plugins run firstly.
  await new Promise((resolve) =>
    setTimeout(resolve, DELAY_SECONDS_BEFORE_DEAL_PR * 1000)
  );

  await postDealPR(octokit, owner, repository, pr.number, addLabels);
  console.info(
    `✅ Post done for pull request: ${owner}/${repository}/${pr.number} ...`,
  );
}

// Execute the main function
/**
 * ---------entry----------------
 * ****** CLI args **************
 * --owner <github ORG>
 * --repository <repo name>
 * --base_ref <base ref>
 * --sub_owner <submodule github ORG>
 * --sub_repository <submodule repo name>
 * --sub_ref <upstream sub module's ref>
 * --path <submodule path in repo>
 * --github_private_token <github private token>
 * --draft, optional.
 * --add_labels <label>
 */
async function main(args: cliArgs) {
  const {
    owner,
    repository,
    base_ref: baseRef,
    sub_owner: subOwner,
    sub_repository: subRepository,
    sub_ref: subRef,
    path,
    github_private_token: githubPrivateToken,
    draft,
    add_labels: addLabels,
  } = args;

  // Create a new Octokit instance using the provided token
  const octokit = new Octokit({ auth: githubPrivateToken });

  await updateSubmodule(
    octokit,
    owner,
    repository,
    baseRef,
    subOwner,
    subRepository,
    subRef,
    path,
    draft,
    addLabels,
  );
}

const args = flags.parse<cliArgs>(Deno.args, {
  collect: ["add_labels"] as never[],
});
await main(args);
