// Fetch mocks intentionally use async functions to preserve fetch's Promise API.
// deno-lint-ignore-file require-await
import assert from "node:assert/strict";
import type { Connection } from "npm:mysql2@3.11.5/promise";
import { strToU8, zipSync } from "npm:fflate@0.8.3";
import {
  branchFor,
  caseKey,
  collectEvents,
  COLLECTOR_URL,
  eventsForRun,
  existingCases,
  GitHub,
  main,
  missingCases,
  postEvent,
  reportEvents,
} from "./main.ts";
import type { CaseEvent } from "./main.ts";

const repo = "tidbcloud/renamed-engine";
const now = new Date("2026-11-12T08:00:00Z");
const cases = { suite: { new_flaky: [{ name: "recovers", reason: "flaky" }] } };
const xml =
  '<testsuites><testsuite><testcase classname="suite" name="recovers"><flakyFailure/></testcase><testcase classname="suite" name="never"><failure/><rerunFailure/></testcase></testsuite></testsuites>';
const archive = zipSync({ "junit.xml": strToU8(xml) });
const clean = zipSync({
  "junit.xml": strToU8(
    '<testsuite><testcase classname="suite" name="passes"/></testsuite>',
  ),
});
const run = (id = 1, overrides = {}) => ({
  id,
  run_attempt: 1,
  status: "completed",
  conclusion: "success",
  event: "pull_request",
  updated_at: now.toISOString(),
  pull_requests: [{ base: { ref: "cloud-engine" } }],
  head_branch: "feature/not-the-target",
  ...overrides,
});
const event = (overrides: Partial<CaseEvent> = {}): CaseEvent => ({
  repo,
  branch: "cloud-engine",
  buildUrl: `https://github.com/${repo}/actions/runs/1/attempts/1`,
  eventId: "github-actions-1-1",
  cases,
  ...overrides,
});
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });

function githubFixture(
  options: {
    runs?: ReturnType<typeof run>[];
    artifacts?: unknown[];
    older?: ReturnType<typeof run>;
    bytes?: Uint8Array;
    latestClean?: boolean;
  } = {},
) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const request = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    const path = new URL(url).pathname;
    if (path === "/repositories/492109999") return json({ full_name: repo });
    if (path.includes("/workflows/unit-test.yml/runs")) {
      return json({
        total_count: (options.runs ?? [run()]).length,
        workflow_runs: options.runs ?? [run()],
      });
    }
    if (path.includes("/workflows/integrations.yml/runs")) {
      return json({ total_count: 0, workflow_runs: [] });
    }
    if (path.endsWith("/artifacts")) {
      return json({
        artifacts: options.artifacts ??
          [{ id: 9, name: "unit-test-junit-attempt-1", expired: false }],
      });
    }
    if (path.endsWith("/attempts/1")) return json(options.older ?? run());
    if (path.endsWith("/zip")) {
      return new Response(null, {
        status: 302,
        headers: {
          location: `https://artifacts.githubusercontent.com/${
            path.split("/").at(-2)
          }.zip?signature=private`,
        },
      });
    }
    if (url.startsWith("https://artifacts.githubusercontent.com/")) {
      return new Response(
        Uint8Array.from(
          options.latestClean && path === "/10.zip"
            ? clean
            : options.bytes ?? archive,
        ),
      );
    }
    throw new Error("Unexpected fake request " + path);
  }) as typeof fetch;
  const github = new GitHub("test-github-token", request);
  github.repository = repo;
  return { github, calls, request };
}

Deno.test("PR base branch, not head branch; ambiguous/unsupported metadata skips", () => {
  assert.equal(branchFor(run()), "cloud-engine");
  for (const source of ["workflow_dispatch", "push", "schedule"]) {
    assert.equal(
      branchFor(run(1, { event: source, head_branch: "dedicated" })),
      "dedicated",
    );
  }
  assert.equal(
    branchFor(
      run(1, {
        pull_requests: [{ base: { ref: "a" } }, { base: { ref: "a" } }],
      }),
    ),
    "a",
  );
  for (
    const source of [
      run(1, { pull_requests: [] }),
      run(1, {
        pull_requests: [{ base: { ref: "a" } }, { base: { ref: "b" } }],
      }),
      run(1, { event: "merge_group" }),
      run(1, { event: "unknown" }),
      run(1, { event: "push", head_branch: "" }),
      run(1, { event: "workflow_dispatch", head_branch: "" }),
    ]
  ) {
    assert.equal(branchFor(source), undefined);
  }
});

Deno.test("resolve numeric repo identity after rename and parse existing nextest artifact", async () => {
  const { github, calls } = githubFixture();
  const { events, failedRuns } = await collectEvents(github, 492109999, now);
  assert.deepEqual(failedRuns, []);
  assert.equal(events.length, 1);
  assert.equal(events[0].repo, repo);
  assert.equal(events[0].branch, "cloud-engine");
  assert.equal(events[0].buildUrl, event().buildUrl);
  assert.deepEqual(events[0].cases, cases);
  assert.equal(
    new Headers(calls[0].init?.headers).get("Authorization"),
    "Bearer test-github-token",
  );
  const download = calls.find((call) =>
    call.url.startsWith("https://artifacts.githubusercontent.com/")
  )!;
  assert.equal(new Headers(download.init?.headers).get("Authorization"), null);
  assert.equal(download.init?.redirect, "error");
});

Deno.test("recover earlier nextest attempts even if latest workflow rerun is clean", async () => {
  const { github } = githubFixture({
    latestClean: true,
    artifacts: [
      { id: 9, name: "unit-test-junit-attempt-1", expired: false },
      { id: 10, name: "unit-test-junit-attempt-2", expired: false },
    ],
  });
  const events = await eventsForRun(
    github,
    run(1, { run_attempt: 2 }),
    "unit-test-junit-attempt-",
  );
  assert.equal(events.length, 1);
  assert.deepEqual(events.map((item) => item.eventId), ["github-actions-1-1"]);
});

Deno.test("unsupported events and unresolved PR branches skip without failing healthy runs", async () => {
  const fixture = githubFixture({
    runs: [
      run(1, { event: "merge_group" }),
      run(2, { pull_requests: [] }),
      run(3, {
        pull_requests: [{ base: { ref: "a" } }, { base: { ref: "b" } }],
      }),
      run(4),
    ],
  });
  const original = console.warn;
  const warnings: string[] = [];
  console.warn = (message: string) => warnings.push(message);
  try {
    const result = await collectEvents(fixture.github, 492109999, now);
    assert.deepEqual(result.failedRuns, []);
    assert.deepEqual(result.events.map((item) => item.eventId), [
      "github-actions-4-1",
    ]);
    assert.equal(warnings.length, 3);
    assert.ok(
      warnings.every((message) => /Skipping workflow run/.test(message)),
    );
    assert.equal(
      fixture.calls.filter((call) => call.url.includes("/artifacts?")).length,
      1,
    );
  } finally {
    console.warn = original;
  }
});

Deno.test("unresolved earlier attempt metadata does not block a supported latest attempt", async () => {
  const fixture = githubFixture({
    older: run(1, { pull_requests: [] }),
    artifacts: [
      { id: 9, name: "unit-test-junit-attempt-1", expired: false },
      { id: 10, name: "unit-test-junit-attempt-2", expired: false },
    ],
  });
  const original = console.warn;
  const warnings: string[] = [];
  console.warn = (message: string) => warnings.push(message);
  try {
    const events = await eventsForRun(
      fixture.github,
      run(1, { run_attempt: 2 }),
      "unit-test-junit-attempt-",
    );
    assert.deepEqual(events.map((item) => item.eventId), [
      "github-actions-1-2",
    ]);
    assert.equal(warnings.length, 1);
    assert.match(warnings[0], /attempt 1/);
  } finally {
    console.warn = original;
  }
});

Deno.test("clean and stale runs produce no events", async () => {
  const fixture = githubFixture({ bytes: clean });
  assert.deepEqual(await collectEvents(fixture.github, 492109999, now), {
    events: [],
    failedRuns: [],
  });
  const stale = githubFixture({
    runs: [run(1, { updated_at: "2026-11-10T00:00:00Z" })],
  });
  assert.deepEqual(await collectEvents(stale.github, 492109999, now), {
    events: [],
    failedRuns: [],
  });
  assert.equal(
    stale.calls.filter((call) =>
      call.url.endsWith("/artifacts?per_page=100&page=1")
    ).length,
    0,
  );
});

Deno.test("missing/expired successful report fails; failed-before-tests attempt skips", async () => {
  for (
    const artifacts of [[], [{
      id: 9,
      name: "unit-test-junit-attempt-1",
      expired: true,
    }]]
  ) {
    const { github } = githubFixture({ artifacts });
    await assert.rejects(
      () => eventsForRun(github, run(), "unit-test-junit-attempt-"),
      /missing its JUnit/,
    );
  }
  const { github } = githubFixture({ artifacts: [] });
  assert.deepEqual(
    await eventsForRun(
      github,
      run(1, { conclusion: "failure" }),
      "unit-test-junit-attempt-",
    ),
    [],
  );
});

Deno.test("ambiguous archives and invalid repository names fail closed", async () => {
  const artifacts = Array.from(
    { length: 2 },
    (_, id) => ({ id, name: "unit-test-junit-attempt-1", expired: false }),
  );
  await assert.rejects(
    () =>
      eventsForRun(
        githubFixture({ artifacts }).github,
        run(),
        "unit-test-junit-attempt-",
      ),
    /Ambiguous/,
  );
  const github = new GitHub(
    "token",
    (async () => json({ full_name: "../../wrong" })) as typeof fetch,
  );
  await assert.rejects(
    () => collectEvents(github, 492109999, now),
    /Invalid repository/,
  );
});

Deno.test("API access failures are explicit and download credentials never redirect", async () => {
  const forbidden = new GitHub(
    "token",
    (async () => json({}, 403)) as typeof fetch,
  );
  await assert.rejects(() => forbidden.json("/user"), /HTTP 403/);
  forbidden.repository = repo;
  await assert.rejects(() => forbidden.artifact(1), /HTTP 403/);
  for (
    const location of [
      "http://artifacts.githubusercontent.com/report.zip",
      "https://storage.example/report.zip",
      "https://artifacts.githubusercontent.com.attacker.example/report.zip",
      "https://githubusercontent.com/report.zip",
      "https://account.blob.core.windows.net.attacker.example/report.zip",
    ]
  ) {
    let calls = 0;
    const unsafe = new GitHub(
      "token",
      (async () => {
        calls++;
        return new Response(null, { status: 302, headers: { location } });
      }) as typeof fetch,
    );
    unsafe.repository = repo;
    await assert.rejects(() => unsafe.artifact(1), /Unsafe/);
    assert.equal(calls, 1, "unsafe redirect must not be fetched");
  }
  const azure = new GitHub(
    "token",
    (async (input, init) => {
      if (String(input).startsWith("https://api.github.com")) {
        return new Response(null, {
          status: 302,
          headers: {
            location:
              "https://productionresultssa0.blob.core.windows.net/report.zip",
          },
        });
      }
      assert.equal(new Headers(init?.headers).get("Authorization"), null);
      return new Response(archive);
    }) as typeof fetch,
  );
  azure.repository = repo;
  assert.deepEqual(await azure.artifact(1), archive);
});

Deno.test("artifact downloads are size-bounded and storage failures surface", async () => {
  let storage = new Response(new Uint8Array(16 * 1024 * 1024 + 1));
  const github = new GitHub(
    "token",
    (async (input) =>
      String(input).startsWith("https://api.github.com")
        ? new Response(null, {
          status: 302,
          headers: {
            location: "https://artifacts.githubusercontent.com/report.zip",
          },
        })
        : storage) as typeof fetch,
  );
  github.repository = repo;
  await assert.rejects(() => github.artifact(1), /too large/);
  storage = new Response(null, { status: 503 });
  await assert.rejects(() => github.artifact(1), /storage download/);
});

Deno.test("database checks are SELECT-only, parameterized, and batched", async () => {
  const queries: Array<{ sql: string; args: unknown[] }> = [];
  const db = {
    execute: (sql: string, args: unknown[]) => {
      queries.push({ sql, args });
      return Promise.resolve([[{
        repo,
        branch: "cloud-engine",
        build_url: event().buildUrl,
        suite_name: "suite",
        case_name: "recovers",
      }], []]);
    },
  } as unknown as Connection;
  const events = Array.from(
    { length: 101 },
    (_, index) => event({ buildUrl: `url-${index}` }),
  );
  const existing = await existingCases(db, events);
  assert.equal(queries.length, 2);
  assert.equal(queries[0].args.length, 101);
  assert.match(queries[0].sql, /^SELECT /);
  assert.ok(!queries[0].sql.includes(repo));
  assert.ok(existing.has(caseKey(event(), "suite", "recovers")));
  assert.deepEqual(missingCases(event(), existing), {});
  assert.deepEqual(
    missingCases(event({ branch: "dedicated" }), existing),
    cases,
  );
  assert.deepEqual(await existingCases(db, []), new Set());
});

Deno.test("TiDB internal POST contract has no new endpoint configuration or credentials", async () => {
  let calls = 0;
  const request = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls++;
    assert.equal(String(input), COLLECTOR_URL);
    assert.equal(COLLECTOR_URL, "http://cloudevents-server.cs.svc/events");
    assert.equal(init?.method, "POST");
    assert.equal(init?.redirect, "error");
    const headers = new Headers(init?.headers);
    assert.equal(headers.get("authorization"), null);
    assert.equal(headers.get("ce-type"), "test-case-run-report");
    assert.equal(
      headers.get("ce-source"),
      `https://github.com/${repo}/actions`,
    );
    assert.equal(headers.get("ce-id"), event().eventId);
    assert.equal(headers.get("ce-repo"), repo);
    assert.equal(headers.get("ce-branch"), "cloud-engine");
    assert.equal(headers.get("ce-buildurl"), event().buildUrl);
    assert.deepEqual(JSON.parse(String(init?.body)), cases);
    return new Response(null, { status: 202 });
  }) as typeof fetch;
  await postEvent(event(), cases, request);
  assert.equal(calls, 1);
});

Deno.test("overlapping poll windows only POST missing cases and in-run duplicates are suppressed", async () => {
  const db = {
    execute: () => Promise.resolve([[], []]),
  } as unknown as Connection;
  let calls = 0;
  const request = (async () => {
    calls++;
    return new Response(null, { status: 202 });
  }) as typeof fetch;
  assert.equal(await reportEvents(db, [event(), event()], request), 1);
  assert.equal(calls, 1);
  const populated = {
    execute: () =>
      Promise.resolve([[{
        repo,
        branch: "cloud-engine",
        build_url: event().buildUrl,
        suite_name: "suite",
        case_name: "recovers",
      }], []]),
  } as unknown as Connection;
  assert.equal(await reportEvents(populated, [event()], request), 0);
  assert.equal(calls, 1);
});

Deno.test("database failure prevents any POST; ambiguous POST failures are never retried", async () => {
  let calls = 0;
  const request = (async () => {
    calls++;
    return new Response(null, { status: 503 });
  }) as typeof fetch;
  const failed = {
    execute: () => Promise.reject(new Error("SQL contains private-case-name")),
  } as unknown as Connection;
  await assert.rejects(
    () => reportEvents(failed, [event()], request),
    { message: "Database deduplication query failed" },
  );
  assert.equal(calls, 0);
  const db = {
    execute: () => Promise.resolve([[], []]),
  } as unknown as Connection;
  await assert.rejects(
    () => reportEvents(db, [event(), event()], request),
    /HTTP 503/,
  );
  assert.equal(calls, 1);
});

Deno.test("GitHub idempotent reads retry transient HTTP failures only", async () => {
  let calls = 0;
  const github = new GitHub(
    "token",
    (async () =>
      ++calls < 3 ? json({}, 503) : json({ ok: true })) as typeof fetch,
  );
  assert.deepEqual(await github.json("/user"), { ok: true });
  assert.equal(calls, 3);
});

Deno.test("Actions pagination and 1000-result partitioning are not truncated", async () => {
  let pages: number[] = [];
  const request = (async (input: RequestInfo | URL) => {
    const url = new URL(String(input));
    if (url.pathname.startsWith("/repositories/")) {
      return json({ full_name: repo });
    }
    if (url.pathname.includes("/workflows/unit-test.yml/runs")) {
      const page = Number(url.searchParams.get("page"));
      pages.push(page);
      return json({
        total_count: 101,
        workflow_runs: Array.from(
          { length: page === 1 ? 100 : 1 },
          (_, i) => run((page - 1) * 100 + i, { conclusion: "failure" }),
        ),
      });
    }
    if (url.pathname.includes("/workflows/integrations.yml/runs")) {
      return json({ total_count: 0, workflow_runs: [] });
    }
    return json({ artifacts: [] });
  }) as typeof fetch;
  assert.deepEqual(
    await collectEvents(new GitHub("token", request), 492109999, now),
    { events: [], failedRuns: [] },
  );
  assert.deepEqual(pages, [1, 2]);
  pages = [];
  const split = (async (input: RequestInfo | URL) => {
    const url = new URL(String(input));
    if (url.pathname.startsWith("/repositories/")) {
      return json({ full_name: repo });
    }
    if (url.pathname.includes("/workflows/unit-test.yml/runs")) {
      pages.push(1);
      return json({
        total_count: pages.length === 1 ? 1000 : 0,
        workflow_runs: [],
      });
    }
    return json({ total_count: 0, workflow_runs: [] });
  }) as typeof fetch;
  assert.deepEqual(
    await collectEvents(new GitHub("token", split), 492109999, now),
    { events: [], failedRuns: [] },
  );
  assert.equal(pages.length, 3);
});

Deno.test("a single malformed run does not block healthy runs", async () => {
  const fixture = githubFixture({ runs: [run(1), run(2)] });
  const request = (async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).includes("/runs/1/artifacts")) {
      return json({
        artifacts: [
          { id: 8, name: "unit-test-junit-attempt-1", expired: false },
          { id: 9, name: "unit-test-junit-attempt-1", expired: false },
        ],
      });
    }
    return await fixture.request(input, init);
  }) as typeof fetch;
  const original = console.error;
  console.error = () => {};
  try {
    const result = await collectEvents(
      new GitHub("token", request),
      492109999,
      now,
    );
    assert.deepEqual(result.failedRuns, [1]);
    assert.equal(result.events.length, 1);
    assert.equal(result.events[0].eventId, "github-actions-2-1");
  } finally {
    console.error = original;
  }
});

Deno.test("reserved suite names remain safe own properties during deduplication", () => {
  const special = Object.fromEntries(
    ["__proto__", "constructor", "toString"].map((
      suite,
    ) => [suite, cases.suite]),
  );
  const missing = missingCases(event({ cases: special }), new Set());
  assert.deepEqual(Object.keys(missing), [
    "__proto__",
    "constructor",
    "toString",
  ]);
  assert.equal(Object.getPrototypeOf(missing), Object.prototype);
  assert.deepEqual(JSON.parse(JSON.stringify(missing)), special);
});

Deno.test("CLI validates existing CI config and skips DB access when no events exist", async () => {
  const keys = ["GITHUB_TOKEN", "PARAM_DSN", "REPOSITORY_ID"];
  const saved = keys.map((key) => Deno.env.get(key));
  const originalFetch = globalThis.fetch;
  const originalLog = console.log;
  const originalError = console.error;
  try {
    Deno.env.set("GITHUB_TOKEN", "test-token");
    Deno.env.set("PARAM_DSN", "intentionally-unused-invalid-dsn");
    Deno.env.set("REPOSITORY_ID", "0");
    await assert.rejects(() => main(), /required/);
    Deno.env.set("REPOSITORY_ID", "492109999");
    const empty = githubFixture({ runs: [] });
    globalThis.fetch = empty.request;
    console.log = () => {};
    await main();
    assert.equal(empty.calls.length, 3);
    assert.ok(
      empty.calls.every((call) =>
        call.url.startsWith("https://api.github.com/")
      ),
    );
    globalThis.fetch = githubFixture({
      runs: [run(1, { updated_at: new Date().toISOString() })],
      artifacts: [],
    }).request;
    console.error = () => {};
    await assert.rejects(() => main(), /Some workflow runs/);
    globalThis.fetch = githubFixture({
      runs: [run(1, { updated_at: new Date().toISOString() })],
    }).request;
    await assert.rejects(() => main(), { message: "Invalid database DSN" });
  } finally {
    globalThis.fetch = originalFetch;
    console.log = originalLog;
    console.error = originalError;
    keys.forEach((key, index) =>
      saved[index] === undefined
        ? Deno.env.delete(key)
        : Deno.env.set(key, saved[index]!)
    );
  }
});

Deno.test("partial DB records are filtered without suppressing new cases", async () => {
  const db = {
    execute: () =>
      Promise.resolve([[{
        repo,
        branch: "cloud-engine",
        build_url: event().buildUrl,
        suite_name: "suite",
        case_name: "recovers",
      }], []]),
  } as unknown as Connection;
  const partial = event({
    cases: {
      suite: {
        new_flaky: [cases.suite.new_flaky[0], {
          name: "another",
          reason: "flaky",
        }],
      },
    },
  });
  let payload: unknown;
  const request = (async (_input: RequestInfo | URL, init?: RequestInit) => {
    payload = JSON.parse(String(init?.body));
    return new Response(null, { status: 202 });
  }) as typeof fetch;
  assert.equal(await reportEvents(db, [partial], request), 1);
  assert.deepEqual(payload, {
    suite: { new_flaky: [{ name: "another", reason: "flaky" }] },
  });
});
