#!/usr/bin/env -S  deno run --allow-net

import { parseArgs } from "jsr:@std/cli@^1.0.1";
import { Octokit } from "https://esm.sh/octokit@4.0.2?dts";

interface CliParams {
  token: string; // github private token
  url: string; // repo url: https://github.com/pingcap/tidb.git
  ref?: string; // refs/heads/master
  eventUrl?: string; // https://example.com
  eventType?: string; // push | create
}

interface Payload {
  ref: string;
  before?: string;
  after?: string;
  ref_type: string;
  repository: {
    name: string;
    full_name: string;
    clone_url: string;
    owner: {
      login: string;
    };
  };
}

async function getCommitSha(
  owner: string,
  repo: string,
  ref: string,
  client: Octokit,
): Promise<string> {
  const response = await client.rest.repos.getCommit({ owner, repo, ref });
  return response.data.sha;
}

async function generatePushEventPayload(
  gitUrl: string,
  ref: string,
  client: Octokit,
) {
  const url = new URL(gitUrl);
  const owner = url.pathname.split("/")[1];
  const repoName = url.pathname.split("/").pop()?.replace(/\.git$/, "");

  return {
    before: "0000000000000000000000000000000000000000",
    after: await getCommitSha(owner, repoName!, ref, client),
    ref,
    repository: {
      name: repoName!,
      full_name: `${owner}/${repoName}`,
      owner: {
        login: owner,
      },
      clone_url: gitUrl,
    },
  };
}

function generateCreatePayload(
  gitUrl: string,
  ref: string,
  ref_type = "branch",
) {
  const url = new URL(gitUrl);
  const owner = url.pathname.split("/")[1];
  const repoName = url.pathname.split("/").pop()?.replace(/\.git$/, "");

  return {
    ref,
    ref_type,
    repository: {
      name: repoName!,
      full_name: `${owner}/${repoName}`,
      owner: {
        login: owner,
      },
      clone_url: gitUrl,
    },
  };
}

async function sendGithubEvent(
  eventType: string,
  gitUrl: string,
  ref: string,
  eventUrl: string,
  client: Octokit,
): Promise<void> {
  let eventPayload;
  switch (eventType) {
    case "create":
      eventPayload = generateCreatePayload(
        gitUrl,
        ref.replace("refs/tags/", "").replace("refs/heads/", ""),
        ref.startsWith("refs/tags/") ? "tag" : "branch",
      );
      break;
    case "push":
      eventPayload = await generatePushEventPayload(
        gitUrl,
        `refs/heads/${ref.replace("refs/heads/", "")}`,
        client,
      );
      break;
    default:
      break;
  }

  const fetchInit = {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-GitHub-Event": eventType,
    },
    body: JSON.stringify(eventPayload),
  };
  console.dir(fetchInit);
  console.dir(fetchInit.body);

  await fetch(eventUrl, fetchInit);
}

async function main(args: CliParams) {
  const gitUrl = args.url;
  const ref = args.ref || "refs/heads/master";
  const eventUrl = args.eventUrl || "https://example.com";
  const eventType = args.eventType || "push";

  const usage =
    "Usage: deno run script.ts --token <github-token> --url <git-url> --eventType push|create [--ref <ref>] [--eventUrl <event-url>]";
  if (!gitUrl || !args.token) {
    console.error(usage);
    Deno.exit(1);
  }

  const octokit = new Octokit({
    auth: args.token,
  });

  await sendGithubEvent(eventType, gitUrl, ref, eventUrl, octokit);
}

const args = parseArgs(Deno.args) as CliParams;
await main(args);
Deno.exit();
