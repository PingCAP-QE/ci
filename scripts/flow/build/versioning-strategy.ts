import * as semver from "jsr:@std/semver@1.0.4";
import { parseArgs } from "jsr:@std/cli@1.0.14/parse-args";

interface builtControl {
  // tell the build system what version we should name for release.
  releaseVersion: string;
  // tell the build system what git tag we should create for building.
  newGitTag?: string | null;
}

/**
 * Determine if the given branch is a release branch.
 * @param {string} branch - The branch name to check.
 * @returns {boolean} True if the branch is a release branch, false otherwise.
 */
function isReleaseBranch(branch: string): boolean {
  return /\brelease-[0-9]+[.][0-9]+(?!-[0-9]{8}-v[0-9]+[.][0-9]+[.][0-9]+)/
    .test(branch);
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
  const releaseVersion = semver.parse(rawVersion.trim());

  // Check if the current branch is a release branch.
  if (!commitInBranches.some(isReleaseBranch)) {
    console.info("Current commit is not contained in any release branches.");
    return { releaseVersion: "v" + semver.format(releaseVersion) };
  }

  // If it's a GA version, return it directly
  if (isGaVer(releaseVersion)) {
    console.info("it's a normal GA version.");
    return { releaseVersion: "v" + semver.format(releaseVersion) };
  }

  const preRelease = (releaseVersion.prerelease || []).join(".");
  let newGitTag = undefined;
  if (preRelease.startsWith("alpha")) {
    newGitTag =
      `v${releaseVersion.major}.${releaseVersion.minor}.${releaseVersion.patch}`;
  } else if (releaseVersion.prerelease![0] == "beta") {
    if (
      releaseVersion.prerelease!.length > 2 &&
      releaseVersion.prerelease![2].toString().startsWith("pre")
    ) {
      // for: v1.1.1-beta.0.pre | v1.1.1-beta.0.pre-2-g1234567
      newGitTag =
        `v${releaseVersion.major}.${releaseVersion.minor}.${releaseVersion.patch}-${
          releaseVersion.prerelease?.slice(0, 2).join(".")
        }`;
    } else {
      console.warn("I will do nothing for this beta version:", rawVersion);
    }
  } else if (releaseVersion.prerelease![0] == "rc") {
    if (
      releaseVersion.prerelease!.length == 2 &&
      typeof releaseVersion.prerelease![1] === "string"
    ) {
      console.info("Now i will increase the rc number.");
      const rcIndexStr = releaseVersion.prerelease![1].toString().split("-")[0];
      const newRCIndex = parseInt(rcIndexStr) + 1;
      newGitTag =
        `v${releaseVersion.major}.${releaseVersion.minor}.${releaseVersion.patch}-${
          releaseVersion.prerelease![0]
        }.${newRCIndex}`;
    } else if (
      releaseVersion.prerelease!.length > 2 &&
      releaseVersion.prerelease![2].toString().startsWith("pre")
    ) {
      // for: v1.1.1-rc.0.pre | v1.1.1-rc.0.pre-2-g1234567
      newGitTag =
        `v${releaseVersion.major}.${releaseVersion.minor}.${releaseVersion.patch}-${
          releaseVersion.prerelease?.slice(0, 2).join(".")
        }`;
    } else {
      console.warn("I will do nothing for this rc version:", rawVersion);
    }
  } else if (preRelease.startsWith("pre")) {
    newGitTag =
      `v${releaseVersion.major}.${releaseVersion.minor}.${releaseVersion.patch}`;
  } else {
    // Handle the case where there are new commits after a GA tag
    newGitTag = `v${releaseVersion.major}.${releaseVersion.minor}.${
      releaseVersion.patch + 1
    }`;
    releaseVersion.patch++;
    releaseVersion.prerelease = ["pre"];
  }

  if (newGitTag) {
    console.info(`Computed new tag: ${newGitTag}`);
  }

  return { releaseVersion: "v" + semver.format(releaseVersion), newGitTag };
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
