import * as semver from "jsr:@std/semver@1.0.4";
import { parseArgs } from "jsr:@std/cli@1.0.14/parse-args";

interface builtControl {
  // tell the build system what version we should name for release.
  releaseVersion: string;
  // tell the build system what git tag we should create for building.
  newGitTag?: string | null;
}

/**
 * Determine if the given branch is a hotfix branch.
 * @param {string} branch - The branch name to check.
 * @returns {boolean} True if the branch is a hotfix branch, false otherwise.
 */
function isHotfixBranch(branch: string): boolean {
  return /\brelease-[0-9]+[.][0-9]+[.][0-9]+-release[.][0-9]+-[0-9]{8}\b/.test(
    branch,
  );
}

/**
 * Determine if the given branch is a release branch.
 * @param {string} branch - The branch name to check.
 * @returns {boolean} True if the branch is a release branch, false otherwise.
 */
function isReleaseBranch(branch: string): boolean {
  // Exclude hotfix branches explicitly
  if (isHotfixBranch(branch)) {
    return false;
  }
  const standardRelease =
    /\brelease-[0-9]+[.][0-9]+(?:-beta\.[0-9]+)?(?!-[0-9]{8}-v[0-9]+[.][0-9]+[.][0-9]+)/
      .test(
        branch,
      );
  const nextgenRelease = /\brelease-nextgen-(\d{6}|\d{8})\b/.test(branch);
  return standardRelease || nextgenRelease;
}

/**
 * Determine if the given version is a GA version.
 * @param {string} version - The version to check.
 * @returns {boolean} true if the version is a GA version, false otherwise.
 */
function isGaVer(version: semver.SemVer): boolean {
  if (!version.prerelease || version.prerelease.length === 0) {
    return true;
  }

  if (version.prerelease.length === 1 && version.prerelease[0] == "fips") {
    return true;
  }

  return false;
}

/**
 * Computes the release version and new git tag based on the raw version and commit branches.
 * @param {string} rawVersion The raw version string.
 * @param {string[]} commitInBranches The branches the commit is in.
 * @returns {builtControl} The computed release version and new git tag.
 */
export function compute(
  rawVersion: string,
  commitInBranches: string[],
): builtControl {
  const rv = semver.parse(rawVersion.trim());

  // If it's a GA version, return it directly
  if (isGaVer(rv)) {
    console.info("it's a normal GA version.");
    return { releaseVersion: "v" + semver.format(rv) };
  }

  // Check if the current branch is a release branch.
  if (!commitInBranches.some(isReleaseBranch)) {
    console.info("Current commit is not contained in any release branches.");

    // Check for hotfix branch
    const hotfixBranch = commitInBranches.find(isHotfixBranch);
    if (hotfixBranch) {
      console.info("Current commit is in a hotfix branch.");
      return { releaseVersion: "v" + semver.format(rv) };
    }

    // Check for feature branch
    const featureBranch = commitInBranches.find((b) =>
      /\bfeature\/[\w.-]+$/.test(b)
    );
    if (featureBranch) {
      console.info("Current commit is in a feature branch.");
      // Extract feature name, replace '/' with '.' for version/tag
      const suffix = featureBranch
        .replace(/.*\bfeature\//, "feature/")
        .replaceAll("/", ".")
        // normalize underscores to dashes to match expected version/tag format
        // NOTE: keep '-' as-is (do not convert to '.') because tests expect dashes to remain.
        .replaceAll("_", "-");
      // Construct feature version, ignoring any existing prerelease (e.g., alpha, beta)
      const featureVersion = `v${rv.major}.${rv.minor}.${rv.patch}-${suffix}`;
      return {
        releaseVersion: featureVersion,
        newGitTag: featureVersion,
      };
    }

    return { releaseVersion: "v" + semver.format(rv) };
  }

  const preRelease = (rv.prerelease || []).join(".");
  let newGitTag = undefined;
  if (preRelease.startsWith("alpha")) {
    newGitTag = `v${rv.major}.${rv.minor}.${rv.patch}`;
    rv.prerelease = ["pre"];
  } else if (rv.prerelease![0] == "beta") {
    if (
      rv.prerelease!.length > 2 &&
      rv.prerelease![2].toString().startsWith("pre")
    ) {
      // for: v1.1.1-beta.0.pre | v1.1.1-beta.0.pre-2-g1234567
      newGitTag = `v${rv.major}.${rv.minor}.${rv.patch}-${
        rv.prerelease?.slice(0, 2).join(".")
      }`;
      // v1.1.1-beta.0.pre-2-g1234567 => v1.1.1-beta.0.pre
      rv.prerelease = rv.prerelease!.slice(0, 2);
      rv.prerelease.push("pre");
    } else {
      console.warn("I will do nothing for this beta version:", rawVersion);
    }
  } else if (rv.prerelease![0] == "rc") {
    if (
      rv.prerelease!.length == 2 &&
      typeof rv.prerelease![1] === "string"
    ) {
      console.info("Now i will increase the rc number.");
      const rcIndexStr = rv.prerelease![1].toString().split("-")[0];
      const newRCIndex = parseInt(rcIndexStr) + 1;
      newGitTag = `v${rv.major}.${rv.minor}.${rv.patch}-${
        rv.prerelease![0]
      }.${newRCIndex}`;
      rv.prerelease = [rv.prerelease![0], newRCIndex, "pre"];
    } else if (
      rv.prerelease!.length > 2 &&
      rv.prerelease![2].toString().startsWith("pre")
    ) {
      // for: v1.1.1-rc.0.pre | v1.1.1-rc.0.pre-2-g1234567
      newGitTag = `v${rv.major}.${rv.minor}.${rv.patch}-${
        rv.prerelease?.slice(0, 2).join(".")
      }`;
      // v1.1.1-beta.0.pre-2-g1234567 => v1.1.1-beta.0.pre
      rv.prerelease = rv.prerelease!.slice(0, 2);
      rv.prerelease.push("pre");
    } else {
      console.warn("I will do nothing for this rc version:", rawVersion);
    }
  } else if (preRelease.startsWith("nextgen")) {
    console.info("I will do nothing for this nextgen version:", rawVersion);
  } else if (preRelease.startsWith("release")) {
    newGitTag = `v${rv.major}.${rv.minor}.${rv.patch}`;
    rv.prerelease = ["pre"];
  } else if (preRelease.startsWith("pre")) {
    newGitTag = `v${rv.major}.${rv.minor}.${rv.patch}`;
    rv.prerelease = ["pre"];
  } else {
    // Handle the case where there are new commits after a GA tag
    newGitTag = `v${rv.major}.${rv.minor}.${rv.patch + 1}`;
    rv.patch++;
    rv.prerelease = ["pre"];
  }

  if (newGitTag) {
    console.info(`Computed new tag: ${newGitTag}`);
  }

  return { releaseVersion: "v" + semver.format(rv), newGitTag };
}

/**
 * Main function to execute the versioning strategy.
 */
function main() {
  const flags = parseArgs(Deno.args, {
    string: [
      "git_version",
      "git_version_file",
      "contain_branches",
      "contain_branches_file",
      "save_release_version_file",
      "save_build_git_tag_file",
    ],
    default: {
      save_release_version_file: "release_version.txt",
      save_build_git_tag_file: "build_git_tag.txt",
    },
  });

  // check the flags
  const argsValid = (flags["git_version"] || flags["git_version_file"]) &&
    (flags["contain_branches"] || flags["contain_branches_file"]);

  if (!argsValid) {
    console.error("Invalid arguments");
    // print help message
    console.error(`
      Usage: deno run versioning-strategy.ts --git_version=<version> --contain_branches=<branch>
      Options:
        --git_version=<version>         The current git version
        --git_version_file=<file>       The file containing the current git version
        --contain_branches=<branch>     The branch to contain
        --contain_branches_file=<file>  The file containing the branches to contain
        --save_release_version_file=<file>  The file to save the release version (default: release_version.txt)
        --save_build_git_tag_file=<file>    The file to save the build git tag (default: build_git_tag.txt)
    `);
    Deno.exit(1);
  }

  const rawVersion = flags.git_version ||
    Deno.readTextFileSync(flags.git_version_file!);
  const currentBranch = flags.contain_branches ||
    Deno.readTextFileSync(flags.contain_branches_file!);
  const ret = compute(rawVersion, currentBranch.split("\n"));

  // Save results for next step
  Deno.writeTextFileSync(flags.save_release_version_file, ret.releaseVersion);
  if (ret.newGitTag) {
    Deno.writeTextFileSync(flags.save_build_git_tag_file, ret.newGitTag);
  }
}

if (import.meta.main) {
  main();
}
