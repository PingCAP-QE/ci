#!/usr/bin/env bash
set -euo pipefail

image="${1:?Usage: smoke-test.sh IMAGE}"

docker run --rm --entrypoint bash "${image}" -euo pipefail -c '
  test "$(id -un)" = runner
  test -x /home/runner/run.sh
  test -d /home/runner/k8s
  for tool in bash git curl jq unzip docker make wget gzip tar xz zip \
      sha256sum b2sum mktemp; do
    command -v "${tool}" >/dev/null
  done
  tar --version | grep -q "GNU tar"
  tmp="$(mktemp -d)"
  trap '\''rm -rf "${tmp}"'\'' EXIT
  printf "runner smoke test\n" > "${tmp}/input"
  (cd "${tmp}" && sha256sum input > sha256.txt && sha256sum -c sha256.txt)
  (cd "${tmp}" && b2sum input > b2.txt && b2sum -c b2.txt)
  tar --sort=name --mtime=2020-01-01 --owner=0 --group=0 --numeric-owner \
    -czf "${tmp}/archive.tar.gz" -C "${tmp}" input
  tar -tzf "${tmp}/archive.tar.gz" | grep -qx input
'
