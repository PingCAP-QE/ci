#!/usr/bin/env python3
"""
CI-optimized conflict resolver: Resolve Git merge conflicts in current directory
Usage: python resolve_conflicts_ci.py

Environment variables:
  OPENAI_API_KEY - OpenAI API key (required)
  OPENAI_API_BASE - Custom OpenAI API address (optional)
    Example: https://api.jiekou.ai/openai
  RECONCILE_MODEL - Model to use (optional, default: gpt-4o)
  MAX_BATCH_SIZE - Batch size for conflict resolution (optional, default: 5)
"""

import os
import sys
import re
import logging
from pathlib import Path
from openai import OpenAI

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('resolve_conflicts_ci')


def get_openai_client(config=None):
    """Get OpenAI client with support for custom API base URL"""
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("OPENAI_API_KEY environment variable must be set")

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


def parse_conflicts_smart(content):
    """
    Intelligently parse conflict blocks, correctly handle nested conflicts
    Only extract the outermost complete conflict blocks
    """
    lines = content.split('\n')
    conflicts = []
    depth = 0
    current_conflict = None
    start_marker_count = 0
    end_marker_count = 0

    for i, line in enumerate(lines):
        stripped = line.strip()

        if stripped.startswith('<<<<<<<'):
            if depth == 0:
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
                if current_conflict:
                    current_conflict['lines'].append(line)
                    current_conflict['start_markers'] += 1
                    start_marker_count += 1
            depth += 1

        elif stripped.startswith('======='):
            if current_conflict:
                current_conflict['lines'].append(line)
                if depth == 1:
                    current_conflict['has_separator'] = True

        elif stripped.startswith('>>>>>>>'):
            if current_conflict:
                current_conflict['lines'].append(line)
                current_conflict['end_markers'] += 1
                end_marker_count += 1
            depth -= 1

            if depth == 0 and current_conflict:
                if (current_conflict.get('has_separator') and
                    current_conflict.get('start_markers', 0) == current_conflict.get('end_markers', 0) and
                    current_conflict.get('start_markers', 0) > 0):
                    conflict_text = '\n'.join(current_conflict['lines'])
                    conflicts.append(conflict_text)
                current_conflict = None
                start_marker_count = 0
                end_marker_count = 0

        else:
            if current_conflict:
                current_conflict['lines'].append(line)

    return conflicts


def clean_ai_response(resolved_text):
    """Clean AI's response, remove markdown code blocks, explanatory text, etc."""
    if not resolved_text:
        return None

    error_indicators = [
        "It seems", "does not contain", "Please provide", "Please share",
        "I cannot", "I'm unable", "I don't have", "cannot resolve", "unable to resolve"
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
        r'^Explanation:.*?\n', r'^Here is.*?:\n', r'^The resolved code.*?:\n',
        r'^RESOLUTION\s+\d+:.*?\n', r'^---.*?\n', r'^\*\*RESOLUTION.*?\*\*.*?\n',
    ]

    for pattern in explanation_patterns:
        resolved_text = re.sub(pattern, '', resolved_text, flags=re.MULTILINE | re.IGNORECASE)

    # Remove markdown format markers
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
    """Validate if the resolution is reasonable"""
    if not resolved_text:
        return False

    if check_conflict_markers(resolved_text):
        return False

    error_indicators = [
        "It seems", "does not contain", "Please provide", "Please share",
        "I cannot", "I'm unable", "I don't have", "cannot resolve", "unable to resolve"
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
    """Check if the conflict block is complete"""
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

    return (start_count >= 1 and separator_count >= 1 and
            end_count >= 1 and start_count == end_count)


def extract_conflict_core(conflict_section):
    """Extract the core content of the conflict (remove marker lines)"""
    lines = conflict_section.split('\n')
    core_lines = []

    for line in lines:
        stripped = line.strip()
        if stripped.startswith('<<<<<<<') or stripped.startswith('=======') or stripped.startswith('>>>>>>>'):
            continue
        core_lines.append(line)

    return '\n'.join(core_lines).strip()


def extract_indent_from_conflict(conflict_section):
    """Extract the indentation of the first actual code line from the conflict block"""
    lines = conflict_section.split('\n')

    for line in lines:
        stripped = line.strip()
        if stripped.startswith('<<<<<<<') or stripped.startswith('=======') or stripped.startswith('>>>>>>>'):
            continue
        if stripped:
            indent = line[:len(line) - len(line.lstrip())]
            return indent

    return ""


def check_conflict_lines_same_indent_level(conflict_section):
    """Check if all code lines in the conflict block have the same indentation level"""
    lines = conflict_section.split('\n')
    code_indents = []
    first_indent = None

    for line in lines:
        stripped = line.strip()
        if stripped.startswith('<<<<<<<') or stripped.startswith('=======') or stripped.startswith('>>>>>>>'):
            continue
        if not stripped:
            continue

        indent = line[:len(line) - len(line.lstrip())]
        code_indents.append(indent)

        if first_indent is None:
            first_indent = indent

    if not code_indents or first_indent is None:
        return True, first_indent or ""

    all_same = all(indent == first_indent for indent in code_indents)
    return all_same, first_indent


def extract_conflict_line_indents(conflict_section):
    """Extract indentation of all code lines from the conflict block"""
    lines = conflict_section.split('\n')
    line_indents = []

    for idx, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith('<<<<<<<') or stripped.startswith('=======') or stripped.startswith('>>>>>>>'):
            continue
        if not stripped:
            continue

        indent = line[:len(line) - len(line.lstrip())]
        line_indents.append((idx, indent))

    return line_indents


def extract_indent_from_context(original_content, conflict_start, conflict_end):
    """Extract indentation information from the context before and after the conflict block"""
    lines = original_content.split('\n')
    conflict_start_line = original_content[:conflict_start].count('\n')
    conflict_end_line = original_content[:conflict_end].count('\n')

    base_indent = ""
    for i in range(max(0, conflict_start_line - 1), max(0, conflict_start_line - 10), -1):
        line = lines[i]
        stripped = line.strip()
        if stripped and not stripped.startswith('<<<<<<<') and not stripped.startswith('=======') and not stripped.startswith('>>>>>>>'):
            base_indent = line[:len(line) - len(line.lstrip())]
            break

    if not base_indent:
        for i in range(conflict_end_line, min(len(lines), conflict_end_line + 10)):
            line = lines[i]
            stripped = line.strip()
            if stripped and not stripped.startswith('<<<<<<<') and not stripped.startswith('=======') and not stripped.startswith('>>>>>>>'):
                base_indent = line[:len(line) - len(line.lstrip())]
                break

    if not base_indent:
        base_indent = extract_indent_from_conflict(original_content[conflict_start:conflict_end])

    if base_indent:
        if base_indent.startswith('\t'):
            indent_unit = '\t'
            indent_size = 1
        else:
            spaces = len(base_indent)
            for unit in [2, 4, 8]:
                if spaces % unit == 0:
                    indent_unit = ' ' * unit
                    indent_size = unit
                    break
            else:
                indent_unit = ' ' * 4
                indent_size = 4
    else:
        indent_unit = ' ' * 4
        indent_size = 4

    return base_indent, indent_unit, indent_size


def normalize_indent_to_target(resolved_code, target_indent, indent_unit, indent_size, preserve_relative_indent=True, conflict_line_indents=None):
    """Adjust the indentation of resolved code to match the target indentation"""
    if not resolved_code:
        return resolved_code

    lines = resolved_code.split('\n')
    if not lines:
        return resolved_code

    if not target_indent:
        target_indent = '    '
        indent_unit = '    '
        indent_size = 4

    if not preserve_relative_indent:
        adjusted_lines = []
        for line in lines:
            if not line.strip():
                adjusted_lines.append(line)
            else:
                adjusted_lines.append(target_indent + line.lstrip())
        return '\n'.join(adjusted_lines)

    if conflict_line_indents and len(conflict_line_indents) > 0:
        first_conflict_indent = conflict_line_indents[0][1]

        if indent_unit == '\t':
            first_level = first_conflict_indent.count('\t')
            first_level += len(first_conflict_indent.replace('\t', '')) // indent_size if indent_size > 0 else 0
        else:
            first_level = len(first_conflict_indent) // indent_size if indent_size > 0 else 0

        if indent_unit == '\t':
            target_level = target_indent.count('\t')
            target_level += len(target_indent.replace('\t', '')) // indent_size if indent_size > 0 else 0
        else:
            target_level = len(target_indent) // indent_size if indent_size > 0 else 0

        adjusted_lines = []
        code_line_idx = 0
        for line in lines:
            if not line.strip():
                adjusted_lines.append(line)
                continue

            if code_line_idx < len(conflict_line_indents):
                conflict_indent = conflict_line_indents[code_line_idx][1]

                if indent_unit == '\t':
                    conflict_level = conflict_indent.count('\t')
                    conflict_level += len(conflict_indent.replace('\t', '')) // indent_size if indent_size > 0 else 0
                else:
                    conflict_level = len(conflict_indent) // indent_size if indent_size > 0 else 0

                relative_level = conflict_level - first_level
                new_level = target_level + relative_level
                new_level = max(0, new_level)
                new_indent = indent_unit * new_level
            else:
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

    min_indent = None
    code_line_indents = []

    for line in lines:
        if not line.strip():
            code_line_indents.append(None)
            continue

        current_indent = line[:len(line) - len(line.lstrip())]
        code_line_indents.append(current_indent)

        if min_indent is None or len(current_indent) < len(min_indent):
            min_indent = current_indent

    if min_indent is None:
        return resolved_code

    adjusted_lines = []
    for idx, line in enumerate(lines):
        if not line.strip():
            adjusted_lines.append(line)
            continue

        original_indent = code_line_indents[idx]

        if len(original_indent) >= len(min_indent):
            relative_indent = original_indent[len(min_indent):]
        else:
            relative_indent = ""

        new_indent = target_indent + relative_indent
        adjusted_lines.append(new_indent + line.lstrip())

    return '\n'.join(adjusted_lines)


def resolve_conflict_with_indent_preservation(section, model="gpt-4o", config=None):
    """Resolve a single conflict, explicitly require AI to preserve original indentation"""
    import time

    conflict_indent = extract_indent_from_conflict(section)

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
        client = get_openai_client(config)

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

        logger.info(f"Resolved conflict section in {latency:.2f}s")

        return resolved
    except Exception as e:
        logger.error(f"Failed to resolve conflict: {e}")
        return section


def resolve_conflicts_batch_with_indent_preservation(sections, model="gpt-4o", max_batch_size=5, config=None):
    """Resolve conflicts in batch, explicitly require AI to preserve original indentation"""
    import time

    if not sections:
        return []

    all_resolutions = []

    for i in range(0, len(sections), max_batch_size):
        batch = sections[i:i + max_batch_size]
        batch_num = (i // max_batch_size) + 1
        total_batches = (len(sections) + max_batch_size - 1) // max_batch_size

        logger.info(f"Processing batch {batch_num}/{total_batches} ({len(batch)} conflicts)")

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
            client = get_openai_client(config)

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

            logger.info(f"Resolved batch {batch_num} in {latency:.2f}s")

            # Parse batch response
            batch_resolutions = []
            parts = re.split(r'RESOLUTION\s+\d+:', resolved_text, flags=re.IGNORECASE)

            for part in parts[1:]:
                cleaned = part.strip()
                if cleaned.startswith('```'):
                    lines = cleaned.split('\n')
                    if len(lines) > 1 and lines[0].startswith('```'):
                        cleaned = '\n'.join(lines[1:])
                    if cleaned.endswith('```'):
                        cleaned = cleaned[:-3].rstrip()
                batch_resolutions.append(cleaned)

            if len(batch_resolutions) != len(batch):
                if len(batch) == 1:
                    batch_resolutions = [resolved_text]
                else:
                    batch_resolutions = [resolved_text] * len(batch)

            all_resolutions.extend(batch_resolutions)

        except Exception as e:
            logger.error(f"Batch resolution failed: {e}")
            # Fall back to individual resolution
            for section in batch:
                try:
                    resolved = resolve_conflict_with_indent_preservation(section, model=model, config=config)
                    all_resolutions.append(resolved)
                except Exception:
                    all_resolutions.append(section)

    return all_resolutions


def remove_duplicate_lines(content, context_lines=3):
    """Remove consecutive duplicate code lines"""
    lines = content.split('\n')
    cleaned_lines = []
    i = 0

    while i < len(lines):
        line = lines[i]
        cleaned_lines.append(line)

        if line.strip() in ['{', '}']:
            j = i + 1
            duplicate_count = 0
            while j < len(lines) and lines[j].strip() == line.strip():
                duplicate_count += 1
                j += 1

            if duplicate_count > 5:
                i = j
            else:
                i += 1
            continue

        j = i + 1
        duplicate_count = 0

        while j < len(lines) and j < i + context_lines + 1:
            current_line = lines[j]

            if current_line.strip() == line.strip() and line.strip():
                if line.strip().startswith('//') or line.strip().startswith('#'):
                    break
                duplicate_count += 1
                j += 1
            else:
                break

        if duplicate_count > 0:
            i = j
        else:
            i += 1

    return '\n'.join(cleaned_lines)


def apply_resolutions_safe(file_path, original_content, resolved_map):
    """Safely apply resolutions"""
    updated = original_content
    replaced_count = 0

    # Clean and validate all resolutions
    cleaned_resolved_map = {}
    for section, resolved in resolved_map.items():
        if resolved is None:
            logger.warning(f"Resolution invalid, skipping this conflict")
            continue

        cleaned = clean_ai_response(resolved)

        if cleaned is None:
            logger.warning(f"Resolution contains error messages, skipping this conflict")
            continue

        if not validate_resolution(section, cleaned):
            logger.warning(f"Resolution validation failed, skipping this conflict")
            continue

        if check_conflict_markers(cleaned):
            lines = cleaned.split('\n')
            cleaned_lines = []
            for line in lines:
                if not (line.strip().startswith('<<<<<<<') or
                        line.strip().startswith('=======') or
                        line.strip().startswith('>>>>>>>')):
                    cleaned_lines.append(line)
            cleaned = '\n'.join(cleaned_lines)

            if check_conflict_markers(cleaned):
                logger.warning(f"Cannot clean conflict markers, skipping this conflict")
                continue

        cleaned_resolved_map[section] = cleaned

    if not cleaned_resolved_map:
        logger.warning(f"All resolutions are invalid, keeping original conflict markers")
        return updated, 0

    # Use smart parsing to find all complete conflict blocks
    all_conflicts = parse_conflicts_smart(updated)

    # Process from back to front to avoid position offset
    for conflict_text in reversed(all_conflicts):
        conflict_start = updated.find(conflict_text)
        if conflict_start == -1:
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
            original_core = extract_conflict_core(original_section)
            current_core = extract_conflict_core(conflict_text)

            if original_core.strip() == current_core.strip():
                resolved = cleaned_resolved
                break

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
            # Extract indentation
            conflict_indent = extract_indent_from_conflict(conflict_text)

            if not conflict_indent:
                base_indent, indent_unit, indent_size = extract_indent_from_context(
                    updated, conflict_start, conflict_end
                )
                target_indent = base_indent
                if not target_indent:
                    target_indent = '    '
                    indent_unit = '    '
                    indent_size = 4
            else:
                if conflict_indent.startswith('\t'):
                    indent_unit = '\t'
                    indent_size = 1
                else:
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

            if not target_indent:
                target_indent = '    '
                indent_unit = '    '
                indent_size = 4

            same_indent_level, conflict_first_indent = check_conflict_lines_same_indent_level(conflict_text)
            conflict_line_indents = extract_conflict_line_indents(conflict_text)

            resolved = normalize_indent_to_target(
                resolved,
                target_indent,
                indent_unit,
                indent_size,
                preserve_relative_indent=not same_indent_level,
                conflict_line_indents=conflict_line_indents if not same_indent_level else None
            )

            updated = updated[:conflict_start] + resolved + updated[conflict_end:]
            replaced_count += 1

    # Clean all remaining conflict markers
    lines = updated.split('\n')
    cleaned_lines = []
    i = 0

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped.startswith('<<<<<<<') or stripped.startswith('=======') or stripped.startswith('>>>>>>>'):
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
                i += 1
                continue

        cleaned_lines.append(line)
        i += 1

    updated = '\n'.join(cleaned_lines)

    # Use regex to clean all remaining conflict markers
    max_cleanup_iterations = 5
    for _ in range(max_cleanup_iterations):
        before = updated
        updated = re.sub(r'^<<<<<<<[^\n]*\n', '', updated, flags=re.MULTILINE)
        updated = re.sub(r'^=======\s*\n', '', updated, flags=re.MULTILINE)
        updated = re.sub(r'^>>>>>>>[^\n]*\n', '', updated, flags=re.MULTILINE)
        if before == updated:
            break

    # Final verification
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

    # Remove duplicate lines
    updated = remove_duplicate_lines(updated)

    # Clean excessive empty lines
    updated = re.sub(r'\n{3,}', '\n\n', updated)

    # Write to file
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(updated)

    return updated, replaced_count


def process_single_file(file_path):
    """Process a single file for conflicts"""
    file_path_obj = Path(file_path)

    if not file_path_obj.exists():
        raise FileNotFoundError(f"File not found: {file_path}")

    if not file_path_obj.is_file():
        raise ValueError(f"Path is not a file: {file_path}")

    try:
        with open(file_path_obj, 'r', encoding='utf-8', errors='ignore') as f:
            content = f.read()

        if '<<<<<<< HEAD' not in content and '<<<<<<<' not in content:
            return None, []

        sections = parse_conflicts_smart(content)
        if not sections:
            return None, []

        return content, sections
    except Exception as e:
        raise IOError(f"Failed to read file {file_path}: {e}")

def main():
    """Main function for CI environment"""
    # Get file path from command line arguments
    if len(sys.argv) < 2:
        print("❌ Error: File path is required")
        print("Usage: python resolve_conflicts_ci.py <file_path>")
        sys.exit(1)

    file_path = sys.argv[1]

    # Get configuration from environment variables
    api_key = os.getenv("OPENAI_API_KEY")
    api_base = os.getenv("OPENAI_API_BASE")
    model = os.getenv("RECONCILE_MODEL", "gpt-4o")
    max_batch_size = int(os.getenv("MAX_BATCH_SIZE", "5"))

    if not api_key:
        print("❌ Error: OPENAI_API_KEY environment variable must be set")
        sys.exit(1)

    # Build config
    config = {}
    if api_base:
        config['api_base_url'] = api_base

    print(f"🤖 Using model: {model}, batch size: {max_batch_size}")
    if api_base:
        print(f"🌐 API address: {api_base}")

    # Process single file
    print(f"🔍 Processing file: {file_path}")
    try:
        content, sections = process_single_file(file_path)
    except Exception as e:
        print(f"❌ Error: {e}")
        sys.exit(1)

    if content is None or not sections:
        print("✅ No conflicts found in file, nothing to resolve")
        return

    total_conflicts = len(sections)
    print(f"📝 Found {total_conflicts} conflict(s) in file")

    # Display conflict details
    print("\n📋 Conflict details:")
    for i, section in enumerate(sections, 1):
        preview = section.split('\n')[0][:80]
        print(f"   Conflict {i}: {preview}...")

    print("\n⚠️  Starting conflict resolution...")

    original_conflict_count = content.count("<<<<<<< HEAD")

    # Filter out incomplete conflict blocks
    complete_sections = []
    skipped_count = 0
    for section in sections:
        if is_complete_conflict(section):
            complete_sections.append(section)
        else:
            logger.warning(f"Skipping incomplete conflict block")
            skipped_count += 1

    if not complete_sections:
        logger.warning(f"No complete conflict blocks to resolve, keeping original conflict markers")
        print("❌ No complete conflict blocks found")
        sys.exit(1)

    # Batch resolve conflicts
    print(f"\n🤖 Using AI to resolve {len(complete_sections)} conflict(s)...")
    try:
        resolved_sections = resolve_conflicts_batch_with_indent_preservation(
            complete_sections,
            model=model,
            max_batch_size=max_batch_size,
            config=config
        )
        cleaned_sections = []
        for i, rs in enumerate(resolved_sections):
            cleaned = clean_ai_response(rs)
            if cleaned is None or not validate_resolution(complete_sections[i], cleaned):
                logger.warning(f"Resolution for conflict {i+1} is invalid, will skip")
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
        logger.error(f"Batch resolution failed, using individual resolution: {e}")

        resolved_map = {}
        for sec in complete_sections:
            try:
                merged = resolve_conflict_with_indent_preservation(sec, model=model, config=config)
                cleaned = clean_ai_response(merged)
                if cleaned is None or not validate_resolution(sec, cleaned):
                    logger.warning(f"Resolution for this conflict is invalid, will skip")
                    skipped_count += 1
                    continue
                resolved_map[sec] = cleaned
            except Exception as e2:
                logger.error(f"Failed to resolve individual conflict: {e2}")
                skipped_count += 1
                continue

        if not resolved_map:
            print("❌ Failed to resolve any conflicts")
            sys.exit(1)

    if not resolved_map:
        logger.warning(f"No valid resolutions, keeping original conflict markers")
        print("❌ No valid resolutions found")
        sys.exit(1)

    # Apply resolutions
    try:
        file_path_obj = Path(file_path)
        final_content, replaced_count = apply_resolutions_safe(str(file_path_obj), content, resolved_map)
        if replaced_count < len(resolved_map):
            logger.warning(f"Only replaced {replaced_count}/{len(resolved_map)} conflict markers")
        if replaced_count == 0:
            logger.warning(f"Failed to replace any conflicts, keeping original conflict markers")
            print("❌ Failed to replace any conflicts")
            sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to apply resolutions: {e}")
        print(f"❌ Error applying resolutions: {e}")
        sys.exit(1)

    # Verify
    remaining_conflicts = final_content.count("<<<<<<< HEAD")
    if remaining_conflicts > 0:
        if remaining_conflicts < original_conflict_count:
            logger.info(f"Resolved {original_conflict_count - remaining_conflicts} conflicts, {remaining_conflicts} conflicts still unresolved")
            print(f"⚠️  Resolved {original_conflict_count - remaining_conflicts} conflicts, {remaining_conflicts} conflicts still unresolved")
        else:
            logger.warning(f"All conflicts unresolved, keeping original conflict markers")
            print("❌ All conflicts unresolved")
            sys.exit(1)

    if skipped_count > 0:
        print(f"\nℹ️  Skipped {skipped_count} unresolvable conflicts")

    print(f"\n✅ Successfully resolved conflicts in {file_path}")
    print(f"   Resolved {replaced_count} conflict(s)")


if __name__ == "__main__":
    main()
