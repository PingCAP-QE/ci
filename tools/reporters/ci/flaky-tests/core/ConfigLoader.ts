/**
 * ConfigLoader - CLI/env parsing and window/DB/SMTP resolution
 *
 * Responsibilities:
 * - Parse CLI arguments together with environment variables into a normalized CliConfig
 * - Resolve time window (from/to) based on --from/--to or --range
 * - Resolve database configuration from either --db-url or discrete params
 * - Resolve SMTP configuration from environment variables
 * - (Optional helper) Load owner mapping file (YAML/JSON) when provided
 *
 * Usage:
 *   const loader = new ConfigLoader();
 *   const { showHelp, config } = loader.parse(Deno.args);
 *   if (showHelp) { console.log(loader.helpText()); Deno.exit(0); }
 *   const window = loader.determineTimeWindow(config);
 *   const dbCfg = loader.determineDbConfig(config);
 *   const smtp = loader.determineSmtpConfig();
 *   const ownerMap = config.ownerMapPath
 *     ? await loader.loadOwnerMapFromFile(config.ownerMapPath)
 *     : null;
 */

import { parseArgs } from "jsr:@std/cli/parse-args";
import { parse as parseYaml } from "jsr:@std/yaml";
import * as mysql from "https://deno.land/x/mysql@v2.12.1/mod.ts";
import { convertDsnToClientConfig } from "../utils/db.ts";

import { CliConfig, OwnerEntry, SmtpConfig, TimeWindow } from "./types.ts";

/* ------------------------------ Helper parsers ------------------------------ */

function parseISOOrDateOnly(input: string): Date | null {
  // Accepts:
  // - ISO-8601 (e.g., 2025-03-01T00:00:00Z, or local time)
  // - Date-only YYYY-MM-DD, interpreted as UTC midnight
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(input);
  if (dateOnly) {
    const [_, y, m, d] = dateOnly;
    return new Date(Date.UTC(Number(y), Number(m) - 1, Number(d), 0, 0, 0));
  }
  const dt = new Date(input);
  if (Number.isNaN(dt.getTime())) return null;
  return dt;
}

function parseRangeShorthand(range: string): number | null {
  // Returns milliseconds. Supports "7d" (days), "12h" (hours), "90m" (minutes).
  const m = /^(\d+)\s*([dhm])$/.exec(range.trim());
  if (!m) return null;
  const value = parseInt(m[1]);
  const unit = m[2];
  switch (unit) {
    case "d":
      return value * 24 * 60 * 60 * 1000;
    case "h":
      return value * 60 * 60 * 1000;
    case "m":
      return value * 60 * 1000;
    default:
      return null;
  }
}

/* --------------------------------- Class ------------------------------------ */

export class ConfigLoader {
  helpText(): string {
    const text = `
Flaky Reporter (Deno)

Usage:
  deno run --allow-net --allow-read --allow-write --allow-env insight/reporters/ci/flaky-tests/src/main.ts [options]

Options:
  --from <ISO>              Inclusive start datetime, e.g. 2025-03-01T00:00:00Z or 2025-03-01
  --to <ISO>                Exclusive end datetime, e.g. 2025-03-08T00:00:00Z or 2025-03-08
  --range <N[d|h|m]>        Relative window if --from/--to omitted, e.g. 7d, 12h, 90m
  --threshold-ms <number>   Runtime threshold, default 600000 (10m)
  --repo <name>             Filter by repository (e.g., pingcap/tidb)
  --branch <name>           Filter by branch (e.g., master)
  --db-url <url>            mysql://user:pass@host:port/dbname
  --db-host <host>
  --db-port <port>
  --db-user <user>
  --db-pass <pass>
  --db-name <name>
  --owner-table <name>      Owner lookup table name, default flaky_owners
  --owner-map <path>        YAML/JSON file with owner mapping
  --html <path>             Output HTML file path, default flaky-report.html
  --json <path>             Optional JSON output path
  --email-to <a,b,c>        Optional recipients (comma-separated)
  --email-from <addr>       Sender address (required if emailing)
  --email-subject <text>    Email subject, default "Flaky Report"
  --dry-run                 Print summary, do not write or email
  --verbose                 Verbose logging
  --help                    Show this help

Environment:
  DB_URL | DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME
  REPO, BRANCH
  SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS, SMTP_SECURE
  THRESHOLD_MS

Examples:
  deno run -A insight/reporters/ci/flaky-tests/src/main.ts --range 7d --repo pingcap/tidb --branch master --html out/flaky.html --json out/flaky.json
  deno run -A insight/reporters/ci/flaky-tests/src/main.ts --from 2025-03-01 --to 2025-03-08 --repo pingcap/tidb --branch master --threshold-ms 300000
`.trim();
    return text;
  }

  parse(args: string[]): { showHelp: boolean; config: CliConfig } {
    const flags = parseArgs(args, {
      string: [
        "from",
        "to",
        "range",
        "repo",
        "branch",
        "threshold-ms",
        "db-url",
        "db-host",
        "db-port",
        "db-user",
        "db-pass",
        "db-name",
        "owner-table",
        "owner-map",
        "html",
        "json",
        "email-to",
        "email-cc",
        "email-from",
        "email-subject",
      ],
      boolean: ["dry-run", "verbose", "help"],
      alias: {
        h: "help",
        v: "verbose",
      },
      default: {
        "threshold-ms": Deno.env.get("THRESHOLD_MS") ?? "600000",
        "owner-table": "flaky_owners",
        html: "flaky-report.html",
        "email-subject": "Flaky Report",
        "dry-run": false,
        verbose: false,
      },
    });

    const cfg: CliConfig = {
      from: flags["from"],
      to: flags["to"],
      range: flags["range"],
      thresholdMs: parseInt(String(flags["threshold-ms"])),
      dbUrl: flags["db-url"] ?? Deno.env.get("DB_URL"),
      dbHost: flags["db-host"] ?? Deno.env.get("DB_HOST"),
      dbPort: flags["db-port"]
        ? parseInt(String(flags["db-port"]))
        : Deno.env.get("DB_PORT")
          ? parseInt(String(Deno.env.get("DB_PORT")))
          : undefined,
      dbUser: flags["db-user"] ?? Deno.env.get("DB_USER"),
      dbPass: flags["db-pass"] ?? Deno.env.get("DB_PASSWORD"),
      dbName: flags["db-name"] ?? Deno.env.get("DB_NAME"),
      repo: flags["repo"] ?? Deno.env.get("REPO"),
      branch: flags["branch"] ?? Deno.env.get("BRANCH"),
      ownerTable: flags["owner-table"],
      ownerMapPath: flags["owner-map"],
      htmlPath: flags["html"],
      jsonPath: flags["json"],
      emailTo: flags["email-to"]
        ? String(flags["email-to"])
            .split(",")
            .map((s: string) => s.trim())
            .filter(Boolean)
        : undefined,
      emailCc: flags["email-cc"]
        ? String(flags["email-cc"])
            .split(",")
            .map((s: string) => s.trim())
            .filter(Boolean)
        : undefined,
      emailFrom: flags["email-from"],
      emailSubject: flags["email-subject"],
      dryRun: !!flags["dry-run"],
      verbose: !!flags["verbose"],
    };

    return { showHelp: !!flags.help, config: cfg };
  }

  determineTimeWindow(cli: CliConfig): TimeWindow {
    let from: Date | undefined;
    let to: Date | undefined;

    if (cli.from) {
      const f = parseISOOrDateOnly(cli.from);
      if (!f) throw new Error(`Invalid --from: ${cli.from}`);
      from = f;
    }
    if (cli.to) {
      const t = parseISOOrDateOnly(cli.to);
      if (!t) throw new Error(`Invalid --to: ${cli.to}`);
      to = t;
    }

    if (!from || !to) {
      const rangeStr = cli.range ?? "7d";
      const ms = parseRangeShorthand(rangeStr);
      if (!ms) throw new Error(`Invalid --range: ${rangeStr}`);
      const now = new Date();
      to = to ?? now;
      from = from ?? new Date(now.getTime() - ms);
    }

    if (from.getTime() >= to.getTime()) {
      throw new Error(`Invalid time window: from >= to`);
    }

    return { from, to };
  }

  determineDbConfig(cli: CliConfig): mysql.ClientConfig {
    if (cli.dbUrl) {
      const parsed = convertDsnToClientConfig(cli.dbUrl);
      if (!parsed) throw new Error(`Invalid --db-url: ${cli.dbUrl}`);
      parsed.tls = { mode: mysql.TLSMode.VERIFY_IDENTITY };
      return parsed;
    }
    const hostname = cli.dbHost ?? "";
    const port = cli.dbPort ?? 3306;
    const username = cli.dbUser ?? "";
    const password = cli.dbPass ?? "";
    const db = cli.dbName ?? "";
    if (!hostname || !username || !db) {
      throw new Error(
        `DB connection is incomplete. Provide --db-url or --db-host/--db-user/--db-name`,
      );
    }
    return {
      hostname,
      port,
      username,
      password,
      db,
      tls: { mode: mysql.TLSMode.VERIFY_IDENTITY },
    };
  }

  determineSmtpConfig(): SmtpConfig | null {
    const host = Deno.env.get("SMTP_HOST");
    const port = Deno.env.get("SMTP_PORT");
    if (!host || !port) return null;
    const username = Deno.env.get("SMTP_USER") ?? undefined;
    const password = Deno.env.get("SMTP_PASS") ?? undefined;
    const secure =
      (Deno.env.get("SMTP_SECURE") ?? "true").toLowerCase() === "true";
    return {
      host,
      port: parseInt(port),
      username,
      password,
      secure,
    };
  }

  async loadOwnerMapFromFile(filePath: string): Promise<OwnerEntry[] | null> {
    try {
      const content = await Deno.readTextFile(filePath);
      let data: unknown;
      if (filePath.endsWith(".yaml") || filePath.endsWith(".yml")) {
        data = parseYaml(content);
      } else if (filePath.endsWith(".json")) {
        data = JSON.parse(content);
      } else {
        // Try YAML first, then JSON
        try {
          data = parseYaml(content);
        } catch {
          data = JSON.parse(content);
        }
      }
      if (!Array.isArray(data)) {
        console.error(`Owner map file must be an array. Got: ${typeof data}`);
        return null;
      }
      const owners: OwnerEntry[] = (data as unknown[]).map((raw, idx) => {
        const e = raw as Record<string, unknown>;
        const entry: OwnerEntry = {
          repo: String(e["repo"] ?? ""),
          branch: String(e["branch"] ?? "*"),
          suite_name: String(e["suite_name"] ?? "*"),
          case_name: String(e["case_name"] ?? "*"),
          owner_team: String(e["owner_team"] ?? "UNOWNED"),
          priority: e["priority"] != null ? Number(e["priority"]) : 0,
          note: e["note"] != null ? String(e["note"]) : undefined,
        };
        if (!entry.repo) {
          throw new Error(`Owner map entry #${idx} missing repo`);
        }
        return entry;
      });
      return owners;
    } catch (e) {
      console.error(
        `Failed to load owner map file: ${filePath}: ${
          e instanceof Error ? e.message : String(e)
        }`,
      );
      return null;
    }
  }
}
