import { assertEquals } from "@std/assert";
import { GithubIssueManager } from "./GithubIssueManager.ts";
import type { CaseAgg, ReportData } from "./types.ts";

type TestIssue = {
  number: number;
  title: string;
  state: "open" | "closed";
  html_url: string;
  closed_at?: string | null;
};

function buildReport(): ReportData {
  return {
    window: { from: "2026-03-06", to: "2026-03-13", thresholdMs: 1000 },
    summary: {
      repos: 1,
      suites: 1,
      cases: 1,
      flakyCases: 1,
      thresholdedCases: 1,
    },
    byTeam: [],
    bySuite: [],
    byCase: [],
    topFlakyCases: [],
  };
}

function buildCase(): CaseAgg {
  return {
    repo: "pingcap/tidb",
    branch: "main",
    suite_name: "pkg/executor",
    case_name: "TestFlakyCase",
    flakyCount: 3,
    thresholdedCount: 1,
    owner: "@test-owner",
  };
}

function createManager(client: object): GithubIssueManager {
  const manager = new GithubIssueManager({
    token: "test-token",
    allowCreate: false,
    allowReopen: true,
    allowComment: false,
    dryRun: false,
    labels: [],
    now: () => new Date("2026-03-13T12:00:00Z"),
    verbose: false,
  });
  Object.defineProperty(manager, "client", {
    value: client,
    configurable: true,
    writable: true,
  });
  return manager;
}

Deno.test("GithubIssueManager.sync reopens issues closed at least 10 days ago", async () => {
  const issue: TestIssue = {
    number: 123,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "closed",
    html_url: "https://github.com/pingcap/tidb/issues/123",
    closed_at: "2026-03-03T12:00:00Z",
  };
  let reopenCalls = 0;
  const manager = createManager({
    searchIssues: async () => [issue],
    reopenIssue: async () => {
      reopenCalls += 1;
      return {
        ...issue,
        state: "open",
        closed_at: null,
      };
    },
  });
  const report = buildReport();
  const flakyCase = buildCase();

  await manager.sync(report, [flakyCase], [flakyCase]);

  assertEquals(reopenCalls, 1);
  assertEquals(flakyCase.issue?.status, "reopened");
  assertEquals(flakyCase.issue?.state, "open");
  assertEquals(flakyCase.issue?.number, 123);
});

Deno.test("GithubIssueManager.sync keeps recently closed issues closed", async () => {
  const issue: TestIssue = {
    number: 456,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "closed",
    html_url: "https://github.com/pingcap/tidb/issues/456",
    closed_at: "2026-03-04T12:00:01Z",
  };
  let reopenCalls = 0;
  const manager = createManager({
    searchIssues: async () => [issue],
    reopenIssue: async () => {
      reopenCalls += 1;
      return issue;
    },
  });
  const report = buildReport();
  const flakyCase = buildCase();

  await manager.sync(report, [flakyCase], [flakyCase]);

  assertEquals(reopenCalls, 0);
  assertEquals(flakyCase.issue?.status, "closed");
  assertEquals(flakyCase.issue?.state, "closed");
  assertEquals(flakyCase.issue?.number, 456);
  assertEquals(
    flakyCase.issue?.note,
    "skip reopen: issue closed less than 10 days ago",
  );
});
