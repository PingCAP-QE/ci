import { assertEquals } from "jsr:@std/assert@1.0.11";
import { CommunityParser } from "./community-parser.ts";

// The OWNERS sync turns community membership into Prow OWNERS entries. A member
// whose scope names a repository must not become a root owner of the others:
// that is what a Map read through Object.keys() used to do.

function parser() {
  return new CommunityParser([]);
}

Deno.test("a member without scope owns the repository at the root", () => {
  const bindings = parser().flattenMemberRights(
    "alice",
    "approvers",
    "pingcap/tidb",
  );

  assertEquals(bindings, [{
    repo: "pingcap/tidb",
    path: "/",
    role: "approvers",
    user: "alice",
  }]);
});

Deno.test("a member scoped to a repository owns it at the root", () => {
  const bindings = parser().flattenMemberRights(
    { name: "bob", scope: ["pingcap/tidb"] },
    "approvers",
    "pingcap/tidb",
  );

  assertEquals(bindings, [{
    repo: "pingcap/tidb",
    path: "/",
    role: "approvers",
    user: "bob",
  }]);
});

Deno.test("a member scoped to a path owns only that path", () => {
  const bindings = parser().flattenMemberRights(
    { name: "carol", scope: ["pingcap/tidb:docs/"] },
    "reviewers",
    "pingcap/tidb",
  );

  assertEquals(bindings, [{
    repo: "pingcap/tidb",
    path: "docs/",
    role: "reviewers",
    user: "carol",
  }]);
});

Deno.test("a member scoped elsewhere owns nothing here", () => {
  const bindings = parser().flattenMemberRights(
    { name: "dave", scope: ["pingcap/tidb:docs/"] },
    "approvers",
    "tikv/tikv",
  );

  assertEquals(bindings, []);
});

Deno.test("an empty scope list still means root ownership", () => {
  const bindings = parser().flattenMemberRights(
    { name: "erin", scope: [] },
    "approvers",
    "tikv/tikv",
  );

  assertEquals(bindings, [{
    repo: "tikv/tikv",
    path: "/",
    role: "approvers",
    user: "erin",
  }]);
});
