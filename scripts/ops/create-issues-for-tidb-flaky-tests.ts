import * as flags from "https://deno.land/std@0.201.0/flags/mod.ts";
import { Client, ClientConfig } from "https://deno.land/x/mysql@v2.12.0/mod.ts";
import { Octokit } from "https://esm.sh/octokit@3.1.0?dts";

const staticsTopNSQL = (dateRange: string) => `
SELECT repo,
    branch,
    suite_name,
    case_name,
    count(*) AS count
FROM tiinsight_problem_case_runs
WHERE (
      report_time BETWEEN ${dateRange}
      AND flaky = TRUE
      AND branch = 'master'
    )
GROUP BY repo,
    branch,
    suite_name,
    case_name
ORDER BY count DESC,
    repo ASC,
    branch ASC,
    suite_name ASC,
    case_name ASC
LIMIT 10
`;

const queryCaseRunSQL = (dateRange: string) => `
Select * from tiinsight_problem_case_runs
where (
  report_time BETWEEN ${dateRange}
  AND flaky = TRUE
  AND branch = 'master'
  AND repo = ?
  AND branch = ?
  AND suite_name = ?
  AND case_name = ?
)
ORDER BY report_time DESC
`;

interface caseRun {
  repo: string;
  branch: string;
  suite_name: string;
  case_name: string;
  count: number;
  build_url?: string;
  extensions: {
    build_url: string;
    pr?: {
      base_ref: string;
      head: string;
      number: number;
      html_link: string;
    };
    errors?: string[];
  }[];
}

interface issueBasicInfo {
  id: number;
  number: number;
  title: string;
  html_url: string;
  state: string;
}

interface cliParams {
  github_pat: string;
  date_range: string; // example: '"2023-09-01" AND "2023-09-08"'
  db: ClientConfig;
}

function genIssueDetails(dateRange: string, run: caseRun) {
  const details = run.extensions.map(({ build_url }) => {
    return `- [CI build](${build_url})`;
  }).join("\n");

  return `<details><summary><h3>Flaky runs happened ${run.count} times between ${dateRange}<h3></summary>

${details}

</details>
`;
}

function genIssueBody(dateRange: string, run: caseRun) {
  const details = genIssueDetails(dateRange, run);

  return `## Bug Report\n\n${details}\n\n<!-- append more -->`;
}

function getIssueCommentBody(dateRange: string, run: caseRun) {
  return genIssueDetails(dateRange, run);
}

function genIssueTitle(run: caseRun) {
  const goPkg = caseFolder(run.suite_name);
  return `flaky test \`${run.case_name}\` in \`${goPkg}\` pkg`;
}

function caseFolder(suite_name: string) {
  return suite_name.split(":", 2)[0].substring(2);
}

// Refs:
// - github rest api: https://docs.github.com/en/rest/issues?apiVersion=2022-11-28
async function run({ db, date_range, github_pat }: cliParams) {
  const dbClient = await new Client().connect(db);

  // 1. 过滤出这段时间出现 flaky 用例。
  const records = await dbClient.query(
    staticsTopNSQL(date_range),
  ) as caseRun[];
  if (records.length === 0) return;

  // Create a new Octokit instance using the provided token
  const octokit = new Octokit({ auth: github_pat });

  // Get all issues and order by created time desc.
  const issues = await octokit.paginate(octokit.rest.issues.listForRepo, {
    owner: "pingcap",
    repo: "tidb",
    state: "open",
    sort: "created",
    since: "2023-01-01T00:00:00Z",
    per_page: 100,
  }) as issueBasicInfo[];
  console.debug("issues count", issues.length);
  // 2. github 上检索 issue, 没有则创建, 有则追加评论.

  for (let index = 0; index < records.length; index++) {
    const run = records[index];
    // 2.0 再查出具体的运行记录
    const rawRecords = await dbClient.query(queryCaseRunSQL(date_range), [
      run.repo,
      run.branch,
      run.suite_name,
      run.case_name,
    ]) as caseRun[];
    run.extensions = rawRecords.map(({ build_url }) => {
      return { build_url: build_url || "" };
    });

    // 2.1 检索已有的 issue
    const existedIssue = issues.find((issue) => {
      return issue.title.includes(run.case_name) &&
        issue.title.toLowerCase().match(`\b(flaky|unstable)\b`);
    });
    if (existedIssue) {
      // 2.1.1 如果有则追加评论。
      const commentPayload = {
        owner: "pingcap",
        repo: "tidb",
        issue_number: existedIssue.number,
        body: getIssueCommentBody(date_range, run),
      };
      console.info("❓ existed:", existedIssue.html_url);
      await octokit.rest.issues.createComment(commentPayload);
    } else {
      // 2.1.2 没有则创建。
      const createRet = await octokit.rest.issues.create({
        owner: "pingcap",
        repo: "tidb",
        title: genIssueTitle(run),
        body: genIssueBody(date_range, run),
        labels: ["type/bug"],
      });
      console.info("🆕 created:", createRet.data.html_url);
    }
  }

  // 3. 更新 dashboard issue, 滚动创建最近一周的内容。
  // 这个还是手工吧。
  console.info("--------");
  console.info(
    "You should paste the output to the dashboard issue if it existed.",
  );

  await dbClient.close();
}

async function main() {
  const cliArgs = flags.parse(Deno.args) as cliParams;
  if (cliArgs.date_range === "") {
    cliArgs.date_range =
      "date(date_add(now(6), INTERVAL -7 day)) AND date(date_add(now(6), INTERVAL 1 day))";
  }

  await run(cliArgs);
}

// ============== entrypoint ======================
// Example:
// deno run --allow-all me.ts \
//  --github_pat <github token> \
//  --date_range '"2023-09-01" AND "2023-09-08"' \
//  --db.host localhost \
//  --db.port 3306 \
//  --db.db <database name> \
//  --db.username <db user> \
//  --db.password <db password>
await main();
console.info(`~~~~~~~~~~~ end ~~~~~~~~~~~~~`);
Deno.exit(0);
