import { assertEquals } from "jsr:@std/assert@1.0.11";
import { OwnerResolver } from "./OwnerResolver.ts";
import { type OwnerEntry, UNOWNED_OWNER } from "./types.ts";

Deno.test("OwnerResolver.resolveViaMap", async (t) => {
  type Want = {
    owner: string;
    level: "case" | "suite" | "parent-suite" | "repo" | "none";
  };
  type TestCase = {
    name: string;
    repo: string;
    branch: string;
    suite: string;
    kase: string;
    ownerMap: OwnerEntry[];
    want: Want;
  };
  function resolverFor(ownerMap: OwnerEntry[]) {
    return new OwnerResolver(null, undefined, ownerMap, { verbose: false });
  }

  const repo = "pingcap/tidb";
  const cases: TestCase[] = [
    {
      name:
        "case-level exact match beats suite-level even when suite has higher priority",
      repo,
      branch: "any-branch",
      suite: "suiteA",
      kase: "case1",
      ownerMap: [
        {
          repo,
          branch: "*",
          suite_name: "suiteA",
          case_name: "*",
          owner_team: "@suite-owner",
          priority: 100, // lower specificity should not override case-level
        },
        {
          repo,
          branch: "*",
          suite_name: "suiteA",
          case_name: "case1",
          owner_team: "@case-owner",
          priority: 0,
        },
      ],
      want: { owner: "@case-owner", level: "case" },
    },
    {
      name:
        "suite-level beats repo-level wildcard even if repo-level has higher priority",
      repo,
      branch: "main",
      suite: "suiteA",
      kase: "caseX",
      ownerMap: [
        {
          repo,
          branch: "*",
          suite_name: "*",
          case_name: "*",
          owner_team: "@repo-owner",
          priority: 100, // lower specificity than suite-level
        },
        {
          repo,
          branch: "*",
          suite_name: "suiteA",
          case_name: "*",
          owner_team: "@suite-owner",
          priority: 0,
        },
      ],
      want: { owner: "@suite-owner", level: "suite" },
    },
    {
      name:
        "repo-level wildcard */* mapping applies when nothing more specific",
      repo,
      branch: "dev",
      suite: "anySuite",
      kase: "anyCase",
      ownerMap: [
        {
          repo,
          branch: "*",
          suite_name: "*",
          case_name: "*",
          owner_team: "@repo-owner",
        },
      ],
      want: { owner: "@repo-owner", level: "repo" },
    },
    {
      name: "within same specificity, higher priority wins",
      repo,
      branch: "dev",
      suite: "suiteA",
      kase: "caseX",
      ownerMap: [
        {
          repo,
          branch: "*",
          suite_name: "suiteA",
          case_name: "*",
          owner_team: "@suite-low",
          priority: 1,
        },
        {
          repo,
          branch: "*",
          suite_name: "suiteA",
          case_name: "*",
          owner_team: "@suite-high",
          priority: 10,
        },
      ],
      want: { owner: "@suite-high", level: "suite" },
    },
    {
      name: "same specificity and same priority keeps first encountered",
      repo,
      branch: "dev",
      suite: "suiteA",
      kase: "caseX",
      ownerMap: [
        {
          repo,
          branch: "*",
          suite_name: "suiteA",
          case_name: "*",
          owner_team: "@suite-first",
          priority: 5,
        },
        {
          repo,
          branch: "*",
          suite_name: "suiteA",
          case_name: "*",
          owner_team: "@suite-second",
          priority: 5,
        },
      ],
      want: { owner: "@suite-first", level: "suite" },
    },
    {
      name: "no match yields UNOWNED",
      repo,
      branch: "main",
      suite: "suiteA",
      kase: "case1",
      ownerMap: [
        {
          repo,
          branch: "*",
          suite_name: "otherSuite",
          case_name: "otherCase",
          owner_team: "@someone",
        },
      ],
      want: { owner: UNOWNED_OWNER, level: "none" },
    },
    {
      name: "rules from other repos do not apply",
      repo: "pingcap/tidb",
      branch: "main",
      suite: "suiteA",
      kase: "case1",
      ownerMap: [
        {
          repo: "another/repo",
          branch: "*",
          suite_name: "suiteA",
          case_name: "case1",
          owner_team: "@wrong",
        },
        {
          repo: "another/repo",
          branch: "*",
          suite_name: "*",
          case_name: "*",
          owner_team: "@wrong2",
        },
      ],
      want: { owner: UNOWNED_OWNER, level: "none" },
    },
    {
      name:
        "current behavior: branch in mapping is ignored by resolveViaMap (matches regardless of branch)",
      repo,
      branch: "another-branch",
      suite: "suiteA",
      kase: "case1",
      ownerMap: [
        {
          repo,
          branch: "main", // ignored by resolveViaMap
          suite_name: "suiteA",
          case_name: "case1",
          owner_team: "@owner",
        },
      ],
      want: { owner: "@owner", level: "case" },
    },
    {
      name:
        "parent suite prefix rules are applied when not matching the exact suite",
      repo,
      branch: "main",
      suite: "pkg/FooTest",
      kase: "testA",
      ownerMap: [
        {
          repo,
          branch: "*",
          suite_name: "pkg", // parent of 'pkg/FooTest' but not exact; filtered out by current logic
          case_name: "*",
          owner_team: "@parent-suite",
        },
      ],
      want: { owner: "@parent-suite", level: "parent-suite" },
    },
    {
      name:
        "suite normalization in resolve(bs): strips leading '//' and trailing ':suffix'",
      repo,
      branch: "main",
      suite: "//pipeline/test:Foo",
      kase: "bar",
      ownerMap: [
        {
          repo,
          branch: "*",
          suite_name: "pipeline/test",
          case_name: "*",
          owner_team: "@suite-owner",
        },
      ],
      want: { owner: "@suite-owner", level: "suite" },
    },
    {
      name: "exact suite match takes precedence over parent suite match",
      repo,
      branch: "main",
      suite: "pkg/executor/importer",
      kase: "testCase",
      ownerMap: [
        {
          repo,
          branch: "*",
          suite_name: "pkg/executor", // parent suite
          case_name: "*",
          owner_team: "@parent-owner",
          priority: 0,
        },
        {
          repo,
          branch: "*",
          suite_name: "pkg/executor/importer", // exact suite match
          case_name: "*",
          owner_team: "@exact-owner",
          priority: 0,
        },
      ],
      want: { owner: "@exact-owner", level: "suite" },
    },
    {
      name:
        "exact suite match takes precedence over parent suite match even with lower priority",
      repo,
      branch: "main",
      suite: "pkg/executor/importer",
      kase: "testCase",
      ownerMap: [
        {
          repo,
          branch: "*",
          suite_name: "pkg/executor", // parent suite
          case_name: "*",
          owner_team: "@parent-owner",
          priority: 10,
        },
        {
          repo,
          branch: "*",
          suite_name: "pkg/executor/importer", // exact suite match
          case_name: "*",
          owner_team: "@exact-owner",
          priority: 0,
        },
      ],
      want: { owner: "@exact-owner", level: "suite" },
    },
    {
      name: "empty owner map yields UNOWNED",
      repo,
      branch: "main",
      suite: "suiteX",
      kase: "caseY",
      ownerMap: [],
      want: { owner: UNOWNED_OWNER, level: "none" },
    },
  ];

  for (const tc of cases) {
    await t.step(tc.name, async () => {
      const resolver = resolverFor(tc.ownerMap);
      const res = await resolver.resolve(tc.repo, tc.branch, tc.suite, tc.kase);
      assertEquals(res, tc.want);
    });
  }
});
