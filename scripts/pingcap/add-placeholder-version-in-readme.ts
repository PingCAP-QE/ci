import * as flags from "https://deno.land/std@0.190.0/flags/mod.ts";
import { Octokit } from "npm:/octokit@3.1.0";

/**
 * Ref:
 * - github api with octokit.js: https://octokit.github.io/rest.js/v20
 */

const FILENAME = "README.md";
const VERSION_PLACEHOLDER_LINE = "<!-- VERSION_PLACEHOLDER: v{{version}} -->";
const VERSION_PLACEHOLDER_REGEX = new RegExp(
  `^<!-- VERSION_PLACEHOLDER: v[\\d.]+ -->`,
);
const HEAD_REF = `chore/add-placeholder-version-${Date.now()}`;
const COMMIT_MESSAGE = "[skip ci] update README.md file\n\n\nskip-checks: true";
const PR_TITLE = "chore: add placeholder version in README.md file";
const PR_BODY = `
### Check List

Tests <!-- At least one of them must be included. -->

- [x] No need to test
  > - [x] I checked and no code files have been changed.
  > <!-- Or your custom  "No need to test" reasons -->

skip-checks: true
`;

async function isFileExistence(
  octokit: Octokit,
  owner: string,
  repo: string,
  ref: string, // Specify the branch name, e.g., "main"
  path: string,
) {
  try {
    const res: { data: { sha: string } } = await octokit.rest.repos.getContent({
      owner,
      repo,
      path,
      ref,
    });
    return res.data.sha;
  } catch (error) {
    if (error.status === 404) {
      return false; // File does not exist
    } else {
      throw error; // Other error occurred
    }
  }
}

async function getFile(
  octokit: Octokit,
  owner: string,
  repo: string,
  ref: string, // Specify the branch name, e.g., "main"
  path: string,
) {
  try {
    const contentResponse = await octokit.rest.repos.getContent({
      owner,
      repo,
      path,
      ref,
    });
    return atob(contentResponse.data.content);
  } catch (error) {
    if (error.status === 404) {
      return undefined; // File does not exist
    } else {
      throw error; // Other error occurred
    }
  }
}

async function createOrUpdateFileContent(
  octokit: Octokit,
  owner: string,
  repo: string,
  path: string,
  message: string,
  content: string,
  branch: string,
) {
  const sha = await isFileExistence(octokit, owner, repo, branch, path);
  const params = sha
    ? {
      owner,
      repo,
      path,
      message,
      content: btoa(content),
      branch,
      sha,
    }
    : {
      owner,
      repo,
      path,
      message,
      content: btoa(content),
      branch,
    };

  // Update the /OWNERS file in the new branch
  await octokit.rest.repos.createOrUpdateFileContents(params);
}

async function createUpdateFilePR(
  octokit: Octokit,
  owner: string,
  repo: string,
  baseRef: string,
  placeholder: string,
  draft = false,
) {
  const fileContent = await getFile(octokit, owner, repo, baseRef, FILENAME);
  if (!fileContent) {
    return;
  }
  const versionPlaceholderLine = VERSION_PLACEHOLDER_LINE.replace(
    "{{version}}",
    placeholder,
  );

  let updatedContent;
  if (fileContent.match(VERSION_PLACEHOLDER_REGEX)) {
    updatedContent = fileContent.replace(
      VERSION_PLACEHOLDER_REGEX,
      versionPlaceholderLine,
    );
    if (fileContent === updatedContent) {
      return;
    }
  } else {
    updatedContent = fileContent + "\n" + versionPlaceholderLine;
  }

  // Create a new branch
  // Get target branch's git commit SHA.
  const { data } = await octokit.rest.git.getRef({
    owner,
    repo,
    ref: `heads/${baseRef}`,
  });
  const baseSha = data.object.sha;
  await octokit.rest.git.createRef({
    owner,
    repo,
    ref: `refs/heads/${HEAD_REF}`,
    sha: baseSha,
  });
  console.debug(`created branch in ${owner}/${repo}: ${HEAD_REF}`);

  console.info(`🫧 updating file ${FILENAME} in ${owner}/${repo}@${HEAD_REF}`);
  await createOrUpdateFileContent(
    octokit,
    owner,
    repo,
    FILENAME,
    COMMIT_MESSAGE,
    updatedContent,
    HEAD_REF,
  );
  console.debug(`📃 updated file ${FILENAME} in ${owner}/${repo}@${HEAD_REF}`);

  // Create a pull request
  const { data: pr } = await octokit.rest.pulls.create({
    owner,
    repo: repo,
    title: PR_TITLE,
    body: PR_BODY,
    head: HEAD_REF,
    base: baseRef,
    draft,
  });

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

  // add "skip-issue-check", "lgtm", "approved" labels:
  await octokit.rest.issues.addLabels({
    owner,
    repo,
    issue_number: prNumber,
    labels: toAddLabels,
  });
}

interface cliArgs {
  owner: string;
  repo: string;
  branch: string;
  placeholder: string;
  github_private_token: string;
  draft: boolean;
  force: boolean; // force create README.md if not existed.
}

async function main(
  {
    owner,
    repo,
    branch,
    placeholder,
    github_private_token,
    draft,
  }: cliArgs,
) {
  // Create a new Octokit instance using the provided token
  const octokit = new Octokit({ auth: github_private_token });

  console.debug(`🫧 prepare update for repo: ${owner}/${repo}`);
  const pr = await createUpdateFilePR(
    octokit,
    owner,
    repo,
    branch,
    placeholder,
    draft,
  );

  if (pr) {
    console.info(`✅ Pull request created: ${pr.html_url}`);
  } else {
    console.info(`🏃 for repo ${owner}/${repo}, no need to create PR.`);
  }

  // Wait a minute after the pull requests created: let the approve plugin dealing firstly.
  await new Promise((resolve) => setTimeout(resolve, 60000));

  console.info(
    `🫧 Post dealing for pull request: ${owner}/${repo}/${pr!.number} ...`,
  );
  const toAddLabels = ["skip-issue-check", "lgtm", "approved"];
  if (branch.includes("release-")) {
    toAddLabels.push("cherry-pick-approved");
  }
  await postDealPR(octokit, owner, repo, pr!.number, toAddLabels);
  console.info(
    `✅ Post done for pull request: ${owner}/${repo}/${pr!.number} ...`,
  );
}

// Execute the main function
/**
 * ---------entry----------------
 * ****** CLI args **************
 * --owner <github ORG>
 * --repo repo name
 * --branch repo branch
 * --placeholder placeholder version(without prefix "v" char.)
 * --draft optional
 * --github_private_token <github private token>
 */
const args = flags.parse<cliArgs>(Deno.args);
await main(args);
Deno.exit(0);
