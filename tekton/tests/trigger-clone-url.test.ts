import { assert, assertEquals } from "jsr:@std/assert@1.0.11";
import { expandGlobSync } from "jsr:@std/fs@1.0.19/expand-glob";
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

// The same bindings are shared with the devbuild (fake-github) triggers, which
// are selected by the tibuild EventListener and are NOT covered by the GitHub
// HMAC check (the events are CloudEvents from an in-cluster service, not
// webhooks). There the payload is not authenticated, so the CEL allowlist and
// the derived clone URL are the only things keeping the cloned repository equal
// to the allowlisted one. Keep those bindings deriving the URL even if the
// github-* webhook path alone would not strictly need it.
const fakeGithubDirectory =
  "tekton/v1/triggers/triggers/env-gcp/_/fake-github/";

Deno.test("bindings reused by the non-HMAC devbuild triggers derive git-url", () => {
  const referenced = new Set<string>();
  for (const entry of expandGlobSync(`${fakeGithubDirectory}*.yaml`, {
    root: root.pathname,
  })) {
    const trigger = load<{
      spec: { bindings?: ({ ref?: string } | { name?: string })[] };
    }>(entry.path);
    for (const binding of trigger.spec.bindings ?? []) {
      if ("ref" in binding && binding.ref) referenced.add(binding.ref);
    }
  }
  assert(
    referenced.size > 0,
    "expected the devbuild triggers to reuse at least one shared binding",
  );
  for (const name of referenced) {
    const path = `tekton/v1/triggers/bindings/${name}.yaml`;
    const text = Deno.readTextFileSync(new URL(path, root));
    assert(
      !text.includes("repository.clone_url"),
      `${path} is reused by unauthenticated devbuild triggers and must not bind repository.clone_url`,
    );
    if (text.includes("name: git-url")) {
      assert(
        text.includes("https://github.com/$(body.repository.full_name).git"),
        `${path} is reused by unauthenticated devbuild triggers and must derive git-url from repository.full_name`,
      );
    }
  }
});
