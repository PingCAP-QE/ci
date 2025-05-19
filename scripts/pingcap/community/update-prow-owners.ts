import { parseArgs } from "jsr:@std/cli@1.0.17";
import { Octokit } from "https://esm.sh/@octokit/rest@21.1.1?dts";
import { CommunityParser } from "./community-parser.ts";
import { RepoUpdater } from "./prow-owners-updater.ts";

interface cliArgs {
  inputs: string[];
  owner: string;
  github_private_token: string;
  draft: boolean;
  force: boolean; // force create owners if not existed.
  filterMode: boolean;
  only_repo?: {
    repo: string;
    branch?: string;
  };
}

async function main(
  { inputs, owner, github_private_token, draft, force, filterMode, only_repo }:
    cliArgs,
) {
  const owners = await CommunityParser.GetAllOwners(inputs);

  // Create a new Octokit instance using the provided token
  const octokit = new Octokit({ auth: github_private_token });

  // filter by repo
  const filtered = Object.entries(owners).filter(([repository]) => {
    return !(
      // skip for repo in other ORG.
      repository.includes("/") ||
      // skip if not same with the only repo name.
      (only_repo && only_repo.repo !== repository)
    );
  });

  // Create or update the `OWNERS` files in each repository.
  await Promise.all(filtered.map(async ([repository, ownersMap], index) => {
    // Introduce a delay between API requests to avoid rate limit errors
    const delay = 5000 * index; // Adjust the delay time according to your needs
    await new Promise((resolve) => setTimeout(resolve, delay));

    console.debug(`🫧 prepare update for repo: ${owner}/${repository}`);
    // get the base ref for create PR.
    const baseRef = await (async () => {
      if (only_repo && only_repo.repo && only_repo.branch) {
        return only_repo.branch;
      }

      // Get the default branch of the repository
      const { data: repo } = await octokit.rest.repos.get({
        owner,
        repo: repository,
      });
      return repo?.default_branch || "main";
    })();

    const repoUpdater = new RepoUpdater(octokit, owner, repository, baseRef);
    await repoUpdater.Update(
      ownersMap,
      draft,
      force,
      filterMode,
    );
  }));
}

// Execute the main function
/**
 * ---------entry----------------
 * ****** CLI args **************
 * --input <team membership.json path>
 * --owner <github ORG>
 * --github_private_token <github private token>
 * --only_repo.repo  optional only repo name
 * --only_repo.branch  optional only repo branch
 * --force optional.
 * --filterMode when true, update with filter mode.
 * --draft when true, create a draft PR
 */
const args = parseArgs<cliArgs>(Deno.args, {
  collect: ["inputs"] as never[],
});
await main(args);
Deno.exit(0);
