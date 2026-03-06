import { CaseAgg, ReportData } from "./types.ts";

export const DEFAULT_ISSUE_SUBSCRIBE_TEXT =
  "You can substitute the flaky test issues by TiRelease Bot with '/sub --repo=pingcap/tidb --label=flaky-test'";

export function parseRepo(repo: string): { owner: string; repo: string } | null {
  if (!repo) return null;
  const parts = repo.split("/").filter(Boolean);
  if (parts.length !== 2) return null;
  const [owner, name] = parts;
  if (!owner || !name) return null;
  return { owner, repo: name };
}

export function formatSuiteName(s: string): string {
  if (s && s.startsWith("//")) {
    const noPrefix = s.slice(2);
    const [pkg] = noPrefix.split(":");
    return pkg;
  }
  return s;
}

export function buildIssueTitle(
  r: CaseAgg,
  opts?: { includeRepo?: boolean },
): string {
  const base = `Flaky test: ${r.case_name} in ${formatSuiteName(r.suite_name)}`;
  if (opts?.includeRepo) return `${base} [${r.repo}]`;
  return base;
}

export function buildIssueBody(
  report: ReportData,
  r: CaseAgg,
  opts?: { includeRepo?: boolean },
): string {
  const title = buildIssueTitle(r, opts);
  const lines = [
    "Automated flaky test report.",
    "",
    `Title: ${title}`,
    `- Repo: ${r.repo}`,
    `- Package: ${formatSuiteName(r.suite_name)}`,
    `- Case: ${r.case_name}`,
    `- Branch: ${r.branch}`,
    `- Flaky Count: ${r.flakyCount ?? 0}`,
    `- Time Thresholded Count: ${r.thresholdedCount ?? 0}`,
    r.latestBuildUrl
      ? `- Latest Build: ${r.latestBuildUrl}`
      : `- Latest Build: N/A`,
    "",
    `Window: ${report.window.from} → ${report.window.to}`,
    `Threshold: ${report.window.thresholdMs} ms`,
    "",
    "Note: This issue is created for a specific branch; updates may be posted as comments.",
  ];
  return lines.join("\n");
}

export function buildIssueComment(report: ReportData, r: CaseAgg): string {
  const lines = [
    "Automated flaky test report update.",
    "",
    `- Repo: ${r.repo}`,
    `- Branch: ${r.branch}`,
    `- Package: ${formatSuiteName(r.suite_name)}`,
    `- Case: ${r.case_name}`,
    `- Flaky Count: ${r.flakyCount ?? 0}`,
    `- Time Thresholded Count: ${r.thresholdedCount ?? 0}`,
    r.latestBuildUrl
      ? `- Latest Build: ${r.latestBuildUrl}`
      : `- Latest Build: N/A`,
    "",
    `Window: ${report.window.from} → ${report.window.to}`,
    `Threshold: ${report.window.thresholdMs} ms`,
  ];
  return lines.join("\n");
}
