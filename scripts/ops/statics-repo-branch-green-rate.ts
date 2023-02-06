import * as flags from "https://deno.land/std@0.173.0/flags/mod.ts";
import { Octokit } from "https://cdn.skypack.dev/octokit?dts";

/**
 * typescript style guide: https://google.github.io/styleguide/tsguide.html
 */

interface cliParams {
  token: string; // oauth token.
  owner: string;
  repo: string;
  since: string; // 2023-01-01T08:00:00
  until?: string; // 2023-01-01T08:00:00
}

async function main({
  token,
  owner,
  repo,
  since,
  until,
}: cliParams) {
  const octokit = new Octokit({
    auth: token,
    userAgent: "myApp v1.2.3",
  });

  const commits = await octokit.paginate(
    octokit.rest.repos.listCommits,
    {
      owner,
      repo,
      since,
      until,
      sha: "master",
      per_page: 100,
    },
    (response) => response.data,
  );

  const results = await Promise.all(
    commits.map(async ({ sha }: { sha: string }) => {
      const ss = await octokit.rest.repos.listCommitStatusesForRef({
        owner,
        repo,
        ref: sha,
      });

      const statuses = ss.data.reduce((
        acc: { [x: string]: string },
        cur: { state: string; context: string },
      ) => {
        if (["success", "failure"].includes(cur.state)) {
          acc[cur.context] = cur.state;
        }
        return acc;
      }, {});

      return Object.values(statuses).every((s) => s === "success");
    }),
  );
  console.debug(`green results:`, results);

  const greens = results.filter((g) => g);
  console.info(`green rate: ${greens.length / results.length}`);
}

const cliArgs = flags.parse(Deno.args) as cliParams;

// if not since arg, set it as a 7 days ago.
if (!cliArgs.since) {
  // default
  cliArgs.since = (new Date(new Date().getTime() - (7 * 24 * 60 * 60 * 1000)))
    .toISOString();
}
await main(cliArgs);

console.log("~~~~~~~~~~~end~~~~~~~~~~~~~~");
// /**
//  * FIXME: A bug in [octokit](https://github.com/octokit/octokit.j).
//  * current I need call an `exit` at the end of deno script.
//  * issue: https://github.com/octokit/octokit.js/issues/2079
//  * working in progress: https://github.com/octokit/webhooks.js/pull/693
//  */
Deno.exit(0);
