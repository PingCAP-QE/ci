#!/usr/bin/env python3
"""
Repository Scripts Analyzer using GitHub MCP Server Functions

This tool analyzes repositories to find build and test scripts (shell scripts, Python scripts, Makefiles)
and generates reports in the docs/jobs/<org>/<repo>/repo-scripts-report.md format.

This version demonstrates how to use the available GitHub MCP server functions.
Since we cannot directly call these functions from Python, this serves as a template.
"""

import os
import sys
import json
import re
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Set, Tuple

# Template for using GitHub MCP server functions
GITHUB_FUNCTIONS_TEMPLATE = '''
# GitHub MCP Server Functions Available:

## Get file contents:
github-mcp-server-get_file_contents(owner, repo, path, ref)

## Search code:
github-mcp-server-search_code(query, page, perPage, sort, order)

## List repository files:
Can use get_file_contents with path="/" to list directory contents

# Example search queries for finding scripts:
- "filename:*.sh repo:pingcap/tidb" 
- "filename:*.py repo:pingcap/tidb"
- "filename:Makefile repo:pingcap/tidb"
'''

class GitHubMCPAnalyzer:
    """
    This class demonstrates how the analyzer would work with real GitHub MCP functions.
    Since we cannot directly call MCP functions from Python, this serves as documentation.
    """
    
    def __init__(self):
        self.script_patterns = {
            'shell': r'\.sh$',
            'python': r'\.py$',
            'makefile': r'^[Mm]akefile$|\.mk$'
        }
        
        self.build_indicators = {
            'build', 'compile', 'make', 'package', 'bundle', 'dist', 'release',
            'install', 'setup', 'configure', 'gen', 'generate'
        }
        
        self.test_indicators = {
            'test', 'check', 'lint', 'validate', 'verify', 'bench', 'benchmark',
            'integration', 'unit', 'e2e', 'spec', 'ci', 'coverage'
        }
    
    def analyze_repository_template(self, owner: str, repo: str) -> str:
        """
        Returns the template code for analyzing a repository using MCP functions.
        This would need to be implemented using the MCP interface.
        """
        return f'''
# Analysis template for {owner}/{repo}

## Step 1: Search for shell scripts
search_query = "filename:*.sh repo:{owner}/{repo}"
# Call: github-mcp-server-search_code(query=search_query)

## Step 2: Search for Python scripts  
search_query = "filename:*.py repo:{owner}/{repo}"
# Call: github-mcp-server-search_code(query=search_query)

## Step 3: Search for Makefiles
search_query = "filename:Makefile repo:{owner}/{repo}"
# Call: github-mcp-server-search_code(query=search_query)

## Step 4: For each found script, get content
# Call: github-mcp-server-get_file_contents(owner="{owner}", repo="{repo}", path=script_path)

## Step 5: Classify scripts based on content and filename
# Apply classification logic to determine if script is for build, test, or other purposes
'''

def generate_mcp_analysis_script(repositories):
    """Generate a script template that shows how to use MCP functions for analysis"""
    
    script_content = '''#!/usr/bin/env python3
"""
GitHub MCP Repository Analysis Script

This script demonstrates how to analyze repositories using GitHub MCP server functions.
You would need to integrate this with the MCP interface to make actual function calls.
"""

# Repository analysis functions using MCP

def analyze_repository_with_mcp(owner, repo):
    """
    Template function showing how to analyze a repository using MCP functions.
    Replace comments with actual MCP function calls.
    """
    
    scripts = {'shell': [], 'python': [], 'makefile': []}
    
    # Search for shell scripts
    # shell_results = github-mcp-server-search_code(
    #     query=f"filename:*.sh repo:{owner}/{repo}",
    #     perPage=100
    # )
    
    # Search for Python scripts
    # python_results = github-mcp-server-search_code(
    #     query=f"filename:*.py repo:{owner}/{repo}", 
    #     perPage=100
    # )
    
    # Search for Makefiles
    # makefile_results = github-mcp-server-search_code(
    #     query=f"filename:Makefile repo:{owner}/{repo}",
    #     perPage=100
    # )
    
    # For each found file, get content and classify
    # for item in shell_results['items']:
    #     content = github-mcp-server-get_file_contents(
    #         owner=owner,
    #         repo=repo, 
    #         path=item['path']
    #     )
    #     purpose = classify_script_type(item['name'], content)
    #     scripts['shell'].append({
    #         'path': item['path'],
    #         'filename': item['name'],
    #         'purpose': purpose,
    #         'size': item.get('size', 0)
    #     })
    
    return {
        'owner': owner,
        'repo': repo,
        'scripts': scripts,
        'analyzed_at': datetime.now().isoformat()
    }

def classify_script_type(filename, content=""):
    """Classify if a script is for build, test, or other purposes"""
    filename_lower = filename.lower()
    content_lower = content.lower()
    
    build_indicators = {
        'build', 'compile', 'make', 'package', 'bundle', 'dist', 'release',
        'install', 'setup', 'configure', 'gen', 'generate'
    }
    
    test_indicators = {
        'test', 'check', 'lint', 'validate', 'verify', 'bench', 'benchmark',
        'integration', 'unit', 'e2e', 'spec', 'ci', 'coverage'
    }
    
    # Check filename for indicators
    build_score = sum(1 for indicator in build_indicators 
                     if indicator in filename_lower)
    test_score = sum(1 for indicator in test_indicators 
                    if indicator in filename_lower)
    
    # Check content for indicators (first 1000 characters)
    content_preview = content_lower[:1000]
    build_score += sum(1 for indicator in build_indicators 
                      if indicator in content_preview) * 0.5
    test_score += sum(1 for indicator in test_indicators 
                     if indicator in content_preview) * 0.5
    
    if test_score > build_score:
        return "test"
    elif build_score > 0:
        return "build"
    else:
        return "other"

# List of repositories to analyze
repositories = [
'''
    
    for owner, repo in repositories:
        script_content += f'    ("{owner}", "{repo}"),\n'
    
    script_content += ''']

def main():
    """Main analysis function"""
    for owner, repo in repositories:
        print(f"Analyzing {owner}/{repo}...")
        analysis = analyze_repository_with_mcp(owner, repo)
        # Save report logic here
        
if __name__ == "__main__":
    main()
'''
    
    return script_content

def main():
    """Generate the MCP analysis template"""
    repositories = [
        ('pingcap', 'docs'),
        ('pingcap', 'docs-cn'),
        ('pingcap', 'docs-tidb-operator'),
        ('pingcap', 'ticdc'),
        ('pingcap', 'tidb'),
        ('pingcap', 'tidb-tools'),
        ('pingcap', 'tiflash'),
        ('pingcap', 'tiflow'),
        ('pingcap', 'tiproxy'),
        ('tikv', 'copr-test'),
        ('tikv', 'migration'),
        ('tikv', 'pd'),
        ('tikv', 'tikv'),
    ]
    
    # Generate MCP analysis script template
    script_template = generate_mcp_analysis_script(repositories)
    
    # Save the template
    template_path = Path(__file__).parent / "mcp_analysis_template.py"
    with open(template_path, 'w') as f:
        f.write(script_template)
    
    print(f"MCP analysis template saved to: {template_path}")
    print("\nTo implement real analysis:")
    print("1. Use the GitHub MCP server functions available in your environment")
    print("2. Replace the commented function calls with actual MCP calls")
    print("3. Integrate with the report generation logic from the mock analyzer")

if __name__ == "__main__":
    main()