import { parseArgs } from "jsr:@std/cli@^1.0.1/parse-args";
import { Octokit } from "npm:octokit@3.1.0";

interface CliArgs {
  github_private_token?: string;
  pr_url?: string;
  check_name?: string;
}

function usage(): never {
  console.error(
    [
      "Usage:",
      "  deno run -A ci/scripts/ops/skip-a-failed-ci-status.ts \\",
      "    --github_private_token <token> \\",
      "    --pr_url https://github.com/<owner>/<repo>/pull/<number> \\",
      "    [--check_name <status-context>]",
      "",
      "Behavior:",
      "  - When --check_name is provided, the script marks that status context as success on the last commit of the PR.",
      "  - When --check_name is omitted, the script lists failed commit statuses on the last commit and prompts you to select one to mark as success.",
    ].join("\n"),
  );
  Deno.exit(2);
}

function parsePRUrl(
  prUrl: string,
): { owner: string; repo: string; pull_number: number } {
  const url = new URL(prUrl);
  const parts = url.pathname.split("/").filter(Boolean);
  // expect: /<owner>/<repo>/pull/<number>
  const owner = parts[0];
  const repo = parts[1];
  if (parts[2] !== "pull") {
    throw new Error(`Invalid PR URL: not a pull request URL: ${prUrl}`);
  }
  const pull_number = Number(parts[3]);
  if (!owner || !repo || !Number.isFinite(pull_number)) {
    throw new Error(`Invalid PR URL: ${prUrl}`);
  }
  return { owner, repo, pull_number };
}

type StatusItem = {
  context: string;
  state: string;
  description?: string | null;
  target_url?: string | null;
};

async function listFailedStatusesForRef(octokit: Octokit, p: {
  owner: string;
  repo: string;
  ref: string;
}): Promise<StatusItem[]> {
  const { data } = await octokit.rest.repos.getCombinedStatusForRef(p);
  // States: "failure" | "error" | "pending" | "success"
  const failed = data.statuses.filter((s) =>
    s.state === "failure" || s.state === "error"
  );
  // Some providers can emit duplicates; keep last occurrence per context
  const byContext = new Map<string, StatusItem>();
  for (const s of failed) {
    byContext.set(s.context, {
      context: s.context,
      state: s.state,
      description: s.description ?? null,
      target_url: s.target_url ?? null,
    });
  }
  return Array.from(byContext.values());
}

function promptSelectFromList(items: StatusItem[]): StatusItem | null {
  if (items.length === 0) {
    return null;
  }
  console.log("Select a failed status to mark as success:");
  items.forEach((s, i) => {
    const idx = i + 1;
    const desc = s.description ? ` — ${s.description}` : "";
    const url = s.target_url ? ` (${s.target_url})` : "";
    console.log(`${idx}. [${s.state}] ${s.context}${desc}${url}`);
  });

  // Keep prompting until valid choice or user quits.
  // Deno provides a global prompt() in interactive terminals.
  while (true) {
    const input = prompt(
      `Enter a number (1-${items.length}) to select, or 'q' to quit:`,
    );
    if (input === null) {
      // Ctrl+C or no input; treat as quit
      return null;
    }
    const trimmed = input.trim().toLowerCase();
    if (trimmed === "q" || trimmed === "quit" || trimmed === "exit") {
      return null;
    }
    const n = Number(trimmed);
    if (Number.isInteger(n) && n >= 1 && n <= items.length) {
      return items[n - 1]!;
    }
    console.log(
      `Invalid selection: "${input}". Please enter a number between 1 and ${items.length}, or 'q' to quit.`,
    );
  }
}

async function main({ github_private_token, pr_url, check_name }: CliArgs) {
  if (!github_private_token || !pr_url) {
    usage();
  }

  const octokit = new Octokit({ auth: github_private_token });

  const { owner, repo, pull_number } = parsePRUrl(pr_url!);
  console.log(`PR: ${owner}/${repo}#${pull_number}`);

  const pr = await octokit.rest.pulls.get({ owner, repo, pull_number });
  const mergeableState = (pr.data.mergeable_state ?? "").toString()
    .toUpperCase();
  if (mergeableState === "CONFLICTING" || mergeableState === "DIRTY") {
    console.error(
      `PR ${pull_number} is conflicted (mergeable_state=${pr.data.mergeable_state}). Please resolve it manually.`,
    );
    Deno.exit(1);
  }

  const sha = pr.data.head.sha;
  console.log(`Last PR commit: ${sha}`);

  let contextToSkip = check_name;

  if (!contextToSkip) {
    const failed = await listFailedStatusesForRef(octokit, {
      owner,
      repo,
      ref: sha,
    });

    if (failed.length === 0) {
      console.log("No failed commit statuses found on the latest commit.");
      console.log("Nothing to skip. Exiting.");
      return;
    }

    const selected = promptSelectFromList(failed);
    if (!selected) {
      console.log("No selection made. Exiting without changes.");
      return;
    }
    contextToSkip = selected.context;
    console.log(`Selected status context: ${contextToSkip}`);
  }

  // Mark the selected or provided status context as success
  await octokit.rest.repos.createCommitStatus({
    owner,
    repo,
    sha,
    state: "success",
    context: contextToSkip!,
    description: "Skipped by CI bot, no need to run it to save time",
  });

  console.info("✅ Set commit status to success");
}

const args = parseArgs(Deno.args) as CliArgs;
await main(args);
