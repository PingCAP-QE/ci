import { assert, assertEquals, assertFalse } from "jsr:@std/assert@1.0.11";
import {
  evaluate,
  filter,
  load,
  payload,
  products,
  type Trigger,
} from "./trigger-test-utils.ts";

const directory = "tekton/v1/triggers/triggers/env-gcp/tidbcloud/serverless/";

Deno.test("formal routes only accept maintained sources, branches and version tags", async (t) => {
  const push = load<Trigger>(`${directory}branch-push.yaml`);
  const tag = load<Trigger>(`${directory}tag-create.yaml`);
  const branches = [
    "main-8.5-keyspace",
    "release-8.5-keyspace",
    "serverless-on-release-1.4",
    "master",
    "main",
  ];
  for (const [index, product] of products.entries()) {
    await t.step(product, () => {
      const body = payload(`tidbcloud/${product}`, "branch-push");
      body.ref = `refs/heads/${branches[index]}`;
      assert(filter(push, body));
      body.deleted = true;
      assertFalse(filter(push, body));
      body.deleted = false;
      body.ref = "refs/heads/feature/unapproved";
      assertFalse(filter(push, body));
      const release = payload(`tidbcloud/${product}`, "tag-create");
      for (const version of ["v1.2.3", "v1.2.3-alpha.0", "v1.2.3-rc.1"]) {
        release.ref = version;
        assert(filter(tag, release));
      }
      release.ref = "production-latest";
      assertFalse(filter(tag, release));
      release.ref = "v1.2.3";
      release.ref_type = "branch";
      assertFalse(filter(tag, release));
      for (const trigger of [push, tag]) {
        const overlays = trigger.spec.interceptors.flatMap((interceptor) =>
          interceptor.params
        )
          .find((param) => param.name === "overlays")!.value;
        assert(typeof overlays !== "string");
        for (const overlay of overlays) evaluate(overlay.expression, body);
      }
    });
  }
  for (const date of ["20260206", "202603", "202604", "202605", "202607"]) {
    const body = payload("tidbcloud/tidb-cse", "branch-push");
    body.ref = `refs/heads/release-8.5-keyspace-${date}`;
    assert(filter(push, body));
  }
  for (
    const repo of [
      "tidbcloud/tikv",
      "tidbcloud/tiflash-cse",
      "tidbcloud/qdrant-prod-us-east-1",
    ]
  ) {
    assertFalse(filter(tag, payload(repo, "tag-create")));
    assertFalse(filter(push, payload(repo, "branch-push")));
  }
});

Deno.test("formal activation references the deployed routes and formal registry", () => {
  const kustomization = load<{ resources: string[] }>(
    "tekton/v1/triggers/triggers/env-gcp/kustomization.yaml",
  );
  for (const file of ["branch-push.yaml", "tag-create.yaml"]) {
    assertEquals(
      kustomization.resources.filter((resource) =>
        resource === `tidbcloud/serverless/${file}`
      ).length,
      1,
    );
    const trigger = load<
      Trigger & { spec: { bindings: { name?: string; value?: string }[] } }
    >(`${directory}${file}`);
    assertEquals(trigger.spec.template.ref, "build-serverless-linux");
    assertEquals(
      trigger.spec.bindings.find((binding) => binding.name === "registry")
        ?.value,
      "us-docker.pkg.dev/pingcap-testing-account/serverless",
    );
  }
});
