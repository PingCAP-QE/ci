#!/usr/bin/env bash
set -euo pipefail

out_name="${1:?usage: $0 <out_name>}"

out_dir="artifacts/${out_name}"
mkdir -p "${out_dir}"

while IFS= read -r -d '' f; do
  safe="${f#/}"
  safe="$(echo "${safe}" | tr '/' '_')"
  cp -f "${f}" "${out_dir}/${safe}" || true
done < <(find . -type f -name '*.log' -print0 2>/dev/null || true)

while IFS= read -r -d '' f; do
  safe="${f#/}"
  safe="$(echo "${safe}" | tr '/' '_')"
  cp -f "${f}" "${out_dir}/${safe}" || true
done < <(find /tmp/ti -type f -name '*.log' ! -path '*/data/*' ! -path '*/tiflash/db*' -print0 2>/dev/null || true)

if [[ -d /tmp/ti ]]; then
  tar -czf "${out_dir}/tmp-ti.tar.gz" -C /tmp ti || true
fi

tar -czf "artifacts/${out_name}.tar.gz" -C artifacts "${out_name}" || true
