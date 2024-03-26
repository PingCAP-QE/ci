
import subprocess
import argparse
from check_info import check_version
import requests

from config import COMPONENT_META, TIUP_PACKAGE_DEFAULT_EDITION, TIUP_MIRRORS


def is_url_valid(url):
    try:
        response = requests.head(url, timeout=5)  # 使用HEAD请求，比GET更快速
        if response.status_code == 200:
            return True
        else:
            return False
    except requests.RequestException as e:  # 捕捉请求异常，如连接错误等
        print(f"请求错误: {e}")
        return False


def install_tiup():
    # TODO: check if tiup is already installed
    try:
        subprocess.run(["curl", "--proto", "=https", "--tlsv1.2", "-sSf", "https://tiup-mirrors.pingcap.com/install.sh", "|", "sh"], check=True)
    except subprocess.CalledProcessError:
        print("Failed to install TiUP.")
        exit(1)


def set_tiup_mirror(mirror):
    try:
        subprocess.run(["tiup", "mirror", "set", mirror], check=True)
    except subprocess.CalledProcessError:
        print(f"Failed to set TiUP mirror to {mirror}.")
        exit(1)


def uninstall_component(component, version):
    try:
        subprocess.run(["tiup", "uninstall", f"{component}:{version}"], check=True, text=True, capture_output=True)
    except subprocess.CalledProcessError as e:
        error_message = e.stderr.strip()
        print(f"Failed to uninstall component {component}:{version}. Error: {error_message}")


def check_tiup_component_exists(component, version, is_tiup_staging):
    check_result = True
    check_detail = []
    tiup_mirror = "https://tiup-mirrors.pingcap.com"
    if is_tiup_staging:
        tiup_mirror = "http://tiup.pingcap.net:8988"

    for tiup_component in COMPONENT_META[component]["tiup_components"]:
        single_component_check_detail = {
            "component": tiup_component,
            "version": version,
            "exist_check": {
                "linux-amd64": False,
                "darwin-amd64": False,
                "linux-arm64": False,
                "darwin-arm64": False
            }
        }
        # Check if the component exists for each OS architecture
        # url example:
        # https://tiup-mirrors.pingcap.com/tidb-v6.1.0-linux-amd64.tar.gz
        # https://tiup-mirrors.pingcap.com/tidb-v6.1.0-darwin-amd64.tar.gz
        # https://tiup-mirrors.pingcap.com/tidb-v6.1.0-linux-arm64.tar.gz
        # https://tiup-mirrors.pingcap.com/tidb-v6.1.0-darwin-arm64.tar.gz
        for os_arch in ["linux-amd64", "darwin-amd64", "linux-arm64", "darwin-arm64"]:
            download_url = f"{tiup_mirror}/{tiup_component}-{version}-{os_arch}.tar.gz"
            if is_url_valid(download_url):
                single_component_check_detail["exist_check"][os_arch] = True
            else:
                check_result = False
                single_component_check_detail["exist_check"][os_arch] = False
        check_detail.append(single_component_check_detail)

    print(f"Check result for component {component}: {check_result}")
    for detail in check_detail:
        print(f"Check detail for component {detail['component']}: {detail['exist_check']}")

    return check_result


def check_tiup_component_version(component, version, commit_hash, is_tiup_staging):
    tiup_components = COMPONENT_META[component]["tiup_components"]
    version_command = COMPONENT_META[component]["version_cmd"]
    expected_version = version
    expected_edition = TIUP_PACKAGE_DEFAULT_EDITION  # all tiup components are community edition
    expected_commit_hash = commit_hash
    tiup_mirror = "https://tiup-mirrors.pingcap.com"
    if is_tiup_staging:
        tiup_mirror = "http://tiup.pingcap.net:8988"
    set_tiup_mirror(tiup_mirror)
    subprocess.run(["tiup", "mirror", "show"],  capture_output=True, text=True, check=True)

    for tiup_component in tiup_components:
        print(f"Checking version info for {tiup_component}")
        uninstall_component(tiup_component, version)
        try:
            result = subprocess.run(
                ["tiup", f"{tiup_component}:{version}", version_command], capture_output=True, text=True, check=True)
            # 假设成功执行命令返回非空结果即为有效
            if result.stdout.strip():
                print(f"Version info ({tiup_component}):\n{result.stdout.strip()}")
                version_check_passed = check_version(result.stdout.strip(), expected_version, expected_edition,
                                                     expected_commit_hash,
                                                     check_version=COMPONENT_META[component]["version_check"]["version"],
                                                     check_edition=COMPONENT_META[component]["version_check"]["edition"],
                                                     check_commit_hash=COMPONENT_META[component]["version_check"]["git_commit_hash"])
                if not version_check_passed:
                    exit(1)
            else:
                print(f"Version info ({tiup_component}): Empty")
                raise Exception("Version info is empty")
        except subprocess.CalledProcessError:
            print(f"Failed to check version info for {tiup_component}")
            raise Exception("Failed to check version info")


def main(component, version, commit_hash,is_tiup_staging):
    # Check if the tiup component exists
    all_tiup_packages_exist = check_tiup_component_exists(component, version, is_tiup_staging)
    if all_tiup_packages_exist:
        print(f"All tiup packages for component {component} exist.")
    else:
        print(f"Component {component} does not exist.")
        exit(1)

    # Check if the tiup component version matches the expected version
    check_tiup_component_version(component, version, commit_hash, is_tiup_staging)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Check Docker Image Version")
    parser.add_argument("component", type=str, help="tiup component name(e.g., tidb)")
    parser.add_argument("version", type=str, help="version to check")
    parser.add_argument("--commit_hash", type=str, help="binary commit hash")
    parser.add_argument("--is_tiup_staging", action="store_true", help="Flag to indicate if it's a TiUP staging check")
    args = parser.parse_args()
    main(args.component, args.version, args.commit_hash, args.is_tiup_staging)
