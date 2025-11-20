#!/usr/bin/env python3
"""
Demo tool: Automatically resolve GitHub PR code conflicts and commit using reconcile-ai
Usage: python resolve_pr_conflicts.py <PR_URL> or python resolve_pr_conflicts.py <owner/repo> <PR_NUMBER>

Environment variables:
  GITHUB_TOKEN - GitHub API token (required)
  OPENAI_API_KEY - OpenAI API key (required)
  OPENAI_API_BASE - Custom OpenAI API address (optional, for domestic proxy)
    Example: https://api.jiekou.ai/openai
  RECONCILE_MODEL - Model to use (optional, default: gpt-4o)
"""

import os
import sys
import re
import subprocess
import tempfile
import shutil
from pathlib import Path
from urllib.parse import urlparse

try:
    import requests
except ImportError:
    print("❌ Need to install requests: pip install requests")
    sys.exit(1)

try:
    from reconcile import (
        detect_conflicts,
        parse_conflicts,
        resolve_conflict_sections_batch,
        resolve_conflict_section_single,
        apply_resolutions,
        load_config,
        setup_logging
    )
    import reconcile
    from git import Repo
except ImportError:
    print("❌ Need to install reconcile-ai: pip install reconcile-ai")
    print("   Also need to install GitPython: pip install GitPython")
    sys.exit(1)


# Fix reconcile-ai's handling of JieKou.AI
_original_get_client = reconcile._get_openai_client

def _patched_get_client(config=None):
    """Modified function that does not append /v1 for JieKou.AI"""
    import os
    from openai import OpenAI
    
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise ValueError(
            "❌ OpenAI API key not found!\n\n"
            "To use AI-powered conflict resolution, you need to:\n"
            "1. Get an API key from https://platform.openai.com/api-keys\n"
            "2. Set it as an environment variable:\n"
            "   export OPENAI_API_KEY='your-api-key-here'\n\n"
            "Alternatively, you can use the --dry-run flag to see conflicts without AI resolution."
        )
    
    if config and config.get('api_base_url'):
        api_base_url = config['api_base_url']
    else:
        api_base_url = os.getenv("OPENAI_API_BASE", "https://api.openai.com/v1")
    
    # For JieKou.AI, do not append /v1
    if 'jiekou.ai' in api_base_url:
        pass
    else:
        if not api_base_url.endswith('/v1'):
            if api_base_url.endswith('/'):
                api_base_url = api_base_url + 'v1'
            else:
                api_base_url = api_base_url + '/v1'
    
    return OpenAI(api_key=api_key, base_url=api_base_url)

reconcile._get_openai_client = _patched_get_client


def parse_conflicts_smart(content):
    """
    Intelligently parse conflict blocks, correctly handle nested conflicts
    Only extract the outermost complete conflict blocks, nested conflict content is included in the outermost conflict
    Improvement: More strict validation to ensure only real conflict blocks are extracted
    """
    lines = content.split('\n')
    conflicts = []
    depth = 0  # Track nesting depth
    current_conflict = None
    start_marker_count = 0  # Track the number of start markers in the current conflict block
    end_marker_count = 0    # Track the number of end markers in the current conflict block
    
    for i, line in enumerate(lines):
        stripped = line.strip()
        
        if stripped.startswith('<<<<<<<'):
            if depth == 0:
                # Start a new outermost conflict
                current_conflict = {
                    'start': i,
                    'lines': [line],
                    'has_separator': False,
                    'start_markers': 1,
                    'end_markers': 0
                }
                start_marker_count = 1
                end_marker_count = 0
            else:
                # Nested conflict, only record it in the current conflict, do not handle separately
                if current_conflict:
                    current_conflict['lines'].append(line)
                    current_conflict['start_markers'] += 1
                    start_marker_count += 1
            depth += 1
        
        elif stripped.startswith('======='):
            if current_conflict:
                current_conflict['lines'].append(line)
                # Only mark the outermost separator (depth == 1 means just entered the outermost conflict)
                if depth == 1:
                    current_conflict['has_separator'] = True
        
        elif stripped.startswith('>>>>>>>'):
            if current_conflict:
                current_conflict['lines'].append(line)
                current_conflict['end_markers'] += 1
                end_marker_count += 1
            depth -= 1
            
            # If back to the outermost level (depth == 0), a complete conflict block is found
            if depth == 0 and current_conflict:
                # Verify that the conflict block is complete:
                # 1. Must have a separator
                # 2. The number of start and end markers should match (ensure it's a complete conflict block)
                if (current_conflict.get('has_separator') and 
                    current_conflict.get('start_markers', 0) == current_conflict.get('end_markers', 0) and
                    current_conflict.get('start_markers', 0) > 0):
                    conflict_text = '\n'.join(current_conflict['lines'])
                    conflicts.append(conflict_text)
                current_conflict = None
                start_marker_count = 0
                end_marker_count = 0
        
        else:
            # Regular content line
            if current_conflict:
                current_conflict['lines'].append(line)
    
    return conflicts


def clean_ai_response(resolved_text):
    """
    Clean AI's response, remove markdown code blocks, explanatory text, etc.
    If it contains error messages or invalid content, return None to indicate a fallback
    """
    if not resolved_text:
        return None
    
    # Check if it contains error messages or invalid content
    error_indicators = [
        "It seems",
        "does not contain",
        "Please provide",
        "Please share",
        "I cannot",
        "I'm unable",
        "I don't have",
        "cannot resolve",
        "unable to resolve"
    ]
    
    for indicator in error_indicators:
        if indicator.lower() in resolved_text.lower():
            return None
    
    # Remove markdown code block markers
    code_block_pattern = r'```(?:\w+)?\s*\n(.*?)\n```'
    matches = re.findall(code_block_pattern, resolved_text, re.DOTALL)
    if matches:
        resolved_text = matches[-1]
    else:
        if '```' in resolved_text:
            parts = resolved_text.split('```')
            if len(parts) >= 2:
                potential_code = parts[1]
                potential_code = re.sub(r'^\w+\s*\n', '', potential_code)
                if potential_code.strip():
                    resolved_text = potential_code
    
    # Remove common explanatory text
    explanation_patterns = [
        r'^Explanation:.*?\n',
        r'^Here is.*?:\n',
        r'^The resolved code.*?:\n',
        r'^RESOLUTION\s+\d+:.*?\n',
        r'^---.*?\n',
        r'^\*\*RESOLUTION.*?\*\*.*?\n',
    ]
    
    for pattern in explanation_patterns:
        resolved_text = re.sub(pattern, '', resolved_text, flags=re.MULTILINE | re.IGNORECASE)
    
    # Remove markdown format markers at the beginning of lines
    lines = resolved_text.split('\n')
    cleaned_lines = []
    skip_until_code = False
    
    for line in lines:
        if line.strip().startswith('**') and line.strip().endswith('**'):
            continue
        if line.strip() == '---':
            continue
        if re.match(r'^RESOLUTION\s+\d+:', line, re.IGNORECASE):
            skip_until_code = True
            continue
        if skip_until_code and (line.strip().startswith('```') or not line.strip()):
            if line.strip().startswith('```'):
                skip_until_code = False
            continue
        
        cleaned_lines.append(line)
    
    resolved_text = '\n'.join(cleaned_lines).strip()
    
    # Ensure there are no conflict markers
    if '<<<<<<< HEAD' in resolved_text or '=======' in resolved_text or '>>>>>>>' in resolved_text:
        code_blocks = re.findall(r'```(?:\w+)?\s*\n(.*?)\n```', resolved_text, re.DOTALL)
        if code_blocks:
            resolved_text = code_blocks[-1].strip()
        # Remove lines containing conflict markers
        lines = resolved_text.split('\n')
        cleaned_lines = []
        for line in lines:
            if not (line.strip().startswith('<<<<<<<') or 
                    line.strip().startswith('=======') or 
                    line.strip().startswith('>>>>>>>')):
                cleaned_lines.append(line)
        resolved_text = '\n'.join(cleaned_lines)
    
    if not resolved_text.strip():
        return None
    
    return resolved_text


def validate_resolution(original_section, resolved_text):
    """
    Validate if the resolution is reasonable
    Only check: must not contain conflict markers and error messages
    """
    if not resolved_text:
        return False
    
    # Check for conflict markers
    if check_conflict_markers(resolved_text):
        return False
    
    # Check for error messages
    error_indicators = [
        "It seems",
        "does not contain",
        "Please provide",
        "Please share",
        "I cannot",
        "I'm unable",
        "I don't have",
        "cannot resolve",
        "unable to resolve"
    ]
    
    for indicator in error_indicators:
        if indicator.lower() in resolved_text.lower():
            return False
    
    return True


def check_conflict_markers(content):
    """Check if the content contains conflict markers"""
    has_start = bool(re.search(r'^<<<<<<<', content, re.MULTILINE))
    has_separator = bool(re.search(r'^=======', content, re.MULTILINE))
    has_end = bool(re.search(r'^>>>>>>>', content, re.MULTILINE))
    
    return has_start or has_separator or has_end


def is_complete_conflict(section):
    """
    Check if the conflict block is complete
    A complete conflict block should contain: <<<<<<< HEAD, =======, and >>>>>>>
    Improvement: More strict validation to ensure start and end marker counts match
    """
    lines = section.split('\n')
    start_count = 0
    separator_count = 0
    end_count = 0
    
    for line in lines:
        stripped = line.strip()
        if stripped.startswith('<<<<<<<'):
            start_count += 1
        elif stripped.startswith('======='):
            separator_count += 1
        elif stripped.startswith('>>>>>>>'):
            end_count += 1
    
    # A complete conflict block should have:
    # - At least one start marker (outermost)
    # - At least one separator (outermost)
    # - At least one end marker (outermost)
    # - The number of start and end markers should match (ensure it's a complete conflict block)
    return (start_count >= 1 and 
            separator_count >= 1 and 
            end_count >= 1 and
            start_count == end_count)


def extract_conflict_core(conflict_section):
    """
    Extract the core content of the conflict (remove marker lines, keep only actual code)
    Used for matching, ignoring branch name differences, etc.
    """
    lines = conflict_section.split('\n')
    core_lines = []
    
    for line in lines:
        stripped = line.strip()
        if stripped.startswith('<<<<<<<') or stripped.startswith('=======') or stripped.startswith('>>>>>>>'):
            continue
        core_lines.append(line)
    
    return '\n'.join(core_lines).strip()


def extract_indent_from_conflict(conflict_section):
    """
    Extract the indentation of the first actual code line from the conflict block
    Returns: indentation string (spaces or tab)
    """
    lines = conflict_section.split('\n')
    
    for line in lines:
        stripped = line.strip()
        # Skip conflict marker lines
        if stripped.startswith('<<<<<<<') or stripped.startswith('=======') or stripped.startswith('>>>>>>>'):
            continue
        # Find the first non-empty code line
        if stripped:
            # Extract leading whitespace
            indent = line[:len(line) - len(line.lstrip())]
            return indent
    
    # If no code line is found, return empty string
    return ""


def check_conflict_lines_same_indent_level(conflict_section):
    """
    Check if all code lines in the conflict block have the same indentation level
    Returns: (whether same level, first line indentation)
    """
    lines = conflict_section.split('\n')
    code_indents = []
    first_indent = None
    
    for line in lines:
        stripped = line.strip()
        # Skip conflict marker lines
        if stripped.startswith('<<<<<<<') or stripped.startswith('=======') or stripped.startswith('>>>>>>>'):
            continue
        # Skip empty lines
        if not stripped:
            continue
        
        # Extract indentation
        indent = line[:len(line) - len(line.lstrip())]
        code_indents.append(indent)
        
        if first_indent is None:
            first_indent = indent
    
    if not code_indents or first_indent is None:
        return True, first_indent or ""
    
    # Check if all lines have the same indentation
    all_same = all(indent == first_indent for indent in code_indents)
    
    return all_same, first_indent


def extract_conflict_line_indents(conflict_section):
    """
    Extract indentation of all code lines from the conflict block
    Returns: list, each element is (line_number, indentation_string)
    """
    lines = conflict_section.split('\n')
    line_indents = []
    
    for idx, line in enumerate(lines):
        stripped = line.strip()
        # Skip conflict marker lines
        if stripped.startswith('<<<<<<<') or stripped.startswith('=======') or stripped.startswith('>>>>>>>'):
            continue
        # Skip empty lines
        if not stripped:
            continue
        
        # Extract indentation
        indent = line[:len(line) - len(line.lstrip())]
        line_indents.append((idx, indent))
    
    return line_indents


def extract_indent_from_context(original_content, conflict_start, conflict_end):
    """
    Extract indentation information from the context before and after the conflict block
    Returns: (base indentation string, indentation unit, indentation size)
    """
    lines = original_content.split('\n')
    conflict_start_line = original_content[:conflict_start].count('\n')
    conflict_end_line = original_content[:conflict_end].count('\n')
    
    # Look for code lines before the conflict (look back at most 10 lines)
    base_indent = ""
    for i in range(max(0, conflict_start_line - 1), max(0, conflict_start_line - 10), -1):
        line = lines[i]
        stripped = line.strip()
        if stripped and not stripped.startswith('<<<<<<<') and not stripped.startswith('=======') and not stripped.startswith('>>>>>>>'):
            # Found a code line before the conflict
            base_indent = line[:len(line) - len(line.lstrip())]
            break
    
    # If not found, look for code lines after the conflict
    if not base_indent:
        for i in range(conflict_end_line, min(len(lines), conflict_end_line + 10)):
            line = lines[i]
            stripped = line.strip()
            if stripped and not stripped.startswith('<<<<<<<') and not stripped.startswith('=======') and not stripped.startswith('>>>>>>>'):
                base_indent = line[:len(line) - len(line.lstrip())]
                break
    
    # Extract indentation from inside the conflict block as fallback
    if not base_indent:
        base_indent = extract_indent_from_conflict(original_content[conflict_start:conflict_end])
    
    # Determine indentation unit (usually 2 or 4 spaces, or 1 tab)
    if base_indent:
        if base_indent.startswith('\t'):
            indent_unit = '\t'
            indent_size = 1
        else:
            # Calculate the number of spaces, find the most common indentation unit
            spaces = len(base_indent)
            # Try common indentation units like 2, 4, 8
            for unit in [2, 4, 8]:
                if spaces % unit == 0:
                    indent_unit = ' ' * unit
                    indent_size = unit
                    break
            else:
                indent_unit = ' ' * 4  # Default 4 spaces
                indent_size = 4
    else:
        indent_unit = ' ' * 4  # Default 4 spaces
        indent_size = 4
    
    return base_indent, indent_unit, indent_size


def normalize_indent_to_target(resolved_code, target_indent, indent_unit, indent_size, preserve_relative_indent=True, conflict_line_indents=None):
    """
    Adjust the indentation of resolved code to match the target indentation
    If preserve_relative_indent is False, all lines use the same target indentation
    If preserve_relative_indent is True, maintain relative indentation relationships within the code
    If conflict_line_indents is provided, use the indentation levels from the original conflict block
    """
    if not resolved_code:
        return resolved_code
    
    lines = resolved_code.split('\n')
    if not lines:
        return resolved_code
    
    # If no target indentation, use default (4 spaces)
    if not target_indent:
        target_indent = '    '  # 4 spaces
        indent_unit = '    '
        indent_size = 4
    
    # If relative indentation should not be preserved, all lines use target indentation
    if not preserve_relative_indent:
        adjusted_lines = []
        for line in lines:
            if not line.strip():
                # Empty lines remain unchanged
                adjusted_lines.append(line)
            else:
                # All lines use target indentation
                adjusted_lines.append(target_indent + line.lstrip())
        return '\n'.join(adjusted_lines)
    
    # If original conflict block indentation information is provided, use it for adjustment
    if conflict_line_indents and len(conflict_line_indents) > 0:
        # Find the first line's indentation as baseline
        first_conflict_indent = conflict_line_indents[0][1]
        
        # Calculate the first line's indentation level relative to target indentation
        # Convert first line indentation to indentation level (relative to indentation unit)
        # Note: need to convert to the same unit to calculate relative indentation
        if indent_unit == '\t':
            # Target indentation is tab, need to convert original conflict block's space indentation to tab level
            # Assume 1 tab = indent_size spaces
            first_level = first_conflict_indent.count('\t')
            first_level += len(first_conflict_indent.replace('\t', '')) // indent_size if indent_size > 0 else 0
        else:
            # Target indentation is spaces, calculate directly
            first_level = len(first_conflict_indent) // indent_size if indent_size > 0 else 0
        
        # Calculate target indentation level
        if indent_unit == '\t':
            target_level = target_indent.count('\t')
            target_level += len(target_indent.replace('\t', '')) // indent_size if indent_size > 0 else 0
        else:
            target_level = len(target_indent) // indent_size if indent_size > 0 else 0
        
        # Calculate offset
        offset = target_level - first_level
        
        # Adjust indentation of all lines
        adjusted_lines = []
        code_line_idx = 0
        for line in lines:
            if not line.strip():
                # Empty lines remain unchanged
                adjusted_lines.append(line)
                continue
            
            # Get the corresponding original conflict block line's indentation
            if code_line_idx < len(conflict_line_indents):
                conflict_indent = conflict_line_indents[code_line_idx][1]
                
                # Calculate original conflict block line's indentation level (relative to first line)
                if indent_unit == '\t':
                    # Target indentation is tab, need to convert original conflict block's space indentation to tab level
                    conflict_level = conflict_indent.count('\t')
                    conflict_level += len(conflict_indent.replace('\t', '')) // indent_size if indent_size > 0 else 0
                else:
                    # Target indentation is spaces, calculate directly
                    conflict_level = len(conflict_indent) // indent_size if indent_size > 0 else 0
                
                # Calculate indentation level difference relative to first line
                relative_level = conflict_level - first_level
                
                # Calculate new indentation level = target level + relative level
                new_level = target_level + relative_level
                new_level = max(0, new_level)
                
                # Generate new indentation
                new_indent = indent_unit * new_level
            else:
                # If AI returned more lines than original conflict block, use last line's indentation
                if conflict_line_indents:
                    last_conflict_indent = conflict_line_indents[-1][1]
                    if indent_unit == '\t':
                        last_level = last_conflict_indent.count('\t')
                        last_level += len(last_conflict_indent.replace('\t', '')) // indent_size if indent_size > 0 else 0
                    else:
                        last_level = len(last_conflict_indent) // indent_size if indent_size > 0 else 0
                    relative_level = last_level - first_level
                    new_level = target_level + relative_level
                    new_level = max(0, new_level)
                    new_indent = indent_unit * new_level
                else:
                    new_indent = target_indent
            
            adjusted_lines.append(new_indent + line.lstrip())
            code_line_idx += 1
        
        return '\n'.join(adjusted_lines)
    
    # Otherwise, use the original logic: find minimum indentation of all code lines (as normalization baseline)
    min_indent = None
    code_line_indents = []
    
    for line in lines:
        if not line.strip():
            # Skip empty lines
            code_line_indents.append(None)
            continue
        
        current_indent = line[:len(line) - len(line.lstrip())]
        code_line_indents.append(current_indent)
        
        # Update minimum indentation
        if min_indent is None or len(current_indent) < len(min_indent):
            min_indent = current_indent
    
    # If no code lines found, return directly
    if min_indent is None:
        return resolved_code
    
    # Adjust indentation of all lines
    adjusted_lines = []
    for idx, line in enumerate(lines):
        if not line.strip():
            # Empty lines remain unchanged
            adjusted_lines.append(line)
            continue
        
        # Get current line's original indentation
        original_indent = code_line_indents[idx]
        
        # Calculate relative indentation (current line's offset relative to minimum indentation)
        # If current line indentation length >= minimum indentation length, calculate difference
        if len(original_indent) >= len(min_indent):
            relative_indent = original_indent[len(min_indent):]
        else:
            # If current line indentation < minimum indentation (should not happen, but as protection)
            relative_indent = ""
        
        # New indentation = target indentation (first code line's indentation inside conflict block) + relative indentation
        new_indent = target_indent + relative_indent
        
        # Combine new line
        adjusted_lines.append(new_indent + line.lstrip())
    
    return '\n'.join(adjusted_lines)


def resolve_conflict_with_indent_preservation(section, model="gpt-4o", config=None):
    """
    Resolve a single conflict, explicitly require AI to preserve original indentation
    """
    from reconcile import _get_openai_client
    import time
    import logging
    
    logger = logging.getLogger('reconcile')
    
    # Extract indentation information from conflict block
    conflict_indent = extract_indent_from_conflict(section)
    
    # Build enhanced prompt that explicitly requires preserving indentation
    prompt = f"""Please resolve this Git merge conflict by providing clean, working code without any conflict markers.

CRITICAL REQUIREMENTS:
1. Maintain the EXACT indentation level shown in the conflict block
2. Do NOT add any new code, imports, variables, or functions not present in the conflict block
3. Only merge the existing code from HEAD and the merge branch
4. Preserve the original code structure and formatting
5. Return ONLY the resolved code, no explanations, comments, markdown, lists, or bullet points

The conflict shows two different versions of the code:
- The HEAD version (current branch)  
- The feature branch version

Please analyze both versions and provide the best merged result that:
1. Preserves the intent of both changes when possible
2. Removes all conflict markers (<<<<<<< HEAD, =======, >>>>>>> branch)
3. Results in syntactically correct, working code
4. Follows the coding style and patterns evident in the code
5. Maintains the EXACT indentation shown in the conflict block

Conflict to resolve:
```
{section}
```

Please respond with ONLY the resolved code, no explanations or markdown formatting. The indentation must match the original conflict block."""

    try:
        client = _get_openai_client(config)
        
        start_time = time.time()
        response = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": "You are a helpful AI assistant specialized in resolving Git merge conflicts. You must preserve the exact indentation from the original conflict block."},
                {"role": "user", "content": prompt}
            ],
            temperature=0
        )
        
        latency = time.time() - start_time
        resolved = response.choices[0].message.content.strip()
        
        logger.info(
            f"Resolved conflict section in {latency:.2f}s",
            extra={'latency': latency, 'model': model}
        )
        
        return resolved
    except Exception as e:
        logger.error(
            f"Failed to resolve conflict: {e}",
            extra={'error': str(e)}
        )
        return section


def resolve_conflicts_batch_with_indent_preservation(sections, model="gpt-4o", max_batch_size=5, config=None):
    """
    Resolve conflicts in batch, explicitly require AI to preserve original indentation
    """
    from reconcile import _get_openai_client
    import time
    import logging
    
    logger = logging.getLogger('reconcile')
    
    if not sections:
        return []
    
    all_resolutions = []
    
    # Extract indentation information for each conflict
    indent_info = []
    for section in sections:
        indent = extract_indent_from_conflict(section)
        indent_info.append(indent)
    
    # Process batches
    for i in range(0, len(sections), max_batch_size):
        batch = sections[i:i + max_batch_size]
        batch_indents = indent_info[i:i + max_batch_size]
        batch_num = (i // max_batch_size) + 1
        total_batches = (len(sections) + max_batch_size - 1) // max_batch_size
        
        logger.info(
            f"Processing batch {batch_num}/{total_batches} ({len(batch)} conflicts)",
            extra={
                'batch_number': batch_num,
                'total_batches': total_batches,
                'batch_size': len(batch)
            }
        )
        
        # Build batch prompt
        batch_prompt = """Please resolve these Git merge conflicts by providing clean, working code without any conflict markers.

CRITICAL REQUIREMENTS FOR EACH CONFLICT:
1. Maintain the EXACT indentation level shown in each conflict block
2. Do NOT add any new code, imports, variables, or functions not present in the conflict block
3. Only merge the existing code from HEAD and the merge branch
4. Preserve the original code structure and formatting
5. Return ONLY the resolved code, no explanations, comments, markdown, lists, or bullet points

For each conflict, analyze both versions and provide the best merged result that:
1. Preserves the intent of both changes when possible
2. Removes all conflict markers (<<<<<<< HEAD, =======, >>>>>>> branch)
3. Results in syntactically correct, working code
4. Follows the coding style and patterns evident in the code
5. Maintains the EXACT indentation shown in each conflict block

Please respond with each resolution numbered and clearly separated like this:

RESOLUTION 1:
[resolved code for conflict 1]

RESOLUTION 2:
[resolved code for conflict 2]

And so on...

Here are the conflicts to resolve:

"""
        
        for j, section in enumerate(batch, 1):
            batch_prompt += f"\n=== CONFLICT {j} ===\n{section}\n"
        
        try:
            client = _get_openai_client(config)
            
            start_time = time.time()
            response = client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": "You are a helpful AI assistant specialized in resolving Git merge conflicts. You must preserve the exact indentation from each original conflict block."},
                    {"role": "user", "content": batch_prompt}
                ],
                temperature=0
            )
            
            latency = time.time() - start_time
            resolved_text = response.choices[0].message.content.strip()
            
            logger.info(
                f"Resolved batch {batch_num} in {latency:.2f}s",
                extra={'batch_number': batch_num, 'latency': latency, 'model': model}
            )
            
            # Parse batch response
            batch_resolutions = []
            parts = re.split(r'RESOLUTION\s+\d+:', resolved_text, flags=re.IGNORECASE)
            
            for part in parts[1:]:  # Skip the first empty part
                cleaned = part.strip()
                # Remove possible markdown code block markers
                if cleaned.startswith('```'):
                    lines = cleaned.split('\n')
                    if len(lines) > 1 and lines[0].startswith('```'):
                        cleaned = '\n'.join(lines[1:])
                    if cleaned.endswith('```'):
                        cleaned = cleaned[:-3].rstrip()
                batch_resolutions.append(cleaned)
            
            # If parsing fails, try other methods
            if len(batch_resolutions) != len(batch):
                # Try splitting by conflict count
                if len(batch) == 1:
                    batch_resolutions = [resolved_text]
                else:
                    # Simple split (may not be accurate, but as fallback)
                    batch_resolutions = [resolved_text] * len(batch)
            
            all_resolutions.extend(batch_resolutions)
            
        except Exception as e:
            logger.error(
                f"Batch resolution failed: {e}",
                extra={'batch_number': batch_num, 'error': str(e)}
            )
            # Fall back to individual resolution
            for section in batch:
                try:
                    resolved = resolve_conflict_with_indent_preservation(section, model=model, config=config)
                    all_resolutions.append(resolved)
                except Exception:
                    all_resolutions.append(section)
    
    return all_resolutions


def remove_duplicate_lines(content, context_lines=3):
    """
    Remove consecutive duplicate code lines
    Avoid accidentally deleting legitimate duplicate structures (like curly braces)
    Improvement: More conservative with curly braces, only delete obviously abnormal duplicates
    """
    lines = content.split('\n')
    cleaned_lines = []
    i = 0
    
    while i < len(lines):
        line = lines[i]
        cleaned_lines.append(line)
        
        # For curly braces, be very conservative: only delete duplicates that exceed 5 consecutive
        if line.strip() in ['{', '}']:
            j = i + 1
            duplicate_count = 0
            while j < len(lines) and lines[j].strip() == line.strip():
                duplicate_count += 1
                j += 1
            
            # Only delete excess if more than 5 consecutive curly braces
            if duplicate_count > 5:
                # Keep the first one, skip the rest
                i = j
            else:
                i += 1
            continue
        
        # For other lines, check for duplicates
        j = i + 1
        duplicate_count = 0
        
        while j < len(lines) and j < i + context_lines + 1:
            current_line = lines[j]
            
            if current_line.strip() == line.strip() and line.strip():
                # Skip comment lines
                if line.strip().startswith('//') or line.strip().startswith('#'):
                    break
                duplicate_count += 1
                j += 1
            else:
                break
        
        if duplicate_count > 0:
            # Skip duplicate lines
            i = j
        else:
            i += 1
    
    return '\n'.join(cleaned_lines)


def detect_duplicate_code(content):
    """
    Detect duplicate lines in code
    Returns the position and content of duplicate lines
    Improvement: Ignore curly brace duplicates (this is normal code structure)
    """
    lines = content.split('\n')
    duplicates = []
    
    i = 0
    while i < len(lines) - 1:
        line_stripped = lines[i].strip()
        
        # Ignore curly brace duplicates (this is normal code structure)
        if line_stripped in ['{', '}']:
            i += 1
            continue
        
        # Ignore empty lines
        if not line_stripped:
            i += 1
            continue
        
        if line_stripped == lines[i + 1].strip():
            dup_start = i
            dup_line = line_stripped
            j = i + 1
            while j < len(lines) and lines[j].strip() == dup_line:
                j += 1
            # Only report cases where duplicates exceed 2 consecutive
            if j - i > 2:
                duplicates.append({
                    'line': dup_start + 1,
                    'content': dup_line,
                    'count': j - i
                })
            i = j
        else:
            i += 1
    
    return duplicates


def format_conflict_preview(conflict_section, max_lines=5):
    """
    Format conflict preview, showing key content of the conflict
    """
    if not is_complete_conflict(conflict_section):
        return f"  ⚠️  Incomplete conflict block (missing markers)\n  {conflict_section[:100]}..."
    
    lines = conflict_section.split('\n')
    preview_lines = []
    in_head = False
    in_merge = False
    content_lines_shown = 0
    
    for line in lines:
        stripped = line.strip()
        if stripped.startswith('<<<<<<<'):
            preview_lines.append('  <<<<<<< HEAD')
            in_head = True
            in_merge = False
            content_lines_shown = 0
        elif stripped.startswith('======='):
            preview_lines.append('  =======')
            in_head = False
            in_merge = True
            content_lines_shown = 0
        elif stripped.startswith('>>>>>>>'):
            preview_lines.append(f'  >>>>>>> {stripped[8:60]}')
            break
        elif in_head or in_merge:
            # Only show first few lines of content
            if content_lines_shown < max_lines:
                preview_lines.append(f'    {line[:60]}')
                content_lines_shown += 1
    
    return '\n'.join(preview_lines)


def parse_pr_url(pr_url):
    """Parse PR URL or parameters"""
    if pr_url.startswith("http"):
        url = urlparse(pr_url)
        if url.hostname != "github.com":
            raise ValueError("Only GitHub PR URLs are supported")
        
        path_parts = url.path.strip("/").split("/")
        if len(path_parts) < 4 or path_parts[2] != "pull":
            raise ValueError("Invalid PR URL format")
        
        owner = path_parts[0]
        repo = path_parts[1]
        pr_number = int(path_parts[3])
        return owner, repo, pr_number
    else:
        if len(sys.argv) < 3:
            raise ValueError("When using PR number, must provide owner/repo and PR_NUMBER")
        repo_parts = pr_url.split("/")
        if len(repo_parts) != 2:
            raise ValueError("Invalid repository format, should be owner/repo")
        pr_number = int(sys.argv[2])
        return repo_parts[0], repo_parts[1], pr_number


def get_pr_info(owner, repo, pr_number, token):
    """Get PR information from GitHub API"""
    url = f"https://api.github.com/repos/{owner}/{repo}/pulls/{pr_number}"
    headers = {"Authorization": f"token {token}"}
    
    response = requests.get(url, headers=headers)
    response.raise_for_status()
    
    pr_data = response.json()
    return {
        "head_ref": pr_data["head"]["ref"],
        "base_ref": pr_data["base"]["ref"],
        "title": pr_data.get("title", ""),
        "head_sha": pr_data["head"]["sha"],
        "mergeable": pr_data.get("mergeable"),
        "mergeable_state": pr_data.get("mergeable_state", ""),
        "merged": pr_data.get("merged", False)
    }


def run_git_cmd(repo_path, args, capture_stderr=True):
    """Run Git command, return stdout and stderr"""
    result = subprocess.run(
        ["git"] + args,
        cwd=repo_path,
        capture_output=True,
        text=True
    )
    return result.stdout.strip(), result.stderr.strip(), result.returncode


def test_api_connection(api_key, api_base, model=None):
    """Test API connection"""
    try:
        from openai import OpenAI
        
        test_model = model or os.getenv("RECONCILE_MODEL") or "gpt-4o"
        
        if api_base:
            test_base = api_base
        else:
            test_base = "https://api.openai.com/v1"
        
        print(f"   Testing model: {test_model}")
        print(f"   API address: {test_base}")
        
        client = OpenAI(api_key=api_key, base_url=test_base)
        
        response = client.chat.completions.create(
            model=test_model,
            messages=[{"role": "user", "content": "test"}],
            max_tokens=1
        )
        print(f"✅ API connection successful, using model: {test_model}")
        return True, None
    except Exception as e:
        error_msg = str(e)
        if "404" in error_msg or "MODEL_NOT_FOUND" in error_msg or "model not found" in error_msg.lower():
            return False, f"Model not supported: {test_model}. Error: {error_msg}"
        return False, error_msg


def clone_repo_optimized(repo_url, repo_path, head_ref, base_ref, token):
    """Optimized repository cloning - using shallow clone and single branch"""
    print("📥 Cloning repository (optimized mode)...")
    
    repo_path.parent.mkdir(parents=True, exist_ok=True)
    
    clone_cmd = [
        "git", "clone",
        "--depth", "1",
        "--single-branch",
        "--branch", head_ref,
        repo_url,
        str(repo_path)
    ]
    
    result = subprocess.run(
        clone_cmd,
        cwd=str(repo_path.parent),
        capture_output=True,
        text=True
    )
    
    if result.returncode != 0:
        print(f"⚠️  Cannot directly clone {head_ref} branch, trying to clone {base_ref}...")
        clone_cmd_base = [
            "git", "clone",
            "--depth", "1",
            "--single-branch",
            "--branch", base_ref,
            repo_url,
            str(repo_path)
        ]
        
        result = subprocess.run(
            clone_cmd_base,
            cwd=str(repo_path.parent),
            capture_output=True,
            text=True
        )
        
        if result.returncode != 0:
            print(f"❌ Clone failed: {result.stderr}")
            return False
        
        print(f"🔀 Switching to PR branch: {head_ref}")
        stdout, stderr, code = run_git_cmd(repo_path, ["fetch", "origin", f"{head_ref}:{head_ref}", "--depth", "1"])
        if code != 0:
            stdout, stderr, code = run_git_cmd(repo_path, ["fetch", "origin", f"{head_ref}:{head_ref}"])
            if code != 0:
                print(f"⚠️  Failed to fetch PR branch: {stderr}")
                return False
        
        stdout, stderr, code = run_git_cmd(repo_path, ["checkout", head_ref])
        if code != 0:
            print(f"❌ Failed to switch branch: {stderr}")
            return False
    
    print(f"📥 Fetching target branch: {base_ref}")
    
    stdout, stderr, code = run_git_cmd(repo_path, ["fetch", "origin", f"{base_ref}:origin/{base_ref}", "--depth", "10"])
    if code != 0:
        print(f"⚠️  Shallow clone fetch target branch failed, trying to increase depth...")
        stdout, stderr, code = run_git_cmd(repo_path, ["fetch", "origin", f"{base_ref}:origin/{base_ref}", "--depth", "50"])
        if code != 0:
            print(f"⚠️  Still failed after increasing depth, trying full fetch...")
            stdout, stderr, code = run_git_cmd(repo_path, ["fetch", "origin", base_ref])
            if code != 0:
                print(f"❌ Failed to fetch target branch: {stderr}")
                return False
    
    return True


def apply_resolutions_safe(file_path, original_content, resolved_map):
    """
    Safely apply resolutions
    Core logic:
    1. Find conflict markers (<<<<<<< HEAD ... ======= ... >>>>>>>)
    2. Replace conflict markers with AI-returned resolutions
    3. Thoroughly clean all remaining conflict markers
    """
    updated = original_content
    replaced_count = 0
    
    # Clean and validate all resolutions
    cleaned_resolved_map = {}
    for section, resolved in resolved_map.items():
        if resolved is None:
            print(f"   ⚠️  Resolution invalid, skipping this conflict (keeping original conflict markers)")
            continue
            
        cleaned = clean_ai_response(resolved)
        
        if cleaned is None:
            print(f"   ⚠️  Resolution contains error messages, skipping this conflict (keeping original conflict markers)")
            continue
        
        if not validate_resolution(section, cleaned):
            print(f"   ⚠️  Resolution validation failed, skipping this conflict (keeping original conflict markers)")
            continue
        
        if check_conflict_markers(cleaned):
            print(f"   ⚠️  Warning: Cleaned resolution still contains conflict markers, trying further cleanup...")
            lines = cleaned.split('\n')
            cleaned_lines = []
            for line in lines:
                if not (line.strip().startswith('<<<<<<<') or 
                        line.strip().startswith('=======') or 
                        line.strip().startswith('>>>>>>>')):
                    cleaned_lines.append(line)
            cleaned = '\n'.join(cleaned_lines)
            
            if check_conflict_markers(cleaned):
                print(f"   ⚠️  Cannot clean conflict markers, skipping this conflict (keeping original conflict markers)")
                continue
        
        cleaned_resolved_map[section] = cleaned
    
    if not cleaned_resolved_map:
        print(f"   ⚠️  All resolutions are invalid, keeping original conflict markers")
        return updated, 0
    
    # Use smart parsing to find all complete conflict blocks (including nested)
    all_conflicts = parse_conflicts_smart(updated)
    
    # Process from back to front to avoid position offset
    for conflict_text in reversed(all_conflicts):
        # Find conflict position in file (using exact match)
        conflict_start = updated.find(conflict_text)
        if conflict_start == -1:
            # If exact match fails, try regex matching (ignoring whitespace differences)
            conflict_lines = conflict_text.split('\n')
            pattern = re.escape(conflict_lines[0])
            for line in conflict_lines[1:]:
                pattern += r'\s*\n\s*' + re.escape(line)
            match = re.search(pattern, updated, re.MULTILINE)
            if match:
                conflict_start = match.start()
                conflict_text = match.group(0)
            else:
                continue
        
        conflict_end = conflict_start + len(conflict_text)
        
        # Find corresponding resolution
        resolved = None
        for original_section, cleaned_resolved in cleaned_resolved_map.items():
            # Extract core content for matching
            original_core = extract_conflict_core(original_section)
            current_core = extract_conflict_core(conflict_text)
            
            # Exact match core content
            if original_core.strip() == current_core.strip():
                resolved = cleaned_resolved
                break
        
        # If exact match not found, try content matching
        if resolved is None:
            conflict_lines = conflict_text.split('\n')
            conflict_head = []
            conflict_merge = []
            in_head = True
            
            for line in conflict_lines:
                stripped = line.strip()
                if stripped.startswith('<<<<<<<'):
                    continue
                elif stripped.startswith('======='):
                    in_head = False
                    continue
                elif stripped.startswith('>>>>>>>'):
                    break
                elif in_head:
                    conflict_head.append(line.strip())
                else:
                    conflict_merge.append(line.strip())
            
            for original_section, cleaned_resolved in cleaned_resolved_map.items():
                original_lines = original_section.split('\n')
                orig_head = []
                orig_merge = []
                in_head = True
                
                for line in original_lines:
                    stripped = line.strip()
                    if stripped.startswith('<<<<<<<'):
                        continue
                    elif stripped.startswith('======='):
                        in_head = False
                        continue
                    elif stripped.startswith('>>>>>>>'):
                        break
                    elif in_head:
                        orig_head.append(line.strip())
                    else:
                        orig_merge.append(line.strip())
                
                # Compare first few lines of key content
                head_match = False
                if orig_head and conflict_head:
                    head_match = any(h.strip() in ' '.join(conflict_head[:10]) for h in orig_head[:5] if h.strip())
                
                merge_match = False
                if orig_merge and conflict_merge:
                    merge_match = any(m.strip() in ' '.join(conflict_merge[:10]) for m in orig_merge[:5] if m.strip())
                
                if head_match or merge_match:
                    resolved = cleaned_resolved
                    break
        
        if resolved:
            # Print AI-returned original code (after cleaning)
            print(f"\n   📝 AI-returned code (after cleaning, before indentation adjustment):")
            print("   " + "=" * 60)
            for i, line in enumerate(resolved.split('\n'), 1):
                # Display indentation (· for spaces, → for tabs)
                display_line = line.replace(' ', '·').replace('\t', '→')
                print(f"   {i:3d}: {display_line}")
            print("   " + "=" * 60)
            
            # Prioritize extracting indentation from inside conflict block (more reliable)
            conflict_indent = extract_indent_from_conflict(conflict_text)
            
            # If no indentation inside conflict block, extract from context
            if not conflict_indent:
                base_indent, indent_unit, indent_size = extract_indent_from_context(
                    updated, conflict_start, conflict_end
                )
                target_indent = base_indent
                # If context extraction also fails, use default (4 spaces)
                if not target_indent:
                    target_indent = '    '  # 4 spaces
                    indent_unit = '    '
                    indent_size = 4
            else:
                # Extract indentation information from inside conflict block
                if conflict_indent.startswith('\t'):
                    indent_unit = '\t'
                    indent_size = 1
                else:
                    # Calculate number of spaces, find most common indentation unit
                    spaces = len(conflict_indent)
                    for unit in [2, 4, 8]:
                        if spaces % unit == 0:
                            indent_unit = ' ' * unit
                            indent_size = unit
                            break
                    else:
                        indent_unit = ' ' * 4
                        indent_size = 4
                target_indent = conflict_indent
            
            # Ensure target_indent is not empty
            if not target_indent:
                target_indent = '    '  # Default 4 spaces
                indent_unit = '    '
                indent_size = 4
            
            # Check if all code lines in original conflict block have the same indentation level
            same_indent_level, conflict_first_indent = check_conflict_lines_same_indent_level(conflict_text)
            
            # Extract indentation of all code lines from original conflict block
            conflict_line_indents = extract_conflict_line_indents(conflict_text)
            
            print(f"   🔍 Target indentation: {repr(target_indent)} (length: {len(target_indent)})")
            print(f"   🔍 All lines in original conflict block same level: {same_indent_level}")
            if conflict_line_indents:
                print(f"   🔍 Original conflict block indentation levels: {[repr(indent) for _, indent in conflict_line_indents]}")
            
            # Adjust AI-returned code indentation to match target indentation
            # If all lines in original conflict block are same level, don't preserve relative indentation
            # Otherwise, use original conflict block's indentation levels for adjustment
            resolved = normalize_indent_to_target(
                resolved, 
                target_indent, 
                indent_unit, 
                indent_size, 
                preserve_relative_indent=not same_indent_level,
                conflict_line_indents=conflict_line_indents if not same_indent_level else None
            )
            
            # Print code after indentation adjustment
            print(f"\n   📝 AI-returned code (after indentation adjustment):")
            print("   " + "=" * 60)
            for i, line in enumerate(resolved.split('\n'), 1):
                # Display indentation (· for spaces, → for tabs)
                display_line = line.replace(' ', '·').replace('\t', '→')
                print(f"   {i:3d}: {display_line}")
            print("   " + "=" * 60)
            
            # Replace conflict markers
            updated = updated[:conflict_start] + resolved + updated[conflict_end:]
            replaced_count += 1
    
    # Thoroughly clean all remaining conflict markers
    # 1. Clean isolated conflict marker lines
    lines = updated.split('\n')
    cleaned_lines = []
    i = 0
    
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        
        if stripped.startswith('<<<<<<<') or stripped.startswith('=======') or stripped.startswith('>>>>>>>'):
            # Check if there's a complete conflict marker structure before and after
            has_start = False
            for j in range(max(0, i - 50), i):
                if lines[j].strip().startswith('<<<<<<<'):
                    has_start = True
                    break
            
            has_end = False
            for j in range(i + 1, min(len(lines), i + 50)):
                if lines[j].strip().startswith('>>>>>>>'):
                    has_end = True
                    break
            
            if not (has_start and has_end):
                print(f"   🔧 Cleaning isolated conflict marker: line {i+1} ({stripped[:50]})")
                i += 1
                continue
        
        cleaned_lines.append(line)
        i += 1
    
    updated = '\n'.join(cleaned_lines)
    
    # 2. Use regex to thoroughly clean all remaining conflict markers
    max_cleanup_iterations = 5
    for _ in range(max_cleanup_iterations):
        before = updated
        updated = re.sub(r'^<<<<<<<[^\n]*\n', '', updated, flags=re.MULTILINE)
        updated = re.sub(r'^=======\s*\n', '', updated, flags=re.MULTILINE)
        updated = re.sub(r'^>>>>>>>[^\n]*\n', '', updated, flags=re.MULTILINE)
        if before == updated:
            break
    
    # 3. Final verification: ensure no remaining conflict markers
    if check_conflict_markers(updated):
        lines = updated.split('\n')
        final_cleaned = []
        for line in lines:
            stripped = line.strip()
            if not (stripped.startswith('<<<<<<<') or 
                    stripped.startswith('=======') or 
                    stripped.startswith('>>>>>>>')):
                final_cleaned.append(line)
        updated = '\n'.join(final_cleaned)
    
    # 4. Remove duplicate code lines (avoid accidentally deleting curly braces)
    updated = remove_duplicate_lines(updated)
    
    # 5. Clean excessive empty lines
    updated = re.sub(r'\n{3,}', '\n\n', updated)
    
    # Write to file
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(updated)
    
    return updated, replaced_count


def check_conflicts_in_branch(repo_path, branch_ref):
    """
    Check if files in the specified branch already contain conflict markers
    Returns a list of files containing conflict markers
    """
    conflicts = {}
    
    # Switch to specified branch
    stdout, stderr, code = run_git_cmd(repo_path, ["checkout", branch_ref])
    if code != 0:
        return conflicts
    
    # Get all files
    stdout, stderr, code = run_git_cmd(repo_path, ["ls-files"])
    if code != 0:
        return conflicts
    
    files = stdout.split('\n')
    
    for file_path in files:
        if not file_path.strip():
            continue
        
        full_path = repo_path / file_path
        if not full_path.exists():
            continue
        
        try:
            with open(full_path, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read()
            
            # Check if it contains conflict markers
            if '<<<<<<< HEAD' in content or '<<<<<<<' in content:
                sections = parse_conflicts_smart(content)
                if sections:
                    conflicts[file_path] = sections
        except Exception:
            continue
    
    return conflicts

def main():
    if len(sys.argv) < 2:
        print("Usage:")
        print("  python resolve_pr_conflicts.py <PR_URL>")
        print("  Example: python resolve_pr_conflicts.py https://github.com/owner/repo/pull/123")
        print()
        print("Or:")
        print("  python resolve_pr_conflicts.py <owner/repo> <PR_NUMBER>")
        print("  Example: python resolve_pr_conflicts.py owner/repo 123")
        sys.exit(1)
    
    # Get GitHub Token
    token = os.getenv("GITHUB_TOKEN")
    if not token:
        print("❌ Error: GITHUB_TOKEN environment variable must be set")
        sys.exit(1)
    
    # Check OpenAI API configuration
    api_key = os.getenv("OPENAI_API_KEY")
    api_base = os.getenv("OPENAI_API_BASE")
    
    if not api_key:
        print("❌ Error: OPENAI_API_KEY environment variable must be set")
        sys.exit(1)
    
    # Get model
    model = os.getenv("RECONCILE_MODEL") or "gpt-4o"
    
    # Handle API base URL
    if api_base:
        print(f"ℹ️  Using custom API address: {api_base}")
        os.environ['OPENAI_API_BASE'] = api_base
        print(f"   Actual API address: {api_base}")
    else:
        print("ℹ️  Using default OpenAI API address: https://api.openai.com/v1")
    
    # Test API connection
    print("🧪 Testing API connection...")
    api_ok, api_error = test_api_connection(api_key, api_base, model=model)
    if not api_ok:
        print(f"❌ API connection failed: {api_error}")
        if "401" in api_error or "invalid_api_key" in api_error.lower():
            print("💡 Tip: API key is invalid, please check:")
            print("   1. Whether using the API key provided by JieKou.AI platform")
            print("   2. Whether the API key is activated")
            print("   3. Whether the account has balance")
        sys.exit(1)
    print("✅ API connection normal")
    
    # Parse PR information
    try:
        owner, repo_name, pr_number = parse_pr_url(sys.argv[1])
    except Exception as e:
        print(f"❌ Failed to parse PR information: {e}")
        sys.exit(1)
    
    print(f"🔍 Getting PR information: {owner}/{repo_name}#{pr_number}")
    
    # Get PR details
    try:
        pr_info = get_pr_info(owner, repo_name, pr_number, token)
    except Exception as e:
        print(f"❌ Failed to get PR information: {e}")
        sys.exit(1)
    
    print(f"📋 PR information:")
    print(f"  Title: {pr_info['title']}")
    print(f"  Source branch: {pr_info['head_ref']}")
    print(f"  Target branch: {pr_info['base_ref']}")
    
    if pr_info['merged']:
        print("ℹ️  PR is already merged, no need to resolve conflicts")
        sys.exit(0)
    
    if pr_info['mergeable'] is False:
        print("⚠️  PR marked as not mergeable, may already have conflicts")
    elif pr_info['mergeable'] is True:
        print("ℹ️  PR marked as mergeable, may have no conflicts")
    else:
        print("ℹ️  PR merge status unknown, continuing to try...")
    
    print(f"   Merge status: {pr_info['mergeable_state']}")
    
    # Create temporary directory
    temp_dir = tempfile.mkdtemp()
    repo_path = Path(temp_dir)
    print(f"📁 Temporary directory: {temp_dir}")
    
    try:
        # Optimized cloning method
        repo_url = f"https://{token}@github.com/{owner}/{repo_name}.git"
        if not clone_repo_optimized(repo_url, repo_path, pr_info['head_ref'], pr_info['base_ref'], token):
            print("❌ Failed to clone repository")
            sys.exit(1)
        
        # Configure Git
        run_git_cmd(repo_path, ["config", "user.name", "reconcile-demo"])
        run_git_cmd(repo_path, ["config", "user.email", "reconcile-demo@example.com"])

        # Improvement: First check if source branch already contains conflict markers
        print(f"🔍 Checking if source branch {pr_info['head_ref']} already contains conflict markers...")
        existing_conflicts = check_conflicts_in_branch(repo_path, pr_info['head_ref'])
        
        # Ensure environment variables are set
        if api_base:
            os.environ['OPENAI_API_BASE'] = api_base
        
        # Use reconcile-ai to detect conflicts (check conflict markers in files even if merge succeeds)
        logger = setup_logging(verbose=True, json_logging=False)
        
        # Load configuration
        config = load_config(str(repo_path))
        
        # Set api_base_url in config
        if api_base:
            config['api_base_url'] = api_base
        
        # Use model
        final_model = os.getenv("RECONCILE_MODEL") or config.get('model', model)
        max_batch_size = config.get('max_batch_size', 5)
        
        print(f"🤖 Using model: {final_model}, batch size: {max_batch_size}")
        if api_base:
            print(f"🌐 API address: {api_base}")
        
        merge_result = None
        conflicts = {}
        
        if existing_conflicts:
            print(f"✅ Found {sum(len(s) for s in existing_conflicts.values())} conflict markers in source branch")
            print("   Using existing conflict markers in source branch, skipping git merge")
            conflicts = existing_conflicts
        else:
            # If no conflict markers, execute git merge to generate conflict markers
            print(f"🔀 Source branch has no conflict markers, trying to merge {pr_info['base_ref']} into {pr_info['head_ref']}")
            merge_result = subprocess.run(
                ["git", "merge", f"origin/{pr_info['base_ref']}", "--allow-unrelated-histories"],
                cwd=repo_path,
                capture_output=True,
                text=True
            )
            
            # Key modification: Check conflict markers in files regardless of whether merge succeeds
            print("🔍 Scanning files for conflict markers...")
            blobs = detect_conflicts(str(repo_path))
            
            if not blobs:
                if merge_result.returncode == 0:
                    print("✅ PR has no conflicts, no need to resolve")
                    return
                else:
                    stderr = merge_result.stderr
                    stdout = merge_result.stdout
                    if "CONFLICT" not in stderr and "CONFLICT" not in stdout:
                        if "unrelated histories" in stderr.lower():
                            print(f"⚠️  Detected unrelated histories, already used --allow-unrelated-histories")
                            print(f"❌ Merge failed: {stderr}")
                            sys.exit(1)
                        print(f"❌ Merge failed, but no conflict markers detected")
                        print(f"   Exit code: {merge_result.returncode}")
                        print(f"   Error message: {stderr if stderr else '(no error message)'}")
                        sys.exit(1)
                    else:
                        print("⚠️  Git reports conflicts, but no conflict markers found in files")
                        print("   Conflict markers may be in non-standard format, or partially resolved")
                        sys.exit(1)
            
            # Key improvement: Use smart parsing function to re-parse conflicts
            for path in blobs:
                full_path = repo_path / path if not Path(path).is_absolute() else Path(path)
                try:
                    with open(full_path, 'r', encoding='utf-8', errors='ignore') as f:
                        content = f.read()
                    # Use smart parsing function
                    sections = parse_conflicts_smart(content)
                    if sections:
                        conflicts[path] = sections
                except Exception as e:
                    print(f"⚠️  Failed to parse file {path}: {e}")
        
        git_repo = Repo(str(repo_path))
        
        total_conflicts = sum(len(sections) for sections in conflicts.values())
        print(f"📝 Found {total_conflicts} conflicts in {len(conflicts)} file(s)")
        
        # New: Display conflict details for each file
        print("\n📋 Conflict details:")
        for path, sections in conflicts.items():
            print(f"\n📄 {path}: {len(sections)} conflict(s)")
            for i, section in enumerate(sections, 1):
                print(f"   Conflict {i}:")
                preview = format_conflict_preview(section, max_lines=3)
                print(preview)
        
        if total_conflicts == 0:
            if merge_result is None or merge_result.returncode == 0:
                print("ℹ️  Files may contain conflict markers, but no valid conflicts found after parsing")
                print("   Conflict markers may be in incomplete format, skipping processing")
                return
            else:
                print("ℹ️  No conflict content found")
                sys.exit(0)
        
        if merge_result is not None and merge_result.returncode == 0:
            print("\n⚠️  Detected conflict markers in files, but Git merge succeeded")
            print("   This may be conflict markers left from previous merge, will automatically clean...")
        
        print("\n⚠️  Starting conflict resolution...")
        print("💡 Resolution strategy: Keep code from one branch, or merge code from both branches")
        print("   If unable to resolve, will keep original conflict markers")
        
        resolved_count = 0
        failed_files = []
        skipped_count = 0
        
        # Resolve conflicts for each file
        for path, sections in conflicts.items():
            full_path = repo_path / path if not Path(path).is_absolute() else Path(path)
            
            print(f"\n🤖 Using AI to resolve conflicts: {path}")
            
            with open(full_path, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read()
            
            original_conflict_count = content.count("<<<<<<< HEAD")
            
            # Filter out incomplete conflict blocks
            complete_sections = []
            for section in sections:
                if is_complete_conflict(section):
                    complete_sections.append(section)
                else:
                    print(f"   ⚠️  Skipping incomplete conflict block (missing necessary markers)")
                    skipped_count += 1
            
            if not complete_sections:
                print(f"   ⚠️  No complete conflict blocks to resolve, keeping original conflict markers")
                failed_files.append(path)
                continue
            
            # Batch resolve conflicts (using indentation-preserving version)
            try:
                resolved_sections = resolve_conflicts_batch_with_indent_preservation(
                    complete_sections,
                    model=final_model,
                    max_batch_size=max_batch_size,
                    config=config
                )
                cleaned_sections = []
                for i, rs in enumerate(resolved_sections):
                    cleaned = clean_ai_response(rs)
                    if cleaned is None or not validate_resolution(complete_sections[i], cleaned):
                        print(f"   ⚠️  Resolution for conflict {i+1} is invalid, will skip (keeping original conflict markers)")
                        cleaned_sections.append(None)
                    else:
                        cleaned_sections.append(cleaned)
                
                resolved_map = {}
                for section, cleaned in zip(complete_sections, cleaned_sections):
                    if cleaned is not None:
                        resolved_map[section] = cleaned
                    else:
                        skipped_count += 1
            except Exception as e:
                error_msg = str(e)
                print(f"⚠️  Batch resolution failed, using individual resolution: {error_msg}")
                
                if "401" in error_msg or "invalid_api_key" in error_msg.lower():
                    print("❌ API key validation failed")
                    print("💡 Please check:")
                    print("   1. Whether API key is correct (use key provided by JieKou.AI platform)")
                    print("   2. Whether API key is activated")
                    print("   3. Whether account has balance")
                
                resolved_map = {}
                for sec in complete_sections:
                    try:
                        merged = resolve_conflict_with_indent_preservation(sec, model=final_model, config=config)
                        cleaned = clean_ai_response(merged)
                        if cleaned is None or not validate_resolution(sec, cleaned):
                            print(f"   ⚠️  Resolution for this conflict is invalid, will skip (keeping original conflict markers)")
                            skipped_count += 1
                            continue
                        resolved_map[sec] = cleaned
                    except Exception as e2:
                        error_msg2 = str(e2)
                        print(f"❌ Failed to resolve individual conflict: {error_msg2}")
                        if "401" in error_msg2 or "invalid_api_key" in error_msg2.lower():
                            failed_files.append(path)
                            break
                        skipped_count += 1
                        continue
                
                if not resolved_map:
                    failed_files.append(path)
                    continue
            
            if not resolved_map:
                print(f"   ⚠️  No valid resolutions, keeping original conflict markers")
                failed_files.append(path)
                continue
            
            # Apply resolutions
            try:
                final_content, replaced_count = apply_resolutions_safe(str(full_path), content, resolved_map)
                if replaced_count < len(resolved_map):
                    print(f"   ⚠️  Only replaced {replaced_count}/{len(resolved_map)} conflict markers")
                if replaced_count == 0:
                    print(f"   ⚠️  Failed to replace any conflicts, keeping original conflict markers")
                    failed_files.append(path)
                    continue
            except Exception as e:
                print(f"⚠️  Using improved method failed, falling back to original method: {e}")
                try:
                    cleaned_resolved_map = {}
                    for k, v in resolved_map.items():
                        cleaned = clean_ai_response(v)
                        if cleaned is not None and validate_resolution(k, cleaned):
                            cleaned_resolved_map[k] = cleaned
                    
                    if cleaned_resolved_map:
                        apply_resolutions(str(full_path), content, cleaned_resolved_map)
                        with open(full_path, 'r', encoding='utf-8', errors='ignore') as f:
                            final_content = f.read()
                        final_content = remove_duplicate_lines(final_content)
                        with open(full_path, 'w', encoding='utf-8') as f:
                            f.write(final_content)
                    else:
                        print(f"   ⚠️  All resolutions are invalid, keeping original conflict markers")
                        failed_files.append(path)
                        continue
                except Exception as e2:
                    print(f"❌ Failed to apply resolutions: {e2}")
                    failed_files.append(path)
                    continue
            
            # Verify: Check if there are still conflict markers
            remaining_conflicts = final_content.count("<<<<<<< HEAD")
            if remaining_conflicts > 0:
                if remaining_conflicts < original_conflict_count:
                    print(f"   ℹ️  Resolved {original_conflict_count - remaining_conflicts} conflicts, {remaining_conflicts} conflicts still unresolved (original conflict markers kept)")
                else:
                    print(f"   ⚠️  All conflicts unresolved, keeping original conflict markers")
                    failed_files.append(path)
                    continue
            
            # Detect duplicate code lines
            duplicates = detect_duplicate_code(final_content)
            if duplicates:
                print(f"   ⚠️  Detected duplicate code lines:")
                for dup in duplicates[:3]:
                    print(f"      Line {dup['line']}: {dup['content'][:60]} (repeated {dup['count']} times)")
                if len(duplicates) > 3:
                    print(f"      ... {len(duplicates) - 3} more duplicates")
                
                print(f"   🔧 Trying to clean duplicate code lines...")
                cleaned_content = remove_duplicate_lines(final_content)
                if cleaned_content != final_content:
                    print(f"   ✅ Cleaned duplicate code lines")
                    final_content = cleaned_content
                    with open(full_path, 'w', encoding='utf-8') as f:
                        f.write(final_content)
            
            # Final verification: Ensure resolved conflicts have no remaining markers
            if check_conflict_markers(final_content):
                current_conflict_count = final_content.count("<<<<<<< HEAD")
                if current_conflict_count <= original_conflict_count - replaced_count:
                    print(f"   ℹ️  {current_conflict_count} conflicts still unresolved (original conflict markers kept)")
                else:
                    print(f"   ⚠️  Warning: Detected abnormal number of conflict markers")
                    lines = final_content.split('\n')
                    conflict_lines = []
                    for i, line in enumerate(lines, 1):
                        if re.match(r'^<<<<<<<', line) or re.match(r'^=======', line) or re.match(r'^>>>>>>>', line):
                            conflict_lines.append(f"  Line {i}: {line[:80]}")
                    
                    if conflict_lines:
                        print(f"   Conflict marker locations:")
                        for line_info in conflict_lines[:5]:
                            print(line_info)
                        if len(conflict_lines) > 5:
                            print(f"   ... {len(conflict_lines) - 5} more conflict markers")
                    print(f"   ⚠️  Keeping all conflict markers (including unresolved conflicts)")
            
            # Use git add command to mark conflicts as resolved
            stdout, stderr, code = run_git_cmd(repo_path, ["add", path])
            if code != 0:
                print(f"⚠️  Failed to add file to staging area: {stderr}")
                if "unmerged" in stderr.lower() or "conflict" in stderr.lower():
                    print(f"   ℹ️  File still has unresolved conflicts, this is normal (original conflict markers kept)")
                failed_files.append(path)
                continue
            
            resolved_count += 1
            print(f"✅ Successfully resolved: {path}")
        
        if skipped_count > 0:
            print(f"\nℹ️  Skipped {skipped_count} unresolvable conflicts (original conflict markers kept)")
        
        if failed_files:
            print(f"\n⚠️  The following files failed to resolve: {', '.join(failed_files)}")
            print("💡 Tips:")
            print("   1. These files may contain complex conflicts that need manual checking")
            print("   2. Unresolved conflicts have kept original conflict markers")
            print("   3. You can check files in the temporary directory for manual fixes")
            print(f"   4. Temporary directory: {temp_dir}")
        
        if resolved_count == 0:
            print("\n❌ Failed to resolve any conflicts")
            if skipped_count > 0:
                print(f"   Skipped {skipped_count} conflicts (AI cannot provide valid resolutions, original conflict markers kept)")
            sys.exit(1)
        
        print(f"\n✅ Successfully resolved conflicts in {resolved_count}/{len(conflicts)} file(s)")
        if skipped_count > 0:
            print(f"   Skipped {skipped_count} conflicts (original conflict markers kept)")
        
        # Check Git status
        stdout, stderr, code = run_git_cmd(repo_path, ["status", "--porcelain"])
        unmerged = [line for line in stdout.split('\n') if line.startswith('UU') or line.startswith('AA')]
        if unmerged:
            print(f"\n⚠️  Still have unmerged files: {unmerged}")
            print(f"   ℹ️  These files may contain unresolved conflicts (original conflict markers kept)")
            for path in failed_files:
                print(f"   Trying to force add: {path}")
                run_git_cmd(repo_path, ["add", "--force", path])
        
        stdout2, stderr2, code2 = run_git_cmd(repo_path, ["status", "--porcelain"])
        remaining_unmerged = [line for line in stdout2.split('\n') if line.startswith('UU') or line.startswith('AA')]
        
        # Commit changes
        print("\n💾 Committing changes...")
        commit_message = "chore: resolve merge conflicts using AI"
        if merge_result is None or (merge_result and merge_result.returncode == 0):
            commit_message = "chore: clean up conflict markers using AI"
        
        stdout, stderr, code = run_git_cmd(repo_path, ["commit", "-m", commit_message])
        if code != 0:
            stdout3, stderr3, code3 = run_git_cmd(repo_path, ["status", "--porcelain"])
            if not stdout3.strip():
                print("ℹ️  No changes to commit")
            else:
                print(f"❌ Commit failed: {stderr}")
                if remaining_unmerged:
                    print(f"⚠️  Still have unmerged files, may need manual resolution: {remaining_unmerged}")
                    print(f"   ℹ️  Unresolved conflicts have kept original conflict markers")
                stdout4, stderr4, code4 = run_git_cmd(repo_path, ["status"])
                print(f"📋 Git status:\n{stdout4}")
                print("⚠️  Continuing to try pushing resolved files...")
        
        # Push to remote
        print("\n🚀 Pushing to remote repository...")
        push_url = f"https://{token}@github.com/{owner}/{repo_name}.git"
        stdout, stderr, code = run_git_cmd(repo_path, ["remote", "set-url", "origin", push_url])
        if code != 0:
            print(f"⚠️  Failed to set remote URL: {stderr}")
        
        stdout, stderr, code = run_git_cmd(repo_path, ["push", "origin", pr_info['head_ref']])
        if code != 0:
            print(f"❌ Push failed: {stderr}")
            if remaining_unmerged:
                print(f"💡 Tip: Still have unmerged files, may need to manually resolve these conflicts first")
                print(f"   Unmerged files: {remaining_unmerged}")
                print(f"   ℹ️  Unresolved conflicts have kept original conflict markers")
            sys.exit(1)
        
        if failed_files:
            print("\n⚠️  Some files failed to resolve, but pushed resolved files")
            print(f"   Failed files: {', '.join(failed_files)}")
            print(f"   Unresolved conflicts have kept original conflict markers")
            print(f"   Please manually check and fix these files")
        else:
            print("\n🎉 Done! All conflicts resolved and pushed to PR branch")
        
        print(f"\nPR: https://github.com/{owner}/{repo_name}/pull/{pr_number}")
        
    finally:
        pass


if __name__ == "__main__":
    main()