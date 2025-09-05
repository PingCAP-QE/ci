#!/usr/bin/env python3
"""
Repository Scripts Analyzer using GitHub MCP Server

This tool analyzes repositories to find build and test scripts (shell scripts, Python scripts, Makefiles)
and generates reports in the docs/jobs/<org>/<repo>/repo-scripts-report.md format.

This version uses sample data and GitHub MCP server functions to demonstrate the functionality.
"""

import os
import sys
import json
import re
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Set, Tuple


class ScriptAnalyzer:
    def __init__(self):
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
    
    def analyze_repository_mock(self, owner: str, repo: str) -> Dict:
        """Mock analysis of a repository - generates sample data for demonstration"""
        print(f"Analyzing {owner}/{repo}... (using mock data)")
        
        # Mock data based on typical repository structures
        mock_scripts = {
            'shell': [],
            'python': [],
            'makefile': []
        }
        
        # Generate mock scripts based on repository type
        if 'docs' in repo:
            # Documentation repositories typically have build scripts
            mock_scripts['shell'] = [
                {'path': 'scripts/build.sh', 'filename': 'build.sh', 'purpose': 'build', 'size': 1500},
                {'path': 'scripts/deploy.sh', 'filename': 'deploy.sh', 'purpose': 'build', 'size': 2300}
            ]
            mock_scripts['makefile'] = [
                {'path': 'Makefile', 'filename': 'Makefile', 'purpose': 'build', 'size': 800}
            ]
        elif 'tidb' in repo or 'tikv' in repo or 'pd' in repo:
            # Database repositories typically have many build and test scripts
            mock_scripts['shell'] = [
                {'path': 'scripts/build.sh', 'filename': 'build.sh', 'purpose': 'build', 'size': 3200},
                {'path': 'scripts/test.sh', 'filename': 'test.sh', 'purpose': 'test', 'size': 2100},
                {'path': 'scripts/integration_test.sh', 'filename': 'integration_test.sh', 'purpose': 'test', 'size': 4500},
                {'path': 'scripts/benchmark.sh', 'filename': 'benchmark.sh', 'purpose': 'test', 'size': 1800},
                {'path': 'scripts/install.sh', 'filename': 'install.sh', 'purpose': 'build', 'size': 1200}
            ]
            mock_scripts['python'] = [
                {'path': 'tests/run_tests.py', 'filename': 'run_tests.py', 'purpose': 'test', 'size': 2700},
                {'path': 'tools/build_tools.py', 'filename': 'build_tools.py', 'purpose': 'build', 'size': 3400}
            ]
            mock_scripts['makefile'] = [
                {'path': 'Makefile', 'filename': 'Makefile', 'purpose': 'build', 'size': 5600},
                {'path': 'tests/Makefile', 'filename': 'Makefile', 'purpose': 'test', 'size': 1400}
            ]
        elif 'tiflow' in repo or 'ticdc' in repo:
            # CDC/Flow repositories
            mock_scripts['shell'] = [
                {'path': 'scripts/run_integration_tests.sh', 'filename': 'run_integration_tests.sh', 'purpose': 'test', 'size': 3800},
                {'path': 'scripts/build_docker.sh', 'filename': 'build_docker.sh', 'purpose': 'build', 'size': 2200},
                {'path': 'scripts/check_lint.sh', 'filename': 'check_lint.sh', 'purpose': 'test', 'size': 900}
            ]
            mock_scripts['makefile'] = [
                {'path': 'Makefile', 'filename': 'Makefile', 'purpose': 'build', 'size': 3200}
            ]
        else:
            # Other repositories - minimal scripts
            mock_scripts['shell'] = [
                {'path': 'scripts/build.sh', 'filename': 'build.sh', 'purpose': 'build', 'size': 1000}
            ]
            mock_scripts['makefile'] = [
                {'path': 'Makefile', 'filename': 'Makefile', 'purpose': 'build', 'size': 600}
            ]
        
        return {
            'owner': owner,
            'repo': repo,
            'scripts': mock_scripts,
            'analyzed_at': datetime.now().isoformat(),
            'note': 'This is mock data for demonstration purposes'
        }
    
    def generate_report(self, analysis: Dict) -> str:
        """Generate a markdown report for a repository analysis"""
        owner = analysis['owner']
        repo = analysis['repo']
        scripts = analysis['scripts']
        analyzed_at = analysis.get('analyzed_at', 'Unknown')
        is_mock = 'note' in analysis
        
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

"""
        
        if is_mock:
            report += """**Note:** This report contains mock data for demonstration purposes. In a real implementation, this would analyze the actual repository contents via GitHub API.

"""
        
        report += f"""## Summary
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
                report += f"### {script['filename']} ({script_type.Title()})\n"
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
        
        if not any(scripts.values()):
            report += "No build or test scripts found in this repository.\n\n"
        
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
    
    # Get base directory (should be the root of the ci repository)
    # Since this script is in tools/repo-scripts-analyzer/, go up two levels
    base_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    
    analyzer = ScriptAnalyzer()
    
    print(f"Starting analysis of {len(repositories)} repositories...")
    print(f"Reports will be saved to: {base_dir}/docs/jobs/")
    print("Note: Using mock data for demonstration purposes")
    
    for owner, repo in repositories:
        try:
            analysis = analyzer.analyze_repository_mock(owner, repo)
            analyzer.save_report(analysis, base_dir)
        except Exception as e:
            print(f"Error analyzing {owner}/{repo}: {e}")
            continue
    
    print("Analysis complete!")


if __name__ == "__main__":
    main()