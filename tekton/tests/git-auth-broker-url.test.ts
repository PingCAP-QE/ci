import { assert, assertEquals } from "jsr:@std/assert@1.0.11";
import { load, root } from "./trigger-test-utils.ts";

// The GitHub token broker URL is an environment fact, not a pipeline fact. It
// must reach the pipeline from the trigger binding of the cluster that runs the
// build (GCP today: https://github-token-broker-gcp.pingcap.net), never from a
// default baked into a Task, Pipeline or StepAction — a hardcoded default there
// silently sends another environment's builds to the wrong endpoint, and it did
// until this test existed.

const GCP_BROKER_URL = "https://github-token-broker-gcp.pingcap.net";

const BUILDER_FILES = [
  "tekton/v1/step-actions/setup-git-auth.yaml",
  "tekton/v1/tasks/pingcap-git-clone.yaml",
  "tekton/v1/tasks/pingcap-build-binaries-linux.yaml",
  "tekton/v1/pipelines/pingcap-build-package-linux.yaml",
];

const LINUX_TEMPLATES = [
  "build-component-linux",
  "build-component-single-platform",
  "build-component-all-platforms-mac",
  "build-serverless-linux",
];

const GCP_BINDINGS = [
  "gcp-classic-build-params.yaml",
  "gcp-dev-build-params.yaml",
  "gcp-hotfix-build-params.yaml",
  "gcp-internal-build-params.yaml",
  "gcp-ng-build-params.yaml",
];

interface Param {
  name: string;
  default?: string;
  description?: string;
  value?: string;
}

interface Doc {
  spec?: { params?: Param[]; resourcetemplates?: unknown[] };
}

interface AnyDoc {
  spec?: { params?: Param[] };
}

function read(path: string): string {
  return Deno.readTextFileSync(new URL(path, root));
}

Deno.test("no task, pipeline or step action defaults the broker URL", () => {
  for (const path of BUILDER_FILES) {
    const text = read(path);
    assert(
      !text.includes("github-token-broker."),
      `${path} must not name a broker endpoint: the trigger decides it per environment`,
    );
    for (const doc of [load<AnyDoc>(path)]) {
      for (const param of doc.spec?.params ?? []) {
        if (!/broker.?url/i.test(param.name)) continue;
        assertEquals(
          param.default,
          undefined,
          `${path}: param ${param.name} must not have a default`,
        );
      }
    }
  }
});

Deno.test("linux trigger templates forward the broker URL from their params", () => {
  for (const name of LINUX_TEMPLATES) {
    const path = `tekton/v1/triggers/templates/_/${name}.yaml`;
    const doc = load<Doc>(path);
    const declared = (doc.spec?.params ?? []).find(
      (p) => p.name === "git-auth-broker-url",
    );
    assert(declared, `${path} must declare the git-auth-broker-url param`);
    assertEquals(
      declared?.default,
      undefined,
      `${path}: the template must not default it either`,
    );

    const runs = (doc.spec as {
      resourcetemplates?: {
        kind?: string;
        spec?: { pipelineRef?: { name?: string }; params?: Param[] };
      }[];
    })?.resourcetemplates ?? [];
    let linuxRuns = 0;
    for (const item of runs) {
      if (item.kind !== "PipelineRun") continue;
      const pipeline = item.spec?.pipelineRef?.name ?? "";
      // The darwin pipeline has no broker params at all.
      if (pipeline.includes("darwin")) continue;
      linuxRuns += 1;
      const passed = (item.spec?.params ?? []).find(
        (p) => p.name === "git-auth-broker-url",
      );
      assertEquals(
        passed?.value,
        "$(tt.params.git-auth-broker-url)",
        `${path}: the ${pipeline} PipelineRun must pass git-auth-broker-url through`,
      );
    }
    assert(linuxRuns > 0, `${path}: expected at least one linux PipelineRun`);
  }
});

Deno.test("every GCP build binding sets the gateway URL", () => {
  for (const name of GCP_BINDINGS) {
    const path = `tekton/v1/triggers/bindings/${name}`;
    const doc = load<{ spec?: { params?: Param[] } }>(path);
    const param = (doc.spec?.params ?? []).find(
      (p) => p.name === "git-auth-broker-url",
    );
    assertEquals(
      param?.value,
      GCP_BROKER_URL,
      `${path} must set git-auth-broker-url to the GCP gateway`,
    );
  }
});

Deno.test("serverless triggers supply the broker URL themselves", () => {
  // These two triggers render `build-serverless-linux` without any of the
  // gcp-*-build-params bindings, so they carry the value inline.
  for (const name of ["branch-push.yaml", "tag-create.yaml"]) {
    const path =
      `tekton/v1/triggers/triggers/env-gcp/tidbcloud/serverless/${name}`;
    const doc = load<{
      spec?: { bindings?: { name?: string; value?: string }[] };
    }>(path);
    const inline = (doc.spec?.bindings ?? []).find(
      (b) => b.name === "git-auth-broker-url",
    );
    assertEquals(
      inline?.value,
      GCP_BROKER_URL,
      `${path} must supply git-auth-broker-url for the serverless template`,
    );
  }
});
