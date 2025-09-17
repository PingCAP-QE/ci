/**
 * FlakyReporter - Aggregation by case, suite, and team.
 *
 * Responsibilities:
 * - Aggregate problem_case_runs into:
 *   - byCase: repo/branch/suite_name/case_name with flaky and threshold counts
 *   - bySuite: repo/branch/suite_name with counts of distinct cases having > 0
 *   - byTeam: owner/repo/branch with counts of distinct cases having > 0
 *   - topFlakyCases: top 10 cases by flakyCount desc (tie-breaks applied)
 * - Resolve per-case ownership via OwnerResolver
 * - Resolve per-suite ownership using OwnerResolver semantics
 *
 * Usage:
 *   const reporter = new FlakyReporter(ownerResolver, { verbose: true });
 *   const { byCase, bySuite, byTeam, topFlakyCases } =
 *     await reporter.buildAggregates(runs, thresholdMs);
 */

import type {
  CaseAgg,
  CaseKey,
  ProblemCaseRunRow,
  SuiteAgg,
  SuiteKey,
  TeamAgg,
} from "./types.ts";
import { OwnerResolver } from "./OwnerResolver.ts";

export class FlakyReporter {
  private readonly verbose: boolean;

  constructor(
    private readonly ownerResolver: OwnerResolver,
    options?: { verbose?: boolean },
  ) {
    this.verbose = !!options?.verbose;
  }

  /**
   * Compute all aggregates for the given runs and threshold (ms).
   */
  async buildAggregates(
    runs: ProblemCaseRunRow[],
    thresholdMs: number,
  ): Promise<{
    byCase: CaseAgg[];
    bySuite: SuiteAgg[];
    byTeam: TeamAgg[];
    topFlakyCases: CaseAgg[];
  }> {
    if (this.verbose) {
      console.debug(
        `[agg] start: runs=${runs.length} thresholdMs=${thresholdMs}`,
      );
    }

    // 1) Per-case aggregation and capture of case-level owners (for suite owner derivation)
    const caseMap = new Map<CaseKey, CaseAgg>();
    const suiteCaseOwners = new Map<SuiteKey, string[]>();

    for (const r of runs) {
      const key = OwnerResolver.key(
        r.repo,
        r.branch,
        r.suite_name,
        r.case_name,
      );
      let agg = caseMap.get(key);

      if (!agg) {
        const ownerRes = await this.ownerResolver.resolve(
          r.repo,
          r.branch,
          r.suite_name,
          r.case_name,
        );

        agg = {
          repo: r.repo,
          branch: r.branch,
          suite_name: r.suite_name,
          case_name: r.case_name,
          flakyCount: 0,
          thresholdedCount: 0,
          owner: ownerRes.owner,
          latestBuildUrl: undefined,
          latestReportTime: undefined,
        };
        caseMap.set(key, agg);

        // Track case-level owner presence only if level is "case"
        if (ownerRes.level === "case") {
          const sKey = this.suiteKey(r.repo, r.suite_name);
          const owners = suiteCaseOwners.get(sKey) ?? [];
          owners.push(ownerRes.owner);
          suiteCaseOwners.set(sKey, owners);
        }
      }

      if (r.flaky && Number(r.flaky) > 0) agg.flakyCount += 1;
      if (r.timecost_ms >= thresholdMs) agg.thresholdedCount += 1;

      const rt = r.report_time instanceof Date
        ? r.report_time
        : new Date(r.report_time);
      if (
        !agg.latestReportTime || rt.getTime() > agg.latestReportTime.getTime()
      ) {
        agg.latestReportTime = rt;
        agg.latestBuildUrl = r.build_url;
      }
    }

    const byCase = Array.from(caseMap.values());

    // 2) Per-suite aggregation with suite owner resolution
    const suiteMap = new Map<SuiteKey, SuiteAgg>();
    for (const c of byCase) {
      const sKey = this.suiteKey(c.repo, c.suite_name);
      let s = suiteMap.get(sKey);

      if (!s) {
        const [repo, suite] = sKey.split("@@");
        // replace the bazel suite name to go pkg name: //pkg/path:test_suite_name => pkg/path
        const toMatchSuite = suite.replace(/^\/\//g, "").replace(/:\w+$/, "");
        const caseOwnersInSuite = suiteCaseOwners.get(sKey) ?? [];
        const suiteOwnerRes = await this.ownerResolver.resolveForSuite(
          repo,
          toMatchSuite,
          caseOwnersInSuite,
        );
        s = {
          repo: c.repo,
          branch: c.branch,
          suite_name: c.suite_name,
          owner: suiteOwnerRes.owner,
          flakyCases: 0,
          thresholdedCases: 0,
        };
        suiteMap.set(sKey, s);
      }

      if (c.flakyCount > 0) s.flakyCases += 1;
      if (c.thresholdedCount > 0) s.thresholdedCases += 1;
    }
    const bySuite = Array.from(suiteMap.values());

    // 3) Per-team aggregation (owner + repo + branch), counting distinct cases with > 0
    const teamMap = new Map<string, TeamAgg>();
    for (const c of byCase) {
      const tKey = `${c.owner}@@${c.repo}@@${c.branch}`;
      let t = teamMap.get(tKey);

      if (!t) {
        t = {
          owner: c.owner,
          repo: c.repo,
          branch: c.branch,
          flakyCases: 0,
          thresholdedCases: 0,
        };
        teamMap.set(tKey, t);
      }

      if (c.flakyCount > 0) t.flakyCases += 1;
      if (c.thresholdedCount > 0) t.thresholdedCases += 1;
    }
    const byTeam = Array.from(teamMap.values());

    // 4) Top 10 flakiest cases
    const topFlakyCases = [...byCase].sort((a, b) => {
      if (b.flakyCount !== a.flakyCount) return b.flakyCount - a.flakyCount;
      if (b.thresholdedCount !== a.thresholdedCount) {
        return b.thresholdedCount - a.thresholdedCount;
      }
      const ak = `${a.repo}/${a.branch}/${a.suite_name}/${a.case_name}`;
      const bk = `${b.repo}/${b.branch}/${b.suite_name}/${b.case_name}`;
      return ak.localeCompare(bk);
    }).slice(0, 10);

    if (this.verbose) {
      console.debug(
        `[agg] done: byCase=${byCase.length} bySuite=${bySuite.length} byTeam=${byTeam.length} top=${topFlakyCases.length}`,
      );
    }

    return { byCase, bySuite, byTeam, topFlakyCases };
  }

  private suiteKey(repo: string, suite: string): SuiteKey {
    return `${repo}@@${suite}`;
  }
}
