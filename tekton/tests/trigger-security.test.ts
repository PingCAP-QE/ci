import { assert, assertEquals } from "jsr:@std/assert@1.0.11";
import { expandGlobSync } from "jsr:@std/fs@1.0.19/expand-glob";
import {
  filter,
  type Header,
  load,
  overlay,
  payload,
  root,
  type Trigger,
} from "./trigger-test-utils.ts";

// These tests lock down the devbuild (fake-github) repository trust boundary:
//   * a request header can never waive or replace a repository allowlist;
//   * the allowlist keys on body.repository.full_name, because a repository
//     name is not unique across organisations;
//   * a forged CloudEvent cannot select an arbitrary repository;
//   * the clone URL is derived from the allowlisted full_name.
//
// GitHub webhook HMAC verification is deliberately NOT tested here: those
// EventListeners (and the `github` interceptor) are deployed from
// PingCAP-QE/ee-ops, see the security model in tekton/v1/triggers/README.md.

type NamedTrigger = Trigger & {
  metadata: { name: string; labels: Record<string, string> };
};

const triggerDirectory = "tekton/v1/triggers/triggers/env-gcp/";
const fakeGithubDirectory =
  "tekton/v1/triggers/triggers/env-gcp/_/fake-github/";

function yamlFiles(directory: string, pattern = "**/*.yaml"): string[] {
  return [...expandGlobSync(`${directory}${pattern}`, { root: root.pathname })]
    .filter((entry) => !entry.path.endsWith("kustomization.yaml"))
    .map((entry) => entry.path)
    .sort();
}

// GitHub delivers branch creation and tag creation as the same `create` event;
// `body.ref_type` is the only discriminator, and `body.ref` carries the bare
// name in both cases. A trigger that filters only on the name therefore fires for
// a branch whose name happens to look like a release tag.
Deno.test("every create-event trigger checks body.ref_type", () => {
  let tagTriggers = 0;
  let branchTriggers = 0;

  for (const path of yamlFiles(triggerDirectory)) {
    const text = Deno.readTextFileSync(path);
    if (text.includes("type: github-tag-create")) {
      tagTriggers++;
      assert(
        text.includes("body.ref_type == 'tag'"),
        `${path}: a tag-creation trigger must require body.ref_type == 'tag'`,
      );
    }
    if (text.includes("type: github-branch-create")) {
      branchTriggers++;
      assert(
        text.includes("body.ref_type == 'branch'"),
        `${path}: a branch-creation trigger must require body.ref_type == 'branch'`,
      );
    }
  }

  assert(tagTriggers > 0, "expected tag-creation triggers to exist");
  assert(branchTriggers > 0, "expected branch-creation triggers to exist");
});

Deno.test("a branch named like a release tag does not drive a release build", () => {
  const tagTrigger = load<NamedTrigger>(
    `${triggerDirectory}pingcap/tiproxy/git-create-tag.yaml`,
  );
  const tagCreation = payload("pingcap/tiproxy", "tag-create");
  assertEquals(filter(tagTrigger, tagCreation), true);

  // Same repository, same tag-shaped name, but the event is a branch creation.
  assertEquals(
    filter(tagTrigger, { ...tagCreation, ref_type: "branch" }),
    false,
  );

  const branchTrigger = load<NamedTrigger>(
    `${triggerDirectory}tikv/tikv/git-create-branch.yaml`,
  );
  const branchCreation = payload("tikv/tikv", "push");
  branchCreation.repository = {
    ...(branchCreation.repository as Record<string, unknown>),
    owner: { login: "tikv" },
  };
  branchCreation.ref = "release-8.5";
  branchCreation.ref_type = "branch";
  assertEquals(filter(branchTrigger, branchCreation), true);
  assertEquals(
    filter(branchTrigger, { ...branchCreation, ref_type: "tag" }),
    false,
  );
});

Deno.test("request headers cannot waive a repository allowlist", () => {
  // The devbuild routes used to accept ce-paramGithubRepo as proof that the
  // request came from an allowlisted repository, which waived the allowlist
  // entirely. No trigger may read that header any more.
  for (const path of yamlFiles(triggerDirectory)) {
    const text = Deno.readTextFileSync(new URL(path, root));
    assertEquals(
      /ce-paramgithubrepo/i.test(text),
      false,
      `${path} must not use the ce-paramGithubRepo header`,
    );
  }
  // Every fake-github (devbuild) route derives its allowlist from the body.
  for (const path of yamlFiles(fakeGithubDirectory, "*.yaml")) {
    const trigger = load<NamedTrigger>(path);
    const repoFilters = trigger.spec.interceptors.flatMap((interceptor) =>
      interceptor.params
        .filter((param) => param.name === "filter")
        .map((param) => String(param.value))
        .filter((value) => value.includes("body.repository"))
    );
    assert(repoFilters.length > 0, `${path} must filter on body.repository.*`);
    for (const value of repoFilters) {
      assert(
        value.includes("body.repository.full_name in ["),
        `${path} must keep a strict repository.full_name allowlist`,
      );
      // A repository name is not a unique key across organisations, so it
      // must never be the allowlist key.
      assert(
        !value.includes("body.repository.name in ["),
        `${path} must not key the repository allowlist on repository.name`,
      );
    }
  }
});

Deno.test("a forged devbuild request cannot select an arbitrary repository", () => {
  const routes = yamlFiles(fakeGithubDirectory, "*.yaml").map((path) =>
    load<NamedTrigger>(path)
  );
  const forgedHeaders: Header[] = [
    {
      "ce-type": "net.pingcap.tibuild.devbuild.push",
      "ce-paramplatform": "linux/amd64",
      "ce-paramgithubrepo": "pingcap/tidb",
      "ce-paramproduct": "tidb",
    },
    {
      "ce-type": "net.pingcap.tibuild.devbuild.push",
      "ce-paramplatform": "linux/amd64",
      "ce-paramgithubrepo": "anything/at-all",
    },
  ];
  for (const kind of ["branch-push", "pr", "tag-create"]) {
    const body = payload("attacker/evil", kind);
    if (kind === "tag-create") body.ref = "v1.2.3";
    for (const trigger of routes) {
      for (const header of forgedHeaders) {
        assertEquals(
          filter(trigger, body, header),
          false,
          `${trigger.metadata.name} accepted attacker/evil with ${
            JSON.stringify(header)
          }`,
        );
      }
    }
  }
  // ...and a renamed / arbitrary fork is rejected too, even when it reuses an
  // allowlisted repository name: the allowlist keys on full_name, because a
  // repository name is not unique across organisations. Renamed/temporary fork
  // devbuild is intentionally blocked until the tibuild transport is
  // authenticated (follow-up task-9); do not restore it by matching on name.
  const branchPushHeaders: Header = {
    "ce-type": "net.pingcap.tibuild.devbuild.push",
    "ce-paramplatform": "linux/amd64",
  };
  for (const repo of ["custom-owner/custom-fork", "custom-owner/tidb"]) {
    const fork = payload(repo, "branch-push");
    assertEquals(
      routes.filter((trigger) => filter(trigger, fork, branchPushHeaders)).length,
      0,
      `${repo} must not be selectable`,
    );
  }
  // ...while the allow-listed upstream repositories keep working.
  const upstream = payload("pingcap/tidb", "branch-push");
  assert(
    routes.some((trigger) => filter(trigger, upstream, branchPushHeaders)),
    "an allow-listed upstream repository must still build",
  );
});

Deno.test("devbuild routes derive git-url from the allowlisted full_name", () => {
  for (const path of yamlFiles(fakeGithubDirectory, "*.yaml")) {
    const text = Deno.readTextFileSync(new URL(path, root));
    assert(
      !text.includes("repository.clone_url"),
      `${path} must not bind the request-supplied clone_url`,
    );
  }
  // The single-platform routes declare their bindings inline.
  for (const kind of ["branch-push", "pr", "tag-create"]) {
    const path = `${fakeGithubDirectory}fake-github-${kind}-single-platform.yaml`;
    const trigger = load<{
      spec: { bindings?: { name?: string; value?: string }[] };
    }>(path);
    const gitUrl = trigger.spec.bindings?.find((binding) =>
      binding.name === "git-url"
    );
    assertEquals(
      gitUrl?.value,
      "https://github.com/$(body.repository.full_name).git",
      `${path} must derive git-url from repository.full_name`,
    );
  }
});

// tibuild labels a component that legitimately lives in a repository with a
// different name — `ticdc-newarch` in pingcap/ticdc, `br` / `dumpling` /
// `tidb-lightning` in pingcap/tidb, `drainer` / `pump` in pingcap/tidb-binlog,
// `dm` in pingcap/tiflow — and a caller may pair a component with an explicit
// repository. The component is derived from the allow-listed
// `body.repository.full_name`, so the product header cannot change what is
// built; requiring it to equal the repository name only dropped those
// legitimate builds (2026-11-06 .. 2026-11-13, see the README).
//
// The Serverless routes are the exception: there the product *is* the
// component, so it must still name the Serverless repository.
Deno.test("ce-paramProduct cannot select the component or the repository", () => {
  const eventTypes: Record<string, string> = {
    "branch-push": "push",
    pr: "pull_request",
    "tag-create": "create",
  };
  const cases: [string, string, string][] = [
    ["pingcap/ticdc", "ticdc-newarch", "ticdc"],
    ["pingcap/ticdc", "ticdc", "ticdc"],
    ["pingcap/tidb", "br", "tidb"],
    ["pingcap/tidb", "dumpling", "tidb"],
    ["pingcap/tidb", "tidb-lightning", "tidb"],
    ["pingcap/tidb-binlog", "drainer", "tidb-binlog"],
    ["pingcap/tidb-binlog", "pump", "tidb-binlog"],
    ["pingcap/tiflow", "dm", "tiflow"],
    // A product may not hijack a component from another repository either.
    ["tikv/pd", "tidb", "pd"],
    ["tidbcloud/cloud-storage-engine", "tikv", "tikv"],
  ];
  // The event type selects the trigger group, so only one kind of route is
  // ever in play for a single event.
  for (const kind of ["branch-push", "pr", "tag-create"]) {
    const routes = ["", "-single-platform", "-darwin"].map((suffix) =>
      load<NamedTrigger>(
        `${fakeGithubDirectory}fake-github-${kind}${suffix}.yaml`,
      )
    );
    for (const [repo, product, component] of cases) {
      for (const platform of ["linux/amd64", "darwin/arm64", ""]) {
        const body = payload(repo, kind);
        const header: Header = {
          "ce-type": `net.pingcap.tibuild.devbuild.${eventTypes[kind]}`,
          "ce-paramproduct": product,
        };
        if (platform) header["ce-paramplatform"] = platform;
        const matched = routes.filter((route) => filter(route, body, header));
        assertEquals(
          matched.length,
          1,
          `${repo} + ${product} (${platform || "all"}) must match one route`,
        );
        assertEquals(
          overlay(matched[0], "component", body, header),
          component,
          `${repo} + ${product}: the component comes from the repository`,
        );
      }
    }
  }
});

Deno.test("a Serverless product still names its own repository", () => {
  const routes = ["", "-single-platform", "-darwin"].map((suffix) =>
    load<NamedTrigger>(`${fakeGithubDirectory}fake-github-branch-push${suffix}.yaml`)
  );
  const header: Header = {
    "ce-type": "net.pingcap.tibuild.devbuild.push",
    "ce-paramplatform": "linux/amd64",
    "ce-paramproduct": "tidb-cse",
  };
  assertEquals(
    routes.filter((route) => filter(route, payload("pingcap/ticdc", "branch-push"), header))
      .length,
    0,
    "a Serverless product must not route a repository it does not name",
  );
  const body = payload("tidbcloud/tidb-cse", "branch-push");
  const matched = routes.filter((route) => filter(route, body, header));
  assertEquals(matched.length, 1);
  assertEquals(
    overlay(matched[0], "component", body, header),
    "tidb-cse",
    "the Serverless route takes its component from the product",
  );
});
