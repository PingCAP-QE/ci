#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
target_branch="${PULL_BASE_REF:-}"
build_type="${BUILD_TYPE:-}"
sanitizer="${SANITIZER:-}"
cmake_build_type="Debug"
run_tests="yes"
build_jobs="${MAKE_JOBS:-2}"
cmake_cxx_flags="${CXXFLAGS:-}"
rocksdb_dir=""

if [[ -z "${target_branch}" ]]; then
  echo "PULL_BASE_REF is required for Titan release branch jobs" >&2
  exit 1
fi

run_with_sanitizer_runtime() {
  "$@"
}

run_git_in_dir() {
  local repo_dir="$1"
  shift
  (
    cd "${repo_dir}"
    git "$@"
  )
}

prepare_rocksdb_source() {
  local resolved=""
  local rocksdb_branch=""
  local rocksdb_repo=""
  local rocksdb_ref=""
  local rocksdb_sha=""
  local repo_path=""
  local repo_key=""
  local rocksdb_root=""

  resolved="$(bash "${script_dir}/resolve_rocksdb_ref.sh" "${target_branch}")"
  IFS=$'\t' read -r rocksdb_branch rocksdb_repo rocksdb_ref rocksdb_sha <<< "${resolved}"

  repo_path="${rocksdb_repo#https://github.com/}"
  repo_path="${repo_path%.git}"
  repo_key="${repo_path//\//-}"
  rocksdb_root="${PWD}/.rocksdb-src"
  rocksdb_dir="${rocksdb_root}/${repo_key}-${rocksdb_sha}"

  mkdir -p "${rocksdb_root}"

  if [[ ! -d "${rocksdb_dir}/.git" ]]; then
    git clone --branch "${rocksdb_ref}" --depth=1 "${rocksdb_repo}" "${rocksdb_dir}"
  else
    run_git_in_dir "${rocksdb_dir}" remote set-url origin "${rocksdb_repo}"
  fi

  if [[ "$(run_git_in_dir "${rocksdb_dir}" rev-parse HEAD 2>/dev/null || true)" != "${rocksdb_sha}" ]]; then
    run_git_in_dir "${rocksdb_dir}" fetch --depth=1 origin "${rocksdb_sha}"
    run_git_in_dir "${rocksdb_dir}" checkout -f "${rocksdb_sha}"
  fi

  echo "using rocksdb for ${rocksdb_branch}: repo=${rocksdb_repo} ref=${rocksdb_ref} sha=${rocksdb_sha}"
}

required_packages=(
  git
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

prepare_rocksdb_source
cmake_args+=("-DROCKSDB_DIR=${rocksdb_dir}")

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
