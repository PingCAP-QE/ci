# Flaky Reporter (Deno)

A small Deno tool that analyzes flaky tests stored in `problem_case_runs` and generates:
- A single-file HTML report
- Optional JSON for automation

It groups results by team (owner), package (suite), and case, and highlights the top 10 flakiest cases.

This README reflects the current implementation and its actual semantics.

---

## What it does

Given a date/time range, the reporter:

- Queries flaky test runs from the `problem_case_runs` table.
- Aggregates into:
  - Top 10 flakiest cases
  - By Case
    - Columns: owner, repo, branch, package (suite), case, flaky count, time-thresholded count, latest build, quick links (issue search/new)
  - By Team
    - Columns: owner, repo, branch, count of distinct flaky cases, count of distinct time-thresholded cases
  - By Suite
    - Columns: owner, repo, branch, package (suite), count of distinct flaky cases, count of distinct time-thresholded cases
- Resolves “team owner” using an owner mapping provided either from:
  - A YAML/JSON file (if provided), or
  - A DB table (all rows are loaded once), when no file is provided.
- Outputs a single HTML file. Optionally writes a JSON file. Optionally emails the HTML.

Notes about current owner-matching behavior:
- Branch is not considered for matching (even if present in data). See “Team owner mapping” for details.
- Suite normalization is applied before matching:
  - Leading `//` is stripped
  - A trailing `:suffix` is removed
  - Parent suite prefix matches are allowed (see details below)

---

## Prerequisites

- Deno 1.45+ (https://deno.land)
- Network access to your MySQL instance that hosts `problem_case_runs`
- Optional: SMTP server access if you plan to email the report

---

## Run it

From this directory:

- Most explicit:
  deno run --allow-net --allow-read --allow-write --allow-env main.ts [options]

- Or via the provided task:
  deno task run [options]

Required permissions:
- --allow-net: database + SMTP
- --allow-read: owner map file, templates
- --allow-write: output report files
- --allow-env: DB/SMTP credentials, defaults

---

## CLI options

- --from
  - Inclusive start of date/time range (ISO-8601 or “YYYY-MM-DD”).
  - Example: --from 2025-03-01T00:00:00Z
- --to
  - Exclusive end of date/time range (ISO-8601 or “YYYY-MM-DD”).
  - Example: --to 2025-03-08T00:00:00Z
- --range
  - Relative range shorthand if --from/--to are omitted. Supported: 7d, 12h, 90m.
  - Default when both --from and --to are omitted: 7d
- --threshold-ms
  - Runtime threshold in milliseconds to count a run as “time-thresholded”.
  - Default: 600000 (10 minutes)

- --repo
  - Filter by repository (e.g., pingcap/tidb). Affects DB query only.
- --branch
  - Filter by branch (e.g., master). Affects DB query only.

- --db-url
  - Alternative single URL form: mysql://user:pass@host:port/dbname
- --db-host, --db-port, --db-user, --db-pass, --db-name
  - MySQL connection params (used when --db-url is not provided)

- --owner-table
  - Name of the DB table to load ownership rules from (when no owner-map file is provided).
  - Default: flaky_owners
- --owner-map
  - Path to a YAML or JSON file for ownership definitions.
  - If provided, it takes precedence and the DB table will not be consulted.

- --html
  - Path to write the HTML report (single file). Default: flaky-report.html
- --json
  - Optional path to write a JSON payload with the same aggregates.

- --email-to
  - Comma-separated list of recipients to email the report to. Requires --email-from.
- --email-from
  - Sender email address (required if emailing).
- --email-subject
  - Email subject. Default: Flaky Report

- --dry-run
  - Prints a summary and top cases to stdout; does not write files or send emails.
- --verbose
  - Verbose logging to stderr.
- --help
  - Show usage.

Notes:
- If both an owner map file and owner table are provided, the file is used exclusively.
- --repo and --branch only filter the DB query; they do not affect owner resolution.

---

## Environment variables

Database:
- DB_URL (alternative single-URL form)
- DB_HOST
- DB_PORT
- DB_USER
- DB_PASSWORD
- DB_NAME

Filters:
- REPO
- BRANCH

SMTP (if emailing):
- SMTP_HOST
- SMTP_PORT
- SMTP_USER
- SMTP_PASS
- SMTP_SECURE
  - “true” or “false” (default true). When true, a TLS connection is used.

Defaults:
- THRESHOLD_MS (default 600000)

---

## Examples

1) Last 7 days (default), write HTML + JSON:
- deno run -A main.ts --html out/flaky.html --json out/flaky.json

2) Explicit date range, custom threshold, filter repo/branch:
- deno run -A main.ts \
    --from 2025-03-01 --to 2025-03-08 \
    --repo pingcap/tidb --branch master \
    --threshold-ms 300000 \
    --html /tmp/flaky.html

3) Use an owner mapping YAML file:
- deno run -A main.ts --range 30d --owner-map docs/owner-map.example.yaml

4) Email the report:
- deno run -A main.ts --range 7d \
    --html /tmp/flaky.html \
    --email-to qa@example.com,eng-leads@example.com \
    --email-from ci-bot@example.com \
    --email-subject "Weekly Flaky Report"

---

## Data source: `problem_case_runs`

Columns used:
- repo (varchar)
- branch (varchar)
- suite_name (varchar)
- case_name (varchar)
- flaky (tinyint(1), >0 indicates flaky event happened for that run)
- timecost_ms (bigint)
- report_time (timestamp)
- build_url (varchar)
- reason (varchar)

Counting rules within the selected time window [from, to):
- Per case:
  - flakyCount = COUNT(rows with flaky > 0)
  - thresholdedCount = COUNT(rows with timecost_ms >= threshold-ms)

- Per team (owner + repo + branch):
  - Count distinct cases with flakyCount > 0 as “Flaky Cases”
  - Count distinct cases with thresholdedCount > 0 as “Time Thresholded Cases”

- Per suite (repo + branch + suite_name):
  - Count distinct cases with flakyCount > 0 as “Flaky Cases”
  - Count distinct cases with thresholdedCount > 0 as “Time Thresholded Cases”

---

## Team owner mapping

Owner resolution determines which “team owner” is attributed to each case/suite.

Current implementation semantics:
- Branch is ignored for matching.
- Matching precedence (most specific to least):
  1) repo + suite_name + case_name
  2) repo + suite_name + "*"
  3) repo + "*" + "*"
- Wildcard for suite/case is exactly "*"
- Suite normalization is applied before matching:
  - Leading `//` is removed
  - A trailing `:suffix` is removed
  - Parent suite prefix matches are allowed. For example, a rule with `suite_name: "pkg"`
    matches a case whose suite is `"pkg/FooTest"`.
- Fallback owner is `UNOWNED` if no rule matches.
- If an owner map file (`--owner-map`) is provided, it is used exclusively.
- If no file is provided and `--owner-table` is set, the entire table is loaded once
  and matched in-memory with the same semantics as the file.

YAML/JSON file format (entries array):
- repo: string (required; no wildcard)
- branch: string (allowed but currently ignored; default "*")
- suite_name: string ("*" allowed)
- case_name: string ("*" allowed)
- owner_team: string (required)
- priority: integer (default 0; higher wins among ties at the same specificity)
- note: string (optional)

YAML example:
- repo: pingcap/tidb
  branch: "*"         # ignored by matching
  suite_name: executor
  case_name: TestExplainAnalyze
  owner_team: SQL-Perf
  priority: 50

- repo: pingcap/tidb
  branch: "*"         # ignored by matching
  suite_name: executor
  case_name: "*"
  owner_team: SQL-Engine
  priority: 1

- repo: pingcap/tidb
  branch: "*"         # ignored by matching
  suite_name: "*"
  case_name: "*"
  owner_team: SQL-Engine
  priority: 0

### DB table schema (suggested)

You can store the same fields in a DB table and point `--owner-table` to it. The current
implementation loads the whole table once and matches it with the same file semantics.
The `branch` column is stored but not used for matching.

Suggested DDL (MySQL):
- CREATE TABLE flaky_owners (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo        VARCHAR(255) NOT NULL,
    branch      VARCHAR(255) NOT NULL DEFAULT '*',    -- currently ignored
    suite_name  VARCHAR(255) NOT NULL DEFAULT '*',
    case_name   VARCHAR(255) NOT NULL DEFAULT '*',
    owner_team  VARCHAR(255) NOT NULL,
    priority    INT NOT NULL DEFAULT 0,
    note        TEXT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_match (repo, suite_name, case_name),
    KEY idx_repo_suite (repo, suite_name),
    KEY idx_owner (owner_team)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

---

## Output

HTML (default: flaky-report.html)
- Single page, embedded styles.
- Sections:
  - Header KPIs:
    - Window, Repos, Suites, Cases, Flaky Cases, Threshold (ms), Time Thresholded Cases
  - Top 10 Flakiest Cases
    - Ranks the top 10 by flakyCount desc, then thresholdedCount desc, then key alpha.
  - By Case
    - Columns: Team Owner, Repo, Branch, Package, Case, Flaky Count, Time Thresholded Count, Latest Build, Issue Search, Create Issue
  - By Team
    - Columns: Team Owner, Repo, Branch, Flaky Cases, Time Thresholded Cases
  - By Suite
    - Columns: Team Owner, Repo, Branch, Package, Flaky Cases, Time Thresholded Cases
- Links:
  - “Latest Build” links to the most recent build_url in the window (best-effort).
  - “Issue Search” links to a GitHub search using the case name.
  - “Create Issue” opens a prefilled GitHub new issue link.

JSON (via --json)
- Keys:
  - window: { from, to, thresholdMs }
  - summary: { repos, suites, cases, flakyCases, thresholdedCases }
  - byTeam: [...]
  - bySuite: [...]
  - byCase: [...]
  - topFlakyCases: [...]

Email (via --email-to/--email-from)
- Sends an HTML email (inline).
- SMTP uses TLS when SMTP_SECURE=true (default). No STARTTLS toggle in this version.
- If sending fails, the process exits with code 4.

---

## Grouping and counting rules (recap)

- By Case aggregates per (repo, branch, suite_name, case_name).
- By Team aggregates per (owner, repo, branch), counting distinct cases with >0 counts.
- By Suite aggregates per (repo, branch, suite_name), counting distinct cases with >0 counts.
- Top 10 is based on flakyCount, then thresholdedCount, then alpha order.

Suite owner resolution used in the “By Suite” table:
- The suite owner is determined by calling owner resolution on the normalized suite with `case_name="*"`.
- It does not attempt to “mix” or reconcile conflicting case-level owners.

---

## Directory layout

- main.ts
  - Orchestrator: CLI/env parsing, DB fetch, aggregation, rendering, email.
- core/
  - ConfigLoader.ts
    - Parses CLI/env; resolves time window, DB/SMTP config; loads owner map file.
  - Database.ts
    - MySQL access for `problem_case_runs` and loading owner table rows.
  - OwnerResolver.ts
    - Owner resolution using file or a loaded owner table (branch ignored).
  - FlakyReporter.ts
    - Aggregation logic for byCase, bySuite, byTeam, and top 10.
  - types.ts
    - Shared types and constants.
  - OwnerResolver.test.ts
    - Tests for map-based owner resolution semantics.
- render/
  - HtmlRenderer.ts
    - Generates a single-file HTML report (email-friendly mode supported).
- utils/
  - EmailClient.ts
    - Simple SMTP client wrapper (HTML/text).
  - db.ts
    - DB DSN parsing helper.
- docs/
  - owner-map.example.yaml
    - Example owner mapping file.
- deno.json
  - Tasks and import maps.

---

## Exit codes

- 0: Success
- 2: Invalid arguments (e.g., bad date/time, missing DB config)
- 3: Database connection/query failure
- 4: Email send failure
- 5: Output write failure
- 1: Unexpected error

---

## FAQ

Q: What if there are no rows in the time window?
- The report still renders. Counts will be zero, and the tables will be empty.

Q: Are branch-specific owner rules supported?
- Not in this version. The owner matching ignores branch.

Q: How are ties handled in the Top 10?
- Ties are resolved by thresholdedCount desc, then by an alphabetical key.

Q: Can I extend the owner model?
- Yes. The mapping records include an optional “note” and a “priority”. You can extend the renderer or storage as needed.
