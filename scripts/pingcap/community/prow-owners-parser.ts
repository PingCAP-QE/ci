import * as yaml from "jsr:@std/yaml@1.0.5";
import { dirname } from "jsr:@std/path@1.0.8";
import { Octokit } from "https://esm.sh/@octokit/rest@21.1.1?dts";
import {
  ProwOwners,
  ProwOwnersAliases,
  ProwRepoOwners,
} from "./prow-owners.ts";

class RepoOwnersParser {
  constructor(
    private octokit: Octokit,
    private owner: string,
    private repo: string,
    private ref: string,
  ) {}

  async listOwnersFiles() {
    const response = await this.octokit.rest.repos.getContent({
      owner: this.owner,
      repo: this.repo,
      ref: this.ref,
      path: "",
    });

    // Check if response.data is an array before using filter
    const ownersFiles = Array.isArray(response.data)
      ? response.data.filter((item) =>
        item.type === "file" && item.name === "OWNERS"
      )
      : [];

    return ownersFiles.map(({ path }) => path);
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

  async Owners() {
    const ownersFiles = await this.listOwnersFiles();
    const results: Record<string, ProwOwners> = {};
    await Promise.all(
      ownersFiles.map(async (path) => {
        const content = await this.getFileContent(path);
        const owners = yaml.parse(content) as ProwOwners;

        // sort the array elements.
        // if owners is a SimpleOwner, sort the approvers and reviewers
        if ("approvers" in owners && Array.isArray(owners.approvers)) {
          owners.approvers.sort();
        }
        if ("reviewers" in owners && Array.isArray(owners.reviewers)) {
          owners.reviewers.sort();
        }
        if ("filters" in owners && Array.isArray(owners.filters)) {
          owners.filters.forEach((o) => {
            if ("approvers" in o && Array.isArray(o.approvers)) {
              o.approvers.sort();
            }
            if ("reviewers" in o && Array.isArray(o.reviewers)) {
              o.reviewers.sort();
            }
          });
        }

        results[path === "OWNERS" ? "/" : dirname(path)] = owners;
      }),
    );

    return results;
  }

  async OwnersAliases() {
    try {
      const content = await this.getFileContent("OWNERS_ALIASES");
      return yaml.parse(content) as { aliases: ProwOwnersAliases };
    } catch (e) {
      // If file not found, return empty aliases
      if (
        e && typeof e === "object" && "status" in e && (e as any).status === 404
      ) {
        return { aliases: {} as ProwOwnersAliases };
      }
      throw e;
    }
  }

  async All() {
    const owners = await this.Owners();
    const aliases = (await this.OwnersAliases()).aliases;
    return { owners, aliases } as ProwRepoOwners;
  }
}

export { RepoOwnersParser };
