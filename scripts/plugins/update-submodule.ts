import * as flags from "https://deno.land/std@0.190.0/flags/mod.ts";
import { Octokit } from "npm:/octokit@3.1.0";

const HEAD_REF = `bot/update-submodule-${Date.now()}`;
const DELAY_SECONDS_BEFORE_CREATE_PR = 5;
const DELAY_SECONDS_BEFORE_DEAL_PR = 5;

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

function newCommitMsg(submodulePath: string) {
  return `[SKIP-CI] update submodule ${submodulePath}

skip-checks: true
`;
}

function newPRDescription(submodulePath: string) {
  return `
### What problem does this PR solve?

Problem Summary: update submodule ${submodulePath}

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

async function createUpdateSubModulePR(
  octokit: Octokit,
  owner: string,
  repository: string,
  subOwner: string,
  subRepository: string,
  baseRef: string,
  subRef: string,
  path: string,
  draft = false,
) {
  // Get target branch's git commit SHA.
  console.debug("-----");
  console.debug({
    owner,
    repository,
    subOwner,
    subRepository,
    baseRef,
    subRef,
  });

  const { data } = await octokit.rest.git.getRef({
    owner,
    repo: repository,
    ref: `heads/${baseRef}`,
  });
  const baseSha = data.object.sha;

  // Get git commit SHA of submodule repo you want to update to
  const { data: subData } = await octokit.rest.git.getRef({
    owner: subOwner,
    repo: subRepository,
    ref: `heads/${subRef}`,
  });
  const subSha = subData.object.sha;

  // Create a new branch
  const { data: headData } = await octokit.rest.git.createRef({
    owner,
    repo: repository,
    ref: `refs/heads/${HEAD_REF}`,
    sha: baseSha,
  });
  console.debug(headData);
  console.debug(`created branch in ${owner}/${repository}: ${HEAD_REF}`);

  // Create a git tree that updates the submodule reference:
  const { data: treeData } = await octokit.rest.git.createTree({
    owner,
    repo: repository,
    base_tree: headData.object.sha,
    tree: [{
      path,
      sha: subSha,
      mode: "160000",
      type: "commit",
    }],
  });

  // Create the update commit
  console.debug("Create commit...");
  const { data: newCommitData } = await octokit.rest.git.createCommit({
    owner,
    repo: repository,
    message: newCommitMsg(path),
    tree: treeData.sha,
    parents: [headData.object.sha],
  });

  // Update head ref to point to your new commit.
  console.debug("Update ref...");
  octokit.rest.git.updateRef({
    owner,
    repo: repository,
    ref: `heads/${HEAD_REF}`,
    sha: newCommitData.sha,
  });

  // Delay for a few seconds, give some time to github to deal the new data.
  await new Promise((resolve) =>
    setTimeout(resolve, DELAY_SECONDS_BEFORE_CREATE_PR * 1000)
  );

  console.debug("🫧 Creating pull request...");
  // Create a pull request
  const { data: pr } = await octokit.rest.pulls.create({
    owner,
    repo: repository,
    title: `${path}: update submodule`,
    body: newPRDescription(path),
    head: HEAD_REF,
    base: baseRef,
    draft,
  });
  console.info(
    `✅ Pull request created for repo ${owner}/${repository}: ${pr.html_url}`,
  );

  return pr;
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
  }).catch((error: any) => console.error("Error creating comment:", error));

  if (toAddLabels) {
    await octokit.rest.issues.addLabels({
      owner,
      repo,
      issue_number: prNumber,
      labels: toAddLabels,
    }).catch((error: any) => console.error("Error add labels:", error));
  }
}

// Ref: https://stackoverflow.com/questions/45789854/how-to-update-a-submodule-to-a-specified-commit-via-github-rest-api
async function main(args: cliArgs) {
  const {
    owner,
    repository,
    base_ref,
    sub_owner,
    sub_repository,
    sub_ref,
    path,
    github_private_token,
    draft,
    add_labels: addLabels,
  } = args;
  // Create a new Octokit instance using the provided token
  const octokit = new Octokit({ auth: github_private_token });

  const pullRequest = await createUpdateSubModulePR(
    octokit,
    owner,
    repository,
    sub_owner,
    sub_repository,
    base_ref,
    sub_ref,
    path,
    draft,
  );

  // Post deal the pull requests.
  console.info(
    `🫧 Post dealing for pull request: ${owner}/${repository}/${pullRequest.number} ...`,
  );

  // Wait a moment, let's other plugins run firstly.
  await new Promise((resolve) =>
    setTimeout(resolve, DELAY_SECONDS_BEFORE_DEAL_PR * 1000)
  );

  await postDealPR(
    octokit,
    owner,
    repository,
    pullRequest.number,
    addLabels,
  );
  console.info(
    `✅ Post done for pull request: ${owner}/${repository}/${pullRequest.number} ...`,
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
const args = flags.parse<cliArgs>(Deno.args, {
  collect: ["add_labels"] as never[],
});
await main(args);
