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

// These tests lock down the trigger-plane security properties:
//   * a request header can never waive or replace a repository allowlist;
//   * the clone URL is derived from the allowlisted repository.full_name,
//     never from the request-supplied repository.clone_url;
//   * every Trigger that accepts forwarded GitHub webhooks verifies the
//     GitHub HMAC signature with the el-github-webhook secret.

type NamedTrigger = Trigger & {
  metadata: { name: string; labels: Record<string, string> };
};

const triggerDirectory = "tekton/v1/triggers/triggers/env-gcp/";
const fakeGithubDirectory =
  "tekton/v1/triggers/triggers/env-gcp/_/fake-github/";

const GITHUB_EVENT_TYPES = [
  "github-pr",
  "github-pr-review",
  "github-branch-push",
  "github-branch-create",
  "github-tag-create",
  "github-issue-comment",
];

function yamlFiles(directory: string, pattern = "**/*.yaml"): string[] {
  return [...expandGlobSync(`${directory}${pattern}`, { root: root.pathname })]
    .filter((entry) => !entry.path.endsWith("kustomization.yaml"))
    .map((entry) => entry.path)
    .sort();
}

function documents(path: string): string[] {
  return Deno.readTextFileSync(new URL(path, root)).split(/^---$/m);
}

function triggerDocumentChunks(): { path: string; chunk: string }[] {
  return yamlFiles(triggerDirectory).flatMap((path) =>
    documents(path)
      .filter((chunk) => /^kind: Trigger$/m.test(chunk))
      .map((chunk) => ({ path, chunk }))
  );
}

Deno.test("trigger plane never binds the request-supplied clone_url", () => {
  for (const path of yamlFiles(triggerDirectory)) {
    assert(
      !Deno.readTextFileSync(new URL(path, root)).includes(
        "body.repository.clone_url",
      ),
      `${path} must not bind repository.clone_url`,
    );
  }
  for (const name of [
    "github-branch-push.yaml",
    "github-pr.yaml",
    "github-tag-create.yaml",
    "github-branch-create.yaml",
  ]) {
    const binding = load<{
      spec: { params: { name: string; value: string }[] };
    }>(`tekton/v1/triggers/bindings/${name}`);
    const gitUrl = binding.spec.params.find((param) => param.name === "git-url");
    assertEquals(
      gitUrl?.value,
      "https://github.com/$(body.repository.full_name).git",
      `${name} must derive git-url from the allowlisted repository.full_name`,
    );
  }
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
        value.includes("body.repository.full_name") &&
          value.includes("body.repository.name in"),
        `${path} is missing the body-derived allowlist`,
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
  // ...while a fork of an allowlisted component keeps working (devbuild
  // intentionally builds forks; the fork keeps the upstream repository name).
  const fork = payload("custom-owner/tidb", "branch-push");
  const branchPushHeaders: Header = {
    "ce-type": "net.pingcap.tibuild.devbuild.push",
    "ce-paramplatform": "linux/amd64",
  };
  assert(
    routes.some((trigger) => filter(trigger, fork, branchPushHeaders)),
    "a fork of an allowlisted component must still build",
  );
});

Deno.test("github webhook triggers verify the HMAC signature", () => {
  const offenders: string[] = [];
  for (const { path, chunk } of triggerDocumentChunks()) {
    const type = /^\s+type: (github-[a-z-]+)$/m.exec(chunk)?.[1];
    if (!type || !GITHUB_EVENT_TYPES.includes(type)) continue;
    if (
      !chunk.includes("ref: { name: github }") ||
      !chunk.includes("secretName: el-github-webhook") ||
      !chunk.includes("secretKey: secret")
    ) {
      offenders.push(`${path}: ${type}`);
    }
  }
  assertEquals(
    offenders,
    [],
    "every webhook Trigger must verify the GitHub HMAC signature",
  );
});

Deno.test("unused harbor-image-push binding stays deleted", () => {
  const kustomization = load<{ resources: string[] }>(
    "tekton/v1/triggers/bindings/kustomization.yaml",
  );
  assertEquals(
    kustomization.resources.includes("harbor-image-push.yaml"),
    false,
  );
  const referenced = yamlFiles(triggerDirectory).filter((path) =>
    Deno.readTextFileSync(new URL(path, root)).includes("ref: harbor-image-push")
  );
  assertEquals(referenced, []);
});
