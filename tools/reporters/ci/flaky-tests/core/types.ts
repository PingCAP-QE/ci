/**
 * Shared type definitions for the Flaky Reporter.
 *
 * These types are used across the reporter's core modules:
 * - Config parsing and validation
 * - Database access
 * - Ownership resolution
 * - Aggregation
 * - Rendering
 */

/* --------------------------------- General --------------------------------- */

/**
 * Time window used for querying `problem_case_runs`:
 * [from, to) — 'from' is inclusive, 'to' is exclusive.
 */
export interface TimeWindow {
  from: Date;
  to: Date;
}

/**
 * CLI-derived runtime configuration.
 */
export interface CliConfig {
  // Time window configuration
  from?: string;
  to?: string;
  range?: string;

  // Aggregation thresholds
  thresholdMs: number;

  // Database (either dbUrl or full connection params)
  dbUrl?: string;
  dbHost?: string;
  dbPort?: number;
  dbUser?: string;
  dbPass?: string;
  dbName?: string;

  // Ownership mapping
  ownerTable?: string;
  ownerMapPath?: string;

  // Output
  htmlPath: string;
  jsonPath?: string;

  // Email
  emailTo?: string[];
  emailFrom?: string;
  emailSubject: string;

  // Behavior
  dryRun: boolean;
  verbose: boolean;
}

/**
 * SMTP configuration for sending emails.
 * When `secure` is true, a TLS connection is used.
 */
export interface SmtpConfig {
  host: string;
  port: number;
  username?: string;
  password?: string;
  secure: boolean;
}

/* ------------------------------ Source records ------------------------------ */

/**
 * Row shape for the `problem_case_runs` table.
 */
export interface ProblemCaseRunRow {
  repo: string;
  branch: string;
  suite_name: string;
  case_name: string;
  flaky: number;
  timecost_ms: number;
  report_time: Date | string;
  build_url: string;
  reason: string;
}

/* --------------------------------- Owners ---------------------------------- */

/**
 * Ownership mapping record (from DB table or YAML/JSON file).
 * Use '*' as wildcard in branch/suite_name/case_name (repo must be explicit).
 */
export interface OwnerEntry {
  repo: string;
  branch: string; // '*' allowed
  suite_name: string; // '*' allowed
  case_name: string; // '*' allowed
  owner_team: string;
  priority?: number;
  note?: string;
}

/**
 * Resolution specificity level for owner matching.
 */
export type OwnerResolutionLevel =
  | "case"
  | "suite"
  | "repo-branch"
  | "repo"
  | "none";

/**
 * Ownership resolution result.
 */
export interface OwnerResolution {
  owner: string;
  level: OwnerResolutionLevel;
}

/* -------------------------------- Aggregates -------------------------------- */

export type CaseKey = string;
export type SuiteKey = string;
export type TeamKey = string;

/**
 * Per-case aggregation result.
 */
export interface CaseAgg {
  repo: string;
  branch: string;
  suite_name: string;
  case_name: string;
  flakyCount: number;
  thresholdedCount: number;
  owner: string;
  latestBuildUrl?: string;
  latestReportTime?: Date;
}

/**
 * Per-suite aggregation result.
 * flakyCases/thresholdedCases count distinct cases in the suite with > 0 counts.
 */
export interface SuiteAgg {
  repo: string;
  branch: string;
  suite_name: string;
  owner: string;
  flakyCases: number;
  thresholdedCases: number;
}

/**
 * Team aggregation result (owner + repo + branch).
 * Counts distinct cases attributed to that owner with > 0 counts.
 */
export interface TeamAgg {
  owner: string;
  repo: string;
  branch: string;
  flakyCases: number;
  thresholdedCases: number;
}

/**
 * Final report data model provided to renderers and (optional) JSON output.
 */
export interface ReportData {
  window: { from: string; to: string; thresholdMs: number };
  summary: {
    repos: number;
    suites: number;
    cases: number;
    flakyCases: number;
    thresholdedCases: number;
  };
  byTeam: TeamAgg[];
  bySuite: SuiteAgg[];
  byCase: CaseAgg[];
  topFlakyCases: CaseAgg[];
}

/* --------------------------------- Constants -------------------------------- */

/**
 * Conventional owner identifiers used by the reporter.
 */
export const UNOWNED_OWNER = "UNOWNED";
export const MIXED_OWNER = "MIXED";
