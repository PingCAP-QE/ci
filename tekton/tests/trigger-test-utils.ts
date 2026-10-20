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

export function evaluate(expression: string, body: Body, header: Header = {}) {
  // Tekton canonicalizes HTTP header names and returns an empty string if absent.
  const canonical = celMethod(
    "canonical",
    CelScalar.DYN,
    [CelScalar.STRING],
    CelScalar.STRING,
    (key) => header[key.toLowerCase()] ?? "",
  );
  const value = run(expression, { body, header }, {
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
  for (const interceptor of trigger.spec.interceptors) {
    const param = interceptor.params.find((param) => param.name === "filter");
    if (param) return evaluate(param.value as string, body, header) === true;
  }
  throw new Error("Missing CEL filter");
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
