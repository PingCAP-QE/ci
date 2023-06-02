import * as yaml from "https://deno.land/std@0.190.0/yaml/mod.ts";
import * as flags from "https://deno.land/std@0.190.0/flags/mod.ts";
import { Octokit } from "https://cdn.skypack.dev/octokit?dts";

interface CommunityTeamConfig {
  _comment?: string;
  description?: string;
  maintainers?: string[];
  committers?: string[];
  reviewers?: string[];
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
  input: string;
  owner: string;
  github_private_token: string;
}

async function main({ input, owner, github_private_token }: cliArgs) {
  // Read the input JSON file
  const inputJson = await Deno.readTextFile(input);

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
  const headRef = `bot/update-owners-${Date.now()}`;
  const commitMessage = "Update OWNERS file";

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
 * --input <team membership.json path>
 * --owner <github ORG>
 * --github_private_token <github private token>
 */
const args = flags.parse<cliArgs>(Deno.args);
await main(args);
Deno.exit(0);
