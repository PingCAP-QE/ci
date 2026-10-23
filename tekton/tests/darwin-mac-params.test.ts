import { assertEquals } from "jsr:@std/assert@1.0.11";
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
