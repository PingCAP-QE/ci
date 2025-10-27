# NextGen Project Operation Scripts

## Next-gen Exact Image Tags Fetcher

A Bash utility that resolves human-friendly branch tags to the exact, content-addressed image tags for next-gen components by inspecting the image’s embedded git SHA and listing available tags from the container registry.

### Overview

The `ci/scripts/ops/nextgen/get-next-gen-exact-image-tags.sh` script:
- Reads the `net.pingcap.tibuild.git-sha` label from a given image tag
- Derives the short commit SHA
- Finds the exact image tag built from that commit (next-gen flavored), validating its existence via digest lookup
- Prints a concise mapping from the branch-style tag to the exact commit-based tag

This is useful when you need deterministic, immutable image tags for deployments or debugging.

### Prerequisites

- jq (for JSON parsing)
- crane (for interacting with container registries)
  - Make sure you have access to `gcr.io/pingcap-public` (e.g., via `gcloud auth configure-docker gcr.io` or `crane auth login gcr.io`)

If either tool is missing, the script exits with a non-zero code and an actionable message.

### Usage

- Directly (executable):
```bash
./ci/scripts/ops/nextgen/get-next-gen-exact-image-tags.sh
```
- Or via Bash:
```bash
bash ci/scripts/ops/nextgen/get-next-gen-exact-image-tags.sh
```

The script prints the results for all covered repositories and branches.

### Repositories and branches covered

- pingcap/ticdc
  - Repo: `gcr.io/pingcap-public/dbaas/ticdc`
  - Tags checked: `master-next-gen`, `release-8.5-next-gen`
- pingcap/tidb family
  - Repo: `gcr.io/pingcap-public/dbaas/tidb`
    - Tags checked: `master-next-gen`, `release-nextgen-20251011`
  - Repo: `gcr.io/pingcap-public/dbaas/br`
    - Tags checked: `master-next-gen`, `release-nextgen-20251011`
  - Repo: `gcr.io/pingcap-public/dbaas/tidb-lightning`
    - Tags checked: `master-next-gen`, `release-nextgen-20251011`
  - Repo: `gcr.io/pingcap-public/dbaas/dumpling`
    - Tags checked: `master-next-gen`, `release-nextgen-20251011`
- pingcap/tiflash
  - Repo: `gcr.io/pingcap-public/dbaas/tiflash`
  - Tags checked: `master-next-gen`, `release-nextgen-20251011`
- pingcap/tiproxy
  - Repo: `gcr.io/pingcap-public/dbaas/tiproxy`
  - Tags checked: `main`, `release-nextgen-20251023`
- tidbcloud/cloud-storage-engine (TiKV)
  - Repo: `gcr.io/pingcap-public/dbaas/tikv`
  - Tags checked: `dedicated-next-gen`, `release-nextgen-20251011`
- tikv/pd (PD)
  - Repo: `gcr.io/pingcap-public/dbaas/pd`
  - Tags checked: `master-next-gen`, `release-nextgen-20251011`

Notes:
- Some components use `<trunk>-next-gen` tags for trunk verification.
- Some release branches are used as-is (no `-next-gen` suffix).

### Output example

```text
🚀 Fetch images built from pingcap/tidb...
💿 gcr.io/pingcap-public/dbaas/tidb
  📦 gcr.io/pingcap-public/dbaas/tidb:master-next-gen
    👉 gcr.io/pingcap-public/dbaas/tidb:next-gen-...-abcd1234...
  📦 gcr.io/pingcap-public/dbaas/tidb:release-nextgen-20251011
    👉 gcr.io/pingcap-public/dbaas/tidb:release-nextgen-20251011-...-abcd1234...

🚀 Fetch images built from pingcap/tiproxy...
  📦 gcr.io/pingcap-public/dbaas/tiproxy:main
    👉 gcr.io/pingcap-public/dbaas/tiproxy:...-gabcd123
  📦 gcr.io/pingcap-public/dbaas/tiproxy:release-nextgen-20251023
    👉 gcr.io/pingcap-public/dbaas/tiproxy:release-nextgen-20251023-...-gabcd123

🎉🎉🎉 All gotten
```

- When an exact tag is found and verified, you will see the pair:
  - base tag line: `📦 repo:branch_or_release_tag`
  - exact tag line: `👉 repo:resolved_exact_tag`
- If no matching commit-based tag is found, only the base tag line is printed.
- If a candidate exact tag is found but its digest cannot be verified, the script prints:
  - `🤷 Image repo:tag not found`
  - and exits with a non-zero code.

### Special cases and matching rules

- For most repos, exact tags are detected by looking for tags that:
  - contain the short commit SHA, and
  - include a word-bounded `next-gen` marker (for next-gen components).
- For `tiproxy`, exact tags follow a different convention and include `-g<shortsha>`; the script uses a tailored pattern for this repository.
- The script validates the found tag with `crane digest repo:tag` before printing it.

### Exit codes

- 0: Completed successfully (tools present; any found exact tags are printed and validated)
- 1: Failure
  - `jq` not installed
  - `crane` not installed
  - A candidate exact image tag was found but does not exist in the registry (digest lookup failed)

Note: If no matching exact tag is found for a given base tag, the script does not treat it as an error; it prints only the base tag line and continues.

### Troubleshooting

- Authentication to `gcr.io`:
  - Ensure you have credentials that allow listing and pulling:
    - `gcloud auth configure-docker gcr.io`, or
    - `crane auth login gcr.io`
- Verify tools:
  - `jq --version`
  - `crane version`
- Verbose registry checks:
  - Try `crane ls gcr.io/pingcap-public/dbaas/<component>` manually to confirm visible tags
  - Try `crane config <repo>:<tag>` to confirm the `net.pingcap.tibuild.git-sha` label exists

### Maintenance notes

- Update the branch variables in the script as release trains change:
  - `trunk_branch` and `release_branch` values per repository block
- Add/remove repositories by copying an existing block in `fetch_all()` and adjusting:
  - `img_repo` value
  - whether trunk uses `-next-gen` suffix
  - special-case matching (e.g., `tiproxy`)
- Keep tag naming conventions consistent across components to ensure reliable matching.
- Consider pinning crane and jq versions in your environment to reduce drift.

## Test Failure Statistics Analyzer

A Deno-based script that analyzes failed test cases from Jenkins JUnit reports and generates statistical insights about error patterns with enhanced visual formatting.

### Overview

The `statistics_failure_tests_from_junit_report.ts` script fetches JSON test reports from Jenkins, analyzes failed test cases, extracts meaningful error patterns, and generates statistics sorted by frequency. This helps identify the most common failure patterns in your test suite with improved visual presentation.

### Features

- **Configurable URL**: Specify Jenkins report URL via command line or environment variable
- **Advanced Error Extraction**: Intelligent filtering of stack traces to extract meaningful error messages
- **Multiple Output Formats**: Support for text, JSON, and CSV output formats
- **Enhanced Visual Output**: Console grouping and table formatting for better readability
- **Progress Indicators**: Verbose mode for detailed logging and progress tracking
- **Flexible Filtering**: Minimum occurrence count and result limit options
- **Error Handling**: Robust error handling and validation

### Installation

The script requires Deno runtime. Install Deno from [deno.land](https://deno.land/) if not already installed.

### Usage

```bash
deno run --allow-net --allow-env ci/scripts/ops/nextgen/statistics_failure_tests_from_j
unit_report.ts [options]
```

### Command Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `--url <url>` | Jenkins test report URL | Required |
| `--output <format>` | Output format: `text`, `json`, or `csv` | `text` |
| `--limit <number>` | Limit output to top N results | No limit |
| `--min-count <number>` | Minimum occurrence count to include | 1 |
| `--verbose` | Enable verbose output with detailed tables | Disabled |
| `--help` | Show help message | - |

### Environment Variables

- `JENKINS_URL`: Alternative way to specify the Jenkins report URL

### Examples

1. **Basic usage with enhanced text output:**
   ```bash
   deno run --allow-net --allow-env statistics_failure_tests_from_junit_report.ts --url "https://jenkins.example.com/job/test-job/lastBuild/testReport/api/json"
   ```

2. **JSON output format:**
   ```bash
   deno run --allow-net --allow-env statistics_failure_tests_from_junit_report.ts --url "https://jenkins.example.com/report" --output json
   ```

3. **Verbose mode with detailed table output:**
   ```bash
   deno run --allow-net --allow-env statistics_failure_tests_from_junit_report.ts --url "https://jenkins.example.com/report" --verbose
   ```

4. **Limit to top 10 results with minimum 2 occurrences:**
   ```bash
   deno run --allow-net --allow-env statistics_failure_tests_from_junit_report.ts --url "https://jenkins.example.com/report" --limit 10 --min-count 2
   ```

5. **CSV output format:**
   ```bash
   deno run --allow-net --allow-env statistics_failure_tests_from_junit_report.ts --url "https://jenkins.example.com/report" --output csv
   ```

### Output Formats

#### Enhanced Text Format (Default)
Displays a human-readable summary with error patterns sorted by frequency using console grouping and table formatting:

```
Test Failure Statistics (5 unique error patterns):

🚩   15 × Error: Connection timeout
     Affected 3 tests:
     ┌─────────┬─────────────────────┐
     │ (index) │      Test Case      │
     ├─────────┼─────────────────────┤
     │    0    │ 'TestClass.testMethod1' │
     │    1    │ 'TestClass.testMethod2' │
     │    2    │ 'TestClass.testMethod3' │
     └─────────┴─────────────────────┘

🚩    8 × Error: Database connection failed
     Affected 2 tests:
     ┌─────────┬─────────────────────┐
     │ (index) │      Test Case      │
     ├─────────┼─────────────────────┤
     │    0    │ 'DBTest.connectTest'  │
     │    1    │ 'DBTest.queryTest'    │
     └─────────┴─────────────────────┘

Total unique error patterns: 5
Total occurrences: 35
```

#### JSON Format
Provides structured data for programmatic consumption:
```json
{
  "statistics": [
    {
      "errorMessage": "Error: Connection timeout",
      "count": 15,
      "testCases": ["TestClass.testMethod1", "TestClass.testMethod2"],
      "firstOccurrence": "TestClass.testMethod1"
    }
  ],
  "summary": {
    "totalPatterns": 5,
    "totalOccurrences": 35
  }
}
```

#### CSV Format
Generates comma-separated values for spreadsheet import:
```csv
"Count","Error Message","Affected Tests Count","First Occurrence"
"15","Error: Connection timeout","3","TestClass.testMethod1"
"8","Error: Database connection failed","2","DBTest.connectTest"
```

### Enhanced Visual Features

The script now includes improved visual formatting:

- **🚩 Emoji Indicators**: Clear visual markers for each error pattern
- **Console Grouping**: Hierarchical organization of output
- **Table Formatting**: Clean tabular display of affected test cases in verbose mode
- **Better Spacing**: Improved readability with proper indentation and grouping

### Error Message Extraction

The script uses intelligent filtering to extract meaningful error patterns:
- Skips empty lines and common unhelpful patterns
- Captures error messages and relevant context
- Filters out stack trace details while preserving error content
- Handles comparison failures and assertion errors specifically

### Integration with Jenkins

To use with Jenkins, provide the JSON API endpoint of your test report:
```
https://[JENKINS_URL]/job/[JOB_NAME]/[BUILD_NUMBER]/testReport/api/json
```

### Error Handling

The script includes comprehensive error handling:
- Validates URL presence and format
- Handles HTTP errors with descriptive messages
- Gracefully handles malformed JSON responses
- Provides clear error messages for troubleshooting

### Permissions

The script requires the following Deno permissions:
- `--allow
-net`: To fetch
 test reports from Jenkins
- `--allow-env`: To read environment variables

### Development

The script is written in Deno and uses:
- Deno standard library for argument parsing
- Modern JavaScript features (ES6+)
- Type-safe interfaces for data structures
- Console grouping and table formatting for enhanced output

### Contributing

To contribute to this script:
1. Ensure code follows existing style patterns
2. Add appropriate error handling
3. Include tests for new functionality
4. Update documentation for any changes
5. Maintain the enhanced visual formatting standards
