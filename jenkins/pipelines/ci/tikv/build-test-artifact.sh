export RUSTFLAGS=-Dwarnings
export FAIL_POINT=1
export ROCKSDB_SYS_SSE=1
export RUST_BACKTRACE=1
export LOG_LEVEL=INFO
export CARGO_INCREMENTAL=0
export RUSTDOCFLAGS="-Z unstable-options --persist-doctests"
echo using gcc 8
source /opt/rh/devtoolset-8/enable
set -o pipefail
# Build and generate a list of binaries
CUSTOM_TEST_COMMAND="nextest list" EXTRA_CARGO_ARGS="--message-format json --list-type binaries-only" make test_with_nextest | grep -E '^{.+}$' > test.json
# Cargo metadata
cargo metadata --format-version 1 > test-metadata.json