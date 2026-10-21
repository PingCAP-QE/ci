import { parse as parseYaml } from "jsr:@std/yaml@1.0.9";
import {
  type CelInput,
  celMethod,
  CelScalar,
  isCelError,
  run,
} from "npm:@bufbuild/cel@0.6.1";
import { strings } from "npm:@bufbuild/cel@0.6.1/ext";

export const root = new URL("../../", import.meta.url);
export const sha = "0123456789abcdef0123456789abcdef01234567";
export const products = [
  "tidb-cse",
  "pd-cse",
  "tidb-operator-cse",
  "cassandra-on-mysql",
  "qdrant-on-tidb",
];
export type Body = Record<string, CelInput>;
export type Header = Record<string, string>;
export interface Trigger {
  spec: {
    bindings?: { name?: string; value?: string; ref?: string }[];
    interceptors: {
      params: {
        name: string;
        value: string | { key: string; expression: string }[];
      }[];
    }[];
    template: { ref: string };
  };
}

export function load<T>(path: string): T {
  return parseYaml(Deno.readTextFileSync(new URL(path, root))) as T;
}

export function evaluate(
  expression: string,
  body: Body,
  header: Header = {},
  extensions: Body = {},
) {
  // Tekton canonicalizes HTTP header names and returns an empty string if absent.
  const canonical = celMethod(
    "canonical",
    CelScalar.DYN,
    [CelScalar.STRING],
    CelScalar.STRING,
    (key) => header[key.toLowerCase()] ?? "",
  );
  const value = run(expression, { body, header, extensions }, {
    funcs: [...strings, canonical],
  });
  if (isCelError(value)) throw new Error(`CEL ${expression}: ${value.message}`);
  return value;
}

export function filter(
  trigger: Trigger,
  body: Body,
  header: Header = {},
): boolean {
  const extensions: Body = {};
  for (const [index, interceptor] of trigger.spec.interceptors.entries()) {
    const param = interceptor.params.find((param) => param.name === "filter");
    if (
      param &&
      evaluate(param.value as string, body, header, extensions) !== true
    ) return false;
    if (index < trigger.spec.interceptors.length - 1) {
      applyOverlays(interceptor, body, header, extensions);
    }
  }
  return true;
}

function applyOverlays(
  interceptor: Trigger["spec"]["interceptors"][number],
  body: Body,
  header: Header,
  extensions: Body,
) {
  const overlays = interceptor.params.find((param) => param.name === "overlays")
    ?.value;
  if (typeof overlays !== "object") return;
  // Each interceptor evaluates against the extensions received from its predecessor.
  const result: Body = {};
  for (const overlay of overlays) {
    result[overlay.key] = evaluate(
      overlay.expression,
      body,
      header,
      extensions,
    );
  }
  Object.assign(extensions, result);
}

export function precedingExtensions(
  trigger: Trigger,
  body: Body,
  header: Header,
): Body {
  const extensions: Body = {};
  for (const interceptor of trigger.spec.interceptors.slice(0, -1)) {
    applyOverlays(interceptor, body, header, extensions);
  }
  return extensions;
}

export function payload(repo: string, kind: string): Body {
  const body: Body = {
    repository: {
      name: repo.split("/")[1],
      full_name: repo,
      clone_url: `https://github.com/${repo}.git`,
    },
    ref: "refs/heads/feature/test",
    after: sha,
    deleted: false,
  };
  if (kind === "tag-create") {
    body.ref = "v1.2.3-alpha.0";
    body.ref_type = "tag";
  }
  if (kind === "pr") {
    body.pull_request = { number: 42n, head: { sha }, base: { ref: "main" } };
  }
  return body;
}
