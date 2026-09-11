#!/usr/bin/env bash
set -euo pipefail

build_type="${BUILD_TYPE:-}"
sanitizer="${SANITIZER:-}"
cmake_build_type="Debug"
run_tests="yes"
build_jobs="${MAKE_JOBS:-2}"
cmake_cxx_flags="${CXXFLAGS:-}"

run_with_sanitizer_runtime() {
  "$@"
}

required_packages=(
  libstdc++-devel
  snappy-devel
  lz4-devel
  zlib-devel
  libzstd-devel
  gflags-devel
)

cmake_args=(
  .
  -L
  -DWITH_SNAPPY=ON
  -DWITH_LZ4=ON
  -DWITH_ZLIB=ON
  -DWITH_ZSTD=ON
  -DROCKSDB_BUILD_SHARED=OFF
)

if [[ -n "${build_type}" ]]; then
  cmake_build_type="${build_type}"
  run_tests="no"
fi

if [[ -n "${sanitizer}" ]]; then
  case "${sanitizer}" in
    ASAN)
      cmake_args+=("-DWITH_ASAN=ON" "-DWITH_TITAN_TOOLS=OFF")
      required_packages+=(devtoolset-8-libasan-devel)
      ;;
    TSAN)
      cmake_args+=("-DWITH_TSAN=ON" "-DWITH_TITAN_TOOLS=OFF")
      required_packages+=(devtoolset-8-libtsan-devel)
      ;;
    UBSAN)
      cmake_args+=("-DWITH_UBSAN=ON" "-DWITH_TITAN_TOOLS=OFF")
      required_packages+=(devtoolset-8-libubsan-devel)
      ;;
    *)
      echo "unsupported sanitizer: ${sanitizer}" >&2
      exit 1
      ;;
  esac
fi

if [[ "${sanitizer}" == "TSAN" ]]; then
  if ! command -v setarch >/dev/null 2>&1; then
    echo "setarch is required for TSan runtime on this job" >&2
    exit 1
  fi

  arch_name="$(uname -m)"
  run_with_sanitizer_runtime() {
    setarch "${arch_name}" -R "$@"
  }
fi

if ! rpm -q "${required_packages[@]}" >/dev/null 2>&1; then
  yum install -y "${required_packages[@]}"
fi

if [[ -f /usr/include/gflags/gflags.h ]] \
  && grep -q 'namespace gflags' /usr/include/gflags/gflags.h \
  && ! grep -q 'namespace google' /usr/include/gflags/gflags.h; then
  cmake_cxx_flags="${cmake_cxx_flags:+${cmake_cxx_flags} }-DGFLAGS_NAMESPACE=gflags"
fi

if [[ -n "${cmake_cxx_flags}" ]]; then
  cmake_args+=("-DCMAKE_CXX_FLAGS=${cmake_cxx_flags}")
fi

cmake_args+=("-DCMAKE_BUILD_TYPE=${cmake_build_type}")

if [[ -f /opt/rh/devtoolset-8/enable ]]; then
  set +u
  source /opt/rh/devtoolset-8/enable
  set -u
fi

g++ --version
cmake --version

rm -rf ./tmp_dir
mkdir -p ./tmp_dir

cmake "${cmake_args[@]}"

VERBOSE=1 make -j"${build_jobs}"

if [[ "${run_tests}" == "yes" ]]; then
  ctest_bin=ctest
  if ! command -v "${ctest_bin}" >/dev/null 2>&1; then
    ctest_bin=ctest3
  fi

  TEST_TMPDIR=./tmp_dir ASAN_OPTIONS=detect_leaks=0 \
    run_with_sanitizer_runtime "${ctest_bin}" --verbose -R titan
fi
