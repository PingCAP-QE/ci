import { Octokit } from "@octokit/rest";
import { throttling } from "@octokit/plugin-throttling";
import { retry } from "@octokit/plugin-retry";
import {
  buildIssueBody,
  buildIssueComment,
  buildIssueTitle,
  buildIssueTitleSearchExpr,
  formatSuiteName,
  normalizeIssueTitle,
  parseRepo,
} from "./IssueUtils.ts";
import type {
  CaseAgg,
  GithubIssueInfo,
  GithubIssueStatus,
  ReportData,
} from "./types.ts";

// --- Types ---

type IssueData = {
  number: number;
  title: string;
  state: "open" | "closed";
  html_url: string;
  closed_at?: string | null;
};

interface IssueManagerOptions {
  token?: string;
  allowCreate: boolean;
  allowReopen: boolean;
  allowComment: boolean;
  mutationLimit: number;
  dryRun: boolean;
  labels: string[];
  repoOverride?: string;
  titleIncludesRepo?: boolean;
  now?: () => Date;
  fetchFn?: typeof fetch;
  verbose?: boolean;
  /** For testing - inject a mock Octokit instance */
  octokit?: Octokit;
}

// --- Octokit factory ---

/**
 * Creates an Octokit instance with retry and throttling plugins.
 */
function createOctokit(token: string, verbose?: boolean): Octokit {
  const OctokitWithPlugins = Octokit.plugin(throttling, retry);
  return new OctokitWithPlugins({
    auth: token,
    userAgent: "flaky-reporter",
    log: verbose
      ? {
        debug: (msg: string) => console.debug(`[github] ${msg}`),
        info: (msg: string) => console.info(`[github] ${msg}`),
        warn: (msg: string) => console.warn(`[github] ${msg}`),
        error: (msg: string) => console.error(`[github] ${msg}`),
      }
      : undefined,
    throttle: {
      onRateLimit: (
        retryAfter: number,
        _options: object,
        _octokit: unknown,
        retryCount: number,
      ) => {
        console.warn(
          `[github] rate-limited, retrying in ${retryAfter}s (attempt ${retryCount})`,
        );
        return retryCount < 5;
      },
      onSecondaryRateLimit: (
        retryAfter: number,
        _options: object,
        _octokit: unknown,
        retryCount: number,
      ) => {
        console.warn(
          `[github] secondary rate-limited, retrying in ${retryAfter}s (attempt ${retryCount})`,
        );
        return retryCount < 5;
      },
    },
    retry: {
      doNotRetry: [],
    },
  });
}

// --- Helpers ---

function buildRepoLabel(owner: string, repo: string): string {
  return `${owner}/${repo}`;
}

/**
 * Search issues matching the given title (and optionally a loose case name)
 * in the given repo.
 */
async function searchIssues(
  octokit: Octokit,
  owner: string,
  repo: string,
  title: string,
  looseCaseName?: string,
): Promise<IssueData[]> {
  const searchExpr = buildIssueTitleSearchExpr(
    title,
    looseCaseName ? String(looseCaseName) : "",
  );
  const query = `${searchExpr} in:title is:issue repo:${
    buildRepoLabel(owner, repo)
  }`;
  const res = await octokit.rest.search.issuesAndPullRequests({ q: query });
  return (res.data.items ?? [])
    .filter((i) => i && i.title)
    .map((i) => ({
      number: i.number,
      title: i.title,
      state: i.state as "open" | "closed",
      html_url: i.html_url,
      closed_at: i.closed_at,
    }));
}

async function createIssue(
  octokit: Octokit,
  owner: string,
  repo: string,
  title: string,
  body: string,
): Promise<IssueData> {
  const res = await octokit.rest.issues.create({ owner, repo, title, body });
  return {
    number: res.data.number,
    title: res.data.title,
    state: res.data.state as "open" | "closed",
    html_url: res.data.html_url,
    closed_at: res.data.closed_at,
  };
}

async function reopenIssue(
  octokit: Octokit,
  owner: string,
  repo: string,
  issueNumber: number,
): Promise<IssueData> {
  const res = await octokit.rest.issues.update({
    owner,
    repo,
    issue_number: issueNumber,
    state: "open",
  });
  return {
    number: res.data.number,
    title: res.data.title,
    state: res.data.state as "open" | "closed",
    html_url: res.data.html_url,
    closed_at: res.data.closed_at,
  };
}

async function addLabels(
  octokit: Octokit,
  owner: string,
  repo: string,
  issueNumber: number,
  labels: string[],
): Promise<void> {
  if (!labels || labels.length === 0) return;
  await octokit.rest.issues.addLabels({
    owner,
    repo,
    issue_number: issueNumber,
    labels,
  });
}

async function addComment(
  octokit: Octokit,
  owner: string,
  repo: string,
  issueNumber: number,
  body: string,
): Promise<void> {
  await octokit.rest.issues.createComment({
    owner,
    repo,
    issue_number: issueNumber,
    body,
  });
}

/**
 * Resolve the started_at timestamp from a build URL.
 * Tries Jenkins first, then Prow.
 */
async function resolveBuildStartedAt(
  buildUrl: string,
  fetchFn: typeof fetch,
  cache: Map<string, { startedAt: Date; source: string } | null>,
): Promise<{ startedAt: Date; source: string } | null> {
  const normalized = String(buildUrl ?? "").trim();
  if (!normalized) return null;

  if (cache.has(normalized)) {
    return cache.get(normalized) ?? null;
  }

  let out: { startedAt: Date; source: string } | null = null;
  try {
    const j = await tryResolveJenkinsBuildStartedAt(normalized, fetchFn);
    if (j) out = { startedAt: j, source: "jenkins.timestamp" };
  } catch {
    // best-effort only
  }

  if (!out) {
    try {
      const p = await tryResolveProwBuildStartedAt(normalized, fetchFn);
      if (p) out = { startedAt: p, source: "prow.started.json" };
    } catch {
      // best-effort only
    }
  }

  cache.set(normalized, out);
  return out;
}

async function tryResolveJenkinsBuildStartedAt(
  buildUrl: string,
  fetchFn: typeof fetch,
): Promise<Date | null> {
  if (!looksLikeJenkinsBuildUrl(buildUrl)) return null;
  const apiUrl = joinUrl(buildUrl, "api/json?tree=timestamp");
  const res = await fetchJson(apiUrl, fetchFn);
  const ts = parseEpochMs((res as { timestamp?: unknown })?.timestamp);
  if (ts === null) return null;
  const startedAt = new Date(ts);
  return Number.isNaN(startedAt.getTime()) ? null : startedAt;
}

async function tryResolveProwBuildStartedAt(
  buildUrl: string,
  fetchFn: typeof fetch,
): Promise<Date | null> {
  const startedJsonUrl = buildProwStartedJsonUrl(buildUrl);
  if (!startedJsonUrl) return null;
  const res = await fetchJson(startedJsonUrl, fetchFn);
  const ts = parseEpochMs((res as { timestamp?: unknown })?.timestamp);
  if (ts === null) return null;
  const startedAt = new Date(ts);
  return Number.isNaN(startedAt.getTime()) ? null : startedAt;
}

function buildProwStartedJsonUrl(buildUrl: string): string | null {
  const v = String(buildUrl ?? "").trim();
  if (!v) return null;

  if (v.startsWith("gs://")) {
    const rest = v.slice("gs://".length).replace(/^\/+/, "");
    return ensureEndsWithStartedJson(`https://storage.googleapis.com/${rest}`);
  }

  let u: URL;
  try {
    u = new URL(v);
  } catch {
    return null;
  }

  if (u.hostname === "storage.googleapis.com") {
    return ensureEndsWithStartedJson(
      `https://storage.googleapis.com${u.pathname}`,
    );
  }

  if (u.hostname !== "prow.tidb.net") return null;

  const path = u.pathname;
  const prefixes = ["/view/gs/", "/view/gcs/"];
  for (const prefix of prefixes) {
    if (!path.startsWith(prefix)) continue;
    const rest = path.slice(prefix.length).replace(/^\/+/, "");
    return ensureEndsWithStartedJson(
      `https://storage.googleapis.com/${rest}`,
    );
  }

  return null;
}

function ensureEndsWithStartedJson(url: string): string {
  const cleaned = url.replace(/\/+$/, "");
  if (cleaned.endsWith("started.json")) return cleaned;
  return `${cleaned}/started.json`;
}

function looksLikeJenkinsBuildUrl(buildUrl: string): boolean {
  try {
    const u = new URL(buildUrl);
    return u.hostname.includes("jenkins") ||
      u.pathname.includes("/jenkins/") ||
      u.pathname.includes("/job/");
  } catch {
    return false;
  }
}

function joinUrl(baseUrl: string, suffix: string): string {
  const base = baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`;
  const s = suffix.startsWith("/") ? suffix.slice(1) : suffix;
  return `${base}${s}`;
}

async function fetchJson(
  url: string,
  fetchFn: typeof fetch,
): Promise<unknown | null> {
  try {
    const res = await fetchFn(url, {
      method: "GET",
      headers: { Accept: "application/json", "User-Agent": "flaky-reporter" },
    });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

function parseEpochMs(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  const n = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(n) || n <= 0) return null;
  return n < 1e12 ? n * 1000 : n;
}

// --- GithubIssueManager ---

export class GithubIssueManager {
  private readonly octokit?: Octokit;
  private readonly enabled: boolean;
  private readonly allowCreate: boolean;
  private readonly allowReopen: boolean;
  private readonly allowComment: boolean;
  private readonly mutationLimit: number;
  private readonly dryRun: boolean;
  private readonly labels: string[];
  private readonly repoOverride?: string;
  private readonly titleIncludesRepo: boolean;
  private readonly fetchFn: typeof fetch;
  private readonly verbose: boolean;
  private readonly buildStartedAtCache = new Map<
    string,
    { startedAt: Date; source: string } | null
  >();

  constructor(opts: IssueManagerOptions) {
    this.enabled = !!opts.token || !!opts.octokit;
    this.octokit = opts.octokit ??
      (opts.token ? createOctokit(opts.token, opts.verbose) : undefined);
    this.allowCreate = !!opts.allowCreate;
    this.allowReopen = !!opts.allowReopen;
    this.allowComment = !!opts.allowComment;
    this.mutationLimit = Math.max(1, opts.mutationLimit || 10);
    this.dryRun = !!opts.dryRun;
    this.labels = opts.labels ?? [];
    this.repoOverride = opts.repoOverride;
    this.titleIncludesRepo = !!opts.titleIncludesRepo;
    this.fetchFn = opts.fetchFn ?? fetch;
    this.verbose = !!opts.verbose;
  }

  isEnabled(): boolean {
    return this.enabled;
  }

  getRepoOverride(): string | undefined {
    return this.repoOverride;
  }

  getTitleIncludesRepo(): boolean {
    return this.titleIncludesRepo;
  }

  isDryRun(): boolean {
    return this.dryRun;
  }

  async sync(
    report: ReportData,
    cases: CaseAgg[],
  ): Promise<void> {
    if (!cases || cases.length === 0) return;

    if (!this.enabled) {
      for (const c of cases) {
        c.issue = {
          repo: this.resolveIssueRepo(c),
          status: "disabled",
        };
      }
      return;
    }

    const groups = this.groupCasesByIssueKey(cases);
    const mutableCases = this.selectMutableCases(cases);

    for (const group of groups.values()) {
      const rankedGroup = this.sortCasesByPriority(group);
      const mutableGroup = rankedGroup.filter((c) => mutableCases.has(c));
      await this.processGroup(report, rankedGroup, mutableGroup);
    }
  }

  private selectMutableCases(cases: CaseAgg[]): Set<CaseAgg> {
    return new Set(
      this.sortCasesByPriority(cases).slice(0, this.mutationLimit),
    );
  }

  private sortCasesByPriority(cases: CaseAgg[]): CaseAgg[] {
    return [...cases].sort((a, b) => {
      if (b.flakyCount !== a.flakyCount) return b.flakyCount - a.flakyCount;
      if (b.thresholdedCount !== a.thresholdedCount) {
        return b.thresholdedCount - a.thresholdedCount;
      }
      const ak = `${a.repo}/${a.branch}/${a.suite_name}/${a.case_name}`;
      const bk = `${b.repo}/${b.branch}/${b.suite_name}/${b.case_name}`;
      return ak.localeCompare(bk);
    });
  }

  private groupCasesByIssueKey(cases: CaseAgg[]): Map<string, CaseAgg[]> {
    const grouped = new Map<string, CaseAgg[]>();
    for (const c of cases) {
      const issueRepo = this.resolveIssueRepo(c);
      const title = buildIssueTitle(c, { includeRepo: this.titleIncludesRepo });
      const key = `${issueRepo}@@${title}`;
      const list = grouped.get(key) ?? [];
      list.push(c);
      grouped.set(key, list);
    }
    return grouped;
  }

  private resolveIssueRepo(c: CaseAgg): string {
    return this.repoOverride ?? c.repo;
  }

  private async processGroup(
    report: ReportData,
    group: CaseAgg[],
    mutableGroup: CaseAgg[],
  ): Promise<void> {
    const canMutate = mutableGroup.length > 0;
    const sample = mutableGroup[0] ?? group[0];
    const issueRepo = this.resolveIssueRepo(sample);
    const parsed = parseRepo(issueRepo);
    if (!parsed) {
      for (const c of group) {
        c.issue = {
          repo: issueRepo,
          status: "error",
          note: `invalid repo: ${issueRepo}`,
        };
      }
      return;
    }

    const title = buildIssueTitle(sample, {
      includeRepo: this.titleIncludesRepo,
    });
    const { owner, repo } = parsed;
    const octokit = this.octokit!;

    let issue: IssueData | null = null;
    let status: GithubIssueStatus = "missing";
    let created = false;
    let note: string | undefined;

    try {
      issue = await this.findBestIssue(octokit, owner, repo, title, sample);
      if (issue?.state === "open") {
        status = "open";
      } else if (issue?.state === "closed") {
        const reopenCheck = this.allowReopen && canMutate
          ? await this.checkReopenEligibility(issue, group)
          : { eligible: false, note: undefined as string | undefined };
        if (reopenCheck.eligible) {
          status = "reopened";
          if (!this.dryRun) {
            issue = await reopenIssue(octokit, owner, repo, issue.number);
            if (reopenCheck.evidence) {
              const comment = this.buildReopenEvidenceComment(
                report,
                reopenCheck.evidence,
              );
              try {
                await addComment(
                  octokit,
                  owner,
                  repo,
                  issue.number,
                  comment,
                );
              } catch (e: unknown) {
                const msg = e instanceof Error ? e.message : String(e);
                if (this.verbose) {
                  console.error(
                    `[github] add reopen evidence comment failed: ${msg}`,
                  );
                }
              }
            }
          }
        } else {
          status = "closed";
          note = reopenCheck.note;
        }
      } else {
        if (this.allowCreate && canMutate) {
          status = "new";
          created = true;
          if (this.dryRun) {
            console.log(
              `[github] would create issue: ${title} 🧪 dry-run mode`,
            );
          } else {
            const body = buildIssueBody(report, sample, {
              includeRepo: this.titleIncludesRepo,
            });
            issue = await createIssue(octokit, owner, repo, title, body);
          }
        } else {
          console.log(`[github] no issue found for: ${title}`);
          status = "missing";
        }
      }
    } catch (e: unknown) {
      console.error("Error occurred:", e);
      const msg = e instanceof Error ? e.message : String(e);
      if (this.verbose) console.error(`[github] ${msg}`);
      for (const c of group) {
        c.issue = {
          repo: issueRepo,
          status: "error",
          note: msg,
        };
      }
      return;
    }

    const info = this.buildIssueInfo(issueRepo, issue, status, note);
    if (this.dryRun && canMutate) info.dryRun = true;

    for (const c of group) {
      c.issue = info;
    }

    if (!issue || !canMutate) return;

    if (this.labels.length > 0) {
      if (this.dryRun) {
        if (this.verbose) {
          console.debug(
            `[github] dryrun labels: ${issueRepo}#${issue.number} -> ${
              this.labels.join(",")
            }`,
          );
        }
      } else {
        try {
          await addLabels(octokit, owner, repo, issue.number, this.labels);
        } catch (e: unknown) {
          const msg = e instanceof Error ? e.message : String(e);
          if (this.verbose) {
            console.error(`[github] add labels failed: ${msg}`);
          }
        }
      }
    }

    const shouldComment = this.allowComment && !created &&
      issue.state === "open" &&
      this.allowCommentForStatus(status);

    if (!shouldComment) return;

    if (this.dryRun) {
      if (this.verbose) {
        console.debug(
          `[github] dryrun comment: ${issueRepo}#${issue.number} cases=${mutableGroup.length}`,
        );
      }
      return;
    }

    for (const c of mutableGroup) {
      const comment = buildIssueComment(report, c);
      try {
        await addComment(octokit, owner, repo, issue.number, comment);
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : String(e);
        if (this.verbose) {
          console.error(`[github] add comment failed: ${msg}`);
        }
      }
    }
  }

  private allowCommentForStatus(status: GithubIssueStatus): boolean {
    return status === "open" || status === "reopened";
  }

  private async findBestIssue(
    octokit: Octokit,
    owner: string,
    repo: string,
    title: string,
    sample: CaseAgg,
  ): Promise<IssueData | null> {
    const exactIssues = await searchIssues(octokit, owner, repo, title);

    const exactRanked = exactIssues
      .map((issue) => ({
        issue,
        score: this.scoreIssueCandidate(issue, title, sample),
      }))
      .sort((a, b) => {
        if (b.score.state !== a.score.state) {
          return b.score.state - a.score.state;
        }
        if (b.score.match !== a.score.match) {
          return b.score.match - a.score.match;
        }
        return a.issue.number - b.issue.number;
      });

    const bestExact = exactRanked[0];
    if (
      bestExact && bestExact.score.match === 3 && bestExact.score.state === 1
    ) {
      return bestExact.issue;
    }

    const looseCaseName = String(sample.case_name ?? "").trim();
    const looseIssues = looseCaseName
      ? await searchIssues(octokit, owner, repo, title, looseCaseName)
      : [];

    const issues = this.mergeIssueCandidates(exactIssues, looseIssues);
    if (!issues || issues.length === 0) return null;

    const ranked = issues
      .map((issue) => ({
        issue,
        score: this.scoreIssueCandidate(issue, title, sample),
      }))
      .sort((a, b) => {
        if (b.score.state !== a.score.state) {
          return b.score.state - a.score.state;
        }
        if (b.score.match !== a.score.match) {
          return b.score.match - a.score.match;
        }
        return a.issue.number - b.issue.number;
      });

    return ranked[0]?.issue ?? null;
  }

  private mergeIssueCandidates(
    exactIssues: IssueData[],
    looseIssues: IssueData[],
  ): IssueData[] {
    const out: IssueData[] = [];
    const seen = new Set<number>();
    for (const issue of [...exactIssues, ...looseIssues]) {
      if (!issue || !Number.isFinite(issue.number) || seen.has(issue.number)) {
        continue;
      }
      seen.add(issue.number);
      out.push(issue);
    }
    return out;
  }

  private scoreIssueCandidate(
    issue: IssueData,
    exactTitle: string,
    sample: CaseAgg,
  ): { state: number; match: number } {
    return {
      state: issue.state === "open" ? 1 : 0,
      match: this.titleMatchLevel(issue.title, exactTitle, sample),
    };
  }

  private titleMatchLevel(
    issueTitle: string,
    exactTitle: string,
    sample: CaseAgg,
  ): number {
    const normalizedIssueTitle = normalizeIssueTitle(issueTitle);
    const normalizedExactTitle = normalizeIssueTitle(exactTitle);
    if (normalizedIssueTitle === normalizedExactTitle) return 3;

    const caseTerm = normalizeIssueTitle(sample.case_name);
    const suiteTerm = normalizeIssueTitle(formatSuiteName(sample.suite_name));
    const hasCase = caseTerm ? normalizedIssueTitle.includes(caseTerm) : false;
    const hasSuite = suiteTerm
      ? normalizedIssueTitle.includes(suiteTerm)
      : false;

    if (hasCase && hasSuite) return 2;
    if (hasCase) return 1;
    return 0;
  }

  private async checkReopenEligibility(
    issue: IssueData,
    group: CaseAgg[],
  ): Promise<{
    eligible: boolean;
    note?: string;
    evidence?: {
      closedAt: Date;
      buildStartedAt: Date;
      buildUrl: string;
      startedAtSource: string;
      sample: CaseAgg;
    };
  }> {
    const closedAt = this.parseClosedAt(issue.closed_at);
    if (!closedAt) {
      return {
        eligible: false,
        note: "skip reopen: missing closed_at timestamp",
      };
    }

    const candidates = await this.collectLatestFlakyBuildCandidates(group);
    if (candidates.length === 0) {
      return {
        eligible: false,
        note:
          "skip reopen: missing latest flaky evidence (build_url/started_at/fallback time)",
      };
    }

    const latest = candidates.reduce((best, cur) =>
      cur.buildStartedAt.getTime() > best.buildStartedAt.getTime() ? cur : best
    );

    const reopenCandidates = candidates.filter((c) =>
      c.buildStartedAt.getTime() > closedAt.getTime()
    );

    if (reopenCandidates.length === 0) {
      return {
        eligible: false,
        note:
          `skip reopen: latest flaky build started_at ${latest.buildStartedAt.toISOString()} <= closed_at ${closedAt.toISOString()} (branch=${latest.sample.branch})`,
      };
    }

    const best = reopenCandidates.reduce((b, c) =>
      c.buildStartedAt.getTime() > b.buildStartedAt.getTime() ? c : b
    );

    return {
      eligible: true,
      evidence: {
        closedAt,
        buildStartedAt: best.buildStartedAt,
        buildUrl: best.buildUrl,
        startedAtSource: best.startedAtSource,
        sample: best.sample,
      },
    };
  }

  private async collectLatestFlakyBuildCandidates(
    group: CaseAgg[],
  ): Promise<
    Array<{
      buildStartedAt: Date;
      buildUrl: string;
      startedAtSource: string;
      sample: CaseAgg;
    }>
  > {
    const out: Array<{
      buildStartedAt: Date;
      buildUrl: string;
      startedAtSource: string;
      sample: CaseAgg;
    }> = [];

    for (const c of group) {
      if (!c || (c.flakyCount ?? 0) <= 0) continue;

      const buildUrl = String(c.latestFlakyBuildUrl ?? "").trim();
      if (!buildUrl) continue;

      const resolved = await resolveBuildStartedAt(
        buildUrl,
        this.fetchFn,
        this.buildStartedAtCache,
      );
      const fallback = c.latestFlakyFoundAt;
      if (!resolved && !fallback) continue;

      out.push({
        buildStartedAt: resolved?.startedAt ?? fallback!,
        buildUrl,
        startedAtSource: resolved?.source ?? "fallback latestFlakyFoundAt",
        sample: c,
      });
    }

    return out;
  }

  private buildReopenEvidenceComment(
    report: ReportData,
    evidence: {
      closedAt: Date;
      buildStartedAt: Date;
      buildUrl: string;
      startedAtSource: string;
      sample: CaseAgg;
    },
  ): string {
    const c = evidence.sample;
    const lines = [
      "Automated reopen: flaky detected after this issue was closed.",
      "",
      `- Repo: ${c.repo}`,
      `- Branch: ${c.branch}`,
      `- Package: ${formatSuiteName(c.suite_name)}`,
      `- Case: ${c.case_name}`,
      "",
      `- Issue closed_at: ${evidence.closedAt.toISOString()}`,
      `- Build started_at: ${evidence.buildStartedAt.toISOString()}`,
      `- Build started_at source: ${evidence.startedAtSource}`,
      `- Build: ${evidence.buildUrl}`,
      "",
      `Window: ${report.window.from} → ${report.window.to}`,
      `Threshold: ${report.window.thresholdMs} ms`,
    ].filter((x) => x !== undefined) as string[];
    return lines.join("\n");
  }

  private parseClosedAt(value?: string | null): Date | null {
    if (!value) return null;
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
  }

  private buildIssueInfo(
    repo: string,
    issue: IssueData | null,
    status: GithubIssueStatus,
    note?: string,
  ): GithubIssueInfo {
    return {
      repo,
      number: issue?.number,
      url: issue?.html_url,
      state: issue?.state,
      status,
      note,
    };
  }
}
