import { Client } from "https://deno.land/x/mysql@v2.11.0/mod.ts";
import * as flags from "https://deno.land/std@0.179.0/flags/mod.ts";

const SQL_TPL_INSERT =
  `INSERT INTO problem_case_runs(repo, branch, target, case_name, timestamp, flaky, timecost) values(?, ?, ?, ?, ?, ?, ?, ?)`;

interface _cliParams {
  hostname: string;
  username: string;
  password: string;
  db: string;
  caseDataFile: string;
  repo: string;
  branch: string;
  timestamp: number;
}

interface _problemCasesFromBazel {
  [target: string]: {
    new_flaky?: string[];
    long_time?: { [tc: string]: number };
  };
}

async function main({
  hostname,
  username,
  password,
  db,
  caseDataFile,
  ...rest
}: _cliParams) {
  const client = await new Client().connect({
    hostname,
    username,
    password,
    db,
  });

  const caseData = JSON.parse(
    await Deno.readTextFile(caseDataFile),
  ) as _problemCasesFromBazel;

  try {
    insert_problem_case_runs(
      client,
      caseData,
      rest.repo,
      rest.branch,
      rest.timestamp,
    );
  } finally {
    await client.close();
  }
}

async function insert_problem_case_runs(
  dbClient: Client,
  caseData: _problemCasesFromBazel,
  repo: string,
  branch: string,
  timestamp: number,
) {
  // insert/update record
  for (const [target, caseResults] of Object.entries(caseData)) {
    caseResults.new_flaky?.forEach(async (flakyCase) => {
      const result = await dbClient.execute(SQL_TPL_INSERT, [
        repo,
        branch,
        target,
        flakyCase,
        timestamp,
        true,
        0,
      ]);
      console.log(result); // { affectedRows: 1, lastInsertId: 1 }
    });

    for (const [tc, timecost] of Object.entries(caseResults?.long_time || [])) {
      const result = await dbClient.execute(SQL_TPL_INSERT, [
        repo,
        branch,
        target,
        tc,
        timestamp,
        false,
        timecost,
      ]);
      console.log(result); // { affectedRows: 1, lastInsertId: 1 }
    }
  }
}

/**
 * ---------entry----------------
 * ****** CLI args **************
 * --hostname        db hostname
 * --username        db username
 * --password        db password
 * --db              db name
 * --repo            to report repo full name,such as: pingcap/tidb *
 * --branch          to report repo's branch,such as `master`
 * --timestamp       timestamp as event time.
 * --case-data-file  case run data file path.
 */
const cliArgs = flags.parse(Deno.args) as _cliParams;
await main(cliArgs);
