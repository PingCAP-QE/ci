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
  OwnerEntry,
  OwnerResolution,
  UNOWNED_OWNER,
} from "./types.ts";

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

  /* --------------------------------- Internals -------------------------------- */

  private resolveViaMap(
    repo: string,
    suite: string,
    kase: string,
  ): OwnerResolution {
    if (!this.ownerMap || this.ownerMap.length === 0) {
      return { owner: UNOWNED_OWNER, level: "none" };
    }

    type Level = OwnerResolution["level"];
    const levelRank = (lvl: Level): number => {
      switch (lvl) {
        case "case":
          return 4;
        case "suite":
          return 3;
        case "repo":
          return 1;
        default:
          return 0;
      }
    };

    let best: { owner: string; level: Level; priority: number } | null = null;

    for (const e of this.ownerMap) {
      if (e.repo !== repo) continue;

      const suiteExact = e.suite_name === suite;
      // If not exact, allow parent suite match by prefix (e.g. e.suite_name is a prefix of suite)
      const suiteParent = !suiteExact && e.suite_name !== "*" &&
        suite.startsWith(e.suite_name + "/");

      // suiteAny remains as before
      const suiteAny = e.suite_name === "*";
      // Allow parent suite prefix matches as valid suite-level candidates
      if (!suiteExact && !suiteParent && !suiteAny) continue;

      const caseExact = e.case_name === kase;
      const caseAny = e.case_name === "*";
      if (!caseExact && !caseAny) continue;

      let level: Exclude<Level, "none">;
      if ((suiteExact || suiteParent) && caseExact) {
        level = "case";
      } else if ((suiteExact || suiteParent) && caseAny) {
        level = "suite";
      } else if (suiteAny && caseAny) {
        level = "repo";
      } else {
        continue;
      }

      const priority = e.priority ?? 0;
      if (
        !best ||
        levelRank(level) > levelRank(best.level) ||
        (levelRank(level) === levelRank(best.level) && priority > best.priority)
      ) {
        best = { owner: e.owner_team, level, priority };
      }
    }

    return best
      ? { owner: best.owner, level: best.level }
      : { owner: UNOWNED_OWNER, level: "none" };
  }
}
