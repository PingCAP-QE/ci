async function dumpRepoCodes(repoDir: string): Promise<any> {
  console.info("🚀 start statistic repo codes");
  const command = new Deno.Command("tokei", {
    args: ["-e", "node_modules", "-o", "json", repoDir],
  });
  const result = await command.output();
  const textDecoder = new TextDecoder();

  console.info("✅ finish statistic repo codes");
  return JSON.parse(textDecoder.decode(result.stdout));
}

async function cloneRepo(
  fullRepoName: string,
  branch?: string,
): Promise<string> {
  console.info("🚀 start clone repo:", fullRepoName, "with branch", branch);
  const cloneCommand = new Deno.Command("gh", {
    args: [
      "repo",
      "clone",
      fullRepoName,
      fullRepoName,
      "--",
      "--depth",
      "1",
      branch ? `--branch=${branch}` : "",
    ],
  });
  const result = await cloneCommand.output();
  if (!result.success) {
    console.error("❌ clone repo failed:", fullRepoName, "with branch", branch);
  }

  console.info("✅ finish clone repo:", fullRepoName, "with branch", branch);
  return fullRepoName;
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

  const repos = [
    "pingcap/tidb",
    "pingcap/tiflow",
    "pingcap/tiflash",
    "tikv/pd",
    "tikv/tikv",
  ];
  const branches = [
    "master",
    "release-8.1",
    "release-8.0",
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
