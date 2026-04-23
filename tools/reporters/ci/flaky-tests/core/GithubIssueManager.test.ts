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
    latestFlakyBuildUrl: "https://ci.example.com/build/1",
    latestFlakyReportTime: new Date("2026-03-12T12:00:00Z"),
  };
}

function createManager(client: Record<string, unknown>): GithubIssueManager {
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
    value: {
      addComment: async () => {},
      ...client,
    },
    configurable: true,
    writable: true,
  });
  return manager;
}

Deno.test("GithubIssueManager.sync reopens issues when latest flaky build started after issue closed", async () => {
  const issue: TestIssue = {
    number: 123,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "closed",
    html_url: "https://github.com/pingcap/tidb/issues/123",
    closed_at: "2026-03-03T12:00:00Z",
  };
  let reopenCalls = 0;
  const comments: string[] = [];
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
    addComment: async (
      _owner: string,
      _repo: string,
      _issueNumber: number,
      body: string,
    ) => {
      comments.push(body);
    },
  });
  const report = buildReport();
  const flakyCase = buildCase();

  await manager.sync(report, [flakyCase], [flakyCase]);

  assertEquals(reopenCalls, 1);
  assertEquals(comments.length, 1);
  assertEquals(comments[0].includes("https://ci.example.com/build/1"), true);
  assertEquals(flakyCase.issue?.status, "reopened");
  assertEquals(flakyCase.issue?.state, "open");
  assertEquals(flakyCase.issue?.number, 123);
});

Deno.test("GithubIssueManager.sync keeps issue closed when latest flaky build is not newer than closed_at", async () => {
  const issue: TestIssue = {
    number: 456,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "closed",
    html_url: "https://github.com/pingcap/tidb/issues/456",
    closed_at: "2026-03-10T12:00:01Z",
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
  const flakyCase = {
    ...buildCase(),
    latestFlakyReportTime: new Date("2026-03-10T12:00:00Z"),
  };

  await manager.sync(report, [flakyCase], [flakyCase]);

  assertEquals(reopenCalls, 0);
  assertEquals(flakyCase.issue?.status, "closed");
  assertEquals(flakyCase.issue?.state, "closed");
  assertEquals(flakyCase.issue?.number, 456);
  assertEquals(
    flakyCase.issue?.note,
    "skip reopen: latest flaky build start 2026-03-10T12:00:00.000Z <= closed_at 2026-03-10T12:00:01.000Z",
  );
});

Deno.test("GithubIssueManager.sync reopens issue even if it was closed in current window (build after closed_at)", async () => {
  const issue: TestIssue = {
    number: 789,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "closed",
    html_url: "https://github.com/pingcap/tidb/issues/789",
    closed_at: "2026-03-10T12:00:01Z",
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
  const flakyCase = {
    ...buildCase(),
    latestFlakyReportTime: new Date("2026-03-12T01:00:00Z"),
  };

  await manager.sync(report, [flakyCase], [flakyCase]);

  assertEquals(reopenCalls, 1);
  assertEquals(flakyCase.issue?.status, "reopened");
  assertEquals(flakyCase.issue?.state, "open");
  assertEquals(flakyCase.issue?.number, 789);
});

Deno.test("GithubIssueManager.sync prefers open issue over closed exact title match", async () => {
  const closedExact: TestIssue = {
    number: 200,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "closed",
    html_url: "https://github.com/pingcap/tidb/issues/200",
    closed_at: "2026-03-01T12:00:00Z",
  };
  const openLoose: TestIssue = {
    number: 100,
    title: "Flaky test: TestFlakyCase intermittently fails",
    state: "open",
    html_url: "https://github.com/pingcap/tidb/issues/100",
  };

  const searchModes: Array<"exact" | "loose"> = [];
  const manager = createManager({
    searchIssues: async (
      _owner: string,
      _repo: string,
      _title: string,
      looseCaseName?: string,
    ) => {
      searchModes.push(
        looseCaseName ? "loose" : "exact",
      );
      if (looseCaseName) return [openLoose];
      return [closedExact];
    },
  });
  const report = buildReport();
  const flakyCase = buildCase();

  await manager.sync(report, [flakyCase], [flakyCase]);

  assertEquals(searchModes, ["exact", "loose"]);
  assertEquals(flakyCase.issue?.number, 100);
  assertEquals(flakyCase.issue?.state, "open");
  assertEquals(flakyCase.issue?.status, "open");
});

Deno.test("GithubIssueManager.sync prefers exact title when issue state is same", async () => {
  const openExact: TestIssue = {
    number: 300,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "open",
    html_url: "https://github.com/pingcap/tidb/issues/300",
  };
  const openLoose: TestIssue = {
    number: 120,
    title: "Flaky test: TestFlakyCase intermittently fails",
    state: "open",
    html_url: "https://github.com/pingcap/tidb/issues/120",
  };

  const manager = createManager({
    searchIssues: async (
      _owner: string,
      _repo: string,
      _title: string,
      looseCaseName?: string,
    ) => {
      if (looseCaseName) return [openLoose];
      return [openExact];
    },
  });
  const report = buildReport();
  const flakyCase = buildCase();

  await manager.sync(report, [flakyCase], [flakyCase]);

  assertEquals(flakyCase.issue?.number, 300);
  assertEquals(flakyCase.issue?.state, "open");
  assertEquals(flakyCase.issue?.status, "open");
});

Deno.test("GithubIssueManager.sync can match issue from loose title query", async () => {
  const openLoose: TestIssue = {
    number: 140,
    title: "Flaky test: TestFlakyCase intermittently fails in executor",
    state: "open",
    html_url: "https://github.com/pingcap/tidb/issues/140",
  };

  let exactCalls = 0;
  let looseCalls = 0;
  const manager = createManager({
    searchIssues: async (
      _owner: string,
      _repo: string,
      _title: string,
      looseCaseName?: string,
    ) => {
      if (looseCaseName) {
        looseCalls += 1;
        return [openLoose];
      }
      exactCalls += 1;
      return [];
    },
  });
  const report = buildReport();
  const flakyCase = buildCase();

  await manager.sync(report, [flakyCase], [flakyCase]);

  assertEquals(exactCalls, 1);
  assertEquals(looseCalls, 1);
  assertEquals(flakyCase.issue?.number, 140);
  assertEquals(flakyCase.issue?.state, "open");
  assertEquals(flakyCase.issue?.status, "open");
});
