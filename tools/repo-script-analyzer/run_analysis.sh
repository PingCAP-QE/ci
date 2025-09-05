#!/bin/bash
# Repository Script Analysis Runner
# This script runs the repository script analyzer for all specified repositories

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT_DIR="${REPO_ROOT}/docs/jobs"

# List of repositories to analyze
REPOS=(
    "pingcap/docs"
    "pingcap/docs-cn"
    "pingcap/docs-tidb-operator"
    "pingcap/ticdc"
    "pingcap/tidb"
    "pingcap/tidb-tools"
    "pingcap/tiflash"
    "pingcap/tiflow"
    "pingcap/tiproxy"
    "tikv/copr-test"
    "tikv/migration"
    "tikv/pd"
    "tikv/tikv"
)

# Function to install dependencies
install_deps() {
    echo "Installing Python dependencies..."
    if command -v pip3 &> /dev/null; then
        pip3 install -r "${SCRIPT_DIR}/requirements.txt"
    elif command -v pip &> /dev/null; then
        pip install -r "${SCRIPT_DIR}/requirements.txt"
    else
        echo "Error: pip not found. Please install Python pip."
        exit 1
    fi
}

# Function to generate sample reports quickly
generate_reports() {
    echo "Generating sample repository script analysis reports..."
    python3 "${SCRIPT_DIR}/generate_reports.py"
    
    if [[ $? -eq 0 ]]; then
        echo "✅ Successfully generated all reports"
    else
        echo "❌ Failed to generate reports"
    fi
}

# Function to check if we have a GitHub token
check_token() {
    if [[ -z "${GITHUB_TOKEN:-}" ]]; then
        echo "Warning: GITHUB_TOKEN not set. API rate limits may apply."
        echo "Set GITHUB_TOKEN environment variable for better performance."
    fi
}

# Function to analyze all repositories
analyze_repos() {
    local token_arg=""
    if [[ -n "${GITHUB_TOKEN:-}" ]]; then
        token_arg="--token ${GITHUB_TOKEN}"
    fi
    
    echo "Starting repository script analysis..."
    echo "Output directory: ${OUTPUT_DIR}"
    
    for repo in "${REPOS[@]}"; do
        echo ""
        echo "=== Analyzing ${repo} ==="
        
        # Extract owner and repo name for directory structure
        IFS='/' read -r owner repo_name <<< "${repo}"
        
        # Create output directory
        mkdir -p "${OUTPUT_DIR}/${owner}/${repo_name}"
        
        # Run analysis
        python3 "${SCRIPT_DIR}/analyze_repo_scripts.py" \
            ${token_arg} \
            --repos "${repo}" \
            --output-dir "${OUTPUT_DIR}"
        
        if [[ $? -eq 0 ]]; then
            echo "✅ Successfully analyzed ${repo}"
        else
            echo "❌ Failed to analyze ${repo}"
        fi
    done
    
    echo ""
    echo "Analysis complete! Reports saved in ${OUTPUT_DIR}"
}

# Function to show usage
usage() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  install    Install Python dependencies"
    echo "  generate   Generate sample reports quickly"
    echo "  analyze    Run full repository analysis (requires network access)"
    echo "  all        Install dependencies and run analysis (default)"
    echo ""
    echo "Environment variables:"
    echo "  GITHUB_TOKEN    GitHub API token (optional but recommended)"
}

# Main script logic
main() {
    local command="${1:-all}"
    
    case "${command}" in
        install)
            install_deps
            ;;
        generate)
            generate_reports
            ;;
        analyze)
            check_token
            analyze_repos
            ;;
        all)
            install_deps
            check_token
            analyze_repos
            ;;
        help|--help|-h)
            usage
            ;;
        *)
            echo "Unknown command: ${command}"
            usage
            exit 1
            ;;
    esac
}

# Run main function with all arguments
main "$@"