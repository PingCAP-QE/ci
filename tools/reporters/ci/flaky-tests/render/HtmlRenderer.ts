import { CaseAgg, ReportData } from "../core/types.ts";

/**
 * HtmlRenderer - renders a single-file HTML report from aggregated data.
 *
 * Usage:
 *   const html = new HtmlRenderer().render(reportData);
 */
export class HtmlRenderer {
  render(report: ReportData): string {
    const css = this.styles();
    const header = this.header(report);
    const team = this.sectionTeam(report);
    const suite = this.sectionSuite(report);
    const byCase = this.sectionCase(report);
    const top = this.sectionTop(report);

    return [
      "<!DOCTYPE html>",
      "<html>",
      "<head>",
      '<meta charset="utf-8" />',
      '<meta name="viewport" content="width=device-width, initial-scale=1" />',
      "<title>Flaky Report</title>",
      `<style>${css}</style>`,
      "</head>",
      "<body>",
      header,
      top,
      byCase,
      team,
      suite,
      "</body>",
      "</html>",
    ].join("\n");
  }

  private styles(): string {
    return `
/* Basic layout */
:root {
  --border: #e5e7eb;
  --muted: #6b7280;
  --bg-th: #f6f8fa;
  --kpi-bg: #f1f8ff;
  --kpi-border: #daf0ff;
  --mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  --sans: system-ui, -apple-system, Segoe UI, Roboto, "Helvetica Neue", Arial, "Noto Sans", "Liberation Sans", "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji", sans-serif;
}
html, body { margin: 0; padding: 0; }
body { font-family: var(--sans); margin: 16px; color: #111827; }

/* Headings and meta */
h1, h2, h3 { margin: 0.6em 0 0.3em; }
.meta { color: var(--muted); margin-bottom: 12px; }
.kpi {
  display: inline-block;
  margin: 4px 16px 4px 0;
  padding: 6px 10px;
  background: var(--kpi-bg);
  border: 1px solid var(--kpi-border);
  border-radius: 6px;
}

/* Tables */
table { border-collapse: collapse; width: 100%; margin: 12px 0 24px; }
th, td { border: 1px solid var(--border); padding: 6px 8px; text-align: left; vertical-align: top; }
th { background: var(--bg-th); font-weight: 600; }

/* Utilities */
.mono { font-family: var(--mono); }
.small { font-size: 12px; }
.muted { color: var(--muted); }
.owner { font-weight: 600; }

/* Collapsibles */
details { margin: 12px 0 24px; border: 1px solid var(--border); border-radius: 6px; }
details > summary { cursor: pointer; padding: 8px 10px; background: var(--bg-th); font-weight: 600; list-style: none; }
details > summary::-webkit-details-marker { display: none; }
details[open] > summary { border-bottom: 1px solid var(--border); }
details .content { padding: 10px; }
    `.trim();
  }

  private header(report: ReportData): string {
    return `
<h1>Flaky Report</h1>
<div class="meta">
  <span class="kpi">Window: <b>${escapeHtml(report.window.from)}</b> → <b>${
      escapeHtml(report.window.to)
    }</b></span>
  <span class="kpi">Repos: <b>${this.num(report.summary.repos)}</b></span>
  <span class="kpi">Suites: <b>${this.num(report.summary.suites)}</b></span>
  <span class="kpi">Cases: <b>${this.num(report.summary.cases)}</b></span>
  <span class="kpi">Flaky Cases: <b>${
      this.num(report.summary.flakyCases)
    }</b></span>
  <span class="kpi">Threshold: <b>${
      this.num(report.window.thresholdMs)
    }</b> ms</span>
    <span class="kpi">Time Thresholded Cases: <b>${
      this.num(report.summary.thresholdedCases)
    }</b></span>
</div>
    `.trim();
  }

  private sectionTeam(report: ReportData): string {
    const rows = report.byTeam.map((r) =>
      `
<tr>
  <td class="owner">${escapeHtml(r.owner)}</td>
  <td class="mono">${escapeHtml(r.repo)}</td>
  <td class="mono">${escapeHtml(r.branch)}</td>
  <td>${this.num(r.flakyCases)}</td>
  <td>${this.num(r.thresholdedCases)}</td>
</tr>
`.trim()
    ).join("\n");

    return `
<details>
  <summary>By Team</summary>
  <div class="content">
<table>
  <thead>
    <tr>
      <th>Team Owner</th>
      <th>Repo</th>
      <th>Branch</th>
      <th>Flaky Cases</th>
      <th>Thresholded Cases</th>
    </tr>
  </thead>
  <tbody>
${rows}
  </tbody>
</table>
</div>
</details>
    `.trim();
  }

  private sectionSuite(report: ReportData): string {
    const rows = report.bySuite.map((r) =>
      `
<tr>
  <td class="owner">${escapeHtml(r.owner)}</td>
  <td class="mono">${escapeHtml(r.repo)}</td>
  <td class="mono">${escapeHtml(r.branch)}</td>
  <td class="mono">${escapeHtml(r.suite_name)}</td>
  <td>${this.num(r.flakyCases)}</td>
  <td>${this.num(r.thresholdedCases)}</td>
</tr>
`.trim()
    ).join("\n");

    return `
<details>
  <summary>By Package (Suite)</summary>
  <div class="content">
<table>
  <thead>
    <tr>
      <th>Team Owner</th>
      <th>Repo</th>
      <th>Branch</th>
      <th>Suite</th>
      <th>Flaky Cases</th>
      <th>Time Thresholded Cases</th>
    </tr>
  </thead>
  <tbody>
${rows}
  </tbody>
</table>
  </div>
</details>
    `.trim();
  }

  private sectionCase(report: ReportData): string {
    const rows = report.byCase.map((r) =>
      `
<tr>
  <td class="owner">${escapeHtml(r.owner)}</td>
  <td class="mono">${escapeHtml(r.repo)}</td>
  <td class="mono">${escapeHtml(r.branch)}</td>
  <td class="mono">${escapeHtml(r.suite_name)}</td>
  <td class="mono small">${escapeHtml(r.case_name)}</td>
  <td>${this.num(r.flakyCount)}</td>
  <td>${this.num(r.thresholdedCount)}</td>
  <td>${
        r.latestBuildUrl
          ? `<a href="${
            escapeHtml(r.latestBuildUrl)
          }" target="_blank" rel="noopener">link</a>`
          : `<span class="muted">N/A</span>`
      }</td>
  <td><a href="${
        escapeHtml(this.githubIssueSearchUrlForCase(r))
      }" target="_blank" rel="noopener">search</a></td>
  <td><a href="${
        escapeHtml(this.githubNewIssueUrlForCase(report, r))
      }" target="_blank" rel="noopener">new</a></td>

</tr>
`.trim()
    ).join("\n");

    return `
<h2>By Case</h2>
<table>
  <thead>
    <tr>
      <th>Team Owner</th>
      <th>Repo</th>
      <th>Branch</th>
      <th>Suite</th>
      <th>Case</th>
      <th>Flaky Count</th>
      <th>Time Thresholded Count</th>
      <th>Latest Build</th>
      <th>Issue Search</th>
      <th>Create Issue</th>

    </tr>
  </thead>
  <tbody>
${rows}
  </tbody>
</table>
    `.trim();
  }

  private sectionTop(report: ReportData): string {
    const rows = report.topFlakyCases.map((r, i) =>
      `
<tr>
  <td>${this.num(i + 1)}</td>
  <td class="owner">${escapeHtml(r.owner)}</td>
  <td class="mono">${escapeHtml(r.repo)}</td>
  <td class="mono">${escapeHtml(r.branch)}</td>
  <td class="mono">${escapeHtml(r.suite_name)}</td>
  <td class="mono small">${escapeHtml(r.case_name)}</td>
  <td>${this.num(r.flakyCount)}</td>
  <td>${this.num(r.thresholdedCount)}</td>
  <td>${
        r.latestBuildUrl
          ? `<a href="${
            escapeHtml(r.latestBuildUrl)
          }" target="_blank" rel="noopener">link</a>`
          : `<span class="muted">N/A</span>`
      }</td>
  <td><a href="${
        escapeHtml(this.githubIssueSearchUrlForCase(r))
      }" target="_blank" rel="noopener">search</a></td>
  <td><a href="${
        escapeHtml(this.githubNewIssueUrlForCase(report, r))
      }" target="_blank" rel="noopener">new</a></td>
</tr>
`.trim()
    ).join("\n");

    return `
<h2>Top 10 Flakiest Cases</h2>
<table>
  <thead>
    <tr>
      <th>#</th>
      <th>Team Owner</th>
      <th>Repo</th>
      <th>Branch</th>
      <th>Suite</th>
      <th>Case</th>
      <th>Flaky Count</th>
      <th>Thresholded Count</th>
      <th>Latest Build</th>
      <th>Issue Search</th>
      <th>Create Issue</th>
    </tr>
  </thead>
  <tbody>
${rows}
  </tbody>
</table>
    `.trim();
  }

  private githubIssueSearchUrlForCase(r: CaseAgg): string {
    const [owner, repo] = (r.repo || "").split("/");
    if (!owner || !repo) return "#";
    const terms = [`"${r.case_name}"`]
      .filter(Boolean)
      .join(" ");
    return `https://github.com/${encodeURIComponent(owner)}/${
      encodeURIComponent(repo)
    }/issues?q=${encodeURIComponent(`${terms} in:title is:issue`)}`;
  }

  private githubNewIssueUrlForCase(report: ReportData, r: CaseAgg): string {
    const [owner, repo] = (r.repo || "").split("/");
    if (!owner || !repo) return "#";
    const title = `Flaky test: ${r.case_name} in ${r.suite_name}`;
    const lines = [
      "Automated flaky test report.",
      "",
      `- Suite: ${r.suite_name}`,
      `- Case: ${r.case_name}`,
      `- Branch: ${r.branch}`,
      `- Flaky Count: ${r.flakyCount ?? 0}`,
      `- Thresholded Count: ${r.thresholdedCount ?? 0}`,
      r.latestBuildUrl
        ? `- Latest Build: ${r.latestBuildUrl}`
        : `- Latest Build: N/A`,
      "",
      `Window: ${report.window.from} → ${report.window.to}`,
      `Threshold: ${report.window.thresholdMs} ms`,
    ];
    const body = lines.join("\n");
    return `https://github.com/${encodeURIComponent(owner)}/${
      encodeURIComponent(repo)
    }/issues/new?title=${encodeURIComponent(title)}&body=${
      encodeURIComponent(body)
    }`;
  }

  private num(n: number): string {
    return Number.isFinite(n)
      ? new Intl.NumberFormat("en-US").format(n)
      : String(n);
  }
}

/* ------------------------------ Local helpers ------------------------------ */

function escapeHtml(s: string): string {
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
