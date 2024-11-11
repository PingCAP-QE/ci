import requests
import subprocess
import argparse
from check_info import check_version
import requests
from config import COMPONENT_META


def check_url_existence_and_size(url_template, edition, version, arch):
    # 使用传入的参数格式化 URL
    url = url_template.format(edition=edition, version=version, arch=arch)

    # 发起 HEAD 请求
    response = requests.head(url)

    # 检查响应状态码
    if response.status_code == 200:
        # 文件存在
        # 尝试从响应头中获取文件大小
        content_length = response.headers.get('Content-Length')
        print(f"URL: {url}, Valid: True, Size: {content_length}")
        return {
            'exists': True,
            'size': int(content_length) if content_length is not None else 'Unknown'
        }
    else:
        # 文件不存在或无法访问
        print(f"URL: {url}, Valid: False")
        return {
            'exists': False,
            'size': 0
        }


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

def get_file_size_v2(url):
    """获取文件大小，以字节为单位返回。"""
    response = requests.head(url)
    if response.status_code != 200:
        # 如果 HEAD 请求失败，尝试 GET 请求但不下载内容
        response = requests.get(url, stream=True)
    if response.status_code == 200:
        content_length = response.headers.get('Content-Length')
        if content_length:
            return int(content_length), True
    return 0, False

def compare_and_display_package_sizes(url1, url2):
    """比较两个包的大小，并显示它们的大小以及体积差异（以 MB 显示）。"""
    size1, exists1 = get_file_size_v2(url1)
    size2, exists2 = get_file_size_v2(url2)

    if not exists1 or not exists2:
        return "One or both URLs do not exist."

    # 将大小从字节转换为 MB，并计算差异
    size1_mb = size1 / (1024 * 1024)
    size2_mb = size2 / (1024 * 1024)
    difference_mb = abs(size1_mb - size2_mb)

    max_diff_mb = 30
    result = {
        'size1_mb': size1_mb,
        'size2_mb': size2_mb,
        'difference_mb': difference_mb,
        'within_tolerance': difference_mb <= max_diff_mb
    }
    return result


def check_offline_package(version):
    results = []
    success = True  # 初始假设所有检查都成功

    editions = ["community", "enterprise"]
    arches = ["amd64", "arm64"]
    package_types = ["server", "toolkit"]

    for edition in editions:
        for arch in arches:
            for package_type in package_types:
                public_url = f"https://download.pingcap.org/tidb-{edition}-{package_type}-{version}-linux-{arch}.tar.gz"
                internal_url = f"http://fileserver.pingcap.net/download/release/tidb-{edition}-{package_type}-{version}-linux-{arch}.tar.gz"

                print(public_url)
                print(internal_url)
                are_sizes_equal, size = compare_package_sizes(public_url, internal_url)
                if are_sizes_equal:
                    results.append(f"{edition} {package_type} {version} {arch}: PASS, size: {size}")
                else:
                    success = False
                    results.append(f"{edition} {package_type} {version} {arch}: FAIL or file(s) not found")

    for result in results:
        print(result)

    return success, results


def check_plugin_package(version):
    results = []
    success = True

    arches = ["amd64", "arm64"]
    for arch in arches:
        public_url = f"https://download.pingcap.org/enterprise-plugin-{version}-linux-{arch}.tar.gz"
        internal_url = f"http://fileserver.pingcap.net/download/release/enterprise-plugin-{version}-linux-{arch}.tar.gz"

        print(public_url)
        print(internal_url)
        are_size_equal, size = compare_package_sizes(public_url, internal_url)
        if are_size_equal:
            results.append(f"plugin {version} {arch}: PASS, size: {size}")
        else:
            success = False
            results.append(f"plugin {version} {arch}: FAIL or file(s) not found")

    for result in results:
        print(result)

    return success, results


def check_dm_package(version):
    results = []
    success = True  # 初始假设所有检查都成功

    arches = ["amd64", "arm64"]
    for arch in arches:
        public_url = f"https://download.pingcap.org/tidb-dm-{version}-linux-{arch}.tar.gz"
        internal_url = f"http://fileserver.pingcap.net/download/release/tidb-dm-{version}-linux-{arch}.tar.gz"

        print(public_url)
        print(internal_url)
        are_size_equal, size = compare_package_sizes(public_url, internal_url)
        if are_size_equal:
            results.append(f"dm {version} {arch}: PASS, size: {size}")
        else:
            success = False
            results.append(f"dm {version} {arch}: FAIL or file(s) not found")

    for result in results:
        print(result)

    return success, results


def check_offline_components(version, edition, arch, component_hash):
    server_package_url = f"https://download.pingcap.org/tidb-{edition}-server-{version}-linux-{arch}.tar.gz"
    server_package_internal_url = f"http://fileserver.pingcap.net/download/release/tidb-{edition}-server-{version}-linux-{arch}.tar.gz"

    # toolkit package url
    toolkit_package_url = f"https://download.pingcap.org/tidb-{edition}-toolkit-{version}-linux-{arch}.tar.gz"
    toolkit_package_internal_url = f"http://fileserver.pingcap.net/download/release/tidb-{edition}-toolkit-{version}-linux-{arch}.tar.gz"

    # download package from internal url
    subprocess.run(["wget",  server_package_url], check=True)
    subprocess.run(["wget",  toolkit_package_url], check=True)

    # extract package
    subprocess.run(["tar", "xf", f"tidb-{edition}-server-{version}-linux-{arch}.tar.gz"], check=True)
    subprocess.run(["tar", "xf", f"tidb-{edition}-toolkit-{version}-linux-{arch}.tar.gz"], check=True)

    # set tiup mirror to server offline package dir
    subprocess.run(["tiup", "mirror", "set", f"tidb-{edition}-server-{version}-linux-{arch}"], check=True)
    subprocess.run(["tiup", "uninstall", "--all"], check=True)
    expected_edition = "Enterprise" if edition == "enterprise" else "Community"

    for component in ["tidb", "pd", "tikv", "tiflash", "tidb-dashboard"]:
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
                ["tiup", f"{tiup_component}:{tiup_check_version}", version_command], capture_output=True, text=True, check=True)
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
    parser.add_argument("type", choices=['quick', 'details'], help="The type of check to perform. (quick or details), quick check only check the existence of the package, details check the existence of the package and the version information of the components.")
    parser.add_argument("version", type=str, help="The Release Version to check.")
    parser.add_argument("edition", type=str, help="The Edition to check. (community or enterprise)")
    parser.add_argument("arch", type=str, help="The arch to check. (amd64 or arm64)")
    parser.add_argument("--components_url", type=str, help="The URL to fetch the components information.")
    args = parser.parse_args()
    main(args.version, args.type, args.edition, args.arch, args.components_url)
