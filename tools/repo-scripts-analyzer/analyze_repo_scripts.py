#!/usr/bin/env python3
"""
Repository Scripts Analyzer

This tool analyzes repositories to find build and test scripts (shell scripts, Python scripts, Makefiles)
and generates reports in the docs/jobs/<org>/<repo>/repo-scripts-report.md format.

Note: This version uses the GitHub MCP server interface which should be available in the environment.
"""

import os
import sys
import json
import re
import base64
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Set, Tuple


class ScriptAnalyzer:
    def __init__(self, github_token: str = None):
        self.github_token = github_token
        self.session = requests.Session()
        if github_token:
            self.session.headers.update({'Authorization': f'token {github_token}'})
        
        # Define script patterns and their types
        self.script_patterns = {
            'shell': r'\.sh$',
            'python': r'\.py$',
            'makefile': r'^[Mm]akefile$|\.mk$'
        }
        
        # Define build/test indicators
        self.build_indicators = {
            'build', 'compile', 'make', 'package', 'bundle', 'dist', 'release',
            'install', 'setup', 'configure', 'gen', 'generate'
        }
        
        self.test_indicators = {
            'test', 'check', 'lint', 'validate', 'verify', 'bench', 'benchmark',
            'integration', 'unit', 'e2e', 'spec', 'ci', 'coverage'
        }
    
    def get_repo_tree(self, owner: str, repo: str, branch: str = 'main') -> List[Dict]:
        """Get the file tree from a GitHub repository"""
        url = f"https://api.github.com/repos/{owner}/{repo}/git/trees/{branch}?recursive=1"
        
        try:
            response = self.session.get(url)
            response.raise_for_status()
            return response.json().get('tree', [])
        except requests.exceptions.RequestException as e:
            print(f"Error fetching tree for {owner}/{repo}: {e}")
            # Try 'master' branch if 'main' fails
            if branch == 'main':
                return self.get_repo_tree(owner, repo, 'master')
            return []
    
    def get_file_content(self, owner: str, repo: str, path: str, branch: str = 'main') -> str:
        """Get the content of a file from GitHub repository"""
        url = f"https://api.github.com/repos/{owner}/{repo}/contents/{path}?ref={branch}"
        
        try:
            response = self.session.get(url)
            response.raise_for_status()
            content_data = response.json()
            
            if content_data.get('encoding') == 'base64':
                return base64.b64decode(content_data['content']).decode('utf-8', errors='ignore')
            return content_data.get('content', '')
        except requests.exceptions.RequestException as e:
            print(f"Error fetching content for {owner}/{repo}/{path}: {e}")
            return ""
    
    def classify_script_type(self, filename: str, content: str = "") -> str:
        """Classify if a script is for build, test, or other purposes"""
        filename_lower = filename.lower()
        content_lower = content.lower()
        
        # Check filename for indicators
        build_score = sum(1 for indicator in self.build_indicators 
                         if indicator in filename_lower)
        test_score = sum(1 for indicator in self.test_indicators 
                        if indicator in filename_lower)
        
        # Check content for indicators (first 1000 characters)
        content_preview = content_lower[:1000]
        build_score += sum(1 for indicator in self.build_indicators 
                          if indicator in content_preview) * 0.5
        test_score += sum(1 for indicator in self.test_indicators 
                         if indicator in content_preview) * 0.5
        
        if test_score > build_score:
            return "test"
        elif build_score > 0:
            return "build"
        else:
            return "other"
    
    def analyze_repository(self, owner: str, repo: str) -> Dict:
        """Analyze a single repository for scripts"""
        print(f"Analyzing {owner}/{repo}...")
        
        tree = self.get_repo_tree(owner, repo)
        if not tree:
            return {
                'owner': owner,
                'repo': repo,
                'error': 'Could not fetch repository tree',
                'scripts': {'shell': [], 'python': [], 'makefile': []}
            }
        
        scripts = {'shell': [], 'python': [], 'makefile': []}
        
        for item in tree:
            if item['type'] != 'blob':
                continue
            
            path = item['path']
            filename = os.path.basename(path)
            
            # Check if file matches any script pattern
            for script_type, pattern in self.script_patterns.items():
                if re.search(pattern, filename, re.IGNORECASE):
                    # Get file content for better classification
                    content = self.get_file_content(owner, repo, path)
                    purpose = self.classify_script_type(filename, content)
                    
                    script_info = {
                        'path': path,
                        'filename': filename,
                        'purpose': purpose,
                        'size': item.get('size', 0)
                    }
                    
                    scripts[script_type].append(script_info)
                    break
        
        return {
            'owner': owner,
            'repo': repo,
            'scripts': scripts,
            'analyzed_at': datetime.now().isoformat()
        }
    
    def generate_report(self, analysis: Dict) -> str:
        """Generate a markdown report for a repository analysis"""
        owner = analysis['owner']
        repo = analysis['repo']
        scripts = analysis['scripts']
        analyzed_at = analysis.get('analyzed_at', 'Unknown')
        
        if 'error' in analysis:
            return f"""# Repository Scripts Report: {owner}/{repo}

**Analysis Date:** {analyzed_at}

## Error
{analysis['error']}
"""
        
        # Count scripts by purpose
        build_scripts = []
        test_scripts = []
        other_scripts = []
        
        for script_type, script_list in scripts.items():
            for script in script_list:
                if script['purpose'] == 'build':
                    build_scripts.append((script_type, script))
                elif script['purpose'] == 'test':
                    test_scripts.append((script_type, script))
                else:
                    other_scripts.append((script_type, script))
        
        total_scripts = len(build_scripts) + len(test_scripts) + len(other_scripts)
        
        report = f"""# Repository Scripts Report: {owner}/{repo}

**Analysis Date:** {analyzed_at}

## Summary
- **Total Scripts Found:** {total_scripts}
- **Build Scripts:** {len(build_scripts)}
- **Test Scripts:** {len(test_scripts)}
- **Other Scripts:** {len(other_scripts)}

"""
        
        # Build Scripts Section
        if build_scripts:
            report += "## Build Scripts\n\n"
            for script_type, script in build_scripts:
                report += f"### {script['filename']} ({script_type.title()})\n"
                report += f"- **Path:** `{script['path']}`\n"
                report += f"- **Size:** {script['size']} bytes\n\n"
        
        # Test Scripts Section
        if test_scripts:
            report += "## Test Scripts\n\n"
            for script_type, script in test_scripts:
                report += f"### {script['filename']} ({script_type.title()})\n"
                report += f"- **Path:** `{script['path']}`\n"
                report += f"- **Size:** {script['size']} bytes\n\n"
        
        # Other Scripts Section
        if other_scripts:
            report += "## Other Scripts\n\n"
            for script_type, script in other_scripts:
                report += f"### {script['filename']} ({script_type.title()})\n"
                report += f"- **Path:** `{script['path']}`\n"
                report += f"- **Size:** {script['size']} bytes\n\n"
        
        # Detailed breakdown by type
        report += "## Detailed Breakdown by Script Type\n\n"
        
        for script_type, script_list in scripts.items():
            if script_list:
                report += f"### {script_type.title()} Scripts ({len(script_list)})\n\n"
                for script in script_list:
                    report += f"- `{script['path']}` ({script['purpose']})\n"
                report += "\n"
        
        return report
    
    def save_report(self, analysis: Dict, base_dir: str):
        """Save the analysis report to the appropriate directory"""
        owner = analysis['owner']
        repo = analysis['repo']
        
        # Create directory structure
        report_dir = Path(base_dir) / "docs" / "jobs" / owner / repo
        report_dir.mkdir(parents=True, exist_ok=True)
        
        # Generate and save report
        report_content = self.generate_report(analysis)
        report_path = report_dir / "repo-scripts-report.md"
        
        with open(report_path, 'w', encoding='utf-8') as f:
            f.write(report_content)
        
        print(f"Report saved to: {report_path}")
        return report_path


def main():
    """Main function to analyze all specified repositories"""
    # List of repositories to analyze
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
    
    # Get GitHub token from environment variable
    github_token = os.getenv('GITHUB_TOKEN')
    if not github_token:
        print("Warning: GITHUB_TOKEN not set. API rate limits may be restrictive.")
    
    # Get base directory (should be the root of the ci repository)
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    
    analyzer = ScriptAnalyzer(github_token)
    
    print(f"Starting analysis of {len(repositories)} repositories...")
    print(f"Reports will be saved to: {base_dir}/docs/jobs/")
    
    for owner, repo in repositories:
        try:
            analysis = analyzer.analyze_repository(owner, repo)
            analyzer.save_report(analysis, base_dir)
        except Exception as e:
            print(f"Error analyzing {owner}/{repo}: {e}")
            continue
    
    print("Analysis complete!")


if __name__ == "__main__":
    main()