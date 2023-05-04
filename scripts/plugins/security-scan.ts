import * as flags from "https://deno.land/std@0.185.0/flags/mod.ts";
import * as path from "https://deno.land/std@0.185.0/path/mod.ts";

interface scanParams {
  gitRefs: string;
  token: string;
  serverBaseUrl: string;
}

interface _cliParams extends scanParams {
  taskIdSaveFile?: string;
  reportSaveFile?: string;
}

async function createTask(
  gitRefs: string,
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
    body: gitRefs,
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
  gitRefs,
  token,
  serverBaseUrl,
  taskIdSaveFile,
  reportSaveFile,
}: _cliParams) {
  const taskId = await createTask(gitRefs, serverBaseUrl, token);
  console.info("%cScan task id:", "color: green", taskId);
  const taskInfo = await waitTask(taskId, serverBaseUrl, token);
  console.info("%cTask info:", "color: yellow", taskInfo);

  taskIdSaveFile && await Deno.writeTextFile(taskIdSaveFile, taskId);
  reportSaveFile &&
    await Deno.writeTextFile(reportSaveFile, taskInfo.report_content);

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
 * --gitRefs        git refs, please ref prow job;
 * --serverBaseUrl  base url of security scan server
 * --token          api token of security scan server
 * --taskIdSaveFile file path to save task id
 * --reportSaveFile file path to save report content.
 */
const cliArgs = flags.parse<_cliParams>(Deno.args);
await main(cliArgs);
