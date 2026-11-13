# GitHub Actions flaky case collection

`periodic-collect-case-runs-cse` runs hourly in the **existing GCP Prow
cluster** (`cluster: default`, inheriting namespace `prow-test-pods` from Prow
configuration). It reads CSE's existing Unit test / Integrations JUnit artifacts
and sends recovered nextest failures to **the same internal service used by TiDB
Jenkins pipelines**:

```
http://cloudevents-server.cs.svc/events
ce-type: test-case-run-report
```

The existing CloudEvents consumer owns INSERTs into `problem_case_runs`. This
collector sends the current `repo`, target `branch`, exact suite/case identity
and attempt-specific GitHub Actions build URL. The existing consumer reads
repo/branch/buildurl extensions directly and does not restrict the event source
to Jenkins. This tool never directly inserts into the database. Persistent
failures, normal passes, and skips are not counted as flaky. `flakyFailure` /
`flakyError` indicate recovery within nextest retries, including nextest's
flaky-fail-status output. Whole-workflow failure-then-success alone does not
count as nextest flakiness.

## No new CSE-side setup

No CSE workflow change, public HTTPS endpoint, authorization secret, repository
variable, or new database/table is needed. The periodic job reuses the existing
Prow `github-token/token` and `ci-job-params-mono/ci-job-flay-report-dsn` Secret
keys. The latter is used **only for SELECT queries** against existing rows to
avoid re-reporting cases in overlapping collection windows. The writer and its
database credentials remain owned by the existing internal consumer.

The stable GitHub repository ID (`492109999`) is resolved to its current full
name on each poll, so a rename does not require changing the job. Existing
historical database rows keep their old names; this collector does not rewrite
history. Target branches come from GitHub metadata, never the PR head branch or
artifact contents.

The CSE Actions runners cannot resolve the internal `.svc` name. Running the
puller in Prow, rather than posting from a GitHub-hosted/self-hosted CSE runner,
keeps collection on the already-working TiDB internal network. The job starts
through normal reviewed CI configuration deployment; there is no additional
opt-in variable, secret, or manual uncomment step.

## Receiver contract verification

The real shared consumer was tested through its CloudEvents HTTP binding,
composite type router and Ent writer against an isolated in-memory SQLite DB. A
GitHub source was accepted, and repo/branch/suite/case/flaky plus the exact
`/attempts/<n>` build URL were persisted. Replaying the same `ce-id` appended
rows, confirming that IDs are **not** an idempotency mechanism. No production
POST or database write was needed for this contract test.

The tested handler source matches the deployed GCP image
`v2026.10.25-56-gd0a6f3d` byte-for-byte. See
[`testcaserun/handler.go`](https://github.com/PingCAP-QE/ee-apps/blob/d0a6f3d/cloudevents-server/pkg/events/custom/testcaserun/handler.go):
`Handle` reads the three extensions directly and `addRecords` calls
`SetBuildURL(buildURL)` without rewriting it; `ce-source` is not consulted. The
deployed service's health, existing CI token's CSE artifact permissions, and
existing DB credential's SELECT access were also verified read-only.

HTTP receipt is still asynchronous in production. Monitor consumer failures and
row arrival during normal rollout; this is not a claim that a production event
has already been inserted. Do not publish DSNs, signed URLs, JUnit contents or
individual case identities with operational verification results.

## Collection and replay semantics

Each poll scans the last 90 days of workflow creation history and processes
completed runs updated in the preceding 24 hours. This overlap tolerates missed
hourly polls and retries transient failures. All available JUnit attempts of a
selected run are inspected, so an earlier nextest-recovered failure is not lost
when a later workflow rerun is clean. The Actions API's 1000-result cap is
handled by splitting time ranges, and list endpoints are paginated. PR runs use
one unambiguous base branch; push, schedule and workflow-dispatch runs use
`head_branch`. Unsupported events (including merge groups) and PRs with
missing/split base metadata are skipped with a run-ID warning, not treated as
retryable artifact failures.

A successful latest attempt missing its expected artifact is an error. Missing
reports for build-failure/cancelled attempts can be normal. Invalid/ambiguous
artifacts fail that run without blocking healthy runs; the periodic job reports
failure after processing the healthy data so operators can investigate.
Artifacts are parsed in memory, never extracted or executed. Compressed and
uncompressed size limits and XML validation reject unsafe input.

Existing rows are matched by repo, branch, build URL, suite and case; only
missing cases are submitted. The job has `max_concurrency: 1`, and it never
immediately retries an ambiguous POST. HTTP success acknowledges receipt, not
completed database ingestion. Because the shared consumer is asynchronous and
append-only, this is **not exactly-once delivery**: a later poll can redeliver
while an accepted event has not reached the table, for example during a long
consumer outage. This change does not alter the consumer or database schema. An
outage longer than the 24-hour overlap, expired artifacts, or a rerun whose
original run was created over 90 days ago requires an operator backfill.

The hourly overlap re-downloads/re-parses each selected artifact roughly 24
times per day, and repeats the 90-day pagination. Each poll uses one repository
lookup plus run-list pages (including split probes), artifact-list pages,
older-attempt lookups and artifact downloads as GitHub API calls. Signed storage
GETs add one request per download. For example, 20 list calls and 100
single-attempt runs with one 1 MiB artifact each cost about 221 API calls and
100 MiB egress per hour (about 2.3 GiB/day). These are illustrative, not
measured CSE rates. Monitor the read-only collection phase against the
900-second deadline and the shared token's 5000-requests/hour budget, leaving
room for retries/other jobs. Dedup reads intentionally retain old rows: a recent
rerun can replay an attempt from up to 90 days ago, so a few-days `report_time`
bound could duplicate it; confirm the production query plan/index before
increasing collection frequency or scale.

No source JUnit or individual case names are printed/uploaded to public Prow
artifacts. Logs contain counts, skipped/failed run IDs and diagnostic errors.
GitHub API credentials are not forwarded to signed artifact-storage URLs or the
internal collector. There are no issue creation, reopening, comment, or email
operations.

## Verification

```bash
deno test --allow-env --coverage=/tmp/github-case-runs-coverage tools/collectors/github-case-runs/
deno coverage /tmp/github-case-runs-coverage
deno check tools/collectors/github-case-runs/main.ts
deno lint tools/collectors/github-case-runs/
deno fmt --check tools/collectors/github-case-runs/
```

Tests use synthetic artifacts, mocked GitHub responses/database reads and a
mocked internal HTTP endpoint. They do not post events or modify a live DB.
