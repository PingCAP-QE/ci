# Repository Scripts Analyzer

This tool analyzes GitHub repositories to identify build and test scripts, including shell scripts (`.sh`), Python scripts (`.py`), and Makefiles.

## Features

- Analyzes multiple repositories via GitHub API
- Identifies shell scripts, Python scripts, and Makefiles
- Classifies scripts as build, test, or other based on naming conventions and content
- Generates detailed markdown reports
- Saves reports in the `docs/jobs/<org>/<repo>/repo-scripts-report.md` structure

## Installation

1. Install Python dependencies:
```bash
pip install -r requirements.txt
```

2. (Optional) Set GitHub token for higher API rate limits:
```bash
export GITHUB_TOKEN=your_github_token_here
```

## Usage

Run the analyzer to process all configured repositories:

```bash
python analyze_repo_scripts.py
```

The tool will analyze the following repositories:
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

## Output

Reports are saved to:
```
docs/jobs/<org>/<repo>/repo-scripts-report.md
```

Each report contains:
- Summary statistics
- Build scripts section
- Test scripts section
- Other scripts section
- Detailed breakdown by script type

## Configuration

You can modify the list of repositories in the `main()` function of `analyze_repo_scripts.py`.

The script classification is based on:
- **Build indicators**: build, compile, make, package, bundle, dist, release, install, setup, configure, gen, generate
- **Test indicators**: test, check, lint, validate, verify, bench, benchmark, integration, unit, e2e, spec, ci, coverage

## API Rate Limits

Without authentication, GitHub API allows 60 requests per hour. With a GitHub token, you get 5000 requests per hour. The tool will automatically use the token if the `GITHUB_TOKEN` environment variable is set.