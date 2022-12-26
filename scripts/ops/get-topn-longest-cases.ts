import * as flags from "https://deno.land/std/flags/mod.ts";
import * as path from "https://deno.land/std/path/mod.ts";
import { format } from "https://deno.land/std@0.170.0/path/win32.ts";
import {
  Direction,
  ISortOptions,
  SortService,
} from "https://deno.land/x/sort@v1.1.1/mod.ts";

/**
 * typescript style guide: https://google.github.io/styleguide/tsguide.html
 */

interface cliParams {
  buildUrl: string;
  topN?: number;
}

async function main({ buildUrl, topN = 10 }: cliParams) {
  const reportUrl = path.join(buildUrl, "testReport/api/json");
  const report = await (await fetch(reportUrl)).json();
  const cases: any[] = [];

  report.suites.forEach((suite: { cases: any[] }) => {
    suite.cases.forEach(({ className, name, duration }) =>
      cases.push({ className, name, duration })
    );
  });

  const sortOptions: ISortOptions[] = [
    { fieldName: "duration", direction: Direction.DESCENDING },
  ];

  const sortedCases = SortService.sort(cases, sortOptions);
  console.log(`total count: ${sortedCases.length}`);
  console.log(
    `P50: <=${sortedCases[Math.floor(sortedCases.length * 0.5)]["duration"]}`,
  );
  console.log(
    `P80: <=${sortedCases[Math.floor(sortedCases.length * 0.2)]["duration"]}`,
  );
  console.log(
    `P90: <=${sortedCases[Math.floor(sortedCases.length * 0.1)]["duration"]}`,
  );
  console.log(
    `P95: <=${sortedCases[Math.floor(sortedCases.length * 0.05)]["duration"]}`,
  );
  console.log(
    `P99: <=${sortedCases[Math.floor(sortedCases.length * 0.01)]["duration"]}`,
  );
  console.log("-------------------");

  sortedCases.slice(0, topN).forEach(
    ({ className, name, duration }) => {
      console.log(className, name, duration);
    },
  );
}

const cliArgs = flags.parse(Deno.args) as cliParams;
await main(cliArgs);
console.log("~~~~~~~~~~~end~~~~~~~~~~~~~~");
