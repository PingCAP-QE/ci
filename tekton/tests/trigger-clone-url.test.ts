import { assert, assertEquals } from "jsr:@std/assert@1.0.11";
import { load, root } from "./trigger-test-utils.ts";

// The clone URL must be derived from the repository identity the CEL
// allowlist approves (`body.repository.full_name`), never taken from the
// request-supplied `body.repository.clone_url`.
//
// The two fields are independent in the payload, so a caller who controls the
// payload can satisfy a `full_name`-based allowlist and still make CI clone a
// different repository — the classic "validate one field, use another". That
// repository is then built and pushed with CI credentials.
// (security finding afe15663-40ae-4f4b-af1e-9aed7cfddadf)

const BINDINGS = [
  "github-branch-push.yaml",
  "github-pr.yaml",
  "github-tag-create.yaml",
  "github-branch-create.yaml",
];

Deno.test("trigger bindings derive git-url from the allowlisted full_name", () => {
  for (const name of BINDINGS) {
    const path = `tekton/v1/triggers/bindings/${name}`;
    const binding = load<{
      spec: { params: { name: string; value: string }[] };
    }>(path);
    const gitUrl = binding.spec.params.find((param) => param.name === "git-url");
    assertEquals(
      gitUrl?.value,
      "https://github.com/$(body.repository.full_name).git",
      `${name} must derive git-url from repository.full_name`,
    );
    assert(
      !Deno.readTextFileSync(new URL(path, root)).includes(
        "repository.clone_url",
      ),
      `${name} must not bind repository.clone_url`,
    );
  }
});
