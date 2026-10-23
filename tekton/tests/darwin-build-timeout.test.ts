import { assert, assertEquals } from "jsr:@std/assert@1.0.11";
import { load } from "./trigger-test-utils.ts";

const darwinTemplatePath =
  "tekton/v1/triggers/templates/_/build-component-darwin-mac.yaml";
const allPlatformsTemplatePath =
  "tekton/v1/triggers/templates/_/build-component-all-platforms-mac.yaml";

const fixedTimeout = "6h";
const triggerTimeout = "$(tt.params.timeout)";

type PipelineRun = {
  metadata: { annotations?: Record<string, string> };
  spec: {
    pipelineRef?: { name?: string };
    timeouts?: { pipeline?: string };
  };
};

type Template = { spec: { resourcetemplates: PipelineRun[] } };

function pipelineRuns(path: string): PipelineRun[] {
  return load<Template>(path).spec.resourcetemplates;
}

function isDarwinRun(run: PipelineRun): boolean {
  return run.spec.pipelineRef?.name?.includes("darwin") === true ||
    run.metadata.annotations?.["tibuild.pingcap.net/platform"]?.startsWith(
        "darwin/",
      ) === true;
}

Deno.test("darwin build pipelines use a fixed 6h timeout", async (t) => {
  await t.step("darwin-only template fixes the pipeline timeout", () => {
    const runs = pipelineRuns(darwinTemplatePath);
    assertEquals(runs.length, 1);
    assertEquals(runs[0].spec.timeouts?.pipeline, fixedTimeout);
  });

  await t.step("all-platforms template only fixes darwin runs", () => {
    const timeoutsByPlatform = new Map(
      pipelineRuns(allPlatformsTemplatePath).map((run) => [
        run.metadata.annotations?.["tibuild.pingcap.net/platform"],
        run.spec.timeouts?.pipeline,
      ]),
    );

    assertEquals(timeoutsByPlatform.get("darwin/amd64"), fixedTimeout);
    assertEquals(timeoutsByPlatform.get("darwin/arm64"), fixedTimeout);
    assertEquals(timeoutsByPlatform.get("linux/amd64"), triggerTimeout);
    assertEquals(timeoutsByPlatform.get("linux/arm64"), triggerTimeout);
  });

  await t.step("no darwin run inherits the trigger timeout", () => {
    const runs = [
      ...pipelineRuns(darwinTemplatePath),
      ...pipelineRuns(allPlatformsTemplatePath),
    ];
    const darwinRuns = runs.filter(isDarwinRun);
    assert(darwinRuns.length >= 3, "expected every darwin pipeline run");
    for (const run of darwinRuns) {
      assertEquals(run.spec.timeouts?.pipeline, fixedTimeout);
    }
  });
});
