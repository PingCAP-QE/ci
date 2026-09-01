#!/bin/sh

set -eu

echo "Validating Jenkins shared library Groovy sources..."
source_files="$(find libraries -type f -path '*/vars/*.groovy' -print | LC_ALL=C sort)"
if [ -z "${source_files}" ]; then
    echo "No Jenkins shared library sources found."
    exit 1
fi

for source_file in ${source_files}; do
    echo "Parsing ${source_file}"
    groovy -e 'new GroovyShell().parse(new File(args[0]))' "${source_file}"
done

echo "Running Jenkins shared library tests..."
for test_file in libraries/tisys/tests/Test*.groovy libraries/tipipeline/tests/Test*.groovy; do
    [ -f "${test_file}" ] || continue
    echo "Running ${test_file}"
    groovy "${test_file}"
done

echo "Jenkins shared library validation passed."
