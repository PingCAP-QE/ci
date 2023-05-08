import * as flags from "https://deno.land/std@0.185.0/flags/mod.ts";
import * as path from "https://deno.land/std@0.185.0/path/mod.ts";

interface taskCreatePayload {
  git_refs: Record<string, unknown> | string;
  cached_key?: string;
  scan_args?: {
    scan_type: string; // "pr";
    task_source: string; // "ci";
  };
}

interface _cliParams extends taskCreatePayload {
  git_refs: string;
  base_url: string;
  token: string;
  save_task_id_to?: string;
  save_report_to?: string;
}

async function createTask(
  payload: taskCreatePayload,
  serverBaseUrl: string,
  token: string,
) {
  const headers = new Headers();
  headers.set("Content-Type", "application/json");
  headers.set("Authorization", token);

  const apiUrl = path.join(serverBaseUrl, "api/v1/task/create");
  const resp = await fetch(apiUrl, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  });

  const body = await resp.json();
  console.debug("response for task creating:", body);

  return body.data;
}

async function waitTask(taskId: string, serverBaseUrl: string, token: string) {
  const headers = new Headers();
  headers.set("Authorization", token);

  const apiUrl = new URL("api/v1/task/info", serverBaseUrl);
  apiUrl.search = new URLSearchParams({ task_id: taskId }).toString();

  let taskInfo;
  while (true) {
    fetch;
    const taskInfoReq = await fetch(apiUrl, { headers });
    taskInfo = (await taskInfoReq.json()).data;

    // 1: queued, 2: scanning, 3: failed  4: success
    if (taskInfo.scan_status > 2) {
      break;
    }

    await new Promise((resolve) => setTimeout(resolve, 5000));
  }

  return taskInfo;
}

async function main({
  base_url,
  token,
  git_refs,
  cached_key,
  save_task_id_to,
  save_report_to,
}: _cliParams) {
  const createPayload: taskCreatePayload = {
    git_refs: JSON.parse(git_refs),
    cached_key,
  };
  const taskId = await createTask(createPayload, base_url, token);
  console.info("%cScan task id:", "color: green", taskId);

  const taskInfo = await waitTask(taskId, base_url, token);
  console.info("%cTask info:", "color: yellow", taskInfo);

  save_task_id_to && await Deno.writeTextFile(save_task_id_to, taskId);
  save_report_to &&
    await Deno.writeTextFile(save_report_to, taskInfo.report_content);

  // 1: blocked, 2: pass, 3: watched, 4: not enabled.
  if (taskInfo.audit_status === 1) {
    console.error("%c Audit status", "color: red", taskInfo.audit_status);
    console.error(`Report:\n${taskInfo.report_content}`);
    console.error(`Report decoded:\n${atob(taskInfo.report_content)}`);
    Deno.exit(1);
  } else {
    console.info("%c Audit status", "color: green", taskInfo.audit_status);
  }
}

/**
 * ---------entry----------------
 * ****** CLI args **************
 * --git_refs       git refs, please ref prow job;
 * --cached_git_key cached key for git refs, optional.
 * --base_url  base url of security scan server
 * --token          api token of security scan server
 * --save_task_id_to file path to save task id
 * --save_report_to file path to save report content.
 */
const cliArgs = flags.parse<_cliParams>(Deno.args);
await main(cliArgs);
