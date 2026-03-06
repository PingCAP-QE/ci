import { buildIssueBody, buildIssueComment, buildIssueTitle, parseRepo } from "./IssueUtils.ts";
import type { CaseAgg, GithubIssueInfo, GithubIssueStatus, ReportData } from "./types.ts";

interface IssueManagerOptions {
  token?: string;
  allowCreate: boolean;
  allowReopen: boolean;
  allowComment: boolean;
  dryRun: boolean;
  labels: string[];
  repoOverride?: string;
  titleIncludesRepo?: boolean;
  verbose?: boolean;
}

interface GitHubIssueApi {
  number: number;
  title: string;
  state: "open" | "closed";
  html_url: string;
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
  ): Promise<GitHubIssueApi[]> {
    const queryTitle = title.replaceAll('"', "\\\"");
    const query = `repo:${owner}/${repo} is:issue in:title \"${queryTitle}\"`;
    const url = `https://api.github.com/search/issues?q=${encodeURIComponent(query)}`;
    const res = await this.request("GET", url);
    const items = Array.isArray(res?.items) ? res.items : [];
    return items
      .filter((i: GitHubIssueApi) => i && i.title)
      .map((i: GitHubIssueApi) => ({
        number: i.number,
        title: i.title,
        state: i.state,
        html_url: i.html_url,
      }));
  }

  async createIssue(
    owner: string,
    repo: string,
    title: string,
    body: string,
  ): Promise<GitHubIssueApi> {
    const url = `https://api.github.com/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues`;
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
    const url = `https://api.github.com/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues/${issueNumber}`;
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
    const url = `https://api.github.com/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues/${issueNumber}/labels`;
    await this.request("POST", url, { labels });
  }

  async addComment(
    owner: string,
    repo: string,
    issueNumber: number,
    body: string,
  ): Promise<void> {
    const url = `https://api.github.com/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues/${issueNumber}/comments`;
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
  private readonly verbose: boolean;

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

    const title = buildIssueTitle(sample, { includeRepo: this.titleIncludesRepo });
    const { owner, repo } = parsed;

    let issue: GitHubIssueApi | null = null;
    let status: GithubIssueStatus = "missing";
    let created = false;

    try {
      issue = await this.findBestIssue(owner, repo, title);
      if (issue?.state === "open") {
        status = "open";
      } else if (issue?.state === "closed") {
        if (this.allowReopen && canMutate) {
          status = "reopened";
          if (!this.dryRun) {
            issue = await this.client!.reopenIssue(owner, repo, issue.number);
          }
        } else {
          status = "closed";
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

    const info = this.buildIssueInfo(issueRepo, issue, status);
    if (this.dryRun && canMutate) info.dryRun = true;

    for (const c of group) {
      c.issue = info;
    }

    if (!issue || !canMutate) return;

    if (this.labels.length > 0) {
      if (this.dryRun) {
        if (this.verbose) {
          console.debug(
            `[github] dryrun labels: ${issueRepo}#${issue.number} -> ${this.labels.join(",")}`,
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

    const shouldComment =
      this.allowComment && !created && issue.state === "open" &&
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
  ): Promise<GitHubIssueApi | null> {
    const issues = await this.client!.searchIssues(owner, repo, title);
    if (!issues || issues.length === 0) return null;

    const normalizedTitle = this.normalizeTitle(title);
    const exact = issues.filter((i) => this.normalizeTitle(i.title) === normalizedTitle);
    const candidates = exact.length > 0 ? exact : issues;

    const open = candidates.find((i) => i.state === "open");
    if (open) return open;
    const closed = candidates.find((i) => i.state === "closed");
    return closed ?? null;
  }

  private normalizeTitle(title: string): string {
    return title.trim().replace(/\s+/g, " ").toLowerCase();
  }

  private buildIssueInfo(
    repo: string,
    issue: GitHubIssueApi | null,
    status: GithubIssueStatus,
  ): GithubIssueInfo {
    return {
      repo,
      number: issue?.number,
      url: issue?.html_url,
      state: issue?.state,
      status,
    };
  }
}
