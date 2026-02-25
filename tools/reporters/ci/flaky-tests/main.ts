/**
 * Flaky Reporter (Deno) - Thin orchestrator
 *
 * This file intentionally contains minimal logic. All heavy lifting (CLI parsing,
 * DB access, owner resolution, aggregation, rendering, and email) is implemented
 * in modular, OOP-style classes under ./core, ./render, and ./utils.
 *
 * Expected class responsibilities (for reference):
 * - core/App.ts                -> FlakyReporterApp: end-to-end orchestration
 * - core/ConfigLoader.ts       -> ConfigLoader: parse CLI/env and produce a Config object
 * - core/Database.ts           -> Database: MySQL connection and low-level queries
 * - core/OwnerResolver.ts      -> OwnerResolver: resolve team owner via DB table and/or file
 * - core/FlakyReporter.ts      -> FlakyReporter: fetching runs and building aggregates
 * - render/HtmlRenderer.ts     -> HtmlRenderer: single-file HTML generation
 * - utils/EmailClient.ts       -> EmailClient: SMTP sending
 * - utils/logger.ts            -> Logger: simple structured/verbose logging
 *
 * Usage:
 *   deno run --allow-net --allow-read --allow-write --allow-env \
 *     insight/reporters/ci/flaky-tests/src/main.ts [options]
 */

import { ConfigLoader } from "./core/ConfigLoader.ts";
import { Database } from "./core/Database.ts";
import { OwnerResolver } from "./core/OwnerResolver.ts";
import { FlakyReporter } from "./core/FlakyReporter.ts";
import { HtmlRenderer } from "./render/HtmlRenderer.ts";
import type {
  CaseAgg,
  ProblemCaseRunRow,
  SmtpConfig,
  SuiteAgg,
  TeamAgg,
} from "./core/types.ts";
import { EmailClient } from "./utils/EmailClient.ts";
import * as path from "jsr:@std/path";

function ensureDirForFile(filePath: string) {
  const dir = path.dirname(filePath);
  if (dir && dir !== "." && dir !== "") {
    return Deno.mkdir(dir, { recursive: true }).catch(() => {});
  }
}

/**
 * Entry function for programmatic usage. Returns a POSIX-style exit code:
 *   0 = success
 *   2 = invalid arguments
 *   3 = database errors
 *   4 = email errors
 *   5 = output write errors
 *   1 = unexpected error
 */
export async function main(args: string[]): Promise<number> {
  const loader = new ConfigLoader();
  const { showHelp, config: cli } = loader.parse(args);
  if (showHelp) {
    console.log(loader.helpText());
    return 0;
  }

  let window;
  try {
    window = loader.determineTimeWindow(cli);
  } catch (e: unknown) {
    console.error(e instanceof Error ? e.message : String(e));
    return 2;
  }

  let dbCfg;
  try {
    dbCfg = loader.determineDbConfig(cli);
  } catch (e: unknown) {
    console.error(e instanceof Error ? e.message : String(e));
    return 2;
  }

  const ownerMap = cli.ownerMapPath
    ? await loader.loadOwnerMapFromFile(cli.ownerMapPath)
    : null;

  const db = new Database(dbCfg, {
    verbose: cli.verbose,
    repo: cli.repo,
    branch: cli.branch,
  });
  if (cli.verbose) {
    console.debug(
      `[cfg] filters: repo=${cli.repo ?? "(any)"} branch=${
        cli.branch ?? "(any)"
      }`,
    );
  }
  try {
    await db.connect();
  } catch (e: unknown) {
    console.error(
      `Failed to connect DB: ${e instanceof Error ? e.message : String(e)}`,
    );
    return 3;
  }

  let runs: ProblemCaseRunRow[] = [];
  let previousWeekRuns: ProblemCaseRunRow[] = [];
  try {
    runs = await db.fetchRuns(window);
  } catch (e: unknown) {
    await db.close();
    console.error(
      `Failed to query runs: ${e instanceof Error ? e.message : String(e)}`,
    );
    return 3;
  }

  try {
    const previousWeekTimeWindow = {
      from: new Date(window.from.getTime() - 7 * 24 * 60 * 60 * 1000),
      to: window.from,
    };
    previousWeekRuns = await db.fetchRuns(previousWeekTimeWindow);
  } catch (e: unknown) {
    await db.close();
    console.error(
      `Failed to query previous week runs: ${
        e instanceof Error ? e.message : String(e)
      }`,
    );
    return 3;
  }

  const ownerResolver = new OwnerResolver(
    db,
    cli.ownerTable,
    ownerMap,
    { verbose: cli.verbose },
  );
  const reporter = new FlakyReporter(ownerResolver, { verbose: cli.verbose });

  let byCase: CaseAgg[],
    bySuite: SuiteAgg[],
    byTeam: TeamAgg[],
    topFlakyCases: CaseAgg[];
  try {
    ({ byCase, bySuite, byTeam, topFlakyCases } = await reporter
      .buildAggregates(runs, cli.thresholdMs, previousWeekRuns));
  } catch (e: unknown) {
    await db.close();
    console.error(
      `Failed to build aggregates: ${
        e instanceof Error ? e.message : String(e)
      }`,
    );
    return 1;
  }

  const repos = new Set(byCase.map((c: CaseAgg) => c.repo)).size;
  const suites = new Set(
    byCase.map((c: CaseAgg) => `${c.repo}@@${c.branch}@@${c.suite_name}`),
  )
    .size;
  const cases = byCase.length;
  const flakyCases = byCase.filter((c: CaseAgg) => c.flakyCount > 0).length;
  const thresholdedCases =
    byCase.filter((c: CaseAgg) => c.thresholdedCount > 0).length;

  const report = {
    window: {
      from: window.from.toISOString(),
      to: window.to.toISOString(),
      thresholdMs: cli.thresholdMs,
    },
    summary: { repos, suites, cases, flakyCases, thresholdedCases },
    byTeam: byTeam.sort((a: TeamAgg, b: TeamAgg) => {
      if (b.flakyCases !== a.flakyCases) return b.flakyCases - a.flakyCases;
      if (b.thresholdedCases !== a.thresholdedCases) {
        return b.thresholdedCases - a.thresholdedCases;
      }
      const ak = `${a.owner}/${a.repo}/${a.branch}`;
      const bk = `${b.owner}/${b.repo}/${b.branch}`;
      return ak.localeCompare(bk);
    }),
    bySuite: bySuite.sort((a: SuiteAgg, b: SuiteAgg) => {
      if (b.flakyCases !== a.flakyCases) return b.flakyCases - a.flakyCases;
      if (b.thresholdedCases !== a.thresholdedCases) {
        return b.thresholdedCases - a.thresholdedCases;
      }
      const ak = `${a.repo}/${a.branch}/${a.suite_name}`;
      const bk = `${b.repo}/${b.branch}/${b.suite_name}`;
      return ak.localeCompare(bk);
    }),
    byCase: byCase.sort((a: CaseAgg, b: CaseAgg) => {
      if (b.flakyCount !== a.flakyCount) return b.flakyCount - a.flakyCount;
      if (b.thresholdedCount !== a.thresholdedCount) {
        return b.thresholdedCount - a.thresholdedCount;
      }
      const ak = `${a.repo}/${a.branch}/${a.suite_name}/${a.case_name}`;
      const bk = `${b.repo}/${b.branch}/${b.suite_name}/${b.case_name}`;
      return ak.localeCompare(bk);
    }),
    topFlakyCases,
  };

  if (cli.dryRun) {
    console.log("DRY RUN SUMMARY");
    console.log(JSON.stringify(report.summary, null, 2));
    console.log("Top 5 flakiest cases:");
    for (const [i, c] of report.topFlakyCases.slice(0, 5).entries()) {
      console.log(
        `${
          i + 1
        }. ${c.owner} :: ${c.repo}/${c.branch}/${c.suite_name}/${c.case_name} flaky=${c.flakyCount} thr=${c.thresholdedCount}`,
      );
    }
    await db.close();
    return 0;
  }

  const reportFileHtml = new HtmlRenderer().render(report);
  try {
    await ensureDirForFile(cli.htmlPath);
    await Deno.writeTextFile(cli.htmlPath, reportFileHtml);
    if (cli.verbose) console.debug(`HTML written: ${cli.htmlPath}`);
  } catch (e: unknown) {
    await db.close();
    console.error(
      `Failed to write HTML: ${e instanceof Error ? e.message : String(e)}`,
    );
    return 5;
  }

  if (cli.jsonPath) {
    try {
      await ensureDirForFile(cli.jsonPath);
      await Deno.writeTextFile(cli.jsonPath, JSON.stringify(report, null, 2));
      if (cli.verbose) console.debug(`JSON written: ${cli.jsonPath}`);
    } catch (e: unknown) {
      await db.close();
      console.error(
        `Failed to write JSON: ${e instanceof Error ? e.message : String(e)}`,
      );
      return 5;
    }
  }

  if (cli.emailTo && cli.emailTo.length > 0) {
    if (!cli.emailFrom) {
      await db.close();
      console.error(`--email-from is required when --email-to is provided`);
      return 2;
    }
    const smtpCfg: SmtpConfig | null = loader.determineSmtpConfig();
    if (!smtpCfg) {
      await db.close();
      console.error(
        `SMTP environment variables are not configured (SMTP_HOST/SMTP_PORT/...).`,
      );
      return 4;
    }
    try {
      const emailHtml = new HtmlRenderer({ email: true }).render(report);
      const mailer = new EmailClient(smtpCfg, { verbose: cli.verbose });
      await mailer.sendHtml(
        cli.emailFrom,
        cli.emailTo,
        cli.emailSubject,
        emailHtml,
        cli.emailCc,
      );
      if (cli.verbose) {
        console.debug(`Email sent to: ${cli.emailTo.join(", ")}`);
      }
    } catch (e: unknown) {
      await db.close();
      console.error(
        `Failed to send email: ${e instanceof Error ? e.message : String(e)}`,
      );
      return 4;
    }
  }

  await db.close();
  return 0;
}

/**
 * When executed as a script, run the application and exit with the returned code.
 */
if (import.meta.main) {
  try {
    const code = await main(Deno.args);
    Deno.exit(code);
  } catch (e) {
    // Last-resort error handling for truly unexpected failures
    const msg = e && typeof e === "object" && "stack" in e
      ? (e as Error).stack
      : String(e);
    console.error(`Unexpected error: ${msg}`);
    Deno.exit(1);
  }
}
