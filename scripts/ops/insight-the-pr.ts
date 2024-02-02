const GHE_QUERY_URL =
  "https://play.clickhouse.com/?add_http_cors_header=1&default_format=JSONCompact&max_result_rows=1000&max_result_bytes=10000000&result_overflow_mode=break";
const GHE_QEURY_AUTHORIZATION = "Basic cGxheTo="; // just username, empty password, it's public service.

interface prTimeline {
  repo: string;
  number: number;
  retested: boolean;
  holded: boolean;
  raisedAt?: Date;
  reviewStartedAt?: Date;
  acceptedAt?: Date;
  mergedAt?: Date;
}

async function prMergedList(repo: string, baseRef: string) {
  const querySQL = `
  SELECT repo_name,    
      base_ref,
      number,
      merged
  FROM github_events
  WHERE repo_name = '${repo}'
      AND base_ref = '${baseRef}'
      AND merged_at >= '2024-01-01 00:00:00'
      AND event_type = 'PullRequestEvent'
      AND action = 'closed'
      AND merged_by != ''
  `;

  const list = await gheSqlQuery(querySQL);
  return list.map(({ number }) => number);
}

// stages timelines:
// - PR Raised: created to first review events.
// - Review Started
// - PR Accepted
// - PR Merged
async function prTimeline(repo: string, prNum: number) {
  const querySQL = `
SELECT * FROM github_events
WHERE repo_name = '${repo}' 
    AND number = '${prNum}'
    order by created_at
`;
  const events = await gheSqlQuery(querySQL);

  let prRaisedEvent;
  let prReviewStartedEvent;
  let prAcceptedEvent;
  let prMergedEvent;
  let retestHappend = false;
  let holdedHappend = false;
  for (const e of events) {
    switch (e.event_type) {
      case "PullRequestEvent":
        if (e.action === "opened") {
          prRaisedEvent ||= e;
        }
        if (e.action === "closed") {
          // found for latest event.
          prMergedEvent = e;
        }
        break;
      case "PullRequestReviewEvent":
        prReviewStartedEvent ||= e;
        break;
      case "IssueCommentEvent":
        if (
          !(e.actor_login as string).includes("bot") &&
          e.actor_login !== e.creator_user_login
        ) {
          prReviewStartedEvent ||= e;
        }

        if (
          (e.labels.includes("lgtm") && e.labels.includes("approved")) ||
          (e.labels.includes("status/can-merge") &&
            e.labels.some((l: string) => l.match(`^status/LGT\\d+$`)))
        ) {
          prAcceptedEvent ||= e;

          if (
            e.labels.includes("do-not-merge/hold") ||
            (e.body as string).match("^/hold")
          ) {
            holdedHappend = true;
          }
          if (
            (e.body as string).match(
              `^/(test\\s+\\w+|retest|retest-required|merge|run-\\w+|test-\\w+)`,
            )
          ) {
            retestHappend = true;
          }
        }
        if (
          (e.body as string).match(`^/(merge)`) && prAcceptedEvent &&
          !e.labels.includes("status/can-merge")
        ) {
          retestHappend = true;
        }
        break;
      default:
        break;
    }
  }

  const ret = {
    repo: repo,
    number: prNum,
    retested: retestHappend,
    holded: holdedHappend,
  } as prTimeline;

  if (prRaisedEvent) {
    ret.raisedAt = new Date(prRaisedEvent.created_at as string);
  }
  if (prReviewStartedEvent) {
    ret.reviewStartedAt = new Date(prReviewStartedEvent.created_at as string);
  }
  if (prAcceptedEvent) {
    ret.acceptedAt = new Date(prAcceptedEvent.created_at as string);
  }
  if (prMergedEvent) {
    ret.mergedAt = new Date(prMergedEvent.created_at as string);
  }

  return ret;
}

async function gheSqlQuery(querySQL: string) {
  const res = await fetch(GHE_QUERY_URL, {
    method: "POST",
    body: querySQL,
    headers: { authorization: GHE_QEURY_AUTHORIZATION },
  });

  const results: { meta: { name: string }[]; data: any[][] } = await res.json();

  return results.data.map((row) =>
    results.meta.reduce((a: { [key: string]: any }, col, i, cols) => {
      a[col.name] = row[i];
      return a;
    }, {})
  );
}

async function main() {
  const repo = "pingcap/tidb";
  const baseRef = "master";
  const prList = await prMergedList(repo, baseRef);
  console.log(
    [
      "repo",
      "pr",
      "merged_at",
      "pickup",
      "accepted",
      "merged",
      "retested",
      "holded",
    ].join(
      ",",
    ),
  );
  for await (const prNum of prList) {
    const timeline = await prTimeline(repo, prNum);
    if (
      timeline.raisedAt && timeline.reviewStartedAt && timeline.acceptedAt &&
      timeline.mergedAt
    ) {
      const row = [
        timeline.repo,
        timeline.number,
        timeline.mergedAt.toISOString().split("T", 2)[0],
        Math.ceil(
          (timeline.reviewStartedAt.getTime() - timeline.raisedAt.getTime()) /
            60000,
        ), // minutes
        Math.ceil(
          (timeline.acceptedAt.getTime() - timeline.reviewStartedAt.getTime()) /
            60000,
        ), // minutes
        Math.ceil(
          (timeline.mergedAt.getTime() - timeline.acceptedAt.getTime()) / 60000,
        ), // minutes
        timeline.retested,
        timeline.holded,
      ];
      console.log(row.join(","));
    }
  }
}

await main();
console.log("~~~~~~~~~~~end~~~~~~~~~~~~~~");
