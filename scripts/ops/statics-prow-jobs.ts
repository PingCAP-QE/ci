interface prowJobRun {
  spec: {
    type: string;
    agent: string;
    cluster: string;
    namespace: string;
    job: string;
    refs?: {
      org: string;
      repo: string;
      base_ref: string;
    };
  };
  report: boolean;
  status: {
    state: string;
    startTime: string;
    completionTime: string;
  };
}

async function main() {
  const res = await fetch(
    "https://prow.tidb.net/prowjobs.js?var=allBuilds&omit=annotations,labels,decoration_config,pod_spec",
  );
  const js = await res.text();
  const list = js.replace(/^var\s+allBuilds\s*=\s*/g, "").replace(/;$/, "");
  const data = JSON.parse(list) as { items: prowJobRun[] };
  const runs = data.items;
  const groupedRuns = runs.reduce((acc, run) => {
    if (!run.spec.refs) {
      return acc;
    }
    const key =
      `${run.spec.refs?.org}/${run.spec.refs?.repo}/${run.spec.refs?.base_ref}|${run.spec.job}`;
    if (!acc[key]) {
      acc[key] = [];
    }
    acc[key].push(run);
    return acc;
  }, {} as { [key: string]: prowJobRun[] });

  const cols = [
    "repo",
    "branch",
    "job_type",
    "job_name",
    "success_count",
    "failure_count",
    "success_rate",
    "avg_timecost_minutes",
  ];

  console.log(cols.join(", "));
  for (const [_, runs] of Object.entries(groupedRuns)) {
    // print the job info
    const fullRepo = `${runs[0].spec.refs?.org}/${runs[0].spec.refs?.repo}`;
    const branch = runs[0].spec.refs?.base_ref;
    const jobType = runs[0].spec.type;
    const job = runs[0].spec.job;

    // static the succeeded and failed count and timecost
    const groupedState = runs.reduce((acc, run) => {
      const key = run.status.state;
      if (!acc[key]) {
        acc[key] = [];
      }
      acc[key].push(run);
      return acc;
    }, {} as { [key: string]: prowJobRun[] });
    // failure | success

    const successRuns = groupedState["success"] || [];
    const failedRuns = groupedState["failure"] || [];

    let successRate = 0;
    if (successRuns.length > 0) {
      successRate = successRuns.length /
        (successRuns.length + failedRuns.length);
    }
    const totalTimecostsMsOfsucceededRuns = successRuns.reduce((acc, run) => {
      const startTime = new Date(run.status.startTime).getTime();
      const completionTime = new Date(run.status.completionTime).getTime();
      return acc + (completionTime - startTime);
    }, 0);
    const avgTimecostMinutes = totalTimecostsMsOfsucceededRuns /
      successRuns.length / 60000;

    const values = [
      fullRepo,
      branch,
      jobType,
      job,
      successRuns.length,
      failedRuns.length,
      successRate,
      avgTimecostMinutes,
    ];
    console.log(values.join(", "));
  }
}

// Should run it weekly.
await main();
console.log("~~~~~~~~~~~end~~~~~~~~~~~~~~");
