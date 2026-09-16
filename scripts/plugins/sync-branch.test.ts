import { assert, assertEquals } from "jsr:@std/assert@1.0.11";
import {
  buildConflictPullRequestBody,
  buildConflictPullRequestTitle,
  buildMergeCommitMessage,
  normalizeSyncSpecs,
  normalizeTargetBranches,
} from "./sync-branch.ts";

Deno.test("normalizeTargetBranches", () => {
  const tests: { description: string; input: string[]; expect: string[] }[] = [
    {
      description: "deduplicates branches",
      input: ["feature/release-8.5-fts", "feature/release-8.5-fts"],
      expect: ["feature/release-8.5-fts"],
    },
    {
      description: "filters empty branches",
      input: ["feature/release-8.5-fts", ""],
      expect: ["feature/release-8.5-fts"],
    },
  ];

  for (const { description, input, expect } of tests) {
    assertEquals(normalizeTargetBranches(input), expect, description);
  }
});

Deno.test("normalizeSyncSpecs", () => {
  const specs = normalizeSyncSpecs({
    syncs: [
      {
        owner: "pingcap",
        repository: "tidb",
        source_branch: "release-8.5",
        target_branches: [
          "feature/release-8.5-fts",
          "feature/release-8.5-fts",
        ],
      },
      {
        owner: "tikv",
        repository: "client-go",
        source_branch: "tidb-8.5",
        target_branches: ["feature/release-8.5-fts"],
      },
      {
        owner: "pingcap",
        repository: "broken",
        source_branch: "",
        target_branches: ["feature/release-8.5-fts"],
      },
      {
        owner: "pingcap",
        repository: "no-target",
        source_branch: "release-8.5",
        target_branches: [],
      },
    ],
  });

  assertEquals(specs.length, 2, "should drop invalid entries");
  assertEquals(specs[0].owner, "pingcap");
  assertEquals(
    specs[0].target_branches,
    ["feature/release-8.5-fts"],
    "should deduplicate target branches",
  );
  assertEquals(specs[1].source_branch, "tidb-8.5");
});

Deno.test("buildMergeCommitMessage", () => {
  const message = buildMergeCommitMessage(
    "release-8.5",
    "feature/release-8.5-fts",
  );

  assert(
    message.includes("Merge branch 'release-8.5'"),
    "should mention the source branch",
  );
  assert(
    message.includes("feature/release-8.5-fts"),
    "should mention the target branch",
  );
  assert(
    message.includes("Tags are never"),
    "should document that tags are not touched",
  );
});

Deno.test("buildConflictPullRequestTitle", () => {
  assertEquals(
    buildConflictPullRequestTitle("release-8.5", "feature/release-8.5-fts"),
    "sync release-8.5 into feature/release-8.5-fts",
  );
});

Deno.test("buildConflictPullRequestBody", () => {
  const body = buildConflictPullRequestBody(
    "release-8.5",
    "feature/release-8.5-fts",
  );

  assert(
    body.includes("git checkout feature/release-8.5-fts"),
    "should include the checkout command",
  );
  assert(
    body.includes("git merge origin/release-8.5"),
    "should include the merge command",
  );
  assert(
    body.includes("git push origin feature/release-8.5-fts"),
    "should include the push command",
  );
});
