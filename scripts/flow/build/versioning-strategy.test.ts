import { assertEquals } from "jsr:@std/assert@1.0.11";
import { compute } from "./versioning-strategy.ts";

Deno.test("compute", () => {
  const tests: {
    description?: string;
    gitVer: string;
    branches: string[];
    expect: { version: string; newBuildTag?: string };
  }[] = [
    {
      description: "history style - init alpha tag on master branch",
      gitVer: "v8.5.0-alpha",
      branches: ["master"],
      expect: {
        version: "v8.5.0-alpha",
      },
    },
    {
      description: "history style - new commit on master branch",
      gitVer: "v8.5.0-alpha-2-g1234567",
      branches: ["master"],
      expect: {
        version: "v8.5.0-alpha-2-g1234567",
      },
    },
    {
      description:
        "history style - create new release branch but no new commit after alpha tag",
      gitVer: "v8.5.0-alpha",
      branches: ["master", "release-8.5"],
      expect: {
        version: "v8.5.0-pre",
        newBuildTag: "v8.5.0",
      },
    },
    {
      description:
        "history style - create new release branch with new commits after alpha tag",
      gitVer: "v8.5.0-alpha-2-g1234567",
      branches: ["master", "release-8.5"],
      expect: {
        version: "v8.5.0-pre",
        newBuildTag: "v8.5.0",
      },
    },
    {
      description: "history style - added GA tag",
      gitVer: "v8.5.0",
      branches: ["release-8.5"],
      expect: {
        version: "v8.5.0",
      },
    },
    {
      description: "`release` pre-release tag on release branch",
      gitVer: "v8.5.4-release.1",
      branches: ["release-8.5"],
      expect: {
        version: "v8.5.4-pre", // will publish packages/images with v8.5.4-pre version.
        newBuildTag: "v8.5.4", // `cdc version` will show v8.5.4 for version value.
      },
    },
    {
      description:
        "`release` pre-release tag on release branch and master branch",
      gitVer: "v8.5.4-release.1",
      branches: ["master", "release-8.5"],
      expect: {
        version: "v8.5.4-pre",
        newBuildTag: "v8.5.4",
      },
    },
    {
      description: "CDC parent commits has EA tagged commit",
      gitVer: "v8.5.4-release.1-1-g1234567",
      branches: ["release-8.5", "master"],
      expect: {
        version: "v8.5.4-pre", // will publish packages/images with v8.5.4-pre version.
        newBuildTag: "v8.5.4", // `cdc version` will show v8.5.4 for version value.
      },
    },
    {
      description: "`nextgen` pre-release tag on release branch",
      gitVer: "v8.5.4-nextgen.202510.0",
      branches: ["release-nextgen-20251011"],
      expect: {
        version: "v8.5.4-nextgen.202510.0",
      },
    },
    {
      description:
        "`nextgen` pre-release tag on release branch and master branch",
      gitVer: "v8.5.4-nextgen.202510.1",
      branches: ["master", "release-nextgen-20251011"],
      expect: {
        version: "v8.5.4-nextgen.202510.1",
      },
    },
    {
      description:
        "history style - has new commits after next-gen tag on release branch, we will do nothing for it",
      gitVer: "v8.5.4-nextgen.202510.0-2-g1234567",
      branches: ["release-nextgen-20251011"],
      expect: {
        version: "v8.5.4-nextgen.202510.0-2-g1234567",
      },
    },
    {
      description:
        "history style - has new commits after next-gen tag on master branch, we will do nothing for it",
      gitVer: "v8.5.4-nextgen.202510.0-1-g1234567",
      branches: ["master"],
      expect: {
        version: "v8.5.4-nextgen.202510.0-1-g1234567",
      },
    },
    {
      description: "history style - has new commits after GA tag",
      gitVer: "v8.5.0-2-g1234567",
      branches: ["release-8.5"],
      expect: {
        version: "v8.5.1-pre",
        newBuildTag: "v8.5.1",
      },
    },
    {
      description: "new style - create alpha tag",
      gitVer: "v9.0.0-alpha",
      branches: ["master"],
      expect: {
        version: "v9.0.0-alpha",
      },
    },
    {
      description: "new style - new commits after alpha tag (current state)",
      gitVer: "v9.0.0-alpha-2-g1234567",
      branches: ["master"],
      expect: {
        version: "v9.0.0-alpha-2-g1234567",
      },
    },
    {
      description: "new style - create beta pre tag on master branch",
      gitVer: "v9.0.0-beta.0.pre",
      branches: ["master"],
      expect: {
        version: "v9.0.0-beta.0.pre",
        // will not create new tag for it.
      },
    },
    {
      description:
        "new style - new commits after beta pre tag on master branch",
      gitVer: "v9.0.0-beta.0.pre-2-g1234567",
      branches: ["master"],
      expect: {
        version: "v9.0.0-beta.0.pre-2-g1234567",
        // will not create new tag for it.
      },
    },
    {
      description:
        "new style - created beta release branch with no new comments after beta pre tag`",
      gitVer: "v9.0.0-beta.0.pre",
      branches: ["master", "release-9.0-beta.0"],
      expect: {
        version: "v9.0.0-beta.0.pre",
        newBuildTag: "v9.0.0-beta.0",
      },
    },
    {
      description:
        "new style - created beta release branch after new comments after beta pre tag`",
      gitVer: "v9.0.0-beta.0.pre-2-g1234567",
      branches: ["master", "release-9.0-beta.0"],
      expect: {
        version: "v9.0.0-beta.0.pre",
        newBuildTag: "v9.0.0-beta.0",
      },
    },
    {
      description: "new style - release beta version`",
      gitVer: "v9.0.0-beta.0",
      branches: ["release-9.0-beta.0"],
      expect: {
        version: "v9.0.0-beta.0",
      },
    },
    {
      description:
        "new style - new commits after released beta version, we will do nothing for it",
      gitVer: "v9.0.0-beta.0-2-g1234567",
      branches: ["release-9.0-beta.0"],
      expect: {
        version: "v9.0.0-beta.0-2-g1234567",
      },
    },
    {
      description: "new style - create rc pre tag on master branch",
      gitVer: "v9.0.0-rc.0.pre",
      branches: ["master"],
      expect: {
        version: "v9.0.0-rc.0.pre",
        // will not create new tag for it.
      },
    },
    {
      description:
        "new style - some new commits after rc pre taged on master branch",
      gitVer: "v9.0.0-rc.0.pre-2-g1234567",
      branches: ["master"],
      expect: {
        version: "v9.0.0-rc.0.pre-2-g1234567",
        // no tag to create.
      },
    },
    {
      description: "new style - checkout release-X.Y branch after rc pre taged",
      gitVer: "v9.0.0-rc.0.pre",
      branches: ["master", "release-9.0"],
      expect: {
        version: "v9.0.0-rc.0.pre",
        newBuildTag: "v9.0.0-rc.0", // prepare for rc releasing (saving builds).
      },
    },
    {
      description:
        "new style - some new commits after rc pre taged on release-X.Y branch",
      gitVer: "v9.0.0-rc.0.pre-2-g1234567",
      branches: ["release-9.0"],
      expect: {
        version: "v9.0.0-rc.0.pre",
        newBuildTag: "v9.0.0-rc.0", // prepare for rc releasing (saving builds).
      },
    },
    {
      description: "new style - create rc tag on release-X.Y branch",
      gitVer: "v9.0.0-rc.0",
      branches: ["release-9.0"],
      expect: {
        version: "v9.0.0-rc.0",
      },
    },
    {
      description:
        "new style - new commits after a rc version released on release-X.Y branch",
      gitVer: "v9.0.0-rc.0-2-g1234567",
      branches: ["release-9.0"],
      expect: {
        version: "v9.0.0-rc.1.pre",
        newBuildTag: "v9.0.0-rc.1", // bump the rc number
      },
    },
    {
      description: "new style - prepare the GA release on release-X.Y branch",
      gitVer: "v9.0.0-pre", // 'p' is before 'r' char, so git describe will show information woth this tag.
      branches: ["release-9.0"],
      expect: {
        version: "v9.0.0-pre",
        newBuildTag: "v9.0.0",
      },
    },
    {
      description:
        "new style - new commits after GA pre tag created on release-X.Y branch",
      gitVer: "v9.0.0-pre-2-g1234567",
      branches: ["release-9.0"],
      expect: {
        version: "v9.0.0-pre",
        newBuildTag: "v9.0.0",
      },
    },
    {
      description: "new style - release GA version on release-X.Y branch",
      gitVer: "v9.0.0",
      branches: ["release-9.0"],
      expect: {
        version: "v9.0.0",
      },
    },
    {
      description:
        "new style - new commits after released GA version on release-X.Y branch",
      gitVer: "v9.0.0-2-g1234567",
      branches: ["release-9.0"],
      expect: {
        version: "v9.0.1-pre",
        newBuildTag: "v9.0.1",
      },
    },
    {
      description: "history style - hotfix branch without hotfix tags",
      gitVer: "v8.5.1-2-g1234567",
      branches: ["release-8.5-20250101-v8.5.1"],
      expect: {
        version: "v8.5.1-2-g1234567",
      },
    },
    {
      description: "history style - hotfix branch with hotfix tag",
      gitVer: "v8.5.1-20250101-fecba32",
      branches: ["release-8.5-20250101-v8.5.1"],
      expect: {
        version: "v8.5.1-20250101-fecba32",
      },
    },
    {
      description: "feature branch - beta prerelease",
      gitVer: "v9.0.0-beta.1.pre-151-gb4c8f4dc8",
      branches: ["feature/fts"],
      expect: {
        version: "v9.0.0-feature.fts",
        newBuildTag: "v9.0.0-feature.fts",
      },
    },
    {
      description: "feature branch - alpha prerelease",
      gitVer: "v8.5.0-alpha-2-g1234567",
      branches: ["feature/fts"],
      expect: {
        version: "v8.5.0-feature.fts",
        newBuildTag: "v8.5.0-feature.fts",
      },
    },
  ];

  for (const { description, gitVer, branches, expect } of tests) {
    console.group("🧪 ", description);
    console.debug("🚀", `gitVer: ${gitVer} & branches: ${branches}`);
    const result = compute(gitVer, branches);
    console.debug("🛬️", result);
    assertEquals(
      result.releaseVersion,
      expect.version,
      "assert failed on version",
    );
    assertEquals(
      result.newGitTag,
      expect.newBuildTag,
      "assert failed on newTag",
    );
    console.debug("🎉");
    console.groupEnd();
  }
});
