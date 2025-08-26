# NextGen Project Operation Scripts

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
