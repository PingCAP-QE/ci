# Guide: How to Cherry-Pick a Pull Request to Another Branch

This guide explains how to cherry-pick changes from one pull request onto another branch. This is particularly useful when you need to backport fixes or features to release branches.

## Prerequisites

Before you start, ensure you have:

1. A local clone of the repository
2. The GitHub CLI (`gh`) installed - [Installation Guide](https://github.com/cli/cli#installation)
3. Set up your `GITHUB_USER` environment variable to your GitHub username

## Using the Helper Script

The repository includes a helper script [`scripts/ops/cherry_pick_pull.sh`](../../scripts/ops/cherry_pick_pull.sh) that automates most of the cherry-picking process. It provides a streamlined way to cherry-pick changes from one branch to another while handling the associated GitHub operations. It's particularly useful for maintaining release branches when you need to backport specific fixes.

### Basic Usage

```bash
ci-repo-cloned-path/scripts/ops/cherry_pick_pull.sh <target-branch> <PR-number> [<additional-PR-numbers>...]
```

For example:
```bash
ci-repo-cloned-path/scripts/ops/cherry_pick_pull.sh upstream/release-8.5 12345
```

This will:
1. Create a new branch based on `upstream/release-8.5`
2. Cherry-pick PR #12345 onto this branch
3. Push the new branch to your fork
4. Open a GitHub PR for the cherry-pick

### Cherry-Picking Multiple PRs

You can cherry-pick multiple PRs in a single command:

```bash
ci-repo-cloned-path/scripts/ops/cherry_pick_pull.sh upstream/release-8.5 12345 67890
```

This will cherry-pick PRs #12345 and #67890 in sequence.

### Advanced Options

The script supports several environment variables to modify its behavior:

- `DRY_RUN`: Skip pushing to GitHub and creating a PR (useful for testing)
- `REGENERATE_DOCS`: Regenerate documentation after cherry-picking (if applicable)
- `UPSTREAM_REMOTE`: Change the name of the upstream remote (default: "upstream")
- `FORK_REMOTE`: Change the name of your fork remote (default: "origin")

Example with environment variables:
```bash
GITHUB_USER=myusername DRY_RUN=1 ci-repo-cloned-path/scripts/ops/cherry_pick_pull.sh upstream/release-8.5 12345
```

## Step-by-Step Process

Here's what happens behind the scenes when you use the script:

1. The script checks that you have a clean working tree
2. It creates a new branch with a name format: `automated-cherry-pick-of-#PR-ID-target-branch-timestamp`
3. It downloads the patch files for the specified PRs
4. It attempts to apply each patch using `git am -3`
5. If there are conflicts, it prompts you to resolve them
6. After successful application of all patches, it pushes the branch to your fork
7. It creates a PR to the target branch with appropriate title and description

## Handling Conflicts

If the cherry-pick encounters conflicts:

1. The script will prompt you to resolve conflicts in another window
2. Resolve the conflicts in your editor
3. Add the resolved files with `git add <files>`
4. Continue the cherry-pick with `git am --continue`
5. When prompted by the script, enter 'y' to proceed


## Resolving Merge Conflicts in a Pull Request

Please refer to the official GitHub documentation for resolving merge conflicts using GitHub web or the command line:
[Addressing merge conflicts](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/addressing-merge-conflicts)

## Manual Cherry-Picking (Alternative)

If you need more control, you can perform the cherry-pick manually:

1. Check out the target branch:
   ```bash
   git checkout upstream/release-8.5
   git checkout -b cherry-pick-pr-12345
   ```

2. Find the commit hashes for the PR (can be seen on the PR page):
   ```bash
   git cherry-pick <commit-hash>
   ```

3. Resolve any conflicts if they occur:
   ```bash
   # After resolving conflicts in your editor
   git add <resolved-files>
   git cherry-pick --continue
   ```

4. Push to **your fork** and create a PR to upstream `release-8.5` branch manually:
   ```bash
   git push origin cherry-pick-pr-12345
   # Then create a PR to upstream release-8.5 branch manually.
   ```

## Best Practices

1. Always make sure your local repository is up-to-date before cherry-picking
2. Create a separate branch for each cherry-pick operation
3. Test the cherry-picked changes before creating a PR
4. Clearly document the source PR in your cherry-pick PR description
5. If conflicts arise, consider consulting with the original PR author

## Troubleshooting

- **Authentication Issues**: Ensure you're authenticated with the GitHub CLI (`gh auth login`)
- **Remote Issues**: Check your remote configuration with `git remote -v`
- **Merge Conflicts**: Complex conflicts might require manual resolution
- **Failed Cherry-Pick**: Try using the manual cherry-pick method if the script fails

## References

- [Cherry Pick Requests documentation of K8S project](https://git.k8s.io/community/contributors/devel/sig-release/cherry-picks.md)
- [Git Cherry-Pick documentation](https://git-scm.com/docs/git-cherry-pick)
- [GitHub Documentation for Addressing merge conflicts](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/addressing-merge-conflicts)
