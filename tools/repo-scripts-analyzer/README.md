# Repository Scripts Analyzer

This tool analyzes GitHub repositories to identify build and test scripts, including shell scripts (`.sh`), Python scripts (`.py`), and Makefiles.

## Features

- Analyzes multiple repositories via GitHub API
- Identifies shell scripts, Python scripts, and Makefiles
- Classifies scripts as build, test, or other based on naming conventions and content
- Generates detailed markdown reports
- Saves reports in the `docs/jobs/<org>/<repo>/repo-scripts-report.md` structure
- Supports real GitHub data collection via MCP functions and enhanced mock data

## Installation

1. Install Python dependencies:
```bash
pip install -r requirements.txt
```

2. (Optional) Set GitHub token for higher API rate limits:
```bash
export GITHUB_TOKEN=your_github_token_here
```

## Tool Versions

### 1. Enhanced Analyzer (Recommended)
The main production tool with real GitHub data and enhanced mock data:

```bash
python analyze_enhanced.py
```

**Features:**
- Uses real GitHub data for `pingcap/tidb` and `tikv/tikv`
- Enhanced mock data for all other repositories based on realistic patterns
- Clear data source indicators in reports

### 2. Real Demo Analyzer
Demonstrates real GitHub MCP function integration:

```bash
python analyze_real_demo.py
```

**Features:**
- Shows integration with GitHub MCP server functions
- Real data collection from `pingcap/tidb`
- Template for expanding to other repositories

### 3. Mock Data Analyzer
Basic version with uniform mock data:

```bash
python analyze_repo_scripts_mock.py
```

**Features:**
- Consistent mock data across all repositories
- Good for testing and demonstration

### 4. MCP Template Generator
Generates templates for full GitHub MCP integration:

```bash
python generate_mcp_template.py
```

## Repository Coverage

The tool analyzes the following repositories:
- **pingcap/docs** (documentation with build scripts)
- **pingcap/docs-cn** (Chinese documentation)
- **pingcap/docs-tidb-operator** (operator documentation)
- **pingcap/ticdc** (change data capture)
- **pingcap/tidb** ✅ (main database - real data)
- **pingcap/tidb-tools** (database tools)
- **pingcap/tiflash** (columnar storage)
- **pingcap/tiflow** (data replication)
- **pingcap/tiproxy** (database proxy)
- **tikv/copr-test** (coprocessor tests)
- **tikv/migration** (migration utilities)
- **tikv/pd** (placement driver)
- **tikv/tikv** ✅ (distributed storage - real data)

✅ = Real GitHub data collected

## Output

Reports are saved to:
```
docs/jobs/<org>/<repo>/repo-scripts-report.md
```

Each report contains:
- **Summary statistics** (total, build, test, other scripts)
- **Build scripts section** with details
- **Test scripts section** with details 
- **Other scripts section** with details
- **Detailed breakdown by script type**
- **Data source indicator** (real vs enhanced mock)

## Script Classification

The tool classifies scripts based on filename and content patterns:

### Build Indicators
- build, compile, make, package, bundle, dist, release
- install, setup, configure, gen, generate, workspace, cargo

### Test Indicators  
- test, check, lint, validate, verify, bench, benchmark
- integration, unit, e2e, spec, ci, coverage, jenkins, clippy

## Configuration

You can modify the list of repositories in the `main()` function of any analyzer script.

## API Rate Limits

Without authentication, GitHub API allows 60 requests per hour. With a GitHub token, you get 5000 requests per hour. The tool will automatically use the token if the `GITHUB_TOKEN` environment variable is set.

## Development

The tool architecture supports:
- **Real data collection** via GitHub MCP server functions
- **Enhanced mock data** with repository-specific patterns
- **Extensible classification** system for script types
- **Modular report generation** with customizable templates