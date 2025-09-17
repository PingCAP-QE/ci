/**
 * OwnerResolver - Resolve ownership from DB table and/or an owner map file.
 *
 * Semantics:
 * - Precedence for matching (most specific to least):
 *   1) repo + branch + suite_name + case_name
 *   2) repo + branch + suite_name + "*"
 *   3) repo + branch + "*" + "*"
 *   4) repo + "*" + "*" + "*"
 * - For file-based matches, multiple candidates at the same specificity are
 *   resolved by higher "priority" (default 0).
 * - Fallback owner is UNOWNED if no rules match.
 *
 * Suite-level owner resolution:
 * - If any case in the suite has a case-level mapping and they do not agree,
 *   return MIXED.
 * - If they agree on a single owner, return that owner.
 * - Otherwise, try suite-level, then repo+branch, then repo-level mappings.
 */
import { Database } from "./Database.ts";
import {
  CaseKey,
  MIXED_OWNER,
  OwnerEntry,
  OwnerResolution,
  OwnerResolutionLevel,
  UNOWNED_OWNER,
} from "./types.ts";

function uniq<T>(arr: T[]): T[] {
  return Array.from(new Set(arr));
}

export class OwnerResolver {
  private cache = new Map<string, OwnerResolution>();
  private ownerMap: OwnerEntry[] | null;
  private readonly db: Database | null;
  private readonly ownerTable?: string;
  private readonly verbose: boolean;

  constructor(
    db: Database | null,
    ownerTable: string | undefined,
    ownerMap: OwnerEntry[] | null,
    options?: { verbose?: boolean },
  ) {
    this.db = db;
    this.ownerTable = ownerTable;
    this.ownerMap = ownerMap ?? null;
    this.verbose = !!options?.verbose;
  }

  static key(
    repo: string,
    branch: string,
    suite: string,
    kase: string,
  ): CaseKey {
    return `${repo}@@${branch}@@${suite}@@${kase}`;
  }

  async init() {
    if (!this.ownerMap && this.db && this.ownerTable) {
      const ownersRecords = await this.db.fetchOwners(this.ownerTable);
      if (ownersRecords) {
        this.ownerMap = ownersRecords;
      }
    }
  }

  async resolve(
    repo: string,
    branch: string,
    bs: string,
    kase: string,
  ): Promise<OwnerResolution> {
    const suite = bs.replace(/^\/\//g, "").replace(/:\w+$/, "");
    const key = OwnerResolver.key(repo, branch, suite, kase);
    const cached = this.cache.get(key);
    if (cached) return cached;

    // 1) DB table lookup by specificity
    await this.init();

    // 2) File map lookup
    if (this.ownerMap && this.ownerMap.length > 0) {
      const mapRes = this.resolveViaMap(repo, suite, kase);
      if (mapRes.owner !== UNOWNED_OWNER) {
        this.cache.set(key, mapRes);
        if (this.verbose) {
          console.debug("[owner] MAP match", {
            repo,
            branch,
            suite,
            kase,
            res: mapRes,
          });
        }
        return mapRes;
      }
    }

    const res: OwnerResolution = { owner: UNOWNED_OWNER, level: "none" };
    this.cache.set(key, res);
    if (this.verbose) {
      console.debug("[owner] no match", { repo, branch, suite, kase });
    }
    return res;
  }

  /**
   * Resolve owner for a suite as a whole, given case-level owners already known
   * within that suite (only capturing case-level resolutions).
   */
  async resolveForSuite(
    repo: string,
    suite: string,
    caseOwnersInSuite: string[],
  ): Promise<OwnerResolution> {
    const specificOwners = uniq(
      caseOwnersInSuite.filter((o) => o && o !== UNOWNED_OWNER),
    );

    if (specificOwners.length > 1) {
      return { owner: MIXED_OWNER, level: "suite" };
    }
    if (specificOwners.length === 1) {
      return { owner: specificOwners[0], level: "suite" };
    }

    // 1) DB table lookup by specificity
    await this.init();

    const matchPatterns: Array<
      [string, string, string, OwnerResolutionLevel]
    > = [];

    // Add patterns from most specific (full suite path) to least (top-level suite)
    const suiteParts = suite.split("/");
    for (let i = suiteParts.length; i > 0; i--) {
      const partialSuite = suiteParts.slice(0, i).join("/");
      matchPatterns.push([repo, partialSuite, "*", "suite"]);
    }

    // Add broader patterns
    matchPatterns.push([repo, "*", "*", "repo"]);

    if (this.ownerMap && this.ownerMap.length > 0) {
      for (const [r, s, c, level] of matchPatterns) {
        const ret = this.resolveViaMap(r, s, c);
        if (ret.owner !== UNOWNED_OWNER) {
          return { owner: ret.owner, level };
        }
      }
    }

    return { owner: UNOWNED_OWNER, level: "none" };
  }

  /* --------------------------------- Internals -------------------------------- */

  private resolveViaMap(
    repo: string,
    suite: string,
    kase: string,
  ): OwnerResolution {
    if (!this.ownerMap || this.ownerMap.length === 0) {
      return { owner: UNOWNED_OWNER, level: "none" };
    }

    const candidates = this.ownerMap.filter((e) =>
      e.repo === repo &&
      (e.suite_name === suite || e.suite_name === "*") &&
      (e.case_name === kase || e.case_name === "*")
    );
    if (candidates.length === 0) return { owner: UNOWNED_OWNER, level: "none" };

    // Specificity scoring: case -> suite -> branch; tie-break by priority
    const picked = [...candidates]
      .map((e) => {
        const score = (e.case_name !== "*" ? 8 : 0) +
          (e.suite_name !== "*" ? 4 : 0) +
          (e.branch !== "*" ? 2 : 0);
        const priority = e.priority ?? 0;
        return { e, score, priority };
      })
      .sort((a, b) => {
        if (b.score !== a.score) return b.score - a.score;
        return b.priority - a.priority;
      })[0].e;

    const level: OwnerResolutionLevel = picked.case_name !== "*"
      ? "case"
      : picked.suite_name !== "*"
      ? "suite"
      : picked.branch !== "*"
      ? "repo-branch"
      : "repo";

    return { owner: picked.owner_team, level };
  }
}
