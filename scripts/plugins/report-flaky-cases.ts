import { Client } from "https://deno.land/x/mysql@v2.11.0/mod.ts";
import * as flags from "https://deno.land/std@0.179.0/flags/mod.ts";

const SQL_TABLE_DSL = `
CREATE TABLE IF NOT EXISTS tiinsight_problem_case_runs (
  id INT auto_increment,
  repo VARCHAR(255), -- repo full name
  branch VARCHAR(255), -- base branch
  suite_name VARCHAR(255), -- suite name, target naem in bazel.
  case_name VARCHAR(255), -- case name, may be TextXxx.TestYyy format.
  report_time timestamp, -- unit timestamp
  flaky BIT, -- true or false
  timecost_ms INT, -- milliseconds.
  primary key (id)
);
`;
const SQL_TPL_INSERT =
  `INSERT INTO tiinsight_problem_case_runs(repo, branch, suite_name, case_name, report_time, flaky, timecost_ms) values(?, ?, ?, ?, FROM_UNIXTIME(?), ?, ?);`;

interface _cliParams {
  hostname: string;
  username: string;
  password: string;
  db: string;
  caseDataFile: string;
  repo: string;
  branch: string;
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
  const caseData = JSON.parse(
    await Deno.readTextFile(caseDataFile),
  ) as _problemCasesFromBazel;

  const client = await new Client().connect({
    hostname,
    username,
    password,
    db,
  });

  try {
    // create table if not exist.
    await client.execute(SQL_TABLE_DSL);
    // insert records.
    await insert_problem_case_runs(
      client,
      caseData,
      rest.repo,
      rest.branch,
      ~~(Date.now() / 1000),
    );
  } finally {
    // close the db connection.
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
    await Promise.all((caseResults.new_flaky || [])?.map(async (flakyCase) => {
      await dbClient.execute(SQL_TPL_INSERT, [
        repo,
        branch,
        target,
        flakyCase,
        timestamp,
        true,
        0,
      ]);
    }));

    for (const [tc, timecost] of Object.entries(caseResults?.long_time || [])) {
      await dbClient.execute(SQL_TPL_INSERT, [
        repo,
        branch,
        target,
        tc,
        timestamp,
        false,
        timecost > 0 ? Math.ceil(timecost * 1000) : timecost,
      ]);
    }
  }
}

/**
 * ---------entry----------------
 * ****** CLI args **************
 * --hostname       db hostname
 * --username       db username
 * --password       db password
 * --db             db name
 * --repo           to report repo full name,such as: pingcap/tidb *
 * --branch         to report repo's branch,such as `master`
 * --caseDataFile   case run data file path.
 */

const cliArgs = flags.parse<_cliParams>(Deno.args);
await main(cliArgs);
