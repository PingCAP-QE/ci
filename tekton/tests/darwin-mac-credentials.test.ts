import { assert, assertEquals } from "jsr:@std/assert@1.0.11";
import { parseAll } from "jsr:@std/yaml@1.0.9";
import {
  evaluate,
  load,
  payload,
  root,
  type Trigger,
} from "./trigger-test-utils.ts";

type NamedTrigger = Trigger & {
  metadata: { name: string };
  spec: Trigger["spec"] & {
    bindings: { name?: string; value?: string; ref?: string }[];
  };
};

type Overlay = { key: string; expression: string };

const fakeGithubDir =
  "tekton/v1/triggers/triggers/env-gcp/_/fake-github/";
const communityPath =
  "tekton/v1/triggers/triggers/env-gcp/_/git-push-branch-build-community-darwin.yaml";

const fakeGithubTriggers: [string, string][] = [
  ["fake-github-branch-push-darwin.yaml", "branch-push"],
  ["fake-github-pr-darwin.yaml", "pr"],
  ["fake-github-tag-create-darwin.yaml", "tag-create"],
  ["fake-github-branch-push.yaml", "branch-push"],
  ["fake-github-pr.yaml", "pr"],
  ["fake-github-tag-create.yaml", "tag-create"],
];

function overlays(trigger: Trigger): Overlay[] {
  const interceptor = trigger.spec.interceptors.find((item) =>
    item.params.some((param) => param.name === "overlays")
  );
  const param = interceptor?.params.find((item) => item.name === "overlays");
  if (!param || typeof param.value === "string") {
    throw new Error("missing overlays interceptor");
  }
  return param.value as Overlay[];
}

function overlayExpression(trigger: Trigger, key: string): string {
  const overlay = overlays(trigger).find((item) => item.key === key);
  if (!overlay) throw new Error(`missing overlay ${key}`);
  return overlay.expression;
}

function mapValue(value: unknown, key: string): unknown {
  if (
    value && typeof value === "object" && "get" in value &&
    typeof (value as { get?: unknown }).get === "function"
  ) {
    return (value as { get: (key: string) => unknown }).get(key);
  }
  throw new Error(`CEL result is not a map: ${String(value)}`);
}

function darwinHeader(): Record<string, string> {
  return {
    "ce-paramplatform": "darwin/amd64",
    "ce-paramproduct": "tiflash",
    "ce-paramprofile": "",
    "ce-paramplugingitref": "",
    "ce-parambuilderimage": "",
  };
}

function byName(triggers: NamedTrigger[], name: string): NamedTrigger {
  const trigger = triggers.find((item) => item.metadata.name === name);
  if (!trigger) throw new Error(`missing trigger ${name}`);
  return trigger;
}

function assertSecret(
  trigger: Trigger,
  key: string,
  kind: string,
  fullName: string,
  want: string,
) {
  const expression = overlayExpression(trigger, key);
  const body = payload(fullName, kind);
  const value = key === "custom-params"
    ? mapValue(evaluate(expression, body, darwinHeader()), "git-secret-ref")
    : evaluate(expression, body);
  assertEquals(value, want, `${fullName} ${key}`);
}

Deno.test("darwin triggers pass github-ssh-secret for tiflash", async (t) => {
  for (const [file, kind] of fakeGithubTriggers) {
    await t.step(file, () => {
      const trigger = load<Trigger>(`${fakeGithubDir}${file}`);
      assertSecret(trigger, "custom-params", kind, "pingcap/tiflash", "github-ssh-secret");
      assertSecret(trigger, "custom-params", kind, "pingcap-inc/tici", "github-pat-secret");
      assertSecret(trigger, "custom-params", kind, "tikv/tikv", "");
    });
  }

  const community = parseAll(
    Deno.readTextFileSync(new URL(communityPath, root)),
  ) as NamedTrigger[];
  for (
    const name of [
      "github-push-build-community-darwin",
      "github-push-build-community-fts-darwin",
    ]
  ) {
    await t.step(name, () => {
      const trigger = byName(community, name);
      assertEquals(trigger.spec.template.ref, "build-component-darwin-mac");
      assert(
        trigger.spec.bindings.some((binding) => binding.name === "git-secret-ref"),
        "community trigger must bind git-secret-ref",
      );
      assertSecret(trigger, "git-secret-ref", "branch-push", "pingcap/tiflash", "github-ssh-secret");
      assertSecret(trigger, "git-secret-ref", "branch-push", "pingcap/tidb", "");
      assertSecret(trigger, "git-secret-ref", "branch-push", "tikv/tikv", "");
    });
  }
});
