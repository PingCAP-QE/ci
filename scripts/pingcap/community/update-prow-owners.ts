import * as yaml from "https://deno.land/std@0.190.0/yaml/mod.ts";
import * as flags from "https://deno.land/std@0.190.0/flags/mod.ts";
import { dirname } from "https://deno.land/std@0.190.0/path/mod.ts";
import { Octokit } from "https://esm.sh/octokit@2.0.19";

const HEAD_REF = `bot/update-owners-${Date.now()}`;
const COMMIT_MESSAGE = "[skip ci] Update OWNERS file\n\n\nskip-checks: true";
const PR_TITLE = "OWNERS: Auto Sync OWNERS files from community membership";

type CommunityMember = string | {
  name: string;
  scope: string[];
};

function communityMemberName(data: CommunityMember): string {
  if (data instanceof Object) {
    return data.name;
  }
  return data.toString();
}

function communityMemberscopes(
  data: CommunityMember,
): Map<string, Set<string>> {
  const ret = new Map<string, Set<string>>();

  if (data instanceof Object) {
    data.scope.forEach((s) => {
      const repoAndPath = s.split(":", 2);

      switch (repoAndPath.length) {
        case 2:
          if (!ret.has(repoAndPath[0])) {
            ret.set(repoAndPath[0], new Set<string>());
          }
          ret.get(repoAndPath[0])!.add(repoAndPath[1]);
          break;
        case 1:
          ret.set(repoAndPath[0], new Set<string>());
          break;
        default:
          break;
      }
    });
  }

  return ret;
}

interface CommunityTeamConfig {
  _comment?: string;
  description?: string;
  maintainers?: CommunityMember[];
  committers?: CommunityMember[];
  reviewers?: CommunityMember[];
  repositories: string[];
}

interface SimpleProwOwners {
  options?: { no_parent_owners?: boolean };
  labels?: string[];
  approvers: string[];
  emeritus_approvers?: string[];
  reviewers: string[];
}

type ProwOwners = SimpleProwOwners | {
  filters: { [pattern: string]: { labels: string[] } };
};

type RightRole = "reviewer" | "approver";
interface mmeberRight {
  repo: string;
  path: string;
  role: RightRole;
  user: string;
}

function parseCommunityConfigs(inputs: CommunityTeamConfig[]): mmeberRight[] {
  const rights: mmeberRight[] = [];

  inputs.forEach((inputData) => {
    // Extract the necessary data from the input
    const { reviewers, maintainers, committers, repositories } = inputData;

    const reviewersSet = new Set(reviewers);

    // Combine maintainers and committers into approvers
    const approversSet = new Set([...maintainers || [], ...committers || []]);

    // delete maintainers and committers from reviewers
    approversSet.forEach((e) => reviewersSet.delete(e));

    for (const repository of repositories) {
      rights.push(
        ...flattenMembersRights(approversSet, "approver", repository),
      );
      rights.push(
        ...flattenMembersRights(reviewersSet, "reviewer", repository),
      );
    }
  });

  return rights;
}

function groupOwners(rights: mmeberRight[]) {
  const results = new Map<string, Map<string, SimpleProwOwners>>();

  for (const r of rights) {
    const roleTarget = r.role === "reviewer" ? "reviewers" : "approvers";
    if (!results.has(r.repo)) {
      results.set(r.repo, new Map<string, SimpleProwOwners>());
    }

    const repoResults = results.get(r.repo)!;
    if (!repoResults.has(r.path)) {
      repoResults.set(r.path, {
        approvers: [],
        reviewers: [],
      });
    }
    const owners = repoResults.get(r.path)!;
    owners[roleTarget].push(r.user);
  }

  // uniq and sort the members.
  results.forEach((scopes) => {
    scopes.forEach((owners) => {
      const approversSet = new Set<string>(owners.approvers);
      const reviewersSet = new Set<string>(owners.reviewers);
      // delete reviewers that already in appprovers.
      approversSet.forEach((e) => reviewersSet.delete(e));

      owners.approvers = Array.from(approversSet).sort((a, b) =>
        a.localeCompare(b)
      );

      owners.reviewers = Array.from(reviewersSet).sort((a, b) =>
        a.localeCompare(b)
      );
    });
  });

  return results;
}

function flattenMembersRights(
  members: Set<CommunityMember>,
  role: RightRole,
  repository: string,
): mmeberRight[] {
  const tempRet: mmeberRight[] = [];

  members.forEach((m) => {
    tempRet.push(...flattenMemberRights(m, role, repository));
  });

  return tempRet;
}

function flattenMemberRights(
  member: CommunityMember,
  role: RightRole,
  repository: string,
): mmeberRight[] {
  const tempRet: mmeberRight[] = [];
  const scopes = communityMemberscopes(member);

  if (Object.keys(communityMemberscopes).length === 0) {
    // root owners of the repo
    tempRet.push({
      repo: repository,
      path: "/",
      role: role,
      user: communityMemberName(member),
    });
  } else {
    if (repository in communityMemberscopes) {
      if (scopes.get(repository)!.size === 0) {
        tempRet.push({
          repo: repository,
          path: "/",
          role: role,
          user: communityMemberName(member),
        });
      } else {
        scopes.get(repository)!.forEach((p) => {
          tempRet.push({
            repo: repository,
            path: p,
            role: role,
            user: communityMemberName(member),
          });
        });
      }
    }
  }

  return tempRet;
}

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

async function getOwnersFiles(
  octokit: Octokit,
  owner: string,
  repo: string,
  ref: string, // Specify the branch name, e.g., "main"
) {
  const response = await octokit.rest.repos.getContent({ owner, repo, ref });
  const ownersFiles = response.data.filter((
    item: { name: string; type: string; path: string },
  ) => item.type === "file" && item.name === "OWNERS");

  const ownersFilesWithContent = new Map<string, ProwOwners>();
  await Promise.all(
    ownersFiles.map(async ({ path }: { path: string }) => {
      const contentResponse = await octokit.rest.repos.getContent({
        owner,
        repo,
        path,
        ref,
      });

      const content = atob(contentResponse.data.content);
      const owners = yaml.parse(content) as ProwOwners;

      ownersFilesWithContent.set(
        path === "OWNERS" ? "/" : dirname(path),
        owners,
      );
    }),
  );

  return ownersFilesWithContent;
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

function sameOwnersMaps(
  map1: Map<string, ProwOwners>,
  map2: Map<string, ProwOwners>,
) {
  if (map1.size !== map2.size) {
    return false;
  }

  for (const [key, value] of map1) {
    if (!map2.has(key)) {
      return false;
    }

    const value1 = value;
    const value2 = map2.get(key)!;
    if (!compareProwOwners(value1, value2)) {
      return false;
    }
  }

  return true;
}

function compareProwOwners(owner1: ProwOwners, owner2: ProwOwners) {
  return orderJSONStringifyForOwners(owner1) ===
    orderJSONStringifyForOwners(owner2);
}

function orderJSONStringifyForOwners(owners: ProwOwners) {
  const sortedArray = Object.entries(owners).sort(([keyA], [keyB]) =>
    keyA.localeCompare(keyB)
  );
  const sortedDictionary = Object.fromEntries(sortedArray);
  return JSON.stringify(sortedDictionary);
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
  repository: string,
  baseRef: string,
  ownersMap: Map<string, ProwOwners>,
  draft = false,
  force = false,
) {
  if (ownersMap.size === 0) {
    console.debug("no need to create PR");
    return undefined;
  }

  // Get target branch's git commit SHA.
  const { data } = await octokit.rest.git.getRef({
    owner,
    repo: repository,
    ref: `heads/${baseRef}`,
  });
  const baseSha = data.object.sha;

  // get current OWNERS.
  const baseOwnersMap = await getOwnersFiles(
    octokit,
    owner,
    repository,
    baseRef,
  );
  // If none OWNERS were not existed, then skip the repo.
  if (!force && !baseOwnersMap.has("/")) {
    console.debug(
      `🏃 no need to create PR for repo ${owner}/${repository}: no root OWNERS file`,
    );
    return undefined;
  }

  // if no diff, then skip the repo.
  if (sameOwnersMaps(ownersMap, baseOwnersMap)) {
    console.debug(
      `🏃 no need to create PR for repo ${owner}/${repository}: no differences found`,
    );
    return undefined;
  }

  const updateFileAndContents = Array.from(ownersMap).map(([scope, owners]) => {
    const yamlContent = `# See the OWNERS docs at https://go.k8s.io/owners\n${
      // yaml.stringify(outputData)
      yaml.stringify(JSON.parse(JSON.stringify(owners)))}`;

    const filePath = scope.startsWith("/")
      ? (scope === "/" ? "OWNERS" : `${scope.substring(1)}/OWNERS`)
      : `${scope}/OWNERS`;

    return [filePath, yamlContent];
  });

  // add .github/licenserc.[yml|yaml] to the udpate list if the file existed.
  for (const ext of ["yml", "yaml"]) {
    const filePath = `.github/licenserc.${ext}`;
    const licensercContent = await getFile(
      octokit,
      owner,
      repository,
      baseRef,
      filePath,
    );
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
      updateFileAndContents.push([filePath, yaml.stringify(licenserc)]);
    }

    break;
  }

  if (updateFileAndContents.length == 0) {
    console.debug(
      `🏃 no need to create PR for repo ${owner}/${repository}: no differences found`,
    );
    return undefined;
  }

  // Create a new branch
  await octokit.rest.git.createRef({
    owner,
    repo: repository,
    ref: `refs/heads/${HEAD_REF}`,
    sha: baseSha,
  });
  console.debug(`created branch in ${owner}/${repository}: ${HEAD_REF}`);

  await Promise.all(
    Array.from(updateFileAndContents).map(async ([filePath, content]) => {
      console.info(
        `🫧 updating file ${filePath} for repo: ${owner}/${repository}@${HEAD_REF}`,
      );
      await createOrUpdateFileContent(
        octokit,
        owner,
        repository,
        filePath,
        COMMIT_MESSAGE,
        content,
        HEAD_REF,
      );
      console.debug(
        `📃 updated file ${filePath} for repo: ${owner}/${repository}@${HEAD_REF}`,
      );
    }),
  );

  // Create a pull request
  const { data: pr } = await octokit.rest.pulls.create({
    owner,
    repo: repository,
    title: PR_TITLE,
    head: HEAD_REF,
    base: baseRef,
    draft,
  });

  // todo: add some comments and labels.
  return pr;
}

async function postDealPR(
  octokit: Octokit,
  owner: string,
  repo: string,
  prNumber: number,
) {
  // add "/release-note-none" comment.
  await octokit.rest.issues.createComment({
    owner,
    repo,
    issue_number: prNumber,
    body: "/release-note-none",
  }).catch((error: any) => console.error("Error creating comment:", error));

  // add "skip-issue-check", "lgtm", "approved" labels:
  const toAddLabels = ["skip-issue-check", "lgtm", "approved"];
  if (repo.startsWith("docs")) {
    toAddLabels.push("translation/no-need");
  }
  await octokit.rest.issues.addLabels({
    owner,
    repo,
    issue_number: prNumber,
    labels: toAddLabels,
  }).catch((error: any) => console.error("Error add labels:", error));
}

interface cliArgs {
  inputs: string[];
  owner: string;
  github_private_token: string;
  draft: boolean;
  force: boolean; // force create owners if not existed.
  only_repo?: {
    repo: string;
    branch?: string;
  };
}

async function main(
  { inputs, owner, github_private_token, draft, force, only_repo }: cliArgs,
) {
  // Read the input JSON file
  const inputDatas = await Promise.all(
    Array.from(inputs).map((input) =>
      Deno.readTextFile(input).then((content) => {
        return JSON.parse(content) as CommunityTeamConfig;
      })
    ),
  );

  const rights = parseCommunityConfigs(inputDatas);
  const owners = groupOwners(rights);

  // Create a new Octokit instance using the provided token
  const octokit = new Octokit({ auth: github_private_token });

  const pullRequests: { owner: string; repo: string; num: number }[] = [];
  // Create or update the `OWNERS` files in each repository.
  await Promise.all(
    Array.from(owners).map(async ([repository, ownersMap], index) => {
      // Introduce a delay between API requests to avoid rate limit errors
      const delay = 5000 * index; // Adjust the delay time according to your needs
      await new Promise((resolve) => setTimeout(resolve, delay));

      // skip for repo in other ORG.
      if (repository.includes("/")) {
        return;
      }

      // skip if not same with the only repo name.
      if (only_repo && only_repo.repo !== repository) {
        return;
      }

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

      const pr = await createUpdateFilePR(
        octokit,
        owner,
        repository,
        baseRef,
        ownersMap,
        draft,
        force,
      );

      if (pr) {
        console.info(
          `✅ Pull request created for repo ${owner}/${repository}: ${pr.html_url}`,
        );
        pullRequests.push({ owner: owner, repo: repository, num: pr.number });
      } else {
        console.info(
          `🏃 for repo ${owner}/${repository}, no need to create PR.`,
        );
      }
    }),
  );

  // Post deal the pull requests.
  await Promise.all(
    Array.from(pullRequests).map(async (pullRequest, index) => {
      // Introduce a delay between API requests to avoid rate limit errors
      const delay = 5000 * index; // Adjust the delay time according to your needs
      await new Promise((resolve) => setTimeout(resolve, delay));
      const { owner, repo, num } = pullRequest;
      console.info(
        `🫧 Post dealing for pull request: ${owner}/${repo}/${num} ...`,
      );
      await postDealPR(
        octokit,
        pullRequest.owner,
        pullRequest.repo,
        pullRequest.num,
      );
      console.info(
        `✅ Post done for pull request: ${owner}/${repo}/${num} ...`,
      );
    }),
  );
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
 */
const args = flags.parse<cliArgs>(Deno.args, {
  collect: ["inputs"] as never[],
});
// console.debug(args);
await main(args);
Deno.exit(0);
