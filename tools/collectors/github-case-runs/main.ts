import { createConnection } from "npm:mysql2@3.11.5/promise";
import type { Connection } from "npm:mysql2@3.11.5/promise";
import { convertDsnToClientConfig } from "../../reporters/ci/flaky-tests/utils/db.ts";
import { parseArtifact } from "./junit.ts";
import type { ProblemCases } from "./junit.ts";

// Identical to the endpoint used by tipipeline's prow.sendTestCaseRunReport.
export const COLLECTOR_URL = "http://cloudevents-server.cs.svc/events";
export const WORKFLOWS = {
  "unit-test.yml": "unit-test-junit-attempt-",
  "integrations.yml": "integration-test-junit-attempt-",
};
const API = "https://api.github.com";
const MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;

type Run = {
  id: number;
  run_attempt: number;
  status: string;
  conclusion: string | null;
  event: string;
  updated_at: string;
  pull_requests: Array<{ base: { ref: string } }>;
  head_branch: string;
};
type Artifact = { id: number; name: string; expired: boolean };
export type CaseEvent = {
  repo: string;
  branch: string;
  buildUrl: string;
  eventId: string;
  cases: ProblemCases;
};

export class GitHub {
  constructor(private token: string, private request: typeof fetch = fetch) {}

  private async get(path: string): Promise<Response> {
    for (let attempt = 0;; attempt++) {
      const response = await this.request(API + path, {
        headers: {
          Authorization: `Bearer ${this.token}`,
          Accept: "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28",
        },
        redirect: "manual",
        signal: AbortSignal.timeout(30000),
      });
      if ((response.status === 429 || response.status >= 500) && attempt < 2) {
        await response.body?.cancel();
        await new Promise((resolve) =>
          setTimeout(resolve, (attempt + 1) * 1000)
        );
        continue;
      }
      return response;
    }
  }

  async json<T>(path: string): Promise<T> {
    const response = await this.get(path);
    if (!response.ok) {
      await response.body?.cancel();
      throw new Error(`GitHub API returned HTTP ${response.status}`);
    }
    return await response.json() as T;
  }

  async artifact(id: number): Promise<Uint8Array> {
    const redirect = await this.get(
      `/repos/${this.repository}/actions/artifacts/${id}/zip`,
    );
    if (redirect.status !== 302) {
      await redirect.body?.cancel();
      throw new Error(
        `GitHub artifact download returned HTTP ${redirect.status}`,
      );
    }
    const location = new URL(redirect.headers.get("location") ?? "");
    await redirect.body?.cancel();
    if (
      location.protocol !== "https:" ||
      !(location.hostname.endsWith(".githubusercontent.com") ||
        location.hostname.endsWith(".blob.core.windows.net"))
    ) {
      throw new Error("Unsafe artifact download URL");
    }
    // GitHub's signed storage URL must not receive the GitHub API credential.
    const response = await this.request(location, {
      redirect: "error",
      signal: AbortSignal.timeout(30000),
    });
    if (!response.ok || !response.body) {
      throw new Error("Artifact storage download failed");
    }
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let length = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        length += value.length;
        if (length > MAX_ARCHIVE_BYTES) {
          throw new Error("Artifact archive is too large");
        }
        chunks.push(value);
      }
    } finally {
      await reader.cancel();
    }
    const bytes = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.length;
    }
    return bytes;
  }

  repository = "";
}

export function branchFor(run: Run): string | undefined {
  if (run.event === "pull_request") {
    const branches = new Set(
      (run.pull_requests ?? []).map((pr) => pr.base?.ref).filter(Boolean),
    );
    return branches.size === 1 ? [...branches][0] : undefined;
  }
  if (
    ["workflow_dispatch", "push", "schedule"].includes(run.event) &&
    run.head_branch
  ) {
    return run.head_branch;
  }
  return undefined;
}

async function discoverRuns(
  github: GitHub,
  workflow: string,
  from: Date,
  to: Date,
): Promise<Run[]> {
  const query = (page: number) =>
    `/repos/${github.repository}/actions/workflows/${workflow}/runs?per_page=100&page=${page}&created=${
      encodeURIComponent(
        from.toISOString() + ".." + new Date(to.getTime() - 1000).toISOString(),
      )
    }`;
  const first = await github.json<
    { total_count: number; workflow_runs: Run[] }
  >(query(1));
  // The Actions API caps search results at 1000. Split the created-time range
  // instead of silently dropping runs from busy repositories.
  if (first.total_count >= 1000) {
    if (to.getTime() - from.getTime() <= 1000) {
      throw new Error("GitHub run result cap reached");
    }
    const middle = new Date(
      Math.floor((from.getTime() + to.getTime()) / 2000) * 1000,
    );
    if (middle <= from || middle >= to) {
      throw new Error("GitHub run result cap reached");
    }
    return [
      ...await discoverRuns(github, workflow, from, middle),
      ...await discoverRuns(github, workflow, middle, to),
    ];
  }
  const runs = first.workflow_runs;
  for (let page = 2; (page - 1) * 100 < first.total_count; page++) {
    runs.push(
      ...(await github.json<{ workflow_runs: Run[] }>(query(page)))
        .workflow_runs,
    );
  }
  return runs;
}

export async function eventsForRun(
  github: GitHub,
  run: Run,
  prefix: string,
): Promise<CaseEvent[]> {
  if (!branchFor(run)) {
    console.warn(
      `Skipping workflow run ${run.id}: unsupported event or unresolved target branch`,
    );
    return [];
  }
  const artifacts: Artifact[] = [];
  for (let page = 1;; page++) {
    const result = await github.json<{ artifacts: Artifact[] }>(
      `/repos/${github.repository}/actions/runs/${run.id}/artifacts?per_page=100&page=${page}`,
    );
    artifacts.push(...result.artifacts);
    if (result.artifacts.length < 100) break;
  }
  const events: CaseEvent[] = [];
  for (let attempt = 1; attempt <= run.run_attempt; attempt++) {
    const matching = artifacts.filter((item) => item.name === prefix + attempt);
    if (matching.length > 1) throw new Error("Ambiguous JUnit artifacts");
    if (!matching.length || matching[0].expired) {
      if (attempt === run.run_attempt && run.conclusion === "success") {
        throw new Error(
          "Successful workflow attempt is missing its JUnit artifact",
        );
      }
      continue;
    }
    const metadata = attempt === run.run_attempt ? run : await github.json<Run>(
      `/repos/${github.repository}/actions/runs/${run.id}/attempts/${attempt}`,
    );
    if (metadata.id !== run.id || metadata.run_attempt !== attempt) {
      throw new Error("Unexpected workflow attempt metadata");
    }
    if (metadata.status !== "completed") continue;
    const branch = branchFor(metadata);
    if (!branch) {
      console.warn(
        `Skipping workflow run ${run.id} attempt ${attempt}: unresolved target branch`,
      );
      continue;
    }
    const cases = parseArtifact(await github.artifact(matching[0].id));
    if (!Object.keys(cases).length) continue;
    events.push({
      repo: github.repository,
      branch,
      buildUrl:
        `https://github.com/${github.repository}/actions/runs/${run.id}/attempts/${attempt}`,
      eventId: `github-actions-${run.id}-${attempt}`,
      cases,
    });
  }
  return events;
}

export async function collectEvents(
  github: GitHub,
  repositoryId: number,
  now = new Date(),
  lookbackHours = 24,
): Promise<{ events: CaseEvent[]; failedRuns: number[] }> {
  // Numeric repository identity survives a repository rename or transfer.
  github.repository =
    (await github.json<{ full_name: string }>(`/repositories/${repositoryId}`))
      .full_name;
  if (!/^[\w.-]+\/[\w.-]+$/.test(github.repository)) {
    throw new Error("Invalid repository metadata");
  }
  const horizon = new Date(now.getTime() - 90 * 86400000);
  const since = now.getTime() - lookbackHours * 3600000;
  const events: CaseEvent[] = [];
  const failedRuns: number[] = [];
  for (const [workflow, prefix] of Object.entries(WORKFLOWS)) {
    const runs = [
      ...new Map(
        (await discoverRuns(github, workflow, horizon, now))
          .filter((run) =>
            run.status === "completed" && Date.parse(run.updated_at) >= since
          )
          .map((run) => [run.id, run]),
      ).values(),
    ];
    for (let offset = 0; offset < runs.length; offset += 4) {
      const batch = await Promise.all(
        runs.slice(offset, offset + 4).map(async (run) => {
          try {
            return await eventsForRun(github, run, prefix);
          } catch {
            // A malformed/unavailable artifact must not block other CI runs.
            failedRuns.push(run.id);
            console.error(
              `Unable to collect workflow run ${run.id}; it will be retried by a later poll`,
            );
            return [];
          }
        }),
      );
      events.push(...batch.flat());
    }
  }
  return { events, failedRuns };
}

export function caseKey(
  event: Omit<CaseEvent, "cases" | "eventId">,
  suite: string,
  name: string,
): string {
  return JSON.stringify([
    event.repo,
    event.branch,
    event.buildUrl,
    suite,
    name,
  ]);
}

export function missingCases(
  event: CaseEvent,
  existing: Set<string>,
): ProblemCases {
  const entries: Array<[string, ProblemCases[string]]> = [];
  for (const [suite, result] of Object.entries(event.cases)) {
    const missing = result.new_flaky.filter((item) =>
      !existing.has(caseKey(event, suite, item.name))
    );
    if (missing.length) entries.push([suite, { new_flaky: missing }]);
  }
  return Object.fromEntries(entries);
}

export async function existingCases(
  db: Connection,
  events: CaseEvent[],
): Promise<Set<string>> {
  const existing = new Set<string>();
  const repo = events[0]?.repo;
  const urls = [...new Set(events.map((event) => event.buildUrl))];
  for (let offset = 0; offset < urls.length; offset += 100) {
    const batch = urls.slice(offset, offset + 100);
    const [rows] = await db.execute(
      `SELECT repo, branch, build_url, suite_name, case_name FROM problem_case_runs WHERE repo = ? AND flaky > 0 AND build_url IN (${
        batch.map(() => "?").join(",")
      })`,
      [repo, ...batch],
    ).catch(() => {
      // Driver errors can include SQL parameters (suite/case names).
      throw new Error("Database deduplication query failed");
    });
    for (
      const row of rows as Array<
        {
          repo: string;
          branch: string;
          build_url: string;
          suite_name: string;
          case_name: string;
        }
      >
    ) {
      existing.add(
        caseKey(
          { repo: row.repo, branch: row.branch, buildUrl: row.build_url },
          row.suite_name,
          row.case_name,
        ),
      );
    }
  }
  return existing;
}

export async function postEvent(
  event: CaseEvent,
  cases: ProblemCases,
  request: typeof fetch = fetch,
): Promise<void> {
  const response = await request(COLLECTOR_URL, {
    method: "POST",
    redirect: "error",
    signal: AbortSignal.timeout(15000),
    headers: {
      "content-type": "application/json; charset=UTF-8",
      "ce-specversion": "1.0",
      "ce-type": "test-case-run-report",
      "ce-id": event.eventId,
      "ce-source": "https://github.com/" + event.repo + "/actions",
      "ce-repo": event.repo,
      "ce-branch": event.branch,
      "ce-buildurl": event.buildUrl,
    },
    body: JSON.stringify(cases),
  });
  await response.body?.cancel();
  if (!response.ok) {
    throw new Error(`Internal collector returned HTTP ${response.status}`);
  }
}

export async function reportEvents(
  db: Connection,
  events: CaseEvent[],
  request: typeof fetch = fetch,
): Promise<number> {
  const existing = await existingCases(db, events);
  let reported = 0;
  for (const event of events) {
    const cases = missingCases(event, existing);
    if (!Object.keys(cases).length) continue;
    // Never automatically retry a POST: the shared receiver is append-only.
    await postEvent(event, cases, request);
    for (const [suite, result] of Object.entries(cases)) {
      for (const item of result.new_flaky) {
        existing.add(caseKey(event, suite, item.name));
      }
      reported += result.new_flaky.length;
    }
  }
  return reported;
}

export async function main(): Promise<void> {
  const token = Deno.env.get("GITHUB_TOKEN");
  const dsn = Deno.env.get("PARAM_DSN");
  const repositoryId = Number(Deno.env.get("REPOSITORY_ID"));
  if (
    !token || !dsn || !Number.isSafeInteger(repositoryId) || repositoryId <= 0
  ) {
    throw new Error(
      "GITHUB_TOKEN, PARAM_DSN and a positive REPOSITORY_ID are required",
    );
  }
  const { events, failedRuns } = await collectEvents(
    new GitHub(token),
    repositoryId,
  );
  if (!events.length) {
    if (failedRuns.length) {
      throw new Error("Some workflow runs could not be collected");
    }
    console.log("No recovered flaky cases in the collection window");
    return;
  }
  let config;
  try {
    config = convertDsnToClientConfig(dsn);
  } catch {
    // URL/driver errors must not expose the DSN or database credentials.
    throw new Error("Invalid database DSN");
  }
  config.ssl = { rejectUnauthorized: true };
  const db = await createConnection(config).catch(() => {
    throw new Error("Database connection or TLS failed");
  });
  try {
    const reported = await reportEvents(db, events);
    console.log(
      `Reported ${reported} new flaky cases from ${events.length} workflow attempts`,
    );
  } finally {
    await db.end();
  }
  if (failedRuns.length) {
    throw new Error("Some workflow runs could not be collected");
  }
}

if (import.meta.main) {
  try {
    await main();
  } catch (error) {
    console.error(
      `Case-run collection failed: ${
        error instanceof Error ? error.message : "Unknown error"
      }`,
    );
    Deno.exit(1);
  }
}
