import { assert, assertEquals } from "jsr:@std/assert@1.0.11";
import { expandGlobSync } from "jsr:@std/fs@1.0.19/expand-glob";
import {
  filter,
  type Header,
  load,
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
