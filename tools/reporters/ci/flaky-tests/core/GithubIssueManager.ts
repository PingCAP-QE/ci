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

interface IssueManagerOptions {
  token?: string;
  allowCreate: boolean;
  allowReopen: boolean;
  allowComment: boolean;
  dryRun: boolean;
  labels: string[];
  repoOverride?: string;
  titleIncludesRepo?: boolean;
  now?: () => Date;
  fetchFn?: typeof fetch;
  verbose?: boolean;
}

interface GitHubIssueApi {
  number: number;
  title: string;
  state: "open" | "closed";
  html_url: string;
  closed_at?: string | null;
}

class GitHubClient {
  constructor(
    private readonly token: string,
    private readonly opts?: { verbose?: boolean },
  ) {}

  async searchIssues(
    owner: string,
    repo: string,
    title: string,
    looseCaseName?: string,
  ): Promise<GitHubIssueApi[]> {
    const searchExpr = buildIssueTitleSearchExpr(
      title,
      looseCaseName ? String(looseCaseName) : "",
    );
    const query = `${searchExpr} in:title is:issue repo:${owner}/${repo}`;
    const url = `https://api.github.com/search/issues?q=${
      encodeURIComponent(query)
    }`;
    const res = await this.request("GET", url) as { items?: GitHubIssueApi[] };
    const items = Array.isArray(res.items) ? res.items : [];
    return items
      .filter((i: GitHubIssueApi) => i && i.title)
      .map((i: GitHubIssueApi) => ({
        number: i.number,
        title: i.title,
        state: i.state,
        html_url: i.html_url,
        closed_at: i.closed_at,
      }));
  }

  async createIssue(
    owner: string,
    repo: string,
    title: string,
    body: string,
  ): Promise<GitHubIssueApi> {
    const url = `https://api.github.com/repos/${encodeURIComponent(owner)}/${
      encodeURIComponent(repo)
    }/issues`;
    const res = await this.request("POST", url, {
      title,
      body,
      type: "Task",
    });
    return res as GitHubIssueApi;
  }

  async reopenIssue(
    owner: string,
    repo: string,
    issueNumber: number,
  ): Promise<GitHubIssueApi> {
    const url = `https://api.github.com/repos/${encodeURIComponent(owner)}/${
      encodeURIComponent(repo)
    }/issues/${issueNumber}`;
    const res = await this.request("PATCH", url, { state: "open" });
    return res as GitHubIssueApi;
  }

  async addLabels(
    owner: string,
    repo: string,
    issueNumber: number,
    labels: string[],
  ): Promise<void> {
    if (!labels || labels.length === 0) return;
    const url = `https://api.github.com/repos/${encodeURIComponent(owner)}/${
      encodeURIComponent(repo)
    }/issues/${issueNumber}/labels`;
    await this.request("POST", url, { labels });
  }

  async addComment(
    owner: string,
    repo: string,
    issueNumber: number,
    body: string,
  ): Promise<void> {
    const url = `https://api.github.com/repos/${encodeURIComponent(owner)}/${
      encodeURIComponent(repo)
    }/issues/${issueNumber}/comments`;
    await this.request("POST", url, { body });
  }

  private async request(
    method: string,
    url: string,
    body?: Record<string, unknown>,
  ): Promise<unknown> {
    const maxAttempts = 3;
    const headers = {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      Authorization: `Bearer ${this.token}`,
      "User-Agent": "flaky-reporter",
      ...(body ? { "Content-Type": "application/json" } : {}),
    };

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      if (this.opts?.verbose) {
        console.debug(`[github] ${method} ${url} (attempt ${attempt})`);
      }

      let res: Response;
      try {
        res = await fetch(url, {
          method,
          headers,
          body: body ? JSON.stringify(body) : undefined,
        });
      } catch (e: unknown) {
        if (attempt < maxAttempts) {
          await this.sleep(this.backoffMs(attempt));
          continue;
        }
        throw e instanceof Error ? e : new Error(String(e));
      }

      if (res.ok) {
        if (res.status === 204) return null;
        return await res.json();
      }

      if (this.isRetryableStatus(res.status) && attempt < maxAttempts) {
        await this.sleep(this.backoffMs(attempt));
        continue;
      }

      const text = await res.text();
      throw new Error(
        `GitHub API ${method} ${url} failed: ${res.status} ${res.statusText} ${text}`,
      );
    }

    throw new Error(`GitHub API ${method} ${url} failed after retries`);
  }

  private isRetryableStatus(status: number): boolean {
    return status === 429 || (status >= 500 && status <= 599);
  }

  private backoffMs(attempt: number): number {
    const base = 500;
    const max = 5000;
    return Math.min(max, base * Math.pow(2, attempt - 1));
  }

  private async sleep(ms: number): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, ms));
  }
}

export class GithubIssueManager {
  private readonly client?: GitHubClient;
  private readonly enabled: boolean;
  private readonly allowCreate: boolean;
  private readonly allowReopen: boolean;
  private readonly allowComment: boolean;
  private readonly dryRun: boolean;
  private readonly labels: string[];
  private readonly repoOverride?: string;
  private readonly titleIncludesRepo: boolean;
  private readonly now: () => Date;
  private readonly fetchFn: typeof fetch;
  private readonly verbose: boolean;
  private readonly buildStartedAtCache = new Map<
    string,
    { startedAt: Date; source: string } | null
  >();

  constructor(opts: IssueManagerOptions) {
    this.enabled = !!opts.token;
    this.client = opts.token
      ? new GitHubClient(opts.token, { verbose: opts.verbose })
      : undefined;
    this.allowCreate = !!opts.allowCreate;
    this.allowReopen = !!opts.allowReopen;
    this.allowComment = !!opts.allowComment;
    this.dryRun = !!opts.dryRun;
    this.labels = opts.labels ?? [];
    this.repoOverride = opts.repoOverride;
    this.titleIncludesRepo = !!opts.titleIncludesRepo;
    this.now = opts.now ?? (() => new Date());
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
    mutateCases?: CaseAgg[],
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
    const mutateKeys = mutateCases
      ? new Set(this.groupCasesByIssueKey(mutateCases).keys())
      : null;

    for (const [key, group] of groups.entries()) {
      const canMutate = mutateKeys ? mutateKeys.has(key) : true;
      await this.processGroup(report, group, canMutate);
    }
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
    canMutate: boolean,
  ): Promise<void> {
    const sample = group[0];
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

    let issue: GitHubIssueApi | null = null;
    let status: GithubIssueStatus = "missing";
    let created = false;
    let note: string | undefined;

    try {
      issue = await this.findBestIssue(owner, repo, title, sample);
      if (issue?.state === "open") {
        status = "open";
      } else if (issue?.state === "closed") {
        const reopenCheck = this.allowReopen && canMutate
          ? await this.checkReopenEligibility(issue, group)
          : { eligible: false, note: undefined };
        if (reopenCheck.eligible) {
          status = "reopened";
          if (!this.dryRun) {
            issue = await this.client!.reopenIssue(owner, repo, issue.number);
            if (reopenCheck.evidence) {
              const comment = this.buildReopenEvidenceComment(
                report,
                reopenCheck.evidence,
              );
              try {
                await this.client!.addComment(owner, repo, issue.number, comment);
              } catch (e: unknown) {
                const msg = e instanceof Error ? e.message : String(e);
                if (this.verbose) {
                  console.error(`[github] add reopen evidence comment failed: ${msg}`);
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
          if (!this.dryRun) {
            const body = buildIssueBody(report, sample, {
              includeRepo: this.titleIncludesRepo,
            });
            issue = await this.client!.createIssue(owner, repo, title, body);
          }
        } else {
          status = "missing";
        }
      }
    } catch (e: unknown) {
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
          await this.client!.addLabels(owner, repo, issue.number, this.labels);
        } catch (e: unknown) {
          const msg = e instanceof Error ? e.message : String(e);
          if (this.verbose) console.error(`[github] add labels failed: ${msg}`);
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
          `[github] dryrun comment: ${issueRepo}#${issue.number} cases=${group.length}`,
        );
      }
      return;
    }

    for (const c of group) {
      const comment = buildIssueComment(report, c);
      try {
        await this.client!.addComment(owner, repo, issue.number, comment);
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : String(e);
        if (this.verbose) console.error(`[github] add comment failed: ${msg}`);
      }
    }
  }

  private allowCommentForStatus(status: GithubIssueStatus): boolean {
    if (status === "open" || status === "reopened") return true;
    return false;
  }

  private async findBestIssue(
    owner: string,
    repo: string,
    title: string,
    sample: CaseAgg,
  ): Promise<GitHubIssueApi | null> {
    const exactIssues = await this.client!.searchIssues(owner, repo, title);
    const looseCaseName = String(sample.case_name ?? "").trim();
    const looseIssues = looseCaseName
      ? await this.client!.searchIssues(owner, repo, title, looseCaseName)
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
    exactIssues: GitHubIssueApi[],
    looseIssues: GitHubIssueApi[],
  ): GitHubIssueApi[] {
    const out: GitHubIssueApi[] = [];
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
    issue: GitHubIssueApi,
    exactTitle: string,
    sample: CaseAgg,
  ): { state: number; match: number } {
    const state = issue.state === "open" ? 1 : 0;
    const match = this.titleMatchLevel(issue.title, exactTitle, sample);
    return { state, match };
  }

  private titleMatchLevel(
    issueTitle: string,
    exactTitle: string,
    sample: CaseAgg,
  ): number {
    const normalizedIssueTitle = normalizeIssueTitle(issueTitle);
    const normalizedExactTitle = normalizeIssueTitle(exactTitle);
    if (normalizedIssueTitle === normalizedExactTitle) {
      return 3;
    }

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
    issue: GitHubIssueApi,
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

    const latest = candidates.reduce((best, cur) => {
      if (cur.buildStartedAt.getTime() > best.buildStartedAt.getTime()) {
        return cur;
      }
      return best;
    }, candidates[0]);

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

    const best = reopenCandidates.reduce((b, c) => {
      if (c.buildStartedAt.getTime() > b.buildStartedAt.getTime()) return c;
      return b;
    }, reopenCandidates[0]);

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

      const resolved = await this.resolveBuildStartedAt(buildUrl);
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

  private async resolveBuildStartedAt(
    buildUrl: string,
  ): Promise<{ startedAt: Date; source: string } | null> {
    const normalized = this.normalizeBuildUrl(buildUrl);
    if (!normalized) return null;

    if (this.buildStartedAtCache.has(normalized)) {
      return this.buildStartedAtCache.get(normalized) ?? null;
    }

    let out: { startedAt: Date; source: string } | null = null;
    try {
      const j = await this.tryResolveJenkinsBuildStartedAt(normalized);
      if (j) out = { startedAt: j, source: "jenkins.timestamp" };
    } catch (_e: unknown) {
      // best-effort only
    }

    if (!out) {
      try {
        const p = await this.tryResolveProwBuildStartedAt(normalized);
        if (p) out = { startedAt: p, source: "prow.started.json" };
      } catch (_e: unknown) {
        // best-effort only
      }
    }

    this.buildStartedAtCache.set(normalized, out);
    return out;
  }

  private normalizeBuildUrl(value: string): string | null {
    const v = String(value ?? "").trim();
    if (!v) return null;
    return v;
  }

  private async tryResolveJenkinsBuildStartedAt(
    buildUrl: string,
  ): Promise<Date | null> {
    if (!this.looksLikeJenkinsBuildUrl(buildUrl)) return null;

    const apiUrl = this.joinUrl(buildUrl, "api/json?tree=timestamp");
    const res = await this.fetchJson(apiUrl);
    const ts = this.parseEpochMs((res as { timestamp?: unknown })?.timestamp);
    if (ts === null) return null;
    const startedAt = new Date(ts);
    if (Number.isNaN(startedAt.getTime())) return null;
    return startedAt;
  }

  private async tryResolveProwBuildStartedAt(
    buildUrl: string,
  ): Promise<Date | null> {
    const startedJsonUrl = this.buildProwStartedJsonUrl(buildUrl);
    if (!startedJsonUrl) return null;

    const res = await this.fetchJson(startedJsonUrl);
    const ts = this.parseEpochMs((res as { timestamp?: unknown })?.timestamp);
    if (ts === null) return null;
    const startedAt = new Date(ts);
    if (Number.isNaN(startedAt.getTime())) return null;
    return startedAt;
  }

  private buildProwStartedJsonUrl(buildUrl: string): string | null {
    const v = this.normalizeBuildUrl(buildUrl);
    if (!v) return null;

    if (v.startsWith("gs://")) {
      const rest = v.slice("gs://".length).replace(/^\/+/, "");
      return this.ensureEndsWithStartedJson(
        `https://storage.googleapis.com/${rest}`,
      );
    }

    let u: URL;
    try {
      u = new URL(v);
    } catch (_e: unknown) {
      return null;
    }

    if (u.hostname === "storage.googleapis.com") {
      return this.ensureEndsWithStartedJson(
        `https://storage.googleapis.com${u.pathname}`,
      );
    }

    if (u.hostname !== "prow.tidb.net") return null;

    const path = u.pathname;
    const prefixes = ["/view/gs/", "/view/gcs/"];
    for (const prefix of prefixes) {
      if (!path.startsWith(prefix)) continue;
      const rest = path.slice(prefix.length).replace(/^\/+/, "");
      return this.ensureEndsWithStartedJson(
        `https://storage.googleapis.com/${rest}`,
      );
    }

    return null;
  }

  private ensureEndsWithStartedJson(url: string): string {
    const cleaned = url.replace(/\/+$/, "");
    if (cleaned.endsWith("started.json")) return cleaned;
    return `${cleaned}/started.json`;
  }

  private looksLikeJenkinsBuildUrl(buildUrl: string): boolean {
    try {
      const u = new URL(buildUrl);
      if (u.hostname.includes("jenkins")) return true;
      if (u.pathname.includes("/jenkins/")) return true;
      if (u.pathname.includes("/job/")) return true;
      return false;
    } catch (_e: unknown) {
      return false;
    }
  }

  private joinUrl(baseUrl: string, suffix: string): string {
    const base = baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`;
    const s = suffix.startsWith("/") ? suffix.slice(1) : suffix;
    return `${base}${s}`;
  }

  private async fetchJson(url: string): Promise<unknown | null> {
    try {
      const res = await this.fetchFn(url, {
        method: "GET",
        headers: {
          Accept: "application/json",
          "User-Agent": "flaky-reporter",
        },
      });
      if (!res.ok) return null;
      return await res.json();
    } catch (e: unknown) {
      if (this.verbose) {
        const msg = e instanceof Error ? e.message : String(e);
        console.error(`[build] fetch json failed: ${url}: ${msg}`);
      }
      return null;
    }
  }

  private parseEpochMs(value: unknown): number | null {
    if (value === null || value === undefined) return null;
    const n = typeof value === "number" ? value : Number(value);
    if (!Number.isFinite(n) || n <= 0) return null;
    // Jenkins "timestamp" is ms; Prow started.json "timestamp" is seconds.
    return n < 1e12 ? n * 1000 : n;
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
    if (Number.isNaN(parsed.getTime())) return null;
    return parsed;
  }

  private buildIssueInfo(
    repo: string,
    issue: GitHubIssueApi | null,
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
