import argparse
import os
import subprocess

import requests

from check_info import check_version
from config import COMPONENT_META


def get_base_url(is_internal=None):
    """
    Determine whether to use internal or public URL based on environment variable.

    Args:
        is_internal: Override environment variable if provided

    Returns:
        tuple: (public_base_url, internal_base_url, use_internal)
    """
    public_base_url = "https://download.pingcap.org"
    internal_base_url = "http://fileserver.pingcap.net/download/release"

    # If is_internal is explicitly provided, use it
    if is_internal is not None:
        return public_base_url, internal_base_url, is_internal

    # Otherwise check environment variable
    use_internal = os.environ.get(
        "USE_INTERNAL_URL", "false"
    ).lower() in ("true", "1", "yes")
    return public_base_url, internal_base_url, use_internal


def get_components_hash(url):
    components_hash = {}
    response = requests.get(url)
    response.raise_for_status()
    json_data = response.json()

    print(json_data)
    for component in json_data["tiup_packages"]:
        components_hash[component["name"]] = component["commit_hash"]

    print(components_hash)
    return components_hash


def get_file_size(url):
    """尝试获取文件大小，如果文件存在返回大小和True，否则返回None和False。"""
    response = requests.head(url)
    if response.status_code != 200:
        # 如果 HEAD 请求失败，尝试 GET 请求
        response = requests.get(url, stream=True)
    if response.status_code == 200:
        return response.headers.get('Content-Length', None), True
    return None, False


def compare_package_sizes(url1, url2):
    """比较两个包的大小，确保它们存在且大小一致。"""
    size1, exists1 = get_file_size(url1)
    size2, exists2 = get_file_size(url2)
    if exists1 and exists2 and size1 == size2:
        return True, size1  # 包存在且大小一致
    return False, None


def check_offline_package(version):
    results = []
    success = True  # 初始假设所有检查都成功

    public_base, internal_base, use_internal = get_base_url()

    editions = ["community", "enterprise"]
    arches = ["amd64", "arm64"]
    package_types = ["server", "toolkit"]

    for edition in editions:
        for arch in arches:
            for package_type in package_types:
                public_url = (
                    f"{public_base}/tidb-{edition}-{package_type}-"
                    f"{version}-linux-{arch}.tar.gz"
                )
                internal_url = (
                    f"{internal_base}/tidb-{edition}-{package_type}-"
                    f"{version}-linux-{arch}.tar.gz"
                )

                # Determine which URL to use for checking
                url_to_check = internal_url if use_internal else public_url

                print(f"Using URL: {url_to_check}")

                # For backward compatibility, still compare sizes if not using internal URL
                if not use_internal:
                    are_sizes_equal, size = compare_package_sizes(
                        public_url, internal_url
                    )
                    if are_sizes_equal:
                        results.append(
                            f"{edition} {package_type} {version} {arch}: PASS, size: {size}"
                        )
                    else:
                        success = False
                        results.append(
                            f"{edition} {package_type} {version} {arch}: FAIL or file(s) not found"
                        )
                else:
                    # Just check if the internal URL exists
                    size, exists = get_file_size(url_to_check)
                    if exists:
                        results.append(
                            f"{edition} {package_type} {version} {arch}: PASS, size: {size}"
                        )
                    else:
                        success = False
                        results.append(
                            f"{edition} {package_type} {version} {arch}: FAIL or file(s) not found"
                        )

    for result in results:
        print(result)

    return success, results


def check_plugin_package(version):
    results = []
    success = True

    public_base, internal_base, use_internal = get_base_url()

    arches = ["amd64", "arm64"]
    for arch in arches:
        public_url = (
            f"{public_base}/enterprise-plugin-{version}-linux-{arch}.tar.gz"
        )
        internal_url = (
            f"{internal_base}/enterprise-plugin-{version}-linux-{arch}.tar.gz"
        )

        # Determine which URL to use for checking
        url_to_check = internal_url if use_internal else public_url

        print(f"Using URL: {url_to_check}")

        # For backward compatibility, still compare sizes if not using internal URL
        if not use_internal:
            are_size_equal, size = compare_package_sizes(
                public_url, internal_url
            )
            if are_size_equal:
                results.append(
                    f"plugin {version} {arch}: PASS, size: {size}"
                )
            else:
                success = False
                results.append(
                    f"plugin {version} {arch}: FAIL or file(s) not found"
                )
        else:
            # Just check if the internal URL exists
            size, exists = get_file_size(url_to_check)
            if exists:
                results.append(
                    f"plugin {version} {arch}: PASS, size: {size}"
                )
            else:
                success = False
                results.append(
                    f"plugin {version} {arch}: FAIL or file(s) not found"
                )

    for result in results:
        print(result)

    return success, results


def check_dm_package(version):
    results = []
    success = True  # 初始假设所有检查都成功

    public_base, internal_base, use_internal = get_base_url()

    arches = ["amd64", "arm64"]
    for arch in arches:
        public_url = f"{public_base}/tidb-dm-{version}-linux-{arch}.tar.gz"
        internal_url = f"{internal_base}/tidb-dm-{version}-linux-{arch}.tar.gz"

        # Determine which URL to use for checking
        url_to_check = internal_url if use_internal else public_url

        print(f"Using URL: {url_to_check}")

        # For backward compatibility, still compare sizes if not using internal URL
        if not use_internal:
            are_size_equal, size = compare_package_sizes(
                public_url, internal_url
            )
            if are_size_equal:
                results.append(f"dm {version} {arch}: PASS, size: {size}")
            else:
                success = False
                results.append(
                    f"dm {version} {arch}: FAIL or file(s) not found"
                )
        else:
            # Just check if the internal URL exists
            size, exists = get_file_size(url_to_check)
            if exists:
                results.append(f"dm {version} {arch}: PASS, size: {size}")
            else:
                success = False
                results.append(
                    f"dm {version} {arch}: FAIL or file(s) not found"
                )

    for result in results:
        print(result)

    return success, results


def check_offline_components(version, edition, arch, component_hash):
    public_base, internal_base, use_internal = get_base_url()

    # Determine which URL to use for downloading
    base_url = internal_base if use_internal else public_base

    server_package_url = f"{base_url}/tidb-{edition}-server-{version}-linux-{arch}.tar.gz"
    toolkit_package_url = f"{base_url}/tidb-{edition}-toolkit-{version}-linux-{arch}.tar.gz"

    print(f"Downloading server package from: {server_package_url}")
    print(f"Downloading toolkit package from: {toolkit_package_url}")

    # download package
    subprocess.run(["wget", "-q", server_package_url], check=True)
    subprocess.run(["wget", "-q", toolkit_package_url], check=True)

    # extract package
    subprocess.run(["tar", "xf", f"tidb-{edition}-server-{version}-linux-{arch}.tar.gz"], check=True)
    subprocess.run(["tar", "xf", f"tidb-{edition}-toolkit-{version}-linux-{arch}.tar.gz"], check=True)

    # set tiup mirror to server offline package dir
    subprocess.run(["tiup", "mirror", "set", f"tidb-{edition}-server-{version}-linux-{arch}"], check=True)
    subprocess.run(["tiup", "uninstall", "--all"], check=True)
    expected_edition = "Enterprise" if edition == "enterprise" else "Community"

    components_need_check_version = ["tidb", "pd", "tikv", "tiflash"]
    if version >= "v6.6.0":
        components_need_check_version.append("tidb-dashboard")
    for component in components_need_check_version:
        check_tiup_component_version(component, version, component_hash[component], expected_edition)
    for component in ["ctl", "grafana", "prometheus"]:
        check_tiup_component_exists(component, version)

    # set tiup mirror to toolkit offline package dir
    subprocess.run(["tiup", "mirror", "set", f"tidb-{edition}-toolkit-{version}-linux-{arch}"], check=True)

    # clean all tiup components before toolkit check
    subprocess.run(["tiup", "uninstall", "--all"], check=True)
    # those components will be checked:
    # [ br & cdc & dmctl & dm-master & dm-worker & drainer & dumpling & grafana & pd-recover & prometheus & pump & tidb-lightning ]
    # Notice: from version v8.4, the repo tidb-binlog is deprecated, so we need to remove it from the components list
    components = ["dumpling", "dm", "br", "ticdc", "lightning", "binlog"]
    if version >= "v8.4.0":
        components.remove("binlog")
    for component in components:
        # TODO: how to handle pd-recover, pd-recover is build from pd repo, but it is in toolkit package tarball
        check_tiup_component_version(component, version, component_hash[component], expected_edition)
    for component in ["grafana", "prometheus"]:
        check_tiup_component_exists(component, version)


def check_tiup_component_exists(component, version):
    tiup_components = COMPONENT_META[component]["tiup_components"]

    for tiup_component in tiup_components:
        print(f"Checking existence for {tiup_component}:{version}")
        try:
            result = subprocess.run(
                ["tiup", "install", f"{tiup_component}:{version}"], capture_output=True, text=True, check=True)
            if result.returncode == 0:
                print(f"Install {tiup_component}:{version} successfully")
                print(f"Check existence for {tiup_component}:{version} successfully")
        except subprocess.CalledProcessError:
            print(f"Failed install {tiup_component}:{version}")
            raise Exception(f"Failed install {tiup_component}:{version}")


def check_tiup_component_version(component, version, commit_hash, edition):
    tiup_components = COMPONENT_META[component]["tiup_components"]
    version_command = COMPONENT_META[component]["version_cmd"]
    expected_version = version
    expected_edition = edition
    expected_commit_hash = commit_hash
    tiup_check_version = version

    for tiup_component in tiup_components:
        print(f"Checking version info for {tiup_component}")

        try:
            result = subprocess.run(
                ["tiup", f"{tiup_component}:{tiup_check_version}", version_command], capture_output=True, text=True,
                check=True)
            # 假设成功执行命令返回非空结果即为有效
            # dmctl and dumpling output version info to stderr, so we need to check both stdout and stderr
            # issue https://github.com/pingcap/tidb/issues/53591
            if result.stdout.strip() or result.stderr.strip():
                version_info = result.stdout.strip() + "\n" + result.stderr.strip()
                print(f"Version info ({tiup_component}):\n{version_info}")
                version_check_passed = check_version(version_info, expected_version, expected_edition,
                                                     expected_commit_hash,
                                                     check_version=COMPONENT_META[component]["version_check"][
                                                         "version"],
                                                     check_edition=COMPONENT_META[component]["version_check"][
                                                         "edition"],
                                                     check_commit_hash=COMPONENT_META[component]["version_check"][
                                                         "git_commit_hash"])
                if not version_check_passed:
                    exit(1)
            else:
                print(f"Version info ({tiup_component}): Empty")
                raise Exception("Version info is empty")
        except subprocess.CalledProcessError:
            print(f"Failed to check version info for {tiup_component}")
            raise Exception("Failed to check version info")


def main(version, check_type, edition, arch, components_url):
    # Get URL configuration
    public_base, internal_base, use_internal = get_base_url()
    url_mode = "internal" if use_internal else "public"
    print(f"Using {url_mode} URLs for package checking")

    if check_type == "quick":
        offline_package_success, _ = check_offline_package(version)
        dm_package_success, _ = check_dm_package(version)
        plugin_package_success, _ = check_plugin_package(version)
        if offline_package_success and dm_package_success and plugin_package_success:
            print("All offline packages url are valid.")
        else:
            print("Some offline packages url are invalid.")
            exit(1)
    elif check_type == "details":
        components_hash = get_components_hash(components_url)
        check_offline_components(version, edition, arch, components_hash)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Check offline package.")
    # quick 检查只检查包的存在性，details 检查包的存在性和组件版本信息
    parser.add_argument("type", choices=['quick', 'details'],
                        help="The type of check to perform. (quick or details), quick check only check the existence of the package, details check the existence of the package and the version information of the components.")
    parser.add_argument("version", type=str, help="The Release Version to check.")
    parser.add_argument("edition", type=str, help="The Edition to check. (community or enterprise)")
    parser.add_argument("arch", type=str, help="The arch to check. (amd64 or arm64)")
    parser.add_argument("--components_url", type=str, help="The URL to fetch the components information.")
    parser.add_argument("--use_internal", action="store_true",
                        help="Force use internal URL regardless of environment variable")
    args = parser.parse_args()

    # Set environment variable if --use_internal flag is provided
    if args.use_internal:
        os.environ["USE_INTERNAL_URL"] = "true"

    main(args.version, args.type, args.edition, args.arch, args.components_url)
