#!/bin/bash

set -x
set -e

if [ $# -ne 2 ]; then
    echo "Usage: $0 <directory> <output_file>"
    exit 1
fi

directory="$1"
output_file="$2"

echo "Searching in directory: $directory"
echo "Output file: $output_file"

temp_file=$(mktemp)

# 查找所有 .yaml 和 .yml 文件，提取 image 字段
find "$directory" -type f \( -name "*.yaml" -o -name "*.yml" \) | while read -r file; do
    echo "Processing file: $file"
    # 使用 sed 来提取 image 字段，并处理带引号的情况
    sed -n 's/^[[:space:]]*image:[[:space:]]*//p' "$file" |
    sed -E 's/^"(.*)"|'\''(.*)'\''|(.*)$/\1\2\3/' |
    sed -E 's/^[[:space:]]+|[[:space:]]+$//g' >> "$temp_file"
done

sort -u "$temp_file" > "$output_file"

rm "$temp_file"

echo "Unique sorted images have been saved to $output_file"
echo "Content of $output_file:"
cat "$output_file"

# 检查结果
if [ ! -s "$output_file" ]; then
    echo "Warning: No images found or output file is empty."
else
    echo "Number of unique images found: $(wc -l < "$output_file")"
fi
