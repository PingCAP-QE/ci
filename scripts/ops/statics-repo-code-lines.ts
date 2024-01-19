import { exec, OutputMode } from "https://deno.land/x/exec@0.0.5/mod.ts";

async function dumpRepoCodes(repoDir: string): Promise<any> {
  const command = `tokei -e node_modules -o json ${repoDir}`;
  console.debug(command);
  const output = await exec(command, {
    output: OutputMode.Capture,
  });
  return JSON.parse(output.output);
}

async function cloneRepo(
  fullRepoName: string,
  branch?: string,
): Promise<string> {
  const cloneCommand =
    `gh repo clone ${fullRepoName} ${fullRepoName} -- --depth 1${
      branch ? ` --branch=${branch}` : ""
    }`;
  console.debug(cloneCommand);
  await exec(cloneCommand);
  return fullRepoName;
}

async function getOrgRepos(orgName: string): Promise<string[]> {
  const command = `
    gh repo list ${orgName} --source --no-archived -L 200 | awk '/${orgName}/ {print $1}'
  `;
  console.debug(command);
  const output = await exec(command, {
    output: OutputMode.Capture,
  });
  return output.output.split("\n").filter(Boolean);
}

function formatCloc(clocs: { [key: string]: any }) {
  const result: { [key: string]: any } = {};
  for (const lan in clocs) {
    result[lan] = {
      blanks: clocs[lan].blanks,
      code: clocs[lan].code,
      comments: clocs[lan].comments,
    };
  }

  return result;
}

function sumCloc(repoClocs: Record<string, any>): Record<string, any> {
  const result: Record<string, any> = {};
  for (const cloc of Object.values(repoClocs)) {
    for (const lan in cloc) {
      if (!(lan in result)) {
        result[lan] = {};
      }
      for (const lineType in cloc[lan]) {
        if (!(lineType in result[lan])) {
          result[lan][lineType] = 0;
        }
        result[lan][lineType] += cloc[lan][lineType];
      }
    }
  }
  return result;
}

async function getJson(filePath: string) {
  return JSON.parse(await Deno.readTextFile(filePath));
}

async function jsonToCsv(filePath: string) {
  const repos = await getJson(filePath);
  const langs = new Set<string>();
  repos.forEach((repo) => {
    for (const l in repo.langs) {
      langs.add(l);
    }
  });

  const result: any[][] = [];

  const titles = ["repo", "branch"];
  langs.forEach((lang) => {
    titles.push(
      `Line-${lang}-code`,
      `Line-${lang}-comments`,
      `Line-${lang}-blanks`,
    );
  });
  result.push(titles);

  repos.forEach((repo) => {
    const row = [repo.repo, repo.branch];
    langs.forEach((lang) => {
      row.push(
        repo.langs[lang]?.code || 0,
        repo.langs[lang]?.comments || 0,
        repo.langs[lang]?.blanks || 0,
      );
    });
    result.push(row);
  });

  return result;
}

// dump cloc(count lines of code) of each repo.
// calculate all repos under tidbcloud may take about 2 hours.
async function main() {
  // const repos = await getOrgRepos("<org>");
  const result: any[] = [];

  const branch = "master";
  const repos = [
    "pingcap/tidb",
    "pingcap/tiflow",
    "pingcap/tiflash",
    "tikv/pd",
    "tikv/tikv",
  ];
  const branches = [
    "master",
    "release-7.5",
    "release-7.4",
    "release-7.3",
    "release-7.2",
    "release-7.1",
    "release-7.0",
    "release-6.6",
    "release-6.5",
    "release-6.4",
    "release-6.3",
    "release-6.2",
    "release-6.1",
    "release-6.0",
    "release-5.4",
  ];

  for (const repo of repos) {
    if (repo.endsWith(".github.io") || repo.trim().length === 0) {
      continue;
    }
    for (const branch of branches) {
      const dirPath = await cloneRepo(repo, branch);
      const rawCodes = await dumpRepoCodes(dirPath);
      const repoResult = formatCloc(rawCodes);
      result.push({ repo, branch, langs: repoResult });
      await Deno.remove(dirPath, { recursive: true });
    }
  }

  const targetFile = "repo_dict.json";
  const targetCsvFile = "repo-codes.csv";
  await Deno.writeTextFile(targetFile, JSON.stringify(result));

  const table = await jsonToCsv(targetFile);
  await Deno.writeTextFile(
    targetCsvFile,
    table.map((r) => r.join(",")).join("\n"),
  );
}

await main();
