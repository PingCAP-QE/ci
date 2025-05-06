type RightRole = "reviewers" | "approvers";
interface RightBinding {
  user: string;
  repo: string;
  path: string;
  role: RightRole;
}

type CommunityMember = string | {
  name: string;
  scope: string[];
};

interface CommunityTeamConfig {
  _comment?: string;
  description?: string;
  maintainers?: CommunityMember[];
  committers?: CommunityMember[];
  reviewers?: CommunityMember[];
  repositories: string[];
}

type PathOwners = Record<RightRole, string[]>;
type RepoOwners = Record<string, PathOwners>;

async function parseCommunityMembershipFiles(inputFiles: string[]) {
  return await Promise.all(
    Array.from(inputFiles).map((input) =>
      Deno.readTextFile(input).then((content) => {
        return JSON.parse(content) as CommunityTeamConfig;
      })
    ),
  );
}

// Parsing for community membership json files.
class CommunityParser {
  private teamConfigs: CommunityTeamConfig[];

  constructor(
    private readonly inputFiles: string[],
  ) {
    this.teamConfigs = [];
  }

  async initialize() {
    this.teamConfigs = await parseCommunityMembershipFiles(this.inputFiles);
  }

  /**
   * Extracts the name from a community member
   * @param data The community member data which can be a string or an object with name and scope
   * @returns The name of the community member
   */
  communityMemberName(data: CommunityMember): string {
    if (data instanceof Object) {
      return data.name;
    }
    return data.toString();
  }

  /**
   * Extracts and maps scopes associated with a community member
   * @param data The community member data which can be a string or an object with name and scope
   * @returns A map of repositories to sets of paths representing the member's scopes
   */
  communityMemberscopes(
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

  flattenMembersRights(
    members: Set<CommunityMember>,
    role: RightRole,
    repository: string,
  ): RightBinding[] {
    const tempRet: RightBinding[] = [];

    members.forEach((m) => {
      tempRet.push(...this.flattenMemberRights(m, role, repository));
    });

    return tempRet;
  }

  flattenMemberRights(
    member: CommunityMember,
    role: RightRole,
    repository: string,
  ): RightBinding[] {
    const tempRet: RightBinding[] = [];
    const scopes = this.communityMemberscopes(member);

    if (Object.keys(scopes).length === 0) {
      // root owners of the repo
      tempRet.push({
        repo: repository,
        path: "/",
        role: role,
        user: this.communityMemberName(member),
      });
    } else {
      if (repository in scopes) {
        if (scopes.get(repository)!.size === 0) {
          tempRet.push({
            repo: repository,
            path: "/",
            role: role,
            user: this.communityMemberName(member),
          });
        } else {
          scopes.get(repository)!.forEach((p) => {
            tempRet.push({
              repo: repository,
              path: p,
              role: role,
              user: this.communityMemberName(member),
            });
          });
        }
      }
    }

    return tempRet;
  }

  GetRightBindings(): RightBinding[] {
    const rights: RightBinding[] = [];
    this.teamConfigs.forEach((inputData) => {
      // Extract the necessary data from the input
      const { reviewers, maintainers, committers, repositories } = inputData;

      const reviewersSet = new Set(reviewers);

      // Combine maintainers and committers into approvers
      const approversSet = new Set([...maintainers || [], ...committers || []]);

      // delete maintainers and committers from reviewers
      approversSet.forEach((e) => reviewersSet.delete(e));

      for (const repository of repositories) {
        rights.push(
          ...this.flattenMembersRights(approversSet, "approvers", repository),
        );
        rights.push(
          ...this.flattenMembersRights(reviewersSet, "reviewers", repository),
        );
      }
    });

    return rights;
  }

  // Return a map of owners grouped by repository and path.
  //   repo -> path -> role -> members
  public GetOwners() {
    const results = {} as Record<string, RepoOwners>;

    // repo -> path -> role -> members
    const rights = this.GetRightBindings();
    Map.groupBy(rights, (right) => right.repo).forEach((paths, repo) => {
      results[repo] = {} as RepoOwners;
      Map.groupBy(paths, (path) => path.path).forEach((members, path) => {
        results[repo][path] = {} as PathOwners;
        Map.groupBy(members, (member) => member.role).forEach((binds, role) => {
          const users = binds.map((bind) => bind.user);
          results[repo][path][role] = Array.from(new Set(users)).sort();
        });

        // remove the members in reviewers that already in approvers
        const reviewers = results[repo][path].reviewers;
        const approvers = results[repo][path].approvers;
        const onlyReviewers = new Set(reviewers).difference(new Set(approvers));
        results[repo][path].reviewers = Array.from(onlyReviewers).sort();
      });
    });

    return results;
  }

  static async GetAllOwners(inputFiles: string[]) {
    const parser = new CommunityParser(inputFiles);
    await parser.initialize();
    return parser.GetOwners();
  }

  static async GetRepoOwners(repo: string, inputFiles: string[]) {
    const ret = await this.GetAllOwners(inputFiles);
    return ret[repo];
  }
}

export { CommunityParser };
export type { PathOwners, RepoOwners };
