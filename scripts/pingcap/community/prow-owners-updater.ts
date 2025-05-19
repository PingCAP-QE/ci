import * as yaml from "jsr:@std/yaml@1.0.5";
import { Octokit } from "https://esm.sh/@octokit/rest@21.1.1?dts";
import { RequestError } from "https://esm.sh/@octokit/request-error@6.1.8?dts";
import {
  mergeProwRepoOwners,
  ProwOwners,
  ProwRepoOwners,
  sameObjects,
} from "./prow-owners.ts";
import { RepoOwnersParser } from "./prow-owners-parser.ts";
import { PathOwners, RepoOwners } from "./community-parser.ts";

/**
 * Ref:
 * - github api with octokit.js: https://octokit.github.io/rest.js/v21
 */

const COMMIT_MESSAGE = `
[skip ci] Update OWNERS file

skip-checks: true
Signed-off-by: Ti Chi Robot <ti-community-prow-bot@tidb.io>
`;
const PR_TITLE = "OWNERS: Auto Sync OWNERS files from community membership";
const PR_BODY = `
### Check List

Tests <!-- At least one of them must be included. -->

- [x] No need to test
  > - [x] I checked and no code files have been changed.
  > <!-- Or your custom  "No need to test" reasons -->

`;

class RepoUpdater {
  constructor(
    private octokit: Octokit,
    private owner: string,
    private repo: string,
    private ref: string,
  ) {}

  async isFileExistence(ref: string, path: string) {
    try {
      const res = await this.octokit.rest.repos.getContent({
        owner: this.owner,
        repo: this.repo,
        ref,
        path,
      });

      if ("sha" in res.data) {
        return res.data.sha;
      } else if (
        Array.isArray(res.data) && res.data.length > 0 && "sha" in res.data[0]
      ) {
        return res.data[0].sha;
      }

      return false;
    } catch (err) {
      // if err is RequestError
      if (err instanceof RequestError && err.status === 404) {
        return false; // File does not exist
      } else {
        console.error(`Error fetching file ${path}: ${err}`);
        throw err; // Other error occurred
      }
    }
  }

  convertPathOwners(
    owners: PathOwners,
    options?: { filter?: boolean; aliases?: boolean; aliasPrefix?: string },
  ) {
    const finalOwners: ProwOwners = { ...owners };
    const aliases: Record<string, string[]> = {};

    if (options?.aliases && options.aliasPrefix) {
      const reviewersAliasName = `${options.aliasPrefix}reviewers`;
      const approversAliasName = `${options.aliasPrefix}approvers`;

      if (finalOwners.reviewers) {
        aliases[reviewersAliasName] = finalOwners.reviewers;
        finalOwners.reviewers = [reviewersAliasName];
      }
      if (finalOwners.approvers) {
        aliases[approversAliasName] = finalOwners.approvers;
        finalOwners.approvers = [approversAliasName];
      }
    }

    if (options?.filter) {
      return { owners: { filters: { ".*": finalOwners } }, aliases };
    } else {
      return { owners: finalOwners, aliases };
    }
  }

  convertRepoOwners(
    owners: RepoOwners,
    rootOptions?: { filter?: boolean; aliases?: boolean },
  ) {
    const results: ProwRepoOwners = { owners: {}, aliases: {} };
    for (const [path, po] of Object.entries(owners)) {
      const options = (path === "/" && rootOptions)
        ? {
          ...rootOptions,
          aliasPrefix: "sig-community-",
        }
        : {};

      const { owners: pr, aliases: pa } = this.convertPathOwners(po, options);
      results.owners[path] = pr;
      for (const [alias, set] of Object.entries(pa)) {
        results.aliases[alias] = set;
      }
    }

    return results;
  }

  async createOrUpdateFileContent(
    path: string,
    message: string,
    content: string,
    branch: string,
  ) {
    const sha = await this.isFileExistence(branch, path);
    const params = sha
      ? {
        owner: this.owner,
        repo: this.repo,
        path,
        message,
        content: btoa(content),
        branch,
        sha,
      }
      : {
        owner: this.owner,
        repo: this.repo,
        path,
        message,
        content: btoa(content),
        branch,
      };

    await this.octokit.rest.repos.createOrUpdateFileContents(params);
  }

  async getFileContent(path: string) {
    const contentResponse = await this.octokit.rest.repos.getContent({
      owner: this.owner,
      repo: this.repo,
      ref: this.ref,
      path,
    });
    const fileContent = contentResponse.data as { content: string };
    return atob(fileContent.content);
  }

  async toUpdateFileAndContents(ownersMap: ProwRepoOwners) {
    const updateFileAndContents: Record<string, string> = {};

    // for `OWNERS` files.
    for (const scope in ownersMap.owners) {
      const yamlContentLines = [
        `# See the OWNERS docs at https://www.kubernetes.dev/docs/guide/owners/#owners`,
      ];
      if (scope === "/" && Object.keys(ownersMap.aliases).length > 0) {
        yamlContentLines.push(
          `# The members of 'sig-community-*' are synced from memberships defined in repository: https://github.com/${this.owner}/community.`,
        );
      }
      yamlContentLines.push(yaml.stringify(
        JSON.parse(JSON.stringify(ownersMap.owners[scope])),
      ));
      const yamlContent = yamlContentLines.join("\n");

      const filePath = scope.startsWith("/")
        ? (scope === "/" ? "OWNERS" : `${scope.substring(1)}/OWNERS`)
        : `${scope}/OWNERS`;

      updateFileAndContents[filePath] = yamlContent;
    }

    // for `OWNERS_ALIASES` file.
    {
      const fileContent = [
        `# See the OWNERS docs at https://www.kubernetes.dev/docs/guide/owners/#owners_aliases`,
        `# The members of 'sig-community-*' are synced from memberships defined in repository: https://github.com/${this.owner}/community.`,
        yaml.stringify(
          JSON.parse(JSON.stringify({ aliases: ownersMap.aliases })),
        ),
      ].join("\n");

      updateFileAndContents["OWNERS_ALIASES"] = fileContent;
    }

    // add .github/licenserc.[yml|yaml] to the udpate list if the file existed.
    for (const ext of ["yml", "yaml"]) {
      const filePath = `.github/licenserc.${ext}`;

      const licensercContent = await this.getFileContent(filePath);
      if (!licensercContent) {
        continue;
      }

      const licenserc = yaml.parse(licensercContent) as {
        header: { "paths-ignore": string[]; [key: string]: unknown };
        [key: string]: unknown;
      };

      const ignorePaths = licenserc.header["paths-ignore"];
      let needUpdateIgnore = false;
      for (const p of ["**/OWNERS", "OWNERS_ALIASES"]) {
        if (!ignorePaths.includes(p)) {
          ignorePaths.push(p);
          needUpdateIgnore = true;
        }
      }

      if (needUpdateIgnore) {
        updateFileAndContents[filePath] = yaml.stringify(licenserc);
      }

      break;
    }

    return updateFileAndContents;
  }

  async createUpdateFilePR(
    ownersMap: RepoOwners,
    draft = false,
    force = false,
    filterMode = false,
  ) {
    if (Object.keys(ownersMap).length === 0) {
      console.debug("no need to create PR");
      return undefined;
    }

    // Get target branch's git commit SHA.
    const { data } = await this.octokit.rest.git.getRef({
      owner: this.owner,
      repo: this.repo,
      ref: `heads/${this.ref}`,
    });
    const baseSha = data.object.sha;

    // get current repo prow OWNERS.
    const repoParser = new RepoOwnersParser(
      this.octokit,
      this.owner,
      this.repo,
      this.ref,
    );
    const curRepoProwOwners = await repoParser.All();

    const newRepoProwOwners = this.convertRepoOwners(
      ownersMap,
      filterMode ? { aliases: true, filter: true } : {},
    );
    const mergedRepoProwOwners = mergeProwRepoOwners(
      curRepoProwOwners,
      newRepoProwOwners,
    );

    if (!mergedRepoProwOwners.owners["/"]) {
      console.log("No new repo prow OWNERS found");
      return;
    }

    if (mergedRepoProwOwners.owners["/"]) {
      const rootIsSame = sameObjects(
        mergedRepoProwOwners.owners["/"],
        newRepoProwOwners.owners["/"],
      ) && sameObjects(
        mergedRepoProwOwners.aliases,
        newRepoProwOwners.aliases,
      );

      if (rootIsSame) {
        console.debug(
          `🏃 no need to create PR for repo ${this.owner}/${this.repo}: no differences found`,
        );
        return;
      }
    } else if (!force) {
      console.debug(`No root OWNERS found for repo ${this.owner}/${this.repo}`);
      return;
    }

    const updateFileAndContents = await this.toUpdateFileAndContents(
      mergedRepoProwOwners,
    );
    if (Object.keys(updateFileAndContents).length == 0) {
      console.debug(
        `🏃 no need to create PR for repo ${this.owner}/${this.repo}: no differences found`,
      );
      return undefined;
    }

    // Create a new branch
    const headBranch = `bot/update-owners-${Date.now()}`;
    await this.octokit.rest.git.createRef({
      owner: this.owner,
      repo: this.repo,
      ref: `refs/heads/${headBranch}`,
      sha: baseSha,
    });
    console.debug(
      `created branch '${headBranch}' in repo '${this.owner}/${this.repo}'`,
    );

    await Promise.all(
      Object.entries(updateFileAndContents).map(
        async ([filePath, content], index) => {
          // wait for some seconds to avoid conflicts on fetch the sha of file blob.
          await new Promise((resolve) => setTimeout(resolve, 5000 * index));

          console.info(
            `🫧 updating file ${filePath} for repo: ${this.owner}/${this.repo}@${headBranch}`,
          );
          await this.createOrUpdateFileContent(
            filePath,
            COMMIT_MESSAGE,
            content,
            headBranch,
          );
          console.debug(
            `📃 updated file ${filePath} for repo: ${this.owner}/${this.repo}@${headBranch}`,
          );
        },
      ),
    );

    // Create a pull request
    const { data: pr } = await this.octokit.rest.pulls.create({
      owner: this.owner,
      repo: this.repo,
      title: PR_TITLE,
      body: PR_BODY,
      head: headBranch,
      base: this.ref,
      draft,
    });

    // todo: add some comments and labels.
    return pr;
  }

  async postDealPR(prNumber: number) {
    // add "/release-note-none" comment.
    await this.octokit.rest.issues.createComment({
      owner: this.owner,
      repo: this.repo,
      issue_number: prNumber,
      body: "/release-note-none",
    }).catch((error: RequestError) =>
      console.error("Error creating comment:", error)
    );

    // add "skip-issue-check", "lgtm", "approved" labels:
    const toAddLabels = ["skip-issue-check", "lgtm", "approved"];
    if (this.repo.startsWith("docs")) {
      toAddLabels.push("translation/no-need");
    }
    if (this.ref.startsWith("release-")) {
      toAddLabels.push("cherry-pick-approved");
    }
    await this.octokit.rest.issues.addLabels({
      owner: this.owner,
      repo: this.repo,
      issue_number: prNumber,
      labels: toAddLabels,
    }).catch((error: RequestError) =>
      console.error("Error add labels:", error)
    );
  }

  public async Update(
    ownersMap: RepoOwners,
    draft: boolean,
    force: boolean,
    filterMode: boolean,
  ) {
    const pr = await this.createUpdateFilePR(
      ownersMap,
      draft,
      force,
      filterMode,
    ).catch((error: RequestError) => {
      console.warn(
        `❌ skiped for repo ${this.owner}/${this.repo}, error happened.`,
        error,
      );
      return null;
    });

    if (!pr) {
      console.info(
        `🏃 for repo ${this.owner}/${this.repo}, no need to create PR.`,
      );
      return null;
    }

    console.info(
      `✅ Pull request created for repo ${this.owner}/${this.repo}: ${pr.html_url}`,
    );

    if (draft) {
      console.info(`👀 for PR: ${pr.html_url}, it is draft.`);
      return null;
    }

    console.info(
      `🫧 Post dealing for pull request: ${pr.html_url}, firstly let's wait for 1 minute for the bot app to process the PR.`,
    );
    await new Promise((resolve) => setTimeout(resolve, 60000));
    console.info(
      `🫧 Waitted, let's post dealing for pull request: ${pr.html_url}.`,
    );
    await this.postDealPR(pr.number);
    console.info(
      `✅ Post done for pull request: ${pr.html_url}.`,
    );

    return {
      owner: this.owner,
      repo: this.repo,
      baseRef: this.ref,
      num: pr.number,
    };
  }
}

export { RepoUpdater };
