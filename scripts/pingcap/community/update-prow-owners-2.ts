import * as yaml from "https://deno.land/std@0.190.0/yaml/mod.ts";
import * as flags from "https://deno.land/std@0.190.0/flags/mod.ts";
import { Octokit } from "https://esm.sh/octokit@2.0.19";

const headRef = `bot/update-owners-${Date.now()}`;
const commitMessage = "Update OWNERS file";

interface CommunityMemeberInterface {
  name(): string;
  scopes(): { [repo: string]: Set<string> };
}

type CommunityMemeberData = string | {
  name: string;
  scope: string[];
};

class CommunityMemeber implements CommunityMemeberInterface {
  public data: CommunityMemeberData;

  constructor(data: CommunityMemeberData) {
    this.data = data;
  }

  name(): string {
    if (this.data instanceof Object) {
      return this.data.name;
    }
    return this.data.toString();
  }

  scopes(): { [repo: string]: Set<string> } {
    if (this.data instanceof Object) {
      let ret: { [repo: string]: Set<string> };

      this.data.scope.forEach((s) => {
        const repoAndPath = s.split(":", 2);

        switch (repoAndPath.length) {
          case 2:
            if (!ret[repoAndPath[0]]) {
              ret[repoAndPath[0]] = new Set<string>();
            }
            ret[repoAndPath[0]].add(repoAndPath[1]);
            break;
          case 1:
            ret[repoAndPath[0]] = new Set<string>();
            break;
          default:
            break;
        }
      });
    }

    return {};
  }
}

interface CommunityTeamConfig {
  _comment?: string;
  description?: string;
  maintainers?: CommunityMemeber[];
  committers?: CommunityMemeber[];
  reviewers?: CommunityMemeber[];
  repositories: string[];
}

interface SimpleProwOwners {
  options?: { no_parent_owners?: boolean };
  labels: string[];
  approvers: string[];
  emeritus_approvers?: string[];
  reviewers: string[];
}

type ProwOwners = SimpleProwOwners | {
  filters: { [pattern: string]: { labels: string[] } };
};

interface cliArgs {
  inputs: string[];
  owner: string;
  github_private_token: string;
}

function composeOwnersData(
  inputs: CommunityTeamConfig[],
): { [repo: string]: { [path: string]: ProwOwners } } {
  const ret: { [repo: string]: { [path: string]: ProwOwners } } = {};

  const tempRet: { repo: string; path: string; role: string; user: string }[] =
    [];

  inputs.forEach((inputData) => {
    // Extract the necessary data from the input
    const { reviewers, maintainers, committers, repositories } = inputData;

    const reviewersSet = new Set(reviewers);

    // Combine maintainers and committers into approvers
    const approversSet = new Set([...maintainers || [], ...committers || []]);

    // delete maintainers and committers from reviewers
    approversSet.forEach((e) => reviewersSet.delete(e));

    for (const repository of repositories) {
      tempRet.push(
        ...flattenMembersRights(approversSet, "approver", repository),
      );
      tempRet.push(
        ...flattenMembersRights(approversSet, "reviewer", repository),
      );
    }

    // todo: ~~~~~~~~~
  });

  return ret;
}

function flattenMembersRights(
  members: Set<CommunityMemeber>,
  role: string,
  repository: string,
): { repo: string; path: string; role: string; user: string }[] {
  const tempRet: { repo: string; path: string; role: string; user: string }[] =
    [];

  members.forEach((m) => {
    tempRet.push(...flattenMemberRights(m, role, repository));
  });

  return tempRet;
}

function flattenMemberRights(
  member: CommunityMemeber,
  role: string,
  repository: string,
): { repo: string; path: string; role: string; user: string }[] {
  const tempRet: { repo: string; path: string; role: string; user: string }[] =
    [];
  const scopes = member.scopes();
  if (Object.keys(scopes).length === 0) {
    // root owners of the repo
    tempRet.push({
      repo: repository,
      path: "/",
      role: role,
      user: member.name(),
    });
  } else {
    if (repository in scopes) {
      if (scopes[repository].size === 0) {
        tempRet.push({
          repo: repository,
          path: "/",
          role: role,
          user: member.name(),
        });
      } else {
        scopes[repository].forEach((p) => {
          tempRet.push({
            repo: repository,
            path: p,
            role: role,
            user: member.name(),
          });
        });
      }
    }
  }

  return tempRet;
}

async function main({ inputs, owner, github_private_token }: cliArgs) {
  // Read the input JSON file
  const inputJson = await Deno.readTextFile(inputs);

  // Parse the input JSON
  const inputData: CommunityTeamConfig = JSON.parse(inputJson);

  // Extract the necessary data from the input
  const { reviewers, maintainers, committers, repositories } = inputData;

  const reviewersSet = new Set(reviewers);

  // Combine maintainers and committers into approvers
  const approversSet = new Set([...maintainers || [], ...committers || []]);

  // delete maintainers and committers from reviewers
  approversSet.forEach((e) => reviewersSet.delete(e));

  // Construct the output object
  const outputData = {
    approvers: Array.from(approversSet).sort((a, b) => a.localeCompare(b)),
    reviewers: Array.from(reviewersSet).sort((a, b) => a.localeCompare(b)),
  };

  // Create a new Octokit instance using the provided token
  const octokit = new Octokit({ auth: github_private_token });

  // Generate the output YAML data
  const yamlContent = `# See the OWNERS docs at https://go.k8s.io/owners\n${
    yaml.stringify(outputData)
  }`;
  const filePath = "OWNERS";

  // Create or update the /OWNERS file in each repository
  for (const repository of repositories) {
    console.debug(`🫧 prepare update for repo: ${owner}/${repository}`);
    // Get the default branch of the repository
    const { data: repo } = await octokit.rest.repos.get({
      owner,
      repo: repository,
    });

    const baseRef = repo?.default_branch || "main";
    const { data } = await octokit.rest.git.getRef({
      owner,
      repo: repository,
      ref: `heads/${baseRef}`,
    });
    const baseSha = data.object.sha;

    await octokit.rest.repos.getContent({
      owner,
      repo: repository,
      path: filePath,
      ref: baseRef,
    }).then(async ({ data: { sha: fileSha } }: { data: { sha: string } }) => {
      console.debug(`file sha: ${fileSha}`);
      const pr = await createUpdateFilePR(
        octokit,
        owner,
        repository,
        baseRef,
        baseSha,
        filePath,
        fileSha,
        yamlContent,
      );
      console.debug(
        `Created pull request for OWNERS file update in ${owner}/${repository}: ${pr.html_url}.`,
      );
    }).catch((error: { status?: number }) => {
      if (error?.status === 404) {
        console.info(
          `File "${filePath}" is not existed in repo: ${owner}/${repository}, I will not create pull request to it.`,
        );
      } else {
        // handle all other errors
        throw error;
      }
    });

    console.debug(`✅ for repo: ${owner}/${repository}`);
  }
}

async function createUpdateFilePR(
  octokit: Octokit,
  owner: string,
  repository: string,
  baseRef: string,
  baseSha: string,
  filePath: string,
  fileSha: string,
  fileContent: string,
) {
  // TODO: get current OWNERS, if has diff, then contine.

  // Create a new branch
  await octokit.rest.git.createRef({
    owner,
    repo: repository,
    ref: `refs/heads/${headRef}`,
    sha: baseSha,
  });

  // Update the /OWNERS file in the new branch
  await octokit.rest.repos.createOrUpdateFileContents({
    owner,
    repo: repository,
    path: filePath,
    message: commitMessage,
    content: btoa(fileContent),
    branch: headRef,
    sha: fileSha,
  });

  // Create a pull request
  const { data: pr } = await octokit.rest.pulls.create({
    owner,
    repo: repository,
    title: commitMessage,
    head: headRef,
    base: baseRef,
  });

  return pr;
}

// Execute the main function
/**
 * ---------entry----------------
 * ****** CLI args **************
 * --inputs <team membership.json path>
 * --owner <github ORG>
 * --github_private_token <github private token>
 */
const args = flags.parse<cliArgs>(Deno.args);
await main(args);
Deno.exit(0);
