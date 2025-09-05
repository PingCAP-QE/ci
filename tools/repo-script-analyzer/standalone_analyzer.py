#!/usr/bin/env python3
"""
GitHub Repository Script Analysis Tool

This tool analyzes build and test scripts across specified GitHub repositories
and generates reports about their CI usage, quality, and provides improvement suggestions.

This is designed to work as a standalone script that can be run directly.
"""

import os
import re
import sys
import json
import subprocess
from pathlib import Path
from typing import Dict, List, Tuple, Optional
from dataclasses import dataclass


@dataclass
class ScriptInfo:
    """Information about a discovered script."""
    name: str
    path: str
    type: str  # 'shell', 'python', 'makefile'
    size: int
    content: str
    ci_usage: bool = False
    ci_references: List[str] = None
    complexity_score: int = 0
    quality_rating: int = 0
    suggestions: List[str] = None

    def __post_init__(self):
        if self.ci_references is None:
            self.ci_references = []
        if self.suggestions is None:
            self.suggestions = []


class RepositoryAnalyzer:
    """Analyzes scripts in GitHub repositories using GitHub CLI or API."""
    
    def __init__(self):
        """Initialize analyzer."""
        # Script patterns to find
        self.script_patterns = {
            'shell': ['.sh', '.bash'],
            'python': ['.py'],
            'makefile': ['Makefile', 'makefile', '.mk']
        }
        
        # CI file patterns to analyze
        self.ci_patterns = [
            '.github/workflows/*.yml',
            '.github/workflows/*.yaml', 
            'Jenkinsfile*',
            '.jenkins/*',
            '.ci/*',
            'ci/*',
            '.circleci/*',
            '.gitlab-ci.yml',
            'azure-pipelines.yml'
        ]

    def run_command(self, cmd: List[str]) -> Tuple[str, int]:
        """Run a command and return output and exit code."""
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
            return result.stdout, result.returncode
        except subprocess.TimeoutExpired:
            return "", 1
        except Exception as e:
            print(f"Error running command {' '.join(cmd)}: {e}")
            return "", 1

    def find_scripts_in_repo(self, owner: str, repo: str) -> List[Dict]:
        """Find scripts in repository using gh CLI."""
        scripts = []
        
        # Clone repository to temporary location
        temp_dir = f"/tmp/repo_analysis_{owner}_{repo}"
        if os.path.exists(temp_dir):
            subprocess.run(["rm", "-rf", temp_dir], check=False)
            
        # Clone the repository
        clone_cmd = ["git", "clone", "--depth", "1", f"https://github.com/{owner}/{repo}.git", temp_dir]
        output, code = self.run_command(clone_cmd)
        
        if code != 0:
            print(f"Failed to clone {owner}/{repo}")
            return scripts
            
        # Find script files
        try:
            for root, dirs, files in os.walk(temp_dir):
                # Skip .git directory
                if '/.git' in root:
                    continue
                    
                for file in files:
                    file_path = os.path.join(root, file)
                    rel_path = os.path.relpath(file_path, temp_dir)
                    
                    # Check if file matches script patterns
                    for script_type, extensions in self.script_patterns.items():
                        if any(file.endswith(ext) or file in extensions for ext in extensions):
                            try:
                                stat = os.stat(file_path)
                                scripts.append({
                                    'name': file,
                                    'path': rel_path,
                                    'type': script_type,
                                    'size': stat.st_size,
                                    'full_path': file_path
                                })
                                break
                            except OSError:
                                continue
        except Exception as e:
            print(f"Error scanning {temp_dir}: {e}")
        
        return scripts

    def get_file_content(self, file_path: str) -> Optional[str]:
        """Get file content from local file."""
        try:
            with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                return f.read()
        except Exception as e:
            print(f"Error reading {file_path}: {e}")
            return None

    def analyze_script_complexity(self, content: str, script_type: str) -> int:
        """Analyze script complexity and return a score (1-10)."""
        if not content:
            return 1
            
        lines = content.split('\n')
        non_empty_lines = [line for line in lines if line.strip() and not line.strip().startswith('#')]
        
        # Basic complexity factors
        complexity = min(len(non_empty_lines) // 10, 5)  # Length factor
        
        if script_type == 'shell':
            # Shell-specific complexity indicators
            complexity += len(re.findall(r'\bif\b|\bwhile\b|\bfor\b|\bcase\b', content))
            complexity += len(re.findall(r'\$\(.*\)', content))  # Command substitutions
            complexity += len(re.findall(r'&&|\|\|', content))  # Logical operators
            
        elif script_type == 'python':
            # Python-specific complexity indicators
            complexity += len(re.findall(r'\bdef\b|\bclass\b', content))
            complexity += len(re.findall(r'\bif\b|\bwhile\b|\bfor\b|\btry\b', content))
            complexity += len(re.findall(r'import\s+\w+', content))
            
        elif script_type == 'makefile':
            # Makefile-specific complexity indicators
            complexity += len(re.findall(r'^[a-zA-Z][^:]*:', content, re.MULTILINE))  # Targets
            complexity += len(re.findall(r'\$\([^)]+\)', content))  # Variable references
            
        return min(complexity, 10)

    def analyze_script_quality(self, content: str, script_type: str) -> Tuple[int, List[str]]:
        """Analyze script quality and return rating (1-5) and suggestions."""
        if not content:
            return 1, ["Empty script file"]
            
        rating = 5  # Start with perfect score
        suggestions = []
        
        lines = content.split('\n')
        
        # Common quality checks
        if not any(line.strip().startswith('#') for line in lines[:5]):
            rating -= 1
            suggestions.append("Add header comments explaining script purpose")
            
        # Type-specific quality checks
        if script_type == 'shell':
            if not content.startswith('#!/bin/bash') and not content.startswith('#!/bin/sh'):
                rating -= 1
                suggestions.append("Add proper shebang line")
                
            if 'set -e' not in content and 'set -euo pipefail' not in content:
                suggestions.append("Consider adding 'set -e' for better error handling")
                
            # Check for unsafe patterns
            if re.search(r'\$\*', content):
                suggestions.append("Avoid using $* - use $@ instead")
                
        elif script_type == 'python':
            if not re.search(r'#!/usr/bin/env python|#!/usr/bin/python', content):
                if not content.strip().startswith('"""') and not content.strip().startswith("'''"):
                    rating -= 1
                    suggestions.append("Add proper shebang or module docstring")
                    
            # Check for basic Python best practices
            if 'if __name__ == "__main__"' not in content and len(lines) > 20:
                suggestions.append("Consider adding if __name__ == '__main__' guard")
                
        elif script_type == 'makefile':
            if not re.search(r'\.PHONY:', content):
                suggestions.append("Consider adding .PHONY targets for non-file targets")
                
        # Check for documentation
        comment_ratio = len([l for l in lines if l.strip().startswith('#')]) / max(len(lines), 1)
        if comment_ratio < 0.1:
            rating -= 1
            suggestions.append("Add more comments for better maintainability")
            
        return max(rating, 1), suggestions

    def find_ci_usage(self, temp_dir: str, script_path: str) -> Tuple[bool, List[str]]:
        """Find if script is used in CI and return usage references."""
        references = []
        script_name = os.path.basename(script_path)
        
        # Search in common CI locations
        ci_locations = [
            '.github/workflows',
            '.jenkins',
            '.ci',
            'ci'
        ]
        
        for ci_dir in ci_locations:
            ci_path = os.path.join(temp_dir, ci_dir)
            if os.path.exists(ci_path):
                for root, dirs, files in os.walk(ci_path):
                    for file in files:
                        file_path = os.path.join(root, file)
                        try:
                            with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                                content = f.read()
                                if script_name in content or script_path in content:
                                    rel_path = os.path.relpath(file_path, temp_dir)
                                    references.append(rel_path)
                        except Exception:
                            continue
        
        return len(references) > 0, references

    def analyze_repository(self, owner: str, repo: str) -> List[ScriptInfo]:
        """Analyze all scripts in a repository."""
        print(f"Analyzing {owner}/{repo}...")
        
        # Find all scripts
        scripts_data = self.find_scripts_in_repo(owner, repo)
        scripts = []
        
        if not scripts_data:
            print(f"  No scripts found or failed to access {owner}/{repo}")
            return scripts
            
        temp_dir = f"/tmp/repo_analysis_{owner}_{repo}"
        
        for script_data in scripts_data:
            print(f"  Analyzing {script_data['path']}...")
            
            # Get script content
            content = self.get_file_content(script_data['full_path'])
            if content is None:
                continue
                
            # Analyze complexity and quality
            complexity = self.analyze_script_complexity(content, script_data['type'])
            quality_rating, suggestions = self.analyze_script_quality(content, script_data['type'])
            
            # Check CI usage
            ci_usage, ci_refs = self.find_ci_usage(temp_dir, script_data['path'])
            
            script_info = ScriptInfo(
                name=script_data['name'],
                path=script_data['path'],
                type=script_data['type'],
                size=script_data['size'],
                content=content,
                ci_usage=ci_usage,
                ci_references=ci_refs,
                complexity_score=complexity,
                quality_rating=quality_rating,
                suggestions=suggestions
            )
            
            scripts.append(script_info)
        
        # Clean up temp directory
        if os.path.exists(temp_dir):
            subprocess.run(["rm", "-rf", temp_dir], check=False)
            
        return scripts

    def generate_markdown_report(self, owner: str, repo: str, scripts: List[ScriptInfo]) -> str:
        """Generate markdown report for repository scripts."""
        
        # Calculate CI coverage
        ci_scripts = [s for s in scripts if s.ci_usage]
        ci_coverage = len(ci_scripts) / max(len(scripts), 1) * 100
        
        # Generate star ratings for CI usage
        def get_ci_stars(script: ScriptInfo) -> str:
            if not script.ci_usage:
                return "N/A"
            
            # Star rating based on CI references count
            ref_count = len(script.ci_references)
            if ref_count >= 5:
                return "⭐⭐⭐⭐⭐"
            elif ref_count >= 3:
                return "⭐⭐⭐⭐"
            elif ref_count >= 2:
                return "⭐⭐⭐"
            elif ref_count >= 1:
                return "⭐⭐"
            else:
                return "⭐"
        
        report = f"""# Repository Scripts Analysis Report

**Repository:** {owner}/{repo}  
**Analysis Date:** {__import__('datetime').datetime.now().strftime('%Y-%m-%d %H:%M:%S')}  
**Total Scripts Found:** {len(scripts)}  
**CI Coverage:** {ci_coverage:.1f}% ({len(ci_scripts)}/{len(scripts)} scripts)

## Summary Table

| Script | Type | Size (bytes) | CI Usage | Quality | Complexity |
|--------|------|--------------|----------|---------|------------|
"""
        
        for script in sorted(scripts, key=lambda x: x.path):
            ci_stars = get_ci_stars(script)
            quality_stars = "⭐" * script.quality_rating
            
            report += f"| `{script.name}` | {script.type} | {script.size} | {ci_stars} | {quality_stars} | {script.complexity_score}/10 |\n"
        
        report += f"""

## Detailed Analysis

"""

        for script in sorted(scripts, key=lambda x: x.path):
            report += f"""
### {script.name}

**Path:** `{script.path}`  
**Type:** {script.type}  
**Size:** {script.size} bytes  
**Complexity Score:** {script.complexity_score}/10  
**Quality Rating:** {"⭐" * script.quality_rating} ({script.quality_rating}/5)

"""
            
            if script.ci_usage:
                report += f"""**CI Usage:** ✅ Yes  
**CI Coverage Rating:** {get_ci_stars(script)}  
**Referenced in:**
"""
                for ref in script.ci_references:
                    report += f"- `{ref}`\n"
            else:
                report += "**CI Usage:** ❌ No\n"
            
            if script.suggestions:
                report += f"""
**Improvement Suggestions:**
"""
                for suggestion in script.suggestions:
                    report += f"- {suggestion}\n"
        
        return report


def main():
    """Main function."""
    # Repository list from the problem statement
    repositories = [
        "pingcap/docs",
        "pingcap/docs-cn",
        "pingcap/docs-tidb-operator",
        "pingcap/ticdc",
        "pingcap/tidb",
        "pingcap/tidb-tools",
        "pingcap/tiflash",
        "pingcap/tiflow",
        "pingcap/tiproxy",
        "tikv/copr-test",
        "tikv/migration",
        "tikv/pd",
        "tikv/tikv"
    ]
    
    analyzer = RepositoryAnalyzer()
    
    # Create base output directory
    base_output_dir = Path("./docs/jobs")
    base_output_dir.mkdir(parents=True, exist_ok=True)
    
    for repo_spec in repositories:
        if '/' not in repo_spec:
            print(f"Invalid repository format: {repo_spec}. Use owner/repo format.")
            continue
            
        owner, repo = repo_spec.split('/', 1)
        
        try:
            scripts = analyzer.analyze_repository(owner, repo)
            report = analyzer.generate_markdown_report(owner, repo, scripts)
            
            # Create output directory
            output_dir = base_output_dir / owner / repo
            output_dir.mkdir(parents=True, exist_ok=True)
            
            # Write report
            report_path = output_dir / 'repo-scripts-report.md'
            with open(report_path, 'w') as f:
                f.write(report)
                
            print(f"Report saved to {report_path}")
            
        except Exception as e:
            print(f"Error analyzing {owner}/{repo}: {e}")


if __name__ == '__main__':
    main()