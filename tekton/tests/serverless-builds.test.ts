import { assert, assertEquals, assertFalse } from "jsr:@std/assert@1.0.11";
import { expandGlobSync } from "jsr:@std/fs@1.0.19/expand-glob";
import {
  evaluate,
  filter,
  type Header,
  load,
  payload,
  products,
  root,
  sha,
  type Trigger,
} from "./trigger-test-utils.ts";

type Param = { name: string; value: string };
type Step = {
  name: string;
  script: string;
  command: string[];
  args: string[];
  when?: { input: string; operator: string; values: string[] }[];
};

function command(
  cmd: string,
  args: string[],
  options: Deno.CommandOptions = {},
) {
  const result = new Deno.Command(cmd, {
    ...options,
    args,
    stdout: "piped",
    stderr: "piped",
  }).outputSync();
  assert(result.success, `${cmd}: ${new TextDecoder().decode(result.stderr)}`);
  return new TextDecoder().decode(result.stdout);
}

function optionalText(path: string) {
  try {
    return Deno.readTextFileSync(path);
  } catch (error) {
    if (error instanceof Deno.errors.NotFound) return undefined;
    throw error;
  }
}

Deno.test("Serverless keeps raw Git versions and the legacy version CLI", async (t) => {
  const task = load<{ spec: { steps: Step[] } }>(
    "tekton/v1/tasks/pingcap-get-set-release-version.yaml",
  );
  const cleanup = task.spec.steps.find((step) => step.name === "cleanup-tags")!;
  const compute = task.spec.steps.find((step) =>
    step.name === "compute-version"
  )!;
  assert(cleanup?.script);
  assertEquals(
    compute.command.join(" "),
    "deno run --allow-read --allow-write https://cdn.jsdelivr.net/gh/PingCAP-QE/ci@main/scripts/flow/build/versioning-strategy.ts",
  );
  assertEquals(
    compute.args.join(" "),
    "--git_version_file=/workspace/raw-version.txt --contain_branches_file=/workspace/branches.txt --save_build_git_tag_file=/workspace/new-tag --save_release_version_file=$(results.version.path)",
  );
  assertEquals(compute.when, [{
    input: "$(params.component)",
    operator: "notin",
    values: products,
  }]);

  type Case = {
    product: string;
    branch: string;
    baseline: string;
    ref: string;
    remote?: string;
    commit?: boolean;
    version: string;
    tag?: string;
  };
  const cases: Case[] = [];
  for (
    const [product, branch, baseline] of [
      ["tidb-cse", "main-8.5-keyspace", "v8.5.3-alpha.0"],
      ["pd-cse", "release-8.5-keyspace", "v8.5.0-alpha.0"],
      ["tidb-operator-cse", "serverless-on-release-1.4", "v1.4.0-alpha.0"],
      ["cassandra-on-mysql", "master", "v0.1.0-alpha.0"],
      ["qdrant-on-tidb", "main", "v1.3.4-alpha.0"],
    ]
  ) {
    cases.push({ product, branch, baseline, ref: baseline, version: baseline });
    cases.push({
      product,
      branch,
      baseline,
      ref: branch,
      version: "raw",
      commit: true,
    });
  }
  for (const date of ["20260206", "202603", "202604", "202605", "202607"]) {
    const branch = `release-8.5-keyspace-${date}`;
    cases.push({
      product: "tidb-cse",
      branch,
      baseline: `v8.5.3-alpha.${date}`,
      ref: branch,
      version: "raw",
      commit: true,
    });
  }
  cases.push(
    {
      product: "tidb-cse",
      branch: "validation",
      baseline: "v8.5.3-alpha.0",
      ref: "pull/42",
      remote: "release-8.5-keyspace-202603",
      version: "raw",
      commit: true,
    },
    {
      product: "tidb-cse",
      branch: "main-8.5-keyspace",
      baseline: "v8.5.3-rc.1",
      ref: "v8.5.3-rc.1",
      version: "v8.5.3-rc.1",
    },
    {
      product: "tidb",
      branch: "main-8.5-keyspace",
      baseline: "v8.5.3-alpha.0",
      ref: "main-8.5-keyspace",
      version: "raw",
      commit: true,
    },
    {
      product: "tidb",
      branch: "release-8.5",
      baseline: "v8.5.3-alpha.0",
      ref: "v8.5.3-alpha.0",
      version: "v8.5.3-pre",
      tag: "v8.5.3",
    },
  );
  for (const tc of cases) {
    await t.step(`${tc.product}/${tc.ref}`, () => {
      const tmp = Deno.makeTempDirSync();
      try {
        const dir = `${tmp}/repo`, output = `${tmp}/output`;
        Deno.mkdirSync(dir);
        Deno.mkdirSync(output);
        const env = {
          GIT_CONFIG_GLOBAL: "/dev/null",
          GIT_CONFIG_NOSYSTEM: "1",
        };
        const git = (...args: string[]) =>
          command("git", [
            "-c",
            "user.name=Version Test",
            "-c",
            "user.email=version@example.invalid",
            "-c",
            "commit.gpgsign=false",
            "-c",
            "tag.gpgsign=false",
            ...args,
          ], { cwd: dir, env });
        git("init", `--initial-branch=${tc.branch}`);
        git("commit", "--allow-empty", "-m", "baseline");
        git("tag", "-a", tc.baseline, "-m", "baseline");
        if (tc.commit) git("commit", "--allow-empty", "-m", "next");
        if (tc.remote) {
          git("update-ref", `refs/remotes/origin/${tc.remote}`, "HEAD");
        }
        const args = cleanup.args.map((arg) =>
          arg.replaceAll("$(params.component)", tc.product).replaceAll(
            "$(results.version.path)",
            `${output}/version`,
          )
        );
        command("sh", [
          "-c",
          cleanup.script.replaceAll("/workspace/", `${output}/`),
          "version-task",
          ...args,
        ], { cwd: dir, env });
        const branches = optionalText(`${output}/branches.txt`);
        if (tc.product !== "tidb") {
          assertEquals(
            branches,
            undefined,
            "Serverless must not classify branches",
          );
        } else {
          if (tc.branch === "main-8.5-keyspace") {
            assert(branches?.includes(tc.branch));
          }
          command(Deno.execPath(), [
            "run",
            "--allow-read",
            "--allow-write",
            new URL("scripts/flow/build/versioning-strategy.ts", root).pathname,
            `--git_version_file=${output}/raw-version.txt`,
            `--contain_branches_file=${output}/branches.txt`,
            `--save_build_git_tag_file=${output}/new-tag`,
            `--save_release_version_file=${output}/version`,
          ]);
        }
        const want = tc.version === "raw"
          ? Deno.readTextFileSync(`${output}/raw-version.txt`).trim()
          : tc.version;
        assertEquals(Deno.readTextFileSync(`${output}/version`), want);
        assertEquals(optionalText(`${output}/new-tag`), tc.tag);
      } finally {
        Deno.removeSync(tmp, { recursive: true });
      }
    });
  }
});

Deno.test("private dependency credentials stay in the mounted helper", () => {
  const task = load<{ spec: { steps: Step[] } }>(
    "tekton/v1/tasks/pingcap-build-binaries-linux.yaml",
  );
  let script =
    task.spec.steps.find((step) => step.name === "build")!.script.split(
      'script="/workspace/build-package-artifacts.sh"',
    )[0];
  assert(script);
  const tmp = Deno.makeTempDirSync();
  try {
    const credentials = `${tmp}/credentials`;
    Deno.mkdirSync(credentials, { mode: 0o700 });
    Deno.writeTextFileSync(`${credentials}/username`, "test-user", {
      mode: 0o600,
    });
    Deno.writeTextFileSync(`${credentials}/password`, "test-password", {
      mode: 0o600,
    });
    for (
      const [from, to] of [
        ["$(workspaces.git-credentials.bound)", "true"],
        ["$(workspaces.git-credentials.path)", credentials],
        ["$(params.git-ref)", "feature/test"],
        ["/workspace/serverless-git-credential", `${tmp}/git-credential`],
      ]
    ) script = script.replaceAll(from, to);
    script +=
      "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential fill\n";
    const env = {
      HOME: tmp,
      GIT_CONFIG_NOSYSTEM: "1",
      GIT_CONFIG_GLOBAL: `${tmp}/.gitconfig`,
    };
    const output = command("sh", ["-c", script], { env });
    assert(output.includes("username=test-user\n"));
    assert(output.includes("password=test-password\n"));
    assertFalse(
      Deno.readTextFileSync(`${tmp}/.gitconfig`).includes("test-password"),
    );
    for (
      const remote of [
        "git@github.com:tidbcloud/private.git",
        "ssh://git@github.com/tidbcloud/private.git",
      ]
    ) {
      assertEquals(
        command("git", ["ls-remote", "--get-url", remote], { env }).trim(),
        "https://github.com/tidbcloud/private.git",
      );
    }
  } finally {
    Deno.removeSync(tmp, { recursive: true });
  }
});

Deno.test("Devbuild routes pin product/revision and exclude generic routes", async (t) => {
  const generic = [
    ...expandGlobSync(
      "tekton/v1/triggers/triggers/env-gcp/_/fake-github/*.yaml",
      { root: root.pathname },
    ),
  ].map((entry) => load<Trigger>(entry.path));
  for (const product of products) {
    for (
      const [kind, event] of Object.entries({
        "branch-push": "push",
        "tag-create": "create",
        pr: "pull_request",
      })
    ) {
      for (const arch of ["amd64", "arm64"]) {
        for (const fork of [false, true]) {
          await t.step(
            `${product}/${kind}/${arch}${fork ? "/fork" : ""}`,
            () => {
              const repo = fork
                ? "custom-owner/custom-fork"
                : `tidbcloud/${product}`;
              const body = payload(repo, kind);
              const header: Header = {
                "ce-type": `net.pingcap.tibuild.devbuild.${event}`,
                "ce-paramproduct": product,
                "ce-paramgitrevision": sha,
                "ce-paramplatform": `linux/${arch}`,
                "ce-paramgithubrepo": repo,
              };
              const trigger = load<Trigger>(
                `tekton/v1/triggers/triggers/env-gcp/_/serverless/devbuild-${kind}.yaml`,
              );
              assert(filter(trigger, body, header));
              assertEquals(
                trigger.spec.template.ref,
                "build-serverless-single-platform",
              );
              for (const interceptor of trigger.spec.interceptors) {
                for (const param of interceptor.params) {
                  if (
                    param.name !== "overlays" || typeof param.value === "string"
                  ) continue;
                  for (const overlay of param.value) {
                    const value = evaluate(overlay.expression, body, header);
                    if (overlay.key === "product") assertEquals(value, product);
                    if (overlay.key === "revision") assertEquals(value, sha);
                    if (overlay.key === "refspec") {
                      assertEquals(value, `+${sha}:refs/devbuild/source`);
                    }
                  }
                }
              }
              for (const old of generic) {
                assertFalse(
                  filter(old, body, header),
                  "duplicate legacy route",
                );
              }
              for (const platform of ["linux", "darwin/arm64"]) {
                header["ce-paramplatform"] = platform;
                assertFalse(
                  filter(trigger, body, header),
                  `unsupported platform: ${platform}`,
                );
              }
              header["ce-paramplatform"] = `linux/${arch}`;
              header["ce-type"] = `net.pingcap.tibuild.hotfix.${event}`;
              assertFalse(filter(trigger, body, header));
            },
          );
        }
      }
    }
  }
});

Deno.test("legacy canonical events and old products retain their routes", () => {
  const trigger = load<Trigger>(
    "tekton/v1/triggers/triggers/env-gcp/_/serverless/devbuild-branch-push.yaml",
  );
  const header = {
    "ce-type": "net.pingcap.tibuild.devbuild.push",
    "ce-paramplatform": "linux/amd64",
  };
  for (const product of products) {
    assert(
      filter(trigger, payload(`tidbcloud/${product}`, "branch-push"), header),
    );
  }
  const body = payload("pingcap/tidb", "branch-push");
  assertFalse(filter(trigger, body, header));
  const old = load<Trigger>(
    "tekton/v1/triggers/triggers/env-gcp/_/fake-github/fake-github-branch-push-single-platform.yaml",
  );
  assert(filter(old, body, header));
});

Deno.test("template run counts and existing Pipeline/Task contracts are preserved", () => {
  for (const [name, count] of [["single-platform", 1], ["linux", 2]] as const) {
    const template = load<
      {
        spec: {
          resourcetemplates: {
            metadata: { annotations: Record<string, string> };
            spec: { params: Param[]; workspaces: { name: string }[] };
          }[];
        };
      }
    >(`tekton/v1/triggers/templates/_/build-serverless-${name}.yaml`);
    assertEquals(template.spec.resourcetemplates.length, count);
    for (const run of template.spec.resourcetemplates) {
      assertEquals(
        run.metadata.annotations["tekton.dev/ce-context"],
        "$(tt.params.ce-context)",
      );
      const params = Object.fromEntries(
        run.spec.params.map((param) => [param.name, param.value]),
      );
      assertEquals(params["deliver-artifacts"], "false");
      assertEquals(params["require-artifacts"], "true");
      const workspaces = run.spec.workspaces.map((workspace) => workspace.name);
      assert(workspaces.includes("git-credentials"));
      assertFalse(workspaces.includes("mac-ssh-credentials"));
    }
  }
  const pipeline = load<
    {
      spec: {
        params: { name: string }[];
        results: { name: string; value: string }[];
        tasks: {
          name: string;
          taskRef: { name: string };
          params: Param[];
          when?: { input: string }[];
        }[];
      };
    }
  >("tekton/v1/pipelines/pingcap-build-package-linux.yaml");
  assertEquals(
    Object.fromEntries(
      pipeline.spec.results.map((result) => [result.name, result.value]),
    ),
    {
      "pushed-binaries": "$(tasks.build-binaries.results.pushed)",
      "pushed-images": "$(tasks.build-images.results.pushed)",
    },
  );
  for (const param of pipeline.spec.params) {
    assertFalse(
      ["artifacts-revision", "versioning-strategy-url"].includes(param.name),
    );
  }
  const definitions = [
    ...expandGlobSync("tekton/v1/tasks/**/*.yaml", { root: root.pathname }),
  ].filter((entry) => entry.name !== "kustomization.yaml").map((entry) =>
    load<{ metadata: { name: string }; spec: { params?: { name: string }[] } }>(
      entry.path,
    )
  );
  for (const task of pipeline.spec.tasks) {
    const definition = definitions.find((definition) =>
      definition.metadata.name === task.taskRef.name
    );
    assert(definition, `missing Task: ${task.taskRef.name}`);
    const declared = definition.spec.params?.map((param) => param.name) ?? [];
    for (const param of task.params ?? []) {
      assert(
        declared.includes(param.name),
        `${task.name} passes undeclared parameter ${param.name}`,
      );
    }
    if (task.name.startsWith("deliver-")) {
      assert(
        task.when?.some((when) => when.input === "$(params.deliver-artifacts)"),
      );
    }
  }
});
