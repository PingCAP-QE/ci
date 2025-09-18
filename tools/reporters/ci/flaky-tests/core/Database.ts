/**
 * Database - MySQL wrapper for the Flaky Reporter
 *
 * Responsibilities:
 * - Manage a MySQL connection lifecycle
 * - Provide typed accessors for problem_case_runs or owner-mapping queries
 *
 * Usage:
 *   const db = new Database(dbConfig, { verbose: true });
 *   await db.connect();
 *   const rows = await db.fetchRuns({ from, to });
 *   const owner = await db.queryOwner("flaky_owners", repo, suite, caseName);
 *   await db.close();
 */

// deno-lint-ignore-file no-explicit-any

import {
  Client as MySQLClient,
  ClientConfig,
} from "https://deno.land/x/mysql@v2.12.1/mod.ts";
import type { OwnerEntry, ProblemCaseRunRow, TimeWindow } from "./types.ts";

export class Database {
  private client: MySQLClient | null = null;
  private readonly cfg: ClientConfig;
  private readonly verbose: boolean;
  private readonly repo?: string;
  private readonly branch?: string;

  constructor(
    cfg: ClientConfig,
    options?: { verbose?: boolean; repo?: string; branch?: string },
  ) {
    this.cfg = cfg;
    this.verbose = !!options?.verbose;
    // Optional filters: prefer constructor options, fallback to environment variables
    this.repo = options?.repo ?? Deno.env.get("REPO") ?? undefined;
    this.branch = options?.branch ?? Deno.env.get("BRANCH") ?? undefined;
  }

  /**
   * Establish a MySQL connection.
   */
  async connect(): Promise<void> {
    if (this.client) return; // Already connected
    this.client = new MySQLClient();
    if (this.verbose) {
      console.debug(
        `[db] connecting: ${this.cfg.username}@${this.cfg.hostname}:${this.cfg.port}/${this.cfg.db}`,
      );
    }
    await this.client.connect(this.cfg);
    if (this.verbose) {
      console.debug("[db] connected");
    }
  }

  /**
   * Close the MySQL connection if open.
   */
  async close(): Promise<void> {
    if (!this.client) return;
    try {
      await this.client.close();
      if (this.verbose) console.debug("[db] closed");
    } finally {
      this.client = null;
    }
  }

  /**
   * Whether the underlying client is connected.
   */
  get connected(): boolean {
    return !!this.client;
  }

  /**
   * Fetch rows from `problem_case_runs` within the given time window [from, to).
   */
  async fetchRuns(window: TimeWindow): Promise<ProblemCaseRunRow[]> {
    this.assertConnected();
    let sql =
      `SELECT repo, branch, suite_name, case_name, flaky, timecost_ms, report_time, build_url, reason
       FROM problem_case_runs
       WHERE report_time >= ? AND report_time < ?`;
    const params: unknown[] = [window.from, window.to];
    if (this.repo) {
      sql += " AND repo = ?";
      params.push(this.repo);
    }
    if (this.branch) {
      sql += " AND branch = ?";
      params.push(this.branch);
    }

    if (this.verbose) {
      console.debug(
        "[db] fetchRuns window:",
        window.from.toISOString(),
        "→",
        window.to.toISOString(),
      );
    }

    const res = await this.client!.execute(sql, params);
    const rows = (res?.rows ?? []) as any[];

    return rows.map((r) => {
      const report_time: Date = r.report_time instanceof Date
        ? r.report_time
        : new Date(r.report_time);
      return {
        repo: String(r.repo),
        branch: String(r.branch),
        suite_name: String(r.suite_name),
        case_name: String(r.case_name),
        flaky: Number(r.flaky) || 0,
        timecost_ms: Number(r.timecost_ms) || 0,
        report_time,
        build_url: String(r.build_url ?? ""),
        reason: String(r.reason ?? ""),
      } as ProblemCaseRunRow;
    });
  }

  /**
   * Query ownership by exact pattern (supports '*' wildcard in owner table rows).
   * Applies ORDER BY priority DESC LIMIT 1 to allow overrides.
   *
   * Callers should invoke this with specific combinations, e.g.:
   *   (repo, suite, case)
   *   (repo, suite, '*')
   *   (repo, '*', '*')
   *   (repo, '*', '*', '*')
   */
  async queryOwner(
    ownerTable: string,
    repo: string,
    suite: string,
    kase: string,
  ): Promise<string | null> {
    this.assertConnected();
    const quotedTable = this.quoteTable(ownerTable);
    const sql = `SELECT owner_team
                 FROM ${quotedTable}
                 WHERE repo = ? AND suite_name = ? AND case_name = ?
                 ORDER BY priority DESC
                 LIMIT 1`;
    if (this.verbose) {
      console.debug(
        `[db] queryOwner: table=${quotedTable} repo=${repo} suite=${suite} case=${kase}`,
      );
    }
    const res = await this.client!.execute(sql, [repo, suite, kase]);
    const row = (res?.rows ?? [])[0] as any;
    const owner: string | undefined = row?.owner_team;
    return owner ? String(owner) : null;
  }

  async fetchOwners(
    ownerTable: string,
  ): Promise<
    OwnerEntry[]
  > {
    this.assertConnected();
    const quotedTable = this.quoteTable(ownerTable);
    const sql =
      `SELECT repo, branch, suite_name, case_name, owner_team, priority
                 FROM ${quotedTable}`;
    if (this.verbose) {
      console.debug(
        `[db] fetchOwners: table=${quotedTable}`,
      );
    }
    const res = await this.client!.execute(sql);
    return res?.rows ?? [];
  }

  /**
   * Internal helper to quote an owner table name safely:
   * - Allows optional schema prefix (schema.table)
   * - Each identifier segment is wrapped in backticks with escaping
   */
  private quoteTable(tableName: string): string {
    const t = String(tableName);
    // Only allow alphanumerics, underscore, dollar, and dot for schema separation.
    if (!/^[A-Za-z0-9_$.]+$/.test(t)) {
      throw new Error("Invalid owner table name");
    }
    return t.split(".")
      .map((part) => `\`${part.replaceAll("`", "``")}\``)
      .join(".");
  }

  private assertConnected(): void {
    if (!this.client) throw new Error("DB not connected");
  }
}
