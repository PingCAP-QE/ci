import { CaseAgg, ReportData } from "../core/types.ts";

/**
 * HtmlRenderer - renders a single-file HTML report from aggregated data.
 *
 * Email-friendly mode:
 * - Avoids CSS variables
 * - Avoids <details>/<summary>
 * - Uses inline styles for rank bars
 *
 * Usage:
 *   const html = new HtmlRenderer({ email: true }).render(reportData);
 *   const html = new HtmlRenderer().render(reportData); // browser mode (default)
 */
export class HtmlRenderer {
  private readonly email: boolean;
  private readonly inlineBarWidthPx: number;

  constructor(opts?: { email?: boolean; inlineBarWidthPx?: number }) {
    this.email = !!opts?.email;
    this.inlineBarWidthPx = Math.max(
      40,
      Math.min(240, opts?.inlineBarWidthPx ?? 120),
    );
  }

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
    if (this.email) {
      // Email-friendly CSS: no CSS variables, no reliance on advanced selectors or pseudo-elements.
      return `
html, body { margin: 0; padding: 0; }
body { font-family: system-ui, -apple-system, Segoe UI, Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif; margin: 16px; color: #111827; }
h1, h2, h3 { margin: 0.6em 0 0.3em; }
.meta { color: #6b7280; margin-bottom: 12px; }
.kpi {
  display: inline-block;
  margin: 4px 16px 4px 0;
  padding: 6px 10px;
  background: #f1f8ff;
  border: 1px solid #daf0ff;
  border-radius: 6px;
}
table { border-collapse: collapse; width: 100%; margin: 12px 0 24px; }
th, td { border: 1px solid #e5e7eb; padding: 6px 8px; text-align: left; vertical-align: top; }
th { background: #f6f8fa; font-weight: 600; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace; }
.small { font-size: 12px; }
.muted { color: #6b7280; }
.owner { font-weight: 600; }
.email-section { border: 1px solid #e5e7eb; border-radius: 6px; margin: 12px 0 24px; }
.email-section > .email-section__title { padding: 8px 10px; background: #f6f8fa; font-weight: 600; }
.email-section > .email-section__content { padding: 10px; }
`.trim();
    }

    // Browser mode: keep the original richer styling
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

/* Rank bars (browser mode uses positioned overlay; email mode inlines styles per-cell) */
.rank-cell { position: relative; padding: 0; }
.rank-cell .rank-label { position: relative; z-index: 1; display: block; padding: 6px 8px; text-align: right; font-variant-numeric: tabular-nums; }
.rank-cell .rank-bar { position: absolute; top: 0; bottom: 0; left: 0; background: #f3f4f6; }
.rank-cell .rank-bar--flaky { background: #fee2e2; } /* light red */
.rank-cell .rank-bar--th { background: #dbeafe; } /* light blue */
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
    const rows = report.byTeam
      .filter((r) => (r.flakyCases || 0) > 0 || (r.thresholdedCases || 0) > 0)
      .map((r) =>
        `
<tr>
  <td class="owner">${escapeHtml(r.owner)}</td>
  <td class="mono">${escapeHtml(r.repo)}</td>
  <td class="mono">${escapeHtml(r.branch)}</td>
  <td>${this.num(r.flakyCases)}</td>
  <td>${this.num(r.thresholdedCases)}</td>
</tr>
`.trim()
      )
      .join("\n");

    const table = `
<table>
  <thead>
    <tr>
      <th>Team Owner</th>
      <th>Repo</th>
      <th>Branch</th>
      <th>Flaky Cases</th>
      <th>Time Thresholded Cases</th>
    </tr>
  </thead>
  <tbody>
${rows}
  </tbody>
</table>
`.trim();

    if (this.email) {
      return `
<h2>By Team</h2>
<div class="email-section">
  <div class="email-section__title">Details</div>
  <div class="email-section__content">
    ${table}
  </div>
</div>
      `.trim();
    }

    return `
<h2>By Team</h2>
<details>
  <summary>Details</summary>
  <div class="content">
    ${table}
  </div>
</details>
    `.trim();
  }

  private sectionSuite(report: ReportData): string {
    const rows = report.bySuite
      .filter((r) => (r.flakyCases || 0) > 0 || (r.thresholdedCases || 0) > 0)
      .map((r) =>
        `
<tr>
  <td class="owner">${escapeHtml(r.owner)}</td>
  <td class="mono">${escapeHtml(r.repo)}</td>
  <td class="mono">${escapeHtml(r.branch)}</td>
  <td class="mono">${escapeHtml(this.formatSuiteName(r.suite_name))}</td>
  <td>${this.num(r.flakyCases)}</td>
  <td>${this.num(r.thresholdedCases)}</td>
</tr>
`.trim()
      )
      .join("\n");

    const table = `
<table>
  <thead>
    <tr>
      <th>Team Owner</th>
      <th>Repo</th>
      <th>Branch</th>
      <th>Package</th>
      <th>Flaky Cases</th>
      <th>Time Thresholded Cases</th>
    </tr>
  </thead>
  <tbody>
${rows}
  </tbody>
</table>
`.trim();

    if (this.email) {
      return `
<h2>By Suite</h2>
<div class="email-section">
  <div class="email-section__title">Details</div>
  <div class="email-section__content">
    ${table}
  </div>
</div>
      `.trim();
    }

    return `
<h2>By Suite</h2>
<details>
  <summary>Details</summary>
  <div class="content">
    ${table}
  </div>
</details>
    `.trim();
  }

  private sectionCase(report: ReportData): string {
    const data = report.byCase.filter((x) =>
      (x.flakyCount || 0) > 0 || (x.thresholdedCount || 0) > 0
    );
    const maxFlaky = Math.max(1, ...data.map((x) => x.flakyCount || 0));
    const maxThresh = Math.max(1, ...data.map((x) => x.thresholdedCount || 0));

    const rows = data.map((r) => {
      const flakyPct = maxFlaky ? (r.flakyCount / maxFlaky) * 100 : 0;
      const thPct = maxThresh ? (r.thresholdedCount / maxThresh) * 100 : 0;

      return `
<tr>
  <td class="owner">${escapeHtml(r.owner)}</td>
  <td class="mono">${escapeHtml(r.repo)}</td>
  <td class="mono">${escapeHtml(r.branch)}</td>
  <td class="mono">${escapeHtml(this.formatSuiteName(r.suite_name))}</td>
  <td class="mono small">${escapeHtml(r.case_name)}</td>
  ${this.rankCell(r.flakyCount ?? 0, flakyPct, "flaky")}
  <td>${
        this.weekOnWeekDiff(r.flakyCount ?? 0, r.previousWeekFlakyCount ?? 0)
      }</td>
  ${this.rankCell(r.thresholdedCount ?? 0, thPct, "th")}
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
`.trim();
    }).join("\n");

    return `
<h2>By Case</h2>
<table>
  <thead>
    <tr>
      <th>Team Owner</th>
      <th>Repo</th>
      <th>Branch</th>
      <th>Package</th>
      <th>Case</th>
      <th>Flaky Count</th>
      <th>Flaky WoW</th>
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
    const data = report.topFlakyCases.filter((x) =>
      (x.flakyCount || 0) > 0 || (x.thresholdedCount || 0) > 0
    );
    const maxFlaky = Math.max(1, ...data.map((x) => x.flakyCount || 0));
    const maxThresh = Math.max(1, ...data.map((x) => x.thresholdedCount || 0));

    const rows = data.map((r, i) => {
      const flakyPct = maxFlaky ? (r.flakyCount / maxFlaky) * 100 : 0;
      const thPct = maxThresh ? (r.thresholdedCount / maxThresh) * 100 : 0;

      return `
<tr>
  <td>${this.num(i + 1)}</td>
  <td class="owner">${escapeHtml(r.owner)}</td>
  <td class="mono">${escapeHtml(r.repo)}</td>
  <td class="mono">${escapeHtml(r.branch)}</td>
  <td class="mono">${escapeHtml(this.formatSuiteName(r.suite_name))}</td>
  <td class="mono small">${escapeHtml(r.case_name)}</td>
  ${this.rankCell(r.flakyCount ?? 0, flakyPct, "flaky")}
  <td>${
        this.weekOnWeekDiff(r.flakyCount ?? 0, r.previousWeekFlakyCount ?? 0)
      }</td>
  ${this.rankCell(r.thresholdedCount ?? 0, thPct, "th")}
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
`.trim();
    }).join("\n");

    return `
<h2>Top 10 Flakiest Cases</h2>
<table>
  <thead>
    <tr>
      <th>#</th>
      <th>Team Owner</th>
      <th>Repo</th>
      <th>Branch</th>
      <th>Package</th>
      <th>Case</th>
      <th>Flaky Count</th>
      <th>Flaky WoW</th>
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

  private rankCell(
    count: number,
    pctOfMax: number,
    variant: "flaky" | "th",
  ): string {
    if (this.email) {
      const barColor = variant === "flaky" ? "#fee2e2" : "#dbeafe";
      const barBase = "#f3f4f6";
      const border = "#e5e7eb";

      const width = this.inlineBarWidthPx;
      const pct = Math.max(0, Math.min(100, pctOfMax || 0));
      const filledWidth = Math.round((pct / 100) * width);

      // Inline-friendly bar: "count | [####.....]" using nested spans with fixed pixel width
      return `
<td>
  <span style="font-variant-numeric: tabular-nums; min-width: 24px; display: inline-block; text-align: right; margin-right: 6px;">${
        this.num(count)
      }</span>
  <span style="display:inline-block; width:${width}px; height:12px; background:${barBase}; border:1px solid ${border}; border-radius:3px; vertical-align:middle; overflow:hidden;">
    <span style="display:inline-block; height:100%; width:${filledWidth}px; background:${barColor};"></span>
  </span>
</td>
      `.trim();
    }

    // Browser mode: keep the original overlay style for a full-width cell bar
    const cls = variant === "flaky" ? "rank-bar--flaky" : "rank-bar--th";
    const pct = Math.max(0, Math.min(100, pctOfMax || 0));
    return `
<td class="rank-cell">
  <span class="rank-bar ${cls}" style="width:${pct}%;"></span>
  <span class="rank-label">${this.num(count)}</span>
</td>
    `.trim();
  }

  private githubIssueSearchUrlForCase(r: CaseAgg): string {
    const [
      owner,
      repo,
    ] = (r.repo || "").split("/");
    if (!owner || !repo) return "#";
    const terms = [`"${r.case_name}"`].filter(Boolean).join(" ");
    return `https://github.com/${encodeURIComponent(owner)}/${
      encodeURIComponent(repo)
    }/issues?q=${encodeURIComponent(`${terms} in:title is:issue`)}`;
  }

  private githubNewIssueUrlForCase(report: ReportData, r: CaseAgg): string {
    const [owner, repo] = (r.repo || "").split("/");
    if (!owner || !repo) return "#";
    const title = `Flaky test: ${r.case_name} in ${
      this.formatSuiteName(r.suite_name)
    }`;
    const lines = [
      "Automated flaky test report.",
      "",
      `- Package: ${this.formatSuiteName(r.suite_name)}`,
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

  private formatSuiteName(s: string): string {
    if (s && s.startsWith("//")) {
      const noPrefix = s.slice(2);
      const [pkg] = noPrefix.split(":");
      return pkg;
    }
    return s;
  }

  /**
   * Calculate week-on-week difference and return formatted string with arrow indicator
   */
  private weekOnWeekDiff(current: number, previous: number): string {
    const diff = current - previous;
    if (diff === 0) {
      return "0";
    } else if (diff > 0) {
      return `⬆️${this.num(diff)}`;
    } else {
      return `⬇️${this.num(Math.abs(diff))}`;
    }
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
