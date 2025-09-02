#!/usr/bin/env -S deno run --allow-net --allow-env
import { parseArgs } from "jsr:@std/cli@1.0.21/parse-args";

/**
 * Test Failure Statistics Analyzer
 *
 * This script fetches JSON test reports from Jenkins, analyzes failed test cases,
 * extracts meaningful error patterns, and generates statistics sorted by frequency.
 *
 * Features:
 * - Configurable URL via command line argument or environment variable
 * - Advanced error message extraction and filtering
 * - Multiple output formats (text, json, csv)
 * - Progress indicators and verbose logging
 * - Error handling and validation
 */

interface TestCase {
  status: string;
  errorStackTrace?: string;
  name?: string;
  className?: string;
  duration?: number;
}

interface TestSuite {
  cases: TestCase[];
  name?: string;
}

interface TestReport {
  suites: TestSuite[];
  failCount?: number;
  passCount?: number;
  skipCount?: number;
}

interface ErrorStat {
  errorMessage: string;
  count: number;
  testCases: string[];
  firstOccurrence?: string;
}

async function fetchTestReport(url: string): Promise<TestReport> {
  if (!url) {
    throw new Error("No URL provided");
  }

  console.error(`Fetching test report from: ${url}`);
  const resp = await fetch(url);

  if (!resp.ok) {
    throw new Error(
      `Failed to fetch test report: ${resp.status} ${resp.statusText}`,
    );
  }

  const json = await resp.json();
  return json as TestReport;
}

function extractRelevantErrorLines(stack: string): string[] {
  const lines = stack.split("\n");
  const result: string[] = [];

  const errorLogRegex = /\[(ERROR|FATAL)\] \[[^:\]\[]+\.go:\d+\] \["[^"]+"\]/;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();

    // Skip empty lines and common unhelpful patterns
    if (
      !line ||
      /^--$/.test(line) ||
      /^\s*expected:/.test(line) ||
      /^\s*Test:\s*Test/.test(line) ||
      /^\s*at\s/.test(line)
    ) {
      continue;
    }

    // Capture error messages and relevant context
    switch (true) {
      case /^\s*Error:\s*.+/.test(line):
        if (/Not\s+equal:/.test(line)) {
          result.push(line);
        } else {
          result.push(line + "\n" + lines[i + 1]);
        }
        break;
      case /^panic: runtime error:\s*/.test(line):
        if (/\[recovered\]/.test(line)) {
          // skip
        } else {
          result.push(line);
        }
        break;
      case errorLogRegex.test(line):
        result.push(errorLogRegex.exec(line)![0]);
        break;
    }
  }

  return result;
}

function analyzeFailedTests(
  report: TestReport,
  verbose: boolean,
  minCount: number,
  limit = 0,
): ErrorStat[] {
  const errorStats = new Map<string, ErrorStat>();
  const failedCases: TestCase[] = [];

  // Collect all failed test cases
  for (const suite of report.suites || []) {
    for (const testCase of suite.cases || []) {
      if (testCase.status === "FAILED" && testCase.errorStackTrace) {
        failedCases.push(testCase);
      }
    }
  }

  if (verbose) {
    console.error(`Found ${failedCases.length} failed test cases`);
  }

  // Process each failed test case
  for (const testCase of failedCases) {
    const relevantLines = extractRelevantErrorLines(testCase.errorStackTrace!);

    for (const line of relevantLines) {
      const trimmed = line.trim();
      if (!trimmed) continue;

      const testName = testCase.name || "Unknown test";
      const className = testCase.className || "Unknown class";
      const testIdentifier = `${className}.${testName}`;

      if (!errorStats.has(trimmed)) {
        errorStats.set(trimmed, {
          errorMessage: trimmed,
          count: 0,
          testCases: [],
          firstOccurrence: testIdentifier,
        });
      }

      const stat = errorStats.get(trimmed)!;
      stat.count++;
      if (!stat.testCases.includes(testIdentifier)) {
        stat.testCases.push(testIdentifier);
      }
    }
  }

  let stats = Array.from(errorStats.values())
    .filter((stat) => stat.count >= minCount);

  // Sort by count descending, then by error message
  stats.sort((a, b) => {
    if (b.count !== a.count) {
      return b.count - a.count;
    }
    return a.errorMessage.localeCompare(b.errorMessage);
  });

  // Apply limit if specified
  if (limit > 0) {
    stats = stats.slice(0, limit);
  }

  return stats;
}

function outputText(
  stats: ErrorStat[],
  verbose = false,
): void {
  console.log(
    `\nTest Failure Statistics (${stats.length} unique error patterns):\n`,
  );

  for (const stat of stats) {
    console.group(
      `🚩 ${stat.count.toString().padStart(4)} × ${stat.errorMessage}`,
    );
    if (verbose && stat.testCases.length > 0) {
      console.log(`Affected ${stat.testCases.length} tests:`);
      console.table(stat.testCases.map((tc) => ({ "Test Case": tc })));
      console.groupEnd();
    }
    console.groupEnd();
  }

  console.log(`Total unique error patterns: ${stats.length}`);
  console.log(
    `Total occurrences: ${stats.reduce((sum, stat) => sum + stat.count, 0)}`,
  );
}

function outputJson(stats: ErrorStat[]): void {
  console.log(JSON.stringify(
    {
      statistics: stats,
      summary: {
        totalPatterns: stats.length,
        totalOccurrences: stats.reduce((sum, stat) => sum + stat.count, 0),
      },
    },
    null,
    2,
  ));
}

function outputCsv(stats: ErrorStat[]): void {
  console.log(
    '"Count","Error Message","Affected Tests Count","First Occurrence"',
  );
  for (const stat of stats) {
    const escapedMessage = stat.errorMessage.replace(/"/g, '""');
    console.log(
      `"${stat.count}","${escapedMessage}","${stat.testCases.length}","${
        stat.firstOccurrence || ""
      }"`,
    );
  }
}

function printHelp() {
  console.log(`
Test Failure Statistics Analyzer

Usage:
  deno run --allow-net --allow-env statistics_failure_tests_from_junit_report.ts [options]

Options:
  --url <url>        Jenkins test report URL
  --output <format>  Output format: text, json, csv (default: text)
  --limit <number>   Limit output to top N results (default: no limit)
  --min-count <number> Minimum occurrence count to include (default: 1)
  --verbose          Enable verbose output
  --help             Show this help message

Environment Variables:
  JENKINS_URL        Alternative way to specify the URL

Examples:
  deno run --allow-net --allow-env statistics_failure_tests_from_junit_report.ts --url "https://jenkins.example.com/report" --output json
  deno run --allow-net --allow-env statistics_failure_tests_from_junit_report.ts --limit 10 --min-count 2
`);
}

async function main() {
  try {
    const rawArgs = parseArgs(Deno.args, {
      boolean: ["help", "verbose"],
      string: ["url", "output", "limit", "min-count"],
      default: { output: "text", "min-count": "1", limit: "0" },
    });

    if (rawArgs.help) {
      printHelp();
      Deno.exit(0);
    }

    const url = rawArgs.url as string;
    const output = (rawArgs.output as string) || "text";
    const verbose = !!rawArgs.verbose;

    if (verbose) {
      console.error("Starting test failure analysis...");
      console.error(`URL: ${url}`);

      console.error(`Output format: ${output}`);
    }

    const report = await fetchTestReport(url);

    if (verbose) {
      const totalTests = (report.failCount || 0) + (report.passCount || 0) +
        (report.skipCount || 0);

      console.error(
        `Report summary: ${report.failCount} failed, ${report.passCount} passed, ${report.skipCount} skipped (${totalTests} total)`,
      );
    }

    const stats = analyzeFailedTests(
      report,
      rawArgs.verbose,
      parseInt(rawArgs["min-count"], 10),
      parseInt(rawArgs.limit, 10),
    );

    switch (output.toLowerCase()) {
      case "json":
        outputJson(stats);
        break;
      case "csv":
        outputCsv(stats);
        break;
      case "text":
      default:
        outputText(stats, rawArgs.verbose);
        break;
    }
  } catch (error) {
    if (error instanceof Error) {
      console.error(`Error: ${error.message}`);
    } else {
      console.error(`Error: ${String(error)}`);
    }
    Deno.exit(1);
  }
}

// Run the main function if this script is executed directly
if (import.meta.main) {
  await main();
}
