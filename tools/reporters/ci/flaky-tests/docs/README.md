# Flaky Reporter (Deno)

A small, standalone Deno tool to analyze flaky tests stored in `problem_case_runs` and generate a single-file HTML report (and optional JSON), grouped by team, package (suite), and case.

This README covers:
- What this tool does
- How to run it
- Configuration (database, owner mapping, thresholds)
- Output formats (HTML, JSON, email)
- Proposed schema for the Team Owner mapping table
- Query semantics and grouping rules
- Directory layout and development notes

---

## What it does

Given a date/time range, the reporter:
- Aggregates flaky test runs from the `problem_case_runs` table.
- Produces three sections in the report:
  - Statistics by team
    - Columns: team owner, repo, branch, flaky case count, runtime-thresholded case count
    - Grouping: team owner + repo + branch
  - Statistics by package (suite)
    - Columns: team owner, repo, branch, suite_name, flaky case count, runtime-thresholded case count
    - Grouping: repo + branch + suite_name
  - Statistics by case
    - Columns: repo, branch, suite_name, case_name, flaky happened count, time-thresholded count, team owner
    - Grouping: repo + branch + suite_name + case_name
    - Highlight the top 10 cases with highest flaky happened count
- Resolves “team owner” via a configurable ownership mapping (DB table or YAML file) with the following precedence:
  1) Full case path (repo + branch + suite_name + case_name)
  2) Suite level (repo + branch + suite_name)
  3) Repo level (repo + branch)
  4) Repo level (repo only)
- Generates a single HTML file. Optionally outputs JSON. Optionally sends the HTML by email.

---

## Prerequisites

- Deno 1.45+ (https://deno.land)
- Network access to your MySQL instance that hosts `problem_case_runs`
- Optional: SMTP server access if you plan to email the report

---

## Run it

From the repository root (or any directory), run:

    deno run -A insight/reporters/ci/flaky-tests/src/main.ts [options]

Required permissions:
- --allow-net: database + SMTP
- --allow-read: owner map file (YAML/JSON), templates
- --allow-write: output report files
- --allow-env: DB/SMTP credentials, defaults

If you prefer being explicit with Deno permissions:

    deno run --allow-net --allow-read --allow-write --allow-env insight/reporters/ci/flaky-tests/src/main.ts [options]

---

## CLI options

- --from
  - Inclusive start of date/time range (ISO-8601 or “YYYY-MM-DD”).
  - Example: --from 2025-03-01T00:00:00Z
- --to
  - Exclusive end of date/time range (ISO-8601 or “YYYY-MM-DD”).
  - Example: --to 2025-03-08T00:00:00Z
- --range
  - Relative range shorthand if --from/--to are omitted. Supported forms:
    - 7d, 30d (days); 12h (hours); 90m (minutes).
  - Example: --range 7d (means [now-7d, now))
- --threshold-ms
  - Runtime threshold in milliseconds to classify a run as “time-thresholded”.
  - Default: 600000 (10 minutes)
- --db-host, --db-port, --db-user, --db-pass, --db-name
  - MySQL connection params. May be overridden by environment variables (below).
- --db-url
  - Alternative single URL form: mysql://user:pass@host:port/dbname
- --owner-table
  - Name of the DB table to resolve ownership (see schema below).
  - Default: flaky_owners
- --owner-map
  - Path to a YAML or JSON file for ownership definitions (when you don’t or can’t use the DB table).
- --html
  - Path to write the HTML report (single file). Default: flaky-report.html
- --json
  - Path to also write a JSON report payload for automation (optional).
- --email-to
  - One or more comma-separated emails to send the report to (optional).
- --email-from
  - Sender email address (optional; required if emailing).
- --email-subject
  - Email subject. Default: Flaky Report
- --dry-run
  - Prints high-level summary to stdout and exits without writing files or sending emails.

Notes:
- If both --owner-table and --owner-map are provided, the DB table is checked first; if no match, the file is used as a fallback.
- If neither is provided, the “owner” will be “UNOWNED” in the report.

---

## Environment variables

Database:
- DB_HOST
- DB_PORT
- DB_USER
- DB_PASSWORD
- DB_NAME
- DB_URL (alternative single-URL form)

SMTP (if emailing):
- SMTP_HOST
- SMTP_PORT
- SMTP_USER
- SMTP_PASS
- SMTP_SECURE
  - “true” or “false” (default true)
- SMTP_STARTTLS
  - “true” or “false” (default true)

Defaults:
- threshold-ms: 600000
- html: flaky-report.html
- email-subject: Flaky Report

---

## Examples

1) Last 7 days, write HTML + JSON:

    deno run -A insight/reporters/ci/flaky-tests/src/main.ts --range 7d --html out/flaky.html --json out/flaky.json

2) Explicit date range, custom threshold, email the report:

    deno run -A insight/reporters/ci/flaky-tests/src/main.ts \
      --from 2025-03-01 --to 2025-03-08 \
      --threshold-ms 300000 \
      --html /tmp/flaky.html \
      --email-to qa@example.com,eng-leads@example.com \
      --email-from ci-bot@example.com \
      --email-subject "Weekly Flaky Report (Mar 01–07)"

3) Use an owner mapping YAML file:

    deno run -A insight/reporters/ci/flaky-tests/src/main.ts --range 30d --owner-map insight/reporters/ci/flaky-tests/owner-map.example.yaml

---

## Data source: problem_case_runs

This table already exists and is populated by crawlers/CI.

Columns used:
- repo (varchar)
- branch (varchar)
- suite_name (varchar)
- case_name (varchar)
- flaky (tinyint(1), 1 indicates flaky event happened for that run)
- timecost_ms (bigint)
- report_time (timestamp)
- build_url (varchar)
- reason (varchar)

The reporter counts “flaky happened” per case as SUM(flaky > 0) and “time thresholded” per case as COUNT(timecost_ms >= threshold_ms). It only considers rows with report_time in [from, to).

---

## Team owner mapping

We need to associate each test case/suite/repo with a “team owner”. Resolution precedence:

1) Most specific: repo + branch + suite_name + case_name
2) repo + branch + suite_name
3) repo + branch
4) repo
5) Fallback owner: “UNOWNED”

You can define this in either:
- A database table, recommended for central management (see schema below)
- A YAML/JSON file (good for quick local usage)

### Proposed DB table schema (MySQL)

We use ‘*’ as a wildcard stored inside columns to make indexing straightforward.

- Table name: flaky_owners (configurable via --owner-table)

Suggested DDL:

    CREATE TABLE flaky_owners (
      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
      repo        VARCHAR(255) NOT NULL,
      branch      VARCHAR(255) NOT NULL DEFAULT '*',
      suite_name  VARCHAR(255) NOT NULL DEFAULT '*',
      case_name   VARCHAR(255) NOT NULL DEFAULT '*',
      owner_team  VARCHAR(255) NOT NULL,
      priority    INT NOT NULL DEFAULT 0,
      note        TEXT NULL,
      created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY uq_match (repo, branch, suite_name, case_name),
      KEY idx_repo_branch (repo, branch),
      KEY idx_repo_suite (repo, suite_name),
      KEY idx_owner (owner_team)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

Conventions:
- repo is always explicit (no wildcard).
- Use '*' in branch/suite_name/case_name to indicate “any”.
- Higher “priority” wins when multiple rows match with the same specificity (default 0). You can use this to handle exceptions/overrides.

Example rows:

    -- Repo-wide default owner
    ('pingcap/tidb', '*', '*', '*', 'SQL-Engine', 0)

    -- Branch-specific override
    ('pingcap/tidb', 'release-8.5', '*', '*', 'Release-Owners', 10)

    -- Suite-level ownership
    ('pingcap/tiflow', '*', 'integrated_etl', '*', 'DM-Platform', 0)

    -- Specific case owner
    ('pingcap/tidb', '*', 'executor', 'TestExplainAnalyze', 'SQL-Perf', 50)

Resolution query idea (pseudocode):

- Prepare the 4 specificity patterns ordered by specificity and priority:
  - (repo, branch, suite, case)
  - (repo, branch, suite, '*')
  - (repo, branch, '*', '*')
  - (repo, '*', '*', '*')
- For each pattern, run:
  SELECT owner_team
  FROM flaky_owners
  WHERE repo = ?
    AND branch = ?
    AND suite_name = ?
    AND case_name = ?
  ORDER BY priority DESC
  LIMIT 1;

Stop at first match. If none, owner = 'UNOWNED'.

### Owner map YAML/JSON

When using a file, define entries with the same matching keys and wildcard semantics using '*'.

YAML example:

    - repo: pingcap/tidb
      branch: "*"
      suite_name: "*"
      case_name: "*"
      owner_team: SQL-Engine
      priority: 0

    - repo: pingcap/tidb
      branch: release-8.5
      suite_name: "*"
      case_name: "*"
      owner_team: Release-Owners
      priority: 10

    - repo: pingcap/tiflow
      branch: "*"
      suite_name: integrated_etl
      case_name: "*"
      owner_team: DM-Platform

    - repo: pingcap/tidb
      branch: "*"
      suite_name: executor
      case_name: TestExplainAnalyze
      owner_team: SQL-Perf
      priority: 50

---

## Output

HTML (default: flaky-report.html)
- Single file, embedded styles, ready to share by email or upload to artifacts.
- Sections:
  - Overview: time window, total flaky cases, total thresholded runs
  - By Team: table grouped by owner_team + repo + branch
  - By Package (Suite): table grouped by repo + branch + suite_name (with owner)
  - By Case: table grouped by repo + branch + suite_name + case_name (with owner)
  - Top 10 Flakiest Cases: a list/table sorted by flaky count desc
- Each row includes links to the “latest build_url” if available (best-effort based on the most recent run in the window).

JSON (optional, via --json)
- Contains the same aggregates as the HTML for programmatic consumption:
  - window: { from, to, thresholdMs }
  - summary: { flakyCases, thresholdedCases, repos, suites, cases }
  - byTeam: [...]
  - bySuite: [...]
  - byCase: [...]
  - topFlakyCases: [...]

Email (optional)
- Sends the HTML inline (and as an attachment if needed).
- SMTP configuration via environment variables (see above).
- If sending fails, the process exits non-zero.

---

## Grouping and counting rules

Within the selected time window [from, to):
- Flaky happened count per case:
  - COUNT of rows where flaky = 1 (or tinyint > 0).
- Time-thresholded count per case:
  - COUNT of rows where timecost_ms >= threshold-ms.
- By team:
  - First resolve owner for each case per its most-specific match.
  - Aggregate by (owner_team, repo, branch).
  - Sum flaky_count and thresholded_count across cases.
- By package:
  - Aggregate by (repo, branch, suite_name).
  - Owner is resolved at case-level and attributed to the suite by majority or most-specific case’s owner:
    - This tool uses “most specific present” semantics:
      - If any case in the suite has a case-level owner mapping, that owner is used for the suite.
      - Else if a suite-level mapping exists, use it.
      - Else fall back to repo/branch-level, then repo-level.
    - Rationale: gives visibility to the highest-signal mapping present.
- By case:
  - Aggregate by (repo, branch, suite_name, case_name) with resolved owner.

---

## Directory layout (scaffold)

The code is organized like this:

- src/main.ts
  - CLI parsing, env loading, orchestration
- src/config.ts
  - Reads CLI + env; produces a normalized Config
- src/db.ts
  - MySQL connection helpers; parameterized queries
- src/owners.ts
  - Owner resolution from DB table and/or YAML/JSON file, with caching
- src/queries.ts
  - Data access for problem_case_runs and aggregation helpers
- src/report.ts
  - Aggregation and shaping for byTeam, bySuite, byCase, and top N
- src/render/html.ts
  - HTML template generation (single-file output)
- src/email.ts
  - SMTP client wrapper (inline HTML send)
- owner-map.example.yaml
  - Example ownership mapping

You can replace, extend, or reorganize as needed.

---

## Development notes

- Deno runtime:
  - Use native fetch for SMTP via third-party library or bring-your-own minimal SMTP client (plaintext or STARTTLS).
  - Use a MySQL client that supports Deno (e.g., x/mysql).
- Safety:
  - Queries use parameter binding.
  - Date parsing accepts ISO-8601; for “YYYY-MM-DD”, interpret as UTC midnight.
- Performance:
  - Time-window filtering is pushed down to SQL.
  - Owner resolution is memoized; it’s inexpensive compared to aggregation.
- Logging:
  - Use simple console logging flags (e.g., --verbose) if needed.

---

## Exit codes

- 0: Success
- 2: Invalid arguments (e.g., bad date/time)
- 3: Database connection/query failure
- 4: Email send failure
- 5: Output write failure

---

## FAQ

Q: What if there are no rows in the time window?
- The report still renders with zeros, and a note that no data was found.

Q: How are ties handled in the “Top 10 Flakiest Cases”?
- Stable ordering by flaky count desc, then time-thresholded desc, then alphabetical case key.

Q: Can we extend ownership beyond teams?
- Yes. The owner table can include a “note” or you can add columns like “service” or “sla_tier”. The tool currently surfaces “owner_team” only, but you can extend renderers as needed.

---

## Roadmap ideas

- Add charts (sparklines, bar charts) inline in HTML using inline SVG.
- Add delta vs. previous period (WoW/DoD).
- Add optional filters (repo list, branch regex, suite glob).
- Add export to CSV.

---

## License

MIT (or follow the repository’s prevailing license policy)
