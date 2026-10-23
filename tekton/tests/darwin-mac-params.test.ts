import { assert, assertEquals } from "jsr:@std/assert@1.0.11";
import { parseAll } from "jsr:@std/yaml@1.0.9";
import { load, root } from "./trigger-test-utils.ts";

const darwinTemplate =
  "tekton/v1/triggers/templates/_/build-component-darwin-mac.yaml";
const allPlatformsTemplate =
  "tekton/v1/triggers/templates/_/build-component-all-platforms-mac.yaml";

type TriggerTemplate = { spec: { params: { name: string }[] } };
type PipelineRun = { spec: { timeouts?: { pipeline?: string } } };
type PipelineTemplate = { spec: { resourcetemplates: PipelineRun[] } };

// `timeout` is kept as a compatibility no-op after the parallel track fixed
// darwin runs to 6h. The other three are reserved for future darwin enterprise
// builds; the current MacBuild pipeline does not consume them yet.
const intentionalUnused = [
  "external-git-ref",
  "fetch-external",
  "git-instead-of",
  "timeout",
];

function declaredParams(path: string): string[] {
  return load<TriggerTemplate>(path).spec.params.map((param) => param.name);
}

function usedParams(path: string): Set<string> {
  const text = Deno.readTextFileSync(new URL(path, root));
  return new Set(
    [...text.matchAll(/\$\(tt\.params\.([\w.-]+)\)/g)].map((match) => match[1]),
  );
}

function unusedParams(path: string): string[] {
  const used = usedParams(path);
  return declaredParams(path).filter((name) => !used.has(name)).sort();
}

Deno.test("darwin macbuild template keeps only intentional unused params", () => {
  assertEquals(unusedParams(darwinTemplate), intentionalUnused);
});

Deno.test("all-platforms mac template has no unused params", () => {
  assertEquals(unusedParams(allPlatformsTemplate), []);
});

Deno.test("darwin macbuild template keeps the fixed 6h timeout", () => {
  const runs = load<PipelineTemplate>(darwinTemplate).spec.resourcetemplates;
  assertEquals(runs.length, 1);
  assertEquals(runs[0].spec.timeouts?.pipeline, "6h");
});

type Binding = { name?: string; value?: string; ref?: string };
type Trigger = {
  spec: { bindings?: Binding[]; template?: { ref?: string } };
};

const removedBindings = [
  "builder-resources-cpu",
  "builder-resources-memory",
  "source-ws-size",
];

const explicitDarwinTriggers = [
  "tekton/v1/triggers/triggers/env-gcp/_/git-push-branch-build-community-darwin.yaml",
  "tekton/v1/triggers/triggers/env-gcp/pingcap-inc/tici/git-push-darwin-amd64.yaml",
  "tekton/v1/triggers/triggers/env-gcp/pingcap-inc/tici/git-push-darwin-arm64.yaml",
  "tekton/v1/triggers/triggers/env-gcp/pingcap-inc/tici/git-create-tag-darwin-amd64.yaml",
  "tekton/v1/triggers/triggers/env-gcp/pingcap-inc/tici/git-create-tag-darwin-arm64.yaml",
  "tekton/v1/triggers/triggers/env-gcp/pingcap/ticdc/git-create-tag-darwin-amd64.yaml",
  "tekton/v1/triggers/triggers/env-gcp/pingcap/ticdc/git-create-tag-darwin-arm64.yaml",
  "tekton/v1/triggers/triggers/env-gcp/pingcap/tiproxy/git-create-tag-darwin-amd64.yaml",
  "tekton/v1/triggers/triggers/env-gcp/pingcap/tiproxy/git-create-tag-darwin-arm64.yaml",
];

const fakeGithubDarwinTriggers = [
  "tekton/v1/triggers/triggers/env-gcp/_/fake-github/fake-github-branch-push-darwin.yaml",
  "tekton/v1/triggers/triggers/env-gcp/_/fake-github/fake-github-pr-darwin.yaml",
  "tekton/v1/triggers/triggers/env-gcp/_/fake-github/fake-github-tag-create-darwin.yaml",
];

function triggerDocs(path: string): Trigger[] {
  return parseAll(Deno.readTextFileSync(new URL(path, root))) as Trigger[];
}

Deno.test("explicit darwin triggers no longer bind removed params", () => {
  for (const path of explicitDarwinTriggers) {
    for (const trigger of triggerDocs(path)) {
      for (const binding of trigger.spec.bindings ?? []) {
        assert(
          !removedBindings.includes(binding.name ?? ""),
          `${path} still binds ${binding.name}`,
        );
      }
    }
  }
});

Deno.test("fake-github darwin triggers keep shared bindings", () => {
  for (const path of fakeGithubDarwinTriggers) {
    const trigger = triggerDocs(path)[0];
    assertEquals(trigger.spec.template?.ref, "build-component-darwin-mac");
    assert(
      trigger.spec.bindings?.some((binding) =>
        binding.ref === "gcp-dev-build-params"
      ),
      `${path} must use gcp-dev-build-params`,
    );
    const names = new Set(
      (trigger.spec.bindings ?? []).map((binding) => binding.name),
    );
    assert(names.has("git-secret-ref"), `${path} must bind git-secret-ref`);
    assert(names.has("submodules"), `${path} must bind submodules`);
  }
});

Deno.test("bindings contain no ksy/boskos residue", () => {
  const dir = new URL("tekton/v1/triggers/bindings/", root);
  for (const entry of Deno.readDirSync(dir)) {
    if (!entry.isFile || !entry.name.endsWith(".yaml")) continue;
    const text = Deno.readTextFileSync(new URL(entry.name, dir));
    assertEquals(/ksy|boskos/i.test(text), false, entry.name);
  }
});

Deno.test("single-platform template no longer declares boskos", () => {
  const names = load<TriggerTemplate>(
    "tekton/v1/triggers/templates/_/build-component-single-platform.yaml",
  ).spec.params.map((param) => param.name);
  assertEquals(names.includes("boskos-server-url"), false);
});
