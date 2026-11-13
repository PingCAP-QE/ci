import {
  assert,
  assertEquals,
  assertFalse,
  assertMatch,
} from "jsr:@std/assert@1.0.11";
import { expandGlobSync } from "jsr:@std/fs@1.0.19/expand-glob";
import {
  evaluate,
  filter,
  type Header,
  load,
  payload,
  precedingExtensions,
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
  env?: { name: string; value?: string }[];
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

// The build step receives its request-derived values through env:, so a test
// that executes the step script must provide the same environment instead of
// substituting $(params.*) inside the script body.
function stepEnv(step: Step, values: Record<string, string>) {
  const env: Record<string, string> = {};
  for (const entry of step.env ?? []) {
    if (!entry.value) continue;
    let value = entry.value;
    for (const [from, to] of Object.entries(values)) {
      value = value.replaceAll(from, to);
    }
    env[entry.name] = value;
  }
  return env;
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
  // The version CLI runs from the release pipeline's credential scope, so it is
  // pinned to a commit rather than resolved from a branch at run time.
  assertMatch(
    compute.command.join(" "),
    /^deno run --allow-read --allow-write https:\/\/cdn\.jsdelivr\.net\/gh\/PingCAP-QE\/ci@[0-9a-f]{40}\/scripts\/flow\/build\/versioning-strategy\.ts$/,
  );
  assertEquals(
    compute.args.join(" "),
    "--git_version_file=/workspace/raw-version.txt --contain_branches_file=/workspace/branches.txt --git_ref=$(params.git-ref) --save_build_git_tag_file=/workspace/new-tag --save_release_version_file=$(results.version.path)",
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
  const build = task.spec.steps.find((step) => step.name === "build")!;
  let script = build.script.split(
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
        ["$(workspaces.git-credentials.path)", credentials],
        ["/workspace/serverless-git-credential", `${tmp}/git-credential`],
      ]
    ) script = script.replaceAll(from, to);
    // This test covers the rollback path: git-auth-app=false keeps using the
    // PAT workspace.
    const env = {
      ...stepEnv(build, {
        "$(params.git-ref)": "feature/test",
        "$(params.git-auth-app)": "false",
        "$(params.git-auth-home)": `${tmp}/app-home`,
        "$(workspaces.git-credentials.path)": credentials,
      }),
      GIT_CONFIG_NOSYSTEM: "1",
    };
    const legacy = script.replaceAll(
      "$(workspaces.git-credentials.bound)",
      "false",
    );
    const legacyHome = `${tmp}/legacy`;
    Deno.mkdirSync(legacyHome);
    command("sh", ["-c", legacy], {
      env: {
        ...env,
        HOME: legacyHome,
        GIT_CONFIG_GLOBAL: `${legacyHome}/.gitconfig`,
      },
    });
    assertEquals(optionalText(`${legacyHome}/.gitconfig`), undefined);
    script = script.replaceAll("$(workspaces.git-credentials.bound)", "true");
    script +=
      "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential fill\n";
    const runEnv = {
      ...env,
      HOME: tmp,
      GIT_CONFIG_GLOBAL: `${tmp}/.gitconfig`,
    };
    const output = command("sh", ["-c", script], { env: runEnv });
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
        command("git", ["ls-remote", "--get-url", remote], { env: runEnv })
          .trim(),
        "https://github.com/tidbcloud/private.git",
      );
    }
  } finally {
    Deno.removeSync(tmp, { recursive: true });
  }
});

Deno.test("git-auth-app installs the broker helper instead of the PAT workspace", () => {
  const task = load<{ spec: { steps: Step[] } }>(
    "tekton/v1/tasks/pingcap-build-binaries-linux.yaml",
  );
  const build = task.spec.steps.find((step) => step.name === "build")!;
  let script = build.script.split(
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
    // Simulate what the setup-git-auth step leaves in the shared home.
    const sharedHome = `${tmp}/shared-home`;
    Deno.mkdirSync(`${sharedHome}/.git-auth`, { recursive: true });
    const marker =
      `[credential "https://github.com"]\n\thelper =\n\thelper = ${sharedHome}/.git-auth/bin/git-credential\n`;
    Deno.writeTextFileSync(
      `${sharedHome}/.git-auth/gitconfig.fragment`,
      marker,
    );
    for (
      const [from, to] of [
        ["$(workspaces.git-credentials.path)", credentials],
        // The workspace stays bound in the rollback templates: app mode must
        // ignore it.
        ["$(workspaces.git-credentials.bound)", "true"],
      ]
    ) script = script.replaceAll(from, to);

    const home = `${tmp}/builder-home`;
    Deno.mkdirSync(home);
    command("sh", ["-c", script], {
      env: {
        ...stepEnv(build, {
          "$(params.git-ref)": "feature/test",
          "$(params.git-auth-app)": "true",
          "$(params.git-auth-home)": sharedHome,
          "$(workspaces.git-credentials.path)": credentials,
        }),
        HOME: home,
        GIT_CONFIG_NOSYSTEM: "1",
      },
    });
    const gitconfig = Deno.readTextFileSync(`${home}/.gitconfig`);
    assert(
      gitconfig.includes(`${sharedHome}/.git-auth/bin/git-credential`),
      gitconfig,
    );
    assertFalse(gitconfig.includes("test-password"));
    // The PAT helper script must not be generated in app mode.
    assertFalse(
      (() => {
        try {
          Deno.statSync(`${tmp}/git-credential`);
          return true;
        } catch {
          return false;
        }
      })(),
    );
  } finally {
    Deno.removeSync(tmp, { recursive: true });
  }
});

Deno.test("components without binary or image configuration keep the empty-result skip", () => {
  for (const kind of ["binaries-linux", "images"]) {
    const task = load<{ spec: { steps: Step[] } }>(
      `tekton/v1/tasks/pingcap-build-${kind}.yaml`,
    );
    const step = task.spec.steps.find((step) => step.name === "generate")!;
    const tmp = Deno.makeTempDirSync();
    try {
      Deno.mkdirSync(`${tmp}/artifacts/packages/scripts`, { recursive: true });
      const generator = kind === "images"
        ? "gen-package-images-with-config.sh"
        : "gen-package-artifacts-with-config.sh";
      Deno.writeTextFileSync(
        `${tmp}/artifacts/packages/scripts/${generator}`,
        "#!/bin/sh\nexit 0\n",
        { mode: 0o700 },
      );
      const script = step.script.replaceAll("/workspace/", `${tmp}/`)
        .replaceAll("$(step.results.generated.path)", `${tmp}/generated`)
        .replaceAll("$(step.results.commit-title.path)", `${tmp}/commit-title`)
        .replaceAll("$(step.results.commit-time.path)", `${tmp}/commit-time`)
        .replaceAll("$(workspaces.source.path)", `${tmp}/source`)
        .replaceAll(/\$\(params\.[^)]+\)/g, "test")
        // macOS /bin/sh prints -n literally; use the portable equivalent in this fixture.
        .replaceAll("echo -n ", 'printf "%s" ');
      // No network checkout: the fixture provides a generator with no matching output.
      command("sh", [
        "-c",
        "git() { :; }\n" + script,
        "generate-task",
        `${tmp}/pushed`,
      ]);
      assertEquals(Deno.readTextFileSync(`${tmp}/generated`), "false");
      assertEquals(Deno.readTextFileSync(`${tmp}/pushed`), '"{}"');
      // The image task also resolves commit metadata; without a resolvable
      // commit both results stay empty, which turns into "omit the label".
      if (kind === "images") {
        assertEquals(Deno.readTextFileSync(`${tmp}/commit-title`), "");
        assertEquals(Deno.readTextFileSync(`${tmp}/commit-time`), "");
      }
    } finally {
      Deno.removeSync(tmp, { recursive: true });
    }
  }
});

// The step that builds images runs inside the kaniko executor image, which
// ships no git, so the generate step resolves the commit metadata and hands it
// to the build step through results and environment variables.
Deno.test("image builds resolve the commit metadata where git is available", () => {
  const task = load<{ spec: { steps: Step[] } }>(
    "tekton/v1/tasks/pingcap-build-images.yaml",
  );
  const generate = task.spec.steps.find((step) => step.name === "generate")!;
  const build = task.spec.steps.find(
    (step) => step.name === "build-and-publish",
  )!;

  const buildEnv = Object.fromEntries(
    (build.env ?? []).map((entry) => [entry.name, entry.value ?? ""]),
  );
  assertEquals(buildEnv.COMMIT_TITLE, "$(steps.generate.results.commit-title)");
  assertEquals(buildEnv.COMMIT_TIME, "$(steps.generate.results.commit-time)");

  const tmp = Deno.makeTempDirSync();
  try {
    Deno.mkdirSync(`${tmp}/artifacts/packages/scripts`, { recursive: true });
    Deno.writeTextFileSync(
      `${tmp}/artifacts/packages/scripts/gen-package-images-with-config.sh`,
      "#!/bin/sh\nexit 0\n",
      { mode: 0o700 },
    );
    const script = generate.script.replaceAll("/workspace/", `${tmp}/`)
      .replaceAll("$(step.results.generated.path)", `${tmp}/generated`)
      .replaceAll("$(step.results.commit-title.path)", `${tmp}/commit-title`)
      .replaceAll("$(step.results.commit-time.path)", `${tmp}/commit-time`)
      .replaceAll("$(workspaces.source.path)", `${tmp}/source`)
      .replaceAll(/\$\(params\.[^)]+\)/g, "test")
      // macOS /bin/sh prints -n literally; use the portable equivalent in this fixture.
      .replaceAll("echo -n ", 'printf "%s" ');
    // Stand in for the checked-out repository. The title keeps its inner
    // spaces; the surrounding whitespace is what the step must trim.
    const gitStub = `git() {
  for arg in "$@"; do
    case "$arg" in
      --pretty=%s) printf '%s\\n' '  tests,cloud_worker: keep   worker configs  '; return 0 ;;
      --pretty=%cI) printf '%s\\n' '2026-11-09T11:13:02Z'; return 0 ;;
    esac
  done
  return 0
}
`;
    command("sh", ["-c", gitStub + script, "generate-task", `${tmp}/pushed`]);
    assertEquals(
      Deno.readTextFileSync(`${tmp}/commit-title`),
      "tests,cloud_worker: keep   worker configs",
    );
    assertEquals(
      Deno.readTextFileSync(`${tmp}/commit-time`),
      "2026-11-09T11:13:02Z",
    );
  } finally {
    Deno.removeSync(tmp, { recursive: true });
  }
});

const routeDirectory = "tekton/v1/triggers/triggers/env-gcp/_/fake-github/";
const cseRepositories = ["tidbcloud/cloud-storage-engine", "pingcap/ticse"];
function overlay(
  trigger: Trigger,
  key: string,
  body: ReturnType<typeof payload>,
  header: Header,
  field = "",
) {
  const expressions =
    trigger.spec.interceptors.slice(-1).flatMap((interceptor) =>
      interceptor.params
    )
      .find((param) => param.name === "overlays")!.value;
  assert(typeof expressions !== "string");
  const expression =
    expressions.find((overlay) => overlay.key === key)!.expression;
  return evaluate(
    `(${expression})${field ? `["${field}"]` : ""}`,
    body,
    header,
    precedingExtensions(trigger, body, header),
  );
}

Deno.test("shared Devbuild routes pin product/revision and select one platform run", async (t) => {
  const routes = [
    ...expandGlobSync(`${routeDirectory}*.yaml`, { root: root.pathname }),
  ]
    .map((entry) => ({
      path: entry.path,
      trigger: load<Trigger & { metadata: { labels: { type: string } } }>(
        entry.path,
      ),
    }));
  for (const product of products) {
    for (
      const [kind, event] of Object.entries({
        "branch-push": "push",
        "tag-create": "create",
        pr: "pull_request",
        commit: "push",
      })
    ) {
      for (const arch of ["amd64", "arm64"]) {
        await t.step(`${product}/${kind}/${arch}`, () => {
          const routeKind = kind === "commit" ? "branch-push" : kind;
          // Devbuild accepts only the allow-listed upstream repositories;
          // a renamed/arbitrary fork must not be selectable.
          const repo = `tidbcloud/${product}`;
          const body = payload(repo, routeKind);
          if (kind === "commit") body.ref = sha;
          // The tag name need not be a formal release version for Devbuild.
          if (kind === "tag-create") body.ref = "custom-build-tag";
          const header: Header = {
            "ce-type": `net.pingcap.tibuild.devbuild.${event}`,
            "ce-paramproduct": product,
            "ce-paramgitrevision": sha,
            "ce-paramplatform": `linux/${arch}`,
          };
          const trigger = load<Trigger>(
            `${routeDirectory}fake-github-${routeKind}-single-platform.yaml`,
          );
          assertEquals(
            trigger.spec.bindings?.find((binding) =>
              binding.name === "ce-context"
            )?.value,
            "$(extensions.ce-context)",
          );
          const candidates = routes.filter((route) =>
            route.trigger.metadata.labels.type ===
              `fake-github-${routeKind}`
          );
          assertEquals(
            candidates.filter((route) => filter(route.trigger, body, header))
              .length,
            1,
          );
          assertEquals(
            trigger.spec.template.ref,
            "build-component-single-platform",
          );
          assertEquals(
            overlay(trigger, "component", body, header),
            product,
          );
          assertEquals(
            overlay(trigger, "source", body, header, "revision"),
            sha,
          );
          assertEquals(
            overlay(trigger, "source", body, header, "refspec"),
            `+${sha}:refs/devbuild/source`,
          );

          assertEquals(
            overlay(trigger, "custom-params", body, header, "profile"),
            "release",
          );
          assertEquals(
            overlay(trigger, "registry", body, header),
            "us-docker.pkg.dev/pingcap-testing-account/dev",
          );
          assertEquals(
            overlay(
              trigger,
              "custom-params",
              body,
              header,
              "credentials-secret",
            ),
            "github-pat-secret",
          );
          assertEquals(
            overlay(trigger, "custom-params", body, header, "os"),
            "linux",
          );
          assertEquals(
            overlay(trigger, "custom-params", body, header, "arch"),
            arch,
          );
          assertEquals(
            overlay(
              trigger,
              "custom-params",
              body,
              header,
              "credentials-workspace",
            ),
            "git-credentials",
          );
          const resource =
            product === "tidb-cse" || product === "qdrant-on-tidb"
              ? "80Gi"
              : "30Gi";
          assertEquals(
            overlay(
              trigger,
              "resource-config",
              body,
              header,
              "sourceWsSize",
            ),
            resource,
          );
          for (
            const platform of ["linux", "darwin/amd64", "darwin/arm64"]
          ) {
            header["ce-paramplatform"] = platform;
            assertEquals(
              candidates.filter((route) => filter(route.trigger, body, header))
                .length,
              0,
            );
          }
          header["ce-paramplatform"] = `linux/${arch}`;
          header["ce-type"] = `net.pingcap.tibuild.hotfix.${event}`;
          assertEquals(
            candidates.filter((route) => filter(route.trigger, body, header))
              .length,
            0,
          );

          // A renamed / arbitrary fork keeps a different full name and is
          // rejected by the strict full_name allowlist.
          header["ce-type"] = `net.pingcap.tibuild.devbuild.${event}`;
          const renamedFork = payload("custom-owner/custom-fork", routeKind);
          if (kind === "commit") renamedFork.ref = sha;
          if (kind === "tag-create") renamedFork.ref = "custom-build-tag";
          assertEquals(
            candidates.filter((route) =>
              filter(route.trigger, renamedFork, header)
            ).length,
            0,
            `${product}/${kind}/${arch}: renamed fork must be rejected`,
          );
        });
      }
    }
  }
});

Deno.test("legacy canonical events and old products retain shared route behavior", () => {
  for (const kind of ["branch-push", "pr", "tag-create"]) {
    const trigger = load<Trigger>(
      `${routeDirectory}fake-github-${kind}-single-platform.yaml`,
    );
    const header: Header = {
      "ce-type": "net.pingcap.tibuild.devbuild.push",
      "ce-paramplatform": "linux/amd64",
    };
    for (const product of products) {
      const body = payload(`tidbcloud/${product}`, kind);
      header["ce-type"] = "net.pingcap.tibuild.devbuild." +
        ({
          "branch-push": "push",
          pr: "pull_request",
          "tag-create": "create",
        }[kind]);
      assert(filter(trigger, body, header));
      assertEquals(overlay(trigger, "component", body, header), product);
      assertEquals(
        overlay(trigger, "source", body, header, "revision"),
        kind === "tag-create" ? body.ref : sha,
      );
    }
    for (
      const [repo, component, timeout] of [
        ["pingcap/tidb", "tidb", "40m"],
        [
          "tidbcloud/cloud-storage-engine",
          "tikv",
          "2h30m",
        ],
        ["pingcap/ticse", "tikv", "2h30m"],
        [
          "tidbcloud/tiflash-cse",
          "tiflash",
          "2h",
        ],
      ]
    ) {
      const body = payload(repo, kind);
      header["ce-paramproduct"] = component;
      for (const engine of ["devbuild", "hotfix"]) {
        header["ce-type"] = `net.pingcap.tibuild.${engine}.push`;
        assert(filter(trigger, body, header));
        assertEquals(overlay(trigger, "component", body, header), component);
        assertEquals(
          overlay(trigger, "resource-config", body, header, "timeout"),
          timeout,
        );

        assertEquals(
          overlay(
            trigger,
            "custom-params",
            body,
            header,
            "credentials-secret",
          ),
          "mac-ssh-credentials",
        );
        assertEquals(
          overlay(trigger, "registry", body, header),
          `us-docker.pkg.dev/pingcap-testing-account/${
            engine === "hotfix" ? "hotfix" : "dev"
          }`,
        );
        assertEquals(
          overlay(trigger, "source", body, header, "revision"),
          kind === "tag-create" ? body.ref : sha,
        );
        assertEquals(
          overlay(trigger, "source", body, header, "ref"),
          kind === "pr"
            ? "main"
            : kind === "branch-push"
            ? "feature/test"
            : body.ref,
        );
      }
    }
    delete header["ce-paramproduct"];
  }
});

Deno.test("old products retain multi-platform and Darwin routing", () => {
  for (const kind of ["branch-push", "pr", "tag-create"]) {
    const routes = [
      ...expandGlobSync(`${routeDirectory}fake-github-${kind}*.yaml`, {
        root: root.pathname,
      }),
    ]
      .map((entry) => load<Trigger>(entry.path));
    for (
      const [repo, product, expected] of [
        // Allow-listed upstream repositories keep multi-platform/Darwin routing.
        ["pingcap/tidb", "tidb", 1],
        ["tidbcloud/cloud-storage-engine", "tikv", 1],
        ["pingcap/ticse", "tikv", 1],
        // Renamed / arbitrary forks are rejected: the allowlist keys on full_name.
        ["custom-owner/custom-fork", "tidb", 0],
        ["custom-owner/tidb", "tidb", 0],
      ] as const
    ) {
      const body = payload(repo, kind);
      const header: Header = {
        "ce-type": "net.pingcap.tibuild.devbuild.push",
        "ce-paramproduct": product,
      };
      for (
        const platform of [
          "linux/amd64",
          "linux/arm64",
          "linux",
          "darwin/amd64",
          "darwin/arm64",
        ]
      ) {
        header["ce-paramplatform"] = platform;
        assertEquals(
          routes.filter((route) => filter(route, body, header)).length,
          expected,
          `${kind}/${repo}/${platform}`,
        );
      }
    }
  }
});

Deno.test("TiCSE keeps both source identities and TiKV manual-build contracts", async (t) => {
  for (const kind of ["branch-push", "pr", "tag-create"]) {
    const routes = ["", "-single-platform", "-darwin"].map((suffix) =>
      load<Trigger>(`${routeDirectory}fake-github-${kind}${suffix}.yaml`)
    );
    for (const repo of cseRepositories) {
      const body = payload(repo, kind);
      for (const engine of ["devbuild", "hotfix"]) {
        for (const product of ["", "tikv", repo.split("/")[1]]) {
          for (
            const platform of [
              "",
              "linux",
              "linux/amd64",
              "linux/arm64",
              "darwin/amd64",
              "darwin/arm64",
            ]
          ) {
            await t.step(
              `${repo}/${kind}/${engine}/${product}/${platform}`,
              () => {
                const header: Header = {
                  "ce-type": `net.pingcap.tibuild.${engine}.${
                    {
                      "branch-push": "push",
                      pr: "pull_request",
                      "tag-create": "create",
                    }[kind]
                  }`,
                  "ce-paramproduct": product,
                  "ce-paramplatform": platform,
                  // CSE source identity and revision remain payload-derived.
                  "ce-paramgithubrepo": "custom-owner/custom-fork",
                  "ce-paramgitrevision": "f".repeat(40),
                };
                const selected = routes.filter((route) =>
                  filter(route, body, header)
                );
                assertEquals(selected.length, 1);
                const trigger = selected[0];
                const expectedTemplate = platform.startsWith("linux/")
                  ? "build-component-single-platform"
                  : platform.startsWith("darwin/")
                  ? "build-component-darwin-mac"
                  : "build-component-all-platforms-mac";
                assertEquals(trigger.spec.template.ref, expectedTemplate);
                assertEquals(
                  overlay(trigger, "component", body, header),
                  "tikv",
                );
                for (
                  const [field, expected] of Object.entries({
                    timeout: "2h30m",
                    sourceWsSize: "100Gi",
                    cpu: "12",
                    memory: "48Gi",
                  })
                ) {
                  assertEquals(
                    overlay(trigger, "resource-config", body, header, field),
                    expected,
                  );
                }
                assertEquals(
                  overlay(trigger, "registry", body, header),
                  `us-docker.pkg.dev/pingcap-testing-account/${
                    engine === "hotfix" ? "hotfix" : "dev"
                  }`,
                );
                for (
                  const [profile, expected] of [["", "release"], [
                    "community",
                    "release",
                  ], ["nextgen", "nextgen"]]
                ) {
                  header["ce-paramprofile"] = profile;
                  assertEquals(
                    overlay(trigger, "custom-params", body, header, "profile"),
                    expected,
                  );
                }
                if (platform.startsWith("linux/")) {
                  assertEquals(
                    overlay(trigger, "source", body, header, "revision"),
                    kind === "tag-create" ? body.ref : sha,
                  );
                }
                const params = trigger.spec.bindings!;
                const shared = params.find((param) =>
                  param.ref === `github-${kind}`
                );
                const gitUrl = shared
                  ? load<{ spec: { params: Param[] } }>(
                    `tekton/v1/triggers/bindings/${shared.ref}.yaml`,
                  ).spec.params.find((param) => param.name === "git-url")?.value
                  : params.find((param) => param.name === "git-url")?.value;
                assertEquals(
                  gitUrl,
                  "https://github.com/$(body.repository.full_name).git",
                );
                if (shared) {
                  const revision = load<{ spec: { params: Param[] } }>(
                    `tekton/v1/triggers/bindings/${shared.ref}.yaml`,
                  ).spec.params.find((param) => param.name === "git-revision")
                    ?.value;
                  assertEquals(
                    revision,
                    kind === "branch-push"
                      ? "$(body.after)"
                      : kind === "pr"
                      ? "$(body.pull_request.head.sha)"
                      : "$(body.ref)",
                  );
                }
                header["ce-paramproduct"] = "tidb";
                assertEquals(
                  routes.filter((route) => filter(route, body, header)).length,
                  0,
                );
              },
            );
          }
        }
      }
    }
    const header: Header = {
      "ce-paramproduct": "tikv",
      "ce-paramgithubrepo": "pingcap/ticse",
    };
    for (
      const repo of [
        "pingcap/cloud-storage-engine",
        "tidbcloud/ticse",
        "custom-owner/ticse",
        "dillon-zheng/cse-migration",
      ]
    ) {
      for (const platform of ["", "linux/amd64", "darwin/arm64"]) {
        header["ce-paramplatform"] = platform;
        assertEquals(
          routes.filter((route) => filter(route, payload(repo, kind), header))
            .length,
          0,
          repo,
        );
      }
    }
  }
});

Deno.test("TiCSE formal routes preserve branches, tags and the version placeholder", () => {
  const directory = "tekton/v1/triggers/triggers/env-gcp/_/";
  const push = load<Trigger>(`${directory}git-push-branch-build-ng.yaml`);
  const tag = load<Trigger>(`${directory}git-create-tag-build-ng.yaml`);
  const branch = load<Trigger>(`${directory}git-create-branch-ng.yaml`);
  for (const repo of cseRepositories) {
    const body = payload(repo, "branch-push");
    for (
      const name of [
        "cloud-engine",
        "dedicated",
        "release-nextgen-202609",
        "release-nextgen-26.3.21-20261111",
      ]
    ) {
      body.ref = `refs/heads/${name}`;
      assert(filter(push, body), `${repo}/${name}`);
    }
    for (const name of ["master", "main", "feature/test"]) {
      body.ref = `refs/heads/${name}`;
      assertFalse(filter(push, body), `${repo}/${name}`);
    }
    for (const version of ["v26.3.21", "v8.5.4-nextgen.202510.1"]) {
      body.ref = version;
      assert(filter(tag, body), `${repo}/${version}`);
    }
    for (const version of ["v8.5.4", "custom-build-tag", "v26.3.21-rc.1"]) {
      body.ref = version;
      assertFalse(filter(tag, body), `${repo}/${version}`);
    }
    for (const trigger of [push, tag]) {
      assertEquals(overlay(trigger, "component", body, {}), "tikv");
      assertEquals(overlay(trigger, "profile", body, {}), "nextgen");
      for (
        const [field, expected] of Object.entries({
          timeout: "30m",
          sourceWsSize: "100Gi",
          cpu: "12",
          memory: "48Gi",
        })
      ) {
        assertEquals(
          overlay(trigger, "resource-config", body, {}, field),
          expected,
        );
      }
      assertEquals(trigger.spec.template.ref, "build-component-linux");
    }
    for (
      const name of [
        "release-nextgen-202609",
        "release-nextgen-26.3.21-20261111",
      ]
    ) {
      body.ref = name;
      assert(filter(branch, body), `${repo}/${name}`);
    }
    body.ref = "cloud-engine";
    assertFalse(filter(branch, body));
    assertEquals(
      branch.spec.template.ref,
      "bump-placeholder-version-in-readme",
    );
  }
  const formalParams = load<{ spec: { params: Param[] } }>(
    "tekton/v1/triggers/bindings/gcp-ng-build-params.yaml",
  );
  assertEquals(
    formalParams.spec.params.find((param) => param.name === "registry")?.value,
    "us-docker.pkg.dev/pingcap-testing-account/tidbx",
  );
  for (
    const repo of [
      "pingcap/cloud-storage-engine",
      "tidbcloud/ticse",
      "custom-owner/ticse",
      "dillon-zheng/cse-migration",
    ]
  ) {
    const body = payload(repo, "branch-push");
    body.ref = "refs/heads/cloud-engine";
    assertFalse(filter(push, body));
    body.ref = "v26.3.21";
    assertFalse(filter(tag, body));
    body.ref = "release-nextgen-202609";
    assertFalse(filter(branch, body));
  }
});

Deno.test("template run counts and existing Pipeline/Task contracts are preserved", () => {
  for (
    const [name, count] of [["component-single-platform", 1], [
      "serverless-linux",
      2,
    ]] as const
  ) {
    const template = load<
      {
        spec: {
          params: { name: string; default?: string }[];
          resourcetemplates: {
            metadata: { annotations: Record<string, string> };
            spec: {
              params: Param[];
              workspaces: { name: string; secret?: { secretName: string } }[];
            };
          }[];
        };
      }
    >(`tekton/v1/triggers/templates/_/build-${name}.yaml`);
    assertEquals(template.spec.resourcetemplates.length, count);
    if (name === "component-single-platform") {
      const defaults = Object.fromEntries(
        template.spec.params.map((param) => [param.name, param.default]),
      );

      assertEquals(defaults["credentials-secret"], "mac-ssh-credentials");
      assertEquals(defaults["credentials-workspace"], "mac-ssh-credentials");
    }
    for (const run of template.spec.resourcetemplates) {
      assertEquals(
        run.metadata.annotations["tekton.dev/ce-context"],
        "$(tt.params.ce-context)",
      );
      const params = Object.fromEntries(
        run.spec.params.map((param) => [param.name, param.value]),
      );

      assertFalse("deliver-artifacts" in params);
      assertFalse("require-artifacts" in params);
      const workspaces = run.spec.workspaces.map((workspace) => workspace.name);
      if (name === "serverless-linux") {
        assert(workspaces.includes("git-credentials"));
        assertFalse(workspaces.includes("mac-ssh-credentials"));
      } else {
        assert(workspaces.includes("$(tt.params.credentials-workspace)"));
        assertEquals(
          run.spec.workspaces.find((workspace) =>
            workspace.name === "$(tt.params.credentials-workspace)"
          )?.secret?.secretName,
          "$(tt.params.credentials-secret)",
        );
      }
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
      "git-auth-mode": "$(tasks.checkout.results.git-auth-mode)",
      "pushed-binaries": "$(tasks.build-binaries.results.pushed)",
      "pushed-images": "$(tasks.build-images.results.pushed)",
    },
  );
  for (const param of pipeline.spec.params) {
    assertFalse(
      [
        "artifacts-revision",
        "versioning-strategy-url",
        "deliver-artifacts",
        "require-artifacts",
      ].includes(param.name),
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
      assertEquals(task.when?.length, task.name === "deliver-binaries" ? 2 : 1);
    }
  }
});

Deno.test("Serverless skips the binary delivery task and keeps legacy delivery", async (t) => {
  const pipeline = load<{
    spec: {
      tasks: {
        name: string;
        when?: { input: string; operator: string; values: string[] }[];
      }[];
    };
  }>("tekton/v1/pipelines/pingcap-build-package-linux.yaml");
  const delivery = pipeline.spec.tasks.find((task) =>
    task.name === "deliver-binaries"
  )!;
  const matches = (component: string, pushed: string) =>
    delivery.when!.every((condition) => {
      const input = condition.input
        .replace("$(params.component)", component)
        .replace("$(tasks.build-binaries.results.pushed)", pushed);
      assertEquals(condition.operator, "notin");
      return !condition.values.includes(input);
    });
  for (const product of products) {
    await t.step(product, () => {
      for (const registry of ["serverless", "dev"]) {
        const pushed =
          `oci:\n  repo: us-docker.pkg.dev/pingcap-testing-account/${registry}/tidbcloud/${product}/package\n  tag: main-0123456_linux_amd64`;
        assertFalse(matches(product, pushed));
      }
    });
  }
  for (const component of ["tidb", "pd", "tikv", "tiflow", "tiflash"]) {
    await t.step(component, () => {
      assert(
        matches(
          component,
          "oci:\n  repo: test/package\n  tag: v8.5.3_linux_amd64",
        ),
      );
      assertFalse(matches(component, "{}"));
      assertFalse(matches(component, '"{}"'));
    });
  }
});

Deno.test("builder image override is only shape-checked inside the cluster", () => {
  // Which builder images a caller may force is product policy, decided by the
  // devbuild API where the request enters the system. Down here the value only
  // has to survive being used as a step `image:`, so upstream images pinned by
  // the artifacts config must pass while anything that could break out of the
  // field must not.
  const task = load<{ spec: { steps: Step[] } }>(
    "tekton/v1/tasks/pingcap-get-builder-image.yaml",
  );
  const get = task.spec.steps.find((step) => step.name === "get")!;
  const tmp = Deno.makeTempDirSync();
  try {
    const resultPath = `${tmp}/image-url`;
    const run = (value: string) =>
      command("sh", ["-c", get.script], {
        env: stepEnv(get, {
          "$(params.force-builder-image)": value,
          "$(results.image-url.path)": resultPath,
        }),
      });
    const rejected = (value: string) => {
      const result = new Deno.Command("sh", {
        args: ["-c", get.script],
        env: stepEnv(get, {
          "$(params.force-builder-image)": value,
          "$(results.image-url.path)": resultPath,
        }),
        stdout: "piped",
        stderr: "piped",
      }).outputSync();
      return !result.success;
    };

    for (
      const image of [
        // Serverless components build with the upstream images the artifacts
        // config pins, so a value allowlist here would reject them.
        "docker.io/library/golang:1.24.2-bullseye@sha256:f50ff25f8331682b44c1582974eb9e620fcb08052fc6ed434f93ca24636fc4d6",
        "public.ecr.aws/amazonlinux/amazonlinux:2023.5.20240805.0@sha256:03c807fdc5d40ab06b21c2e6d158c63c37d5149a127b7fdfa9e3c1150352ff20",
        "ghcr.io/pingcap-qe/cd/utils/release:v2025.10.12-7-gfdd779c",
      ]
    ) {
      assertEquals(run(image), `use force builder image: ${image}\n`);
      assertEquals(Deno.readTextFileSync(resultPath), image);
    }
    for (
      const image of [
        "docker.io/library/golang:1.24.2\nmalicious",
        "docker.io/library/golang:1.24.2 --entrypoint=/bin/sh",
        "golang:1.24.2; rm -rf /",
      ]
    ) {
      assert(rejected(image), `must reject ${JSON.stringify(image)}`);
    }
  } finally {
    Deno.removeSync(tmp, { recursive: true });
  }
});
