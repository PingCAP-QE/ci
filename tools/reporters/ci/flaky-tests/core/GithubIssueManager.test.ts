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
    latestFlakyFoundAt: new Date("2026-03-12T12:00:00Z"),
  };
}

function createManager(
  client: Record<string, unknown>,
  opts?: {
    allowCreate?: boolean;
    allowReopen?: boolean;
    allowComment?: boolean;
    mutationLimit?: number;
    dryRun?: boolean;
  },
  fetchFn?: typeof fetch,
): GithubIssueManager {
  const manager = new GithubIssueManager({
    token: "test-token",
    allowCreate: opts?.allowCreate ?? false,
    allowReopen: opts?.allowReopen ?? true,
    allowComment: opts?.allowComment ?? false,
    mutationLimit: opts?.mutationLimit ?? 10,
    dryRun: opts?.dryRun ?? false,
    labels: [],
    now: () => new Date("2026-03-13T12:00:00Z"),
    fetchFn,
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

  await manager.sync(report, [flakyCase]);

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
    latestFlakyFoundAt: new Date("2026-03-10T12:00:00Z"),
  };

  await manager.sync(report, [flakyCase]);

  assertEquals(reopenCalls, 0);
  assertEquals(flakyCase.issue?.status, "closed");
  assertEquals(flakyCase.issue?.state, "closed");
  assertEquals(flakyCase.issue?.number, 456);
  assertEquals(
    flakyCase.issue?.note,
    "skip reopen: latest flaky build started_at 2026-03-10T12:00:00.000Z <= closed_at 2026-03-10T12:00:01.000Z (branch=main)",
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
    latestFlakyFoundAt: new Date("2026-03-12T01:00:00Z"),
  };

  await manager.sync(report, [flakyCase]);

  assertEquals(reopenCalls, 1);
  assertEquals(flakyCase.issue?.status, "reopened");
  assertEquals(flakyCase.issue?.state, "open");
  assertEquals(flakyCase.issue?.number, 789);
});

Deno.test("GithubIssueManager.sync uses Jenkins timestamp when available for reopen check", async () => {
  const issue: TestIssue = {
    number: 321,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "closed",
    html_url: "https://github.com/pingcap/tidb/issues/321",
    closed_at: "2026-03-10T12:00:00Z",
  };

  const jenkinsBuildUrl = "https://prow.tidb.net/jenkins/job/test/123/";
  const jenkinsStartedAt = new Date("2026-03-12T00:00:00Z");

  let reopenCalls = 0;
  const comments: string[] = [];

  const fetchFn: typeof fetch = async (input) => {
    const url = typeof input === "string" ? input : input.toString();
    if (url === `${jenkinsBuildUrl}api/json?tree=timestamp`) {
      return new Response(
        JSON.stringify({ timestamp: jenkinsStartedAt.getTime() }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    }
    return new Response("not found", { status: 404 });
  };

  const manager = createManager(
    {
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
    },
    undefined,
    fetchFn,
  );

  const report = buildReport();
  const flakyCase = {
    ...buildCase(),
    latestFlakyBuildUrl: jenkinsBuildUrl,
    // Make fallback older than closed_at to ensure Jenkins timestamp is used.
    latestFlakyFoundAt: new Date("2026-03-09T00:00:00Z"),
  };

  await manager.sync(report, [flakyCase]);

  assertEquals(reopenCalls, 1);
  assertEquals(comments.length, 1);
  assertEquals(comments[0].includes("jenkins.timestamp"), true);
  assertEquals(comments[0].includes(jenkinsBuildUrl), true);
  assertEquals(flakyCase.issue?.status, "reopened");
});

Deno.test("GithubIssueManager.sync uses Prow started.json when available for reopen check", async () => {
  const issue: TestIssue = {
    number: 654,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "closed",
    html_url: "https://github.com/pingcap/tidb/issues/654",
    closed_at: "2026-03-10T12:00:00Z",
  };

  const prowViewUrl =
    "https://prow.tidb.net/view/gs/prow-tidb-logs/pr-logs/pull/foo/bar/123";
  const startedJsonUrl =
    "https://storage.googleapis.com/prow-tidb-logs/pr-logs/pull/foo/bar/123/started.json";
  const prowStartedAt = new Date("2026-03-12T00:00:00Z");

  let reopenCalls = 0;
  const comments: string[] = [];

  const fetchFn: typeof fetch = async (input) => {
    const url = typeof input === "string" ? input : input.toString();
    if (url === startedJsonUrl) {
      // started.json timestamp is seconds.
      return new Response(
        JSON.stringify({
          timestamp: Math.floor(prowStartedAt.getTime() / 1000),
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    }
    return new Response("not found", { status: 404 });
  };

  const manager = createManager(
    {
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
    },
    undefined,
    fetchFn,
  );

  const report = buildReport();
  const flakyCase = {
    ...buildCase(),
    latestFlakyBuildUrl: prowViewUrl,
    // Make fallback older than closed_at to ensure started.json is used.
    latestFlakyFoundAt: new Date("2026-03-09T00:00:00Z"),
  };

  await manager.sync(report, [flakyCase]);

  assertEquals(reopenCalls, 1);
  assertEquals(comments.length, 1);
  assertEquals(comments[0].includes("prow.started.json"), true);
  assertEquals(comments[0].includes(prowViewUrl), true);
  assertEquals(flakyCase.issue?.status, "reopened");
});

Deno.test("GithubIssueManager.sync skips reopen when started_at cannot be resolved and no fallback exists", async () => {
  const issue: TestIssue = {
    number: 999,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "closed",
    html_url: "https://github.com/pingcap/tidb/issues/999",
    closed_at: "2026-03-10T12:00:00Z",
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
    latestFlakyBuildUrl: "https://ci.example.com/unknown/1",
    // No fallback time available.
    latestFlakyFoundAt: undefined,
  };

  await manager.sync(report, [flakyCase]);

  assertEquals(reopenCalls, 0);
  assertEquals(flakyCase.issue?.status, "closed");
  assertEquals(
    flakyCase.issue?.note,
    "skip reopen: missing latest flaky evidence (build_url/started_at/fallback time)",
  );
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

  await manager.sync(report, [flakyCase]);

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

  await manager.sync(report, [flakyCase]);

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

  await manager.sync(report, [flakyCase]);

  assertEquals(exactCalls, 1);
  assertEquals(looseCalls, 1);
  assertEquals(flakyCase.issue?.number, 140);
  assertEquals(flakyCase.issue?.state, "open");
  assertEquals(flakyCase.issue?.status, "open");
});

Deno.test("GithubIssueManager.sync comments only top-N cases within the same issue key", async () => {
  const issue: TestIssue = {
    number: 555,
    title: "Flaky test: TestFlakyCase in pkg/executor",
    state: "open",
    html_url: "https://github.com/pingcap/tidb/issues/555",
  };
  const comments: string[] = [];
  const manager = createManager({
    searchIssues: async () => [issue],
    addComment: async (
      _owner: string,
      _repo: string,
      _issueNumber: number,
      body: string,
    ) => {
      comments.push(body);
    },
  }, {
    allowComment: true,
    mutationLimit: 1,
  });
  const report = buildReport();
  const mainCase = {
    ...buildCase(),
    branch: "main",
    flakyCount: 5,
  };
  const releaseCase = {
    ...buildCase(),
    branch: "release-8.5",
    flakyCount: 1,
  };

  await manager.sync(report, [releaseCase, mainCase]);

  assertEquals(comments.length, 1);
  assertEquals(comments[0].includes("- Branch: main"), true);
  assertEquals(mainCase.issue?.status, "open");
  assertEquals(releaseCase.issue?.status, "open");
});

Deno.test("GithubIssueManager.sync creates shared issue from the top-ranked case in a mutable group", async () => {
  const createBodies: string[] = [];
  const manager = createManager({
    searchIssues: async () => [],
    createIssue: async (
      _owner: string,
      _repo: string,
      _title: string,
      body: string,
    ) => {
      createBodies.push(body);
      return {
        number: 556,
        title: "Flaky test: TestFlakyCase in pkg/executor",
        state: "open",
        html_url: "https://github.com/pingcap/tidb/issues/556",
      };
    },
  }, {
    allowCreate: true,
    mutationLimit: 1,
  });
  const report = buildReport();
  const mainCase = {
    ...buildCase(),
    branch: "main",
    flakyCount: 5,
  };
  const releaseCase = {
    ...buildCase(),
    branch: "release-8.5",
    flakyCount: 1,
  };

  await manager.sync(report, [releaseCase, mainCase]);

  assertEquals(createBodies.length, 1);
  assertEquals(createBodies[0].includes("- Branch: main"), true);
  assertEquals(mainCase.issue?.status, "new");
  assertEquals(releaseCase.issue?.status, "new");
});
