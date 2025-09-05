# Repository Script Analyzer

This tool analyzes build and test scripts across GitHub repositories, evaluating their CI usage, quality, and providing improvement suggestions.

## Features

- **Script Discovery**: Finds shell scripts (.sh), Python scripts (.py), and Makefiles in repositories
- **CI Usage Analysis**: Determines if scripts are used in CI/CD pipelines and provides coverage ratings
- **Quality Assessment**: Evaluates script quality with star ratings and improvement suggestions
- **Complexity Analysis**: Measures script complexity on a 1-10 scale
- **Automated Reporting**: Generates detailed markdown reports with summary tables

## Usage

### Quick Start

```bash
# Generate reports for all configured repositories (quick)
python3 generate_reports.py

# Or run full analysis (requires network access to clone repositories)
./run_analysis.sh

# Or step by step:
./run_analysis.sh install    # Install dependencies
./run_analysis.sh analyze    # Run analysis
```

### Manual Usage

```bash
# Install dependencies
pip3 install -r requirements.txt

# Analyze specific repositories
python3 analyze_repo_scripts.py --repos pingcap/tidb tikv/tikv --output-dir ./reports

# With GitHub token for better rate limits
GITHUB_TOKEN=your_token python3 analyze_repo_scripts.py --repos pingcap/tidb
```

### Environment Variables

- `GITHUB_TOKEN`: Optional GitHub API token for better rate limits and private repository access

## Output

Reports are saved in `docs/jobs/<org>/<repo>/repo-scripts-report.md` with:

1. **Summary Table**: Overview of all scripts with ratings
   - CI usage star ratings (⭐-⭐⭐⭐⭐⭐ based on CI references)
   - Quality ratings (⭐-⭐⭐⭐⭐⭐ based on code quality)
   - Complexity scores (1-10)

2. **Detailed Analysis**: Per-script breakdown including:
   - Path and basic information
   - CI usage and reference locations
   - Quality assessment and improvement suggestions

## Analysis Criteria

### CI Usage Rating
- ⭐⭐⭐⭐⭐: Referenced in 5+ CI files
- ⭐⭐⭐⭐: Referenced in 3-4 CI files  
- ⭐⭐⭐: Referenced in 2 CI files
- ⭐⭐: Referenced in 1 CI file
- N/A: Not used in CI

### Quality Rating
Based on code quality factors:
- Header comments and documentation
- Proper shebang lines
- Error handling patterns
- Best practices for each script type
- Comment-to-code ratio

### Complexity Score (1-10)
Calculated based on:
- Script length
- Control flow structures (if/while/for)
- Function/class definitions
- Variable usage patterns

## Supported Repositories

Currently configured to analyze:
- pingcap/docs
- pingcap/docs-cn
- pingcap/docs-tidb-operator
- pingcap/ticdc
- pingcap/tidb
- pingcap/tidb-tools
- pingcap/tiflash
- pingcap/tiflow
- pingcap/tiproxy
- tikv/copr-test
- tikv/migration
- tikv/pd
- tikv/tikv

## Requirements

- Python 3.6+
- requests library
- Internet access for GitHub API calls
- Optional: GitHub token for better rate limits