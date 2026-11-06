import { assert, assertEquals } from "jsr:@std/assert@1.0.11";
import { parseAll } from "jsr:@std/yaml@1.0.9";
import { expandGlobSync } from "jsr:@std/fs@1.0.19/expand-glob";
import { load, root } from "./trigger-test-utils.ts";

// Community darwin branch-push builds must publish darwin/amd64 *and*
// darwin/arm64. The prod2 template did both; the gcp macbuild migration only
// kept amd64, which silently stopped darwin/arm64 package artifacts (and the
// tiup nightly tarballs built from them) for every community repo after
// 2026-10-19. These tests keep the two-arch fan-out from regressing.

const allArchTemplate =
  "tekton/v1/triggers/templates/_/build-component-darwin-mac-all-arch.yaml";
const singleArchTemplate =
  "tekton/v1/triggers/templates/_/build-component-darwin-mac.yaml";
const communityDarwinTrigger =
  "tekton/v1/triggers/triggers/env-gcp/_/git-push-branch-build-community-darwin.yaml";
const darwinPipeline = "pingcap-build-package-darwin-mac";
const fixedTimeout = "6h";

type PipelineRun = {
  metadata: { generateName?: string; annotations?: Record<string, string> };
  spec: {
    pipelineRef?: { name?: string };
    params?: { name: string; value: string }[];
    timeouts?: { pipeline?: string };
  };
};
type TriggerTemplate = {
  spec: {
    params: { name: string; default?: string }[];
    resourcetemplates: PipelineRun[];
  };
};
type Binding = { name?: string; value?: string; ref?: string };
type Trigger = {
  metadata: { name: string };
  spec: { bindings?: Binding[]; template?: { ref?: string } };
};

function platformOf(run: PipelineRun): string | undefined {
  const annotations = run.metadata.annotations ?? {};
  const key = Object.keys(annotations).find((name) =>
    name.endsWith("/platform")
  );
  return key ? annotations[key] : undefined;
}

function paramOf(run: PipelineRun, name: string): string | undefined {
  return (run.spec.params ?? []).find((param) => param.name === name)?.value;
}

function declaredParams(path: string): string[] {
  return load<TriggerTemplate>(path).spec.params.map((param) => param.name);
}

function usedParams(path: string): Set<string> {
  const text = Deno.readTextFileSync(new URL(path, root));
  return new Set(
    [...text.matchAll(/\$\(tt\.params\.([\w.-]+)\)/g)].map((match) => match[1]),
  );
}

Deno.test("all-arch darwin template renders one run per darwin arch", () => {
  const runs = load<TriggerTemplate>(allArchTemplate).spec.resourcetemplates;
  assertEquals(runs.length, 2);

  const byArch = new Map(
    runs.map((run) => [paramOf(run, "arch") ?? "", run] as const),
  );
  assertEquals([...byArch.keys()].sort(), ["amd64", "arm64"]);

  for (const [arch, run] of byArch) {
    assertEquals(run.spec.pipelineRef?.name, darwinPipeline, arch);
    assertEquals(paramOf(run, "os"), "$(tt.params.os)", arch);
    assertEquals(platformOf(run), `$(tt.params.os)/${arch}`, arch);
    assertEquals(run.spec.timeouts?.pipeline, fixedTimeout, arch);
    assert(
      run.metadata.generateName?.endsWith(`-${arch}-`) === true,
      `${arch} must keep the arch in generateName`,
    );
  }
});

Deno.test("all-arch darwin template declares exactly the params it uses", () => {
  assertEquals(
    declaredParams(allArchTemplate).sort(),
    [...usedParams(allArchTemplate)].sort(),
  );
});

Deno.test("all-arch darwin template keeps the params its callers bind", () => {
  const declared = new Set(declaredParams(allArchTemplate));
  for (
    const name of [
      "git-url",
      "git-refspec",
      "git-revision",
      "git-ref",
      "component",
      "profile",
      "registry",
      "publisher-url",
      "git-secret-ref",
      "submodules",
    ]
  ) {
    assert(declared.has(name), `missing param ${name}`);
  }
});

Deno.test("community darwin push triggers build both darwin arches", () => {
  const triggers = parseAll(
    Deno.readTextFileSync(new URL(communityDarwinTrigger, root)),
  ) as Trigger[];
  assertEquals(triggers.length, 2);
  for (const trigger of triggers) {
    assertEquals(
      trigger.spec.template?.ref,
      "build-component-darwin-mac-all-arch",
      trigger.metadata.name,
    );
  }
});

Deno.test("single-arch darwin triggers still pin one explicit arch", () => {
  // Guards the other direction: triggers that own a single arch must not drift
  // onto the two-arch template, which would double their mac builds.
  const files = [
    ...expandGlobSync("tekton/v1/triggers/triggers/**/*.yaml", {
      root: root.pathname,
    }),
  ]
    .map((entry) => entry.path)
    .filter((path) => !path.endsWith("kustomization.yaml"));

  const users: string[] = [];
  for (const path of files) {
    const text = Deno.readTextFileSync(path);
    if (/build-component-darwin-mac-all-arch/.test(text)) users.push(path);
  }
  assertEquals(
    users.map((path) => path.slice(root.pathname.length)),
    [communityDarwinTrigger],
  );

  const singleArchUsers = files.filter((path) =>
    Deno.readTextFileSync(path).includes("build-component-darwin-mac\n")
  );
  assert(
    singleArchUsers.length > 0,
    "expected tag triggers to keep using build-component-darwin-mac",
  );
  for (const path of singleArchUsers) {
    for (const trigger of parseAll(Deno.readTextFileSync(path)) as Trigger[]) {
      assert(
        (trigger.spec.bindings ?? []).some((binding) =>
          binding.name === "arch"
        ),
        `${path}#${trigger.metadata.name} must bind arch explicitly`,
      );
    }
  }
});

Deno.test("both darwin mac templates share the same build pipeline", () => {
  const refs = [
    ...load<TriggerTemplate>(allArchTemplate).spec.resourcetemplates,
    ...load<TriggerTemplate>(singleArchTemplate).spec.resourcetemplates,
  ].map((run) => run.spec.pipelineRef?.name);
  assertEquals(refs, [darwinPipeline, darwinPipeline, darwinPipeline]);
});
