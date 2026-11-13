import { assertEquals } from "@std/assert";
import { parse } from "@std/yaml";

type Job = {
  name: string;
  cluster: string;
  namespace?: string;
  cron: string;
  max_concurrency: number;
  spec: {
    containers: Array<{
      command: string[];
      args: string[];
      env: Array<
        {
          name: string;
          valueFrom: { secretKeyRef: { name: string; key: string } };
        }
      >;
    }>;
  };
};

async function loadJob(): Promise<Job> {
  const config = parse(
    await Deno.readTextFile(
      new URL(
        "../../../../prow-jobs/pingcap-qe/ci/periodics.yaml",
        import.meta.url,
      ),
    ),
  ) as { periodics: Job[] };
  return config.periodics.find((job) =>
    job.name === "periodic-reporter-flaky-tests-cse"
  )!;
}

Deno.test("CSE issue job uses the existing GCP credentials and daily schedule", async () => {
  const job = await loadJob();
  assertEquals(job.cluster, "default");
  assertEquals(job.namespace, undefined);
  assertEquals(job.cron, "55 0 * * *");
  assertEquals(job.max_concurrency, 1);
  const container = job.spec.containers[0];
  assertEquals(container.command, ["bash", "-c"]);
  assertEquals(
    container.env.map((env) => [env.name, env.valueFrom.secretKeyRef]),
    [
      ["GITHUB_TOKEN", { name: "github-token", key: "token" }],
      ["PARAM_DSN", {
        name: "ci-job-params-mono",
        key: "ci-job-flay-report-dsn",
      }],
    ],
  );
  assertEquals(container.args[0].includes("/repositories/492109999"), true);
});

for (
  const scenario of [
    {
      name: "normal execution",
      repo: "tidbcloud/cloud-storage-engine",
      exit: 0,
    },
    {
      name: "repository rename",
      repo: "synthetic-org/renamed-engine",
      exit: 0,
    },
    {
      name: "reporter failure",
      repo: "tidbcloud/cloud-storage-engine",
      exit: 7,
    },
    {
      name: "repository lookup failure",
      repo: "",
      exit: 0,
      lookupFailure: true,
    },
  ]
) {
  Deno.test(`CSE issue job protects case names during ${scenario.name}`, async () => {
    const dir = await Deno.makeTempDir({ prefix: "cse-issue-job-test-" });
    try {
      await Deno.writeTextFile(
        `${dir}/deno`,
        `#!/bin/bash
set -eu
if [ "$1" = eval ]; then
  [ "$MOCK_LOOKUP_FAILURE" = 0 ] || exit 1
  printf '%s\\n' "$MOCK_REPO"
elif [ "$1" = run ]; then
  printf '%s\\n' "$@" > "$CALLS_FILE"
  echo 'synthetic-private-case-marker stdout'
  echo 'synthetic-private-case-marker stderr' >&2
  exit "$MOCK_EXIT"
else
  exit 99
fi
`,
        { mode: 0o700 },
      );
      // The pod uses GNU date; a deterministic stub also works on macOS.
      await Deno.writeTextFile(
        `${dir}/date`,
        `#!/bin/sh
case "$*" in
  *"1 days ago"*) echo 2026-03-12 ;;
  *) echo 2026-03-13 ;;
esac
`,
        { mode: 0o700 },
      );
      const job = await loadJob();
      const script = job.spec.containers[0].args[0]
        .replaceAll("/tmp/cse-flaky-", `${dir}/cse-flaky-`);
      const callsFile = `${dir}/calls`;
      const result = await new Deno.Command("/bin/bash", {
        args: ["-c", script],
        clearEnv: true,
        env: {
          PATH: `${dir}:/usr/bin:/bin`,
          GITHUB_TOKEN: "synthetic-token",
          PARAM_DSN: "mysql://synthetic:synthetic@localhost:3306/test",
          MOCK_REPO: scenario.repo,
          MOCK_EXIT: String(scenario.exit),
          MOCK_LOOKUP_FAILURE: scenario.lookupFailure ? "1" : "0",
          CALLS_FILE: callsFile,
        },
        stdout: "piped",
        stderr: "piped",
      }).output();
      const output = new TextDecoder().decode(result.stdout) +
        new TextDecoder().decode(result.stderr);
      assertEquals(output.includes("synthetic-private-case-marker"), false);
      assertEquals(
        result.code,
        scenario.exit || scenario.lookupFailure ? 1 : 0,
      );
      if (scenario.lookupFailure) {
        assertEquals(
          await Deno.stat(callsFile).then(() => true).catch(() => false),
          false,
        );
        return;
      }
      const args = (await Deno.readTextFile(callsFile)).trim().split("\n");
      for (
        const flag of ["--issue-create", "--issue-comment", "--issue-reopen"]
      ) {
        assertEquals(args.includes(flag), true, flag);
      }
      assertEquals(args[args.indexOf("--repo") + 1], scenario.repo);
      assertEquals(args[args.indexOf("--branch") + 1], "cloud-engine");
      assertEquals(args[args.indexOf("--from") + 1], "2026-03-12");
      assertEquals(args[args.indexOf("--to") + 1], "2026-03-13");
      assertEquals(args[args.indexOf("--issue-labels") + 1], "type/bug");
      assertEquals(args[args.indexOf("--issue-mutation-limit") + 1], "10000");
      assertEquals(
        args[args.indexOf("--html") + 1],
        `${dir}/cse-flaky-report.html`,
      );
      assertEquals(args.some((arg) => arg.startsWith("--email")), false);
      const privateLog = await Deno.readTextFile(
        `${dir}/cse-flaky-reporter.log`,
      );
      assertEquals(
        privateLog.includes("synthetic-private-case-marker stdout"),
        true,
      );
      assertEquals(
        privateLog.includes("synthetic-private-case-marker stderr"),
        true,
      );
    } finally {
      await Deno.remove(dir, { recursive: true });
    }
  });
}
