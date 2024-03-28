import subprocess
import argparse

from check_info import check_version
from config import COMPONENT_META as image_info


def check_image_exists(image):
    try:
        subprocess.run(["docker", "pull", image], check=True)
        print(f"Image {image} exists.")
        return True
    except subprocess.CalledProcessError:
        print(f"Image {image} does not exist.")
        return False


def get_image_version_info(full_image_name, component, version, edition, git_commit_hash):
    entrypoints = image_info[component]["entrypoints"]
    version_command = image_info[component]["version_cmd"]
    expected_version = version
    expected_edition = edition
    expected_commit_hash = git_commit_hash
    for entrypoint in entrypoints:
        print(f"Checking version info for {entrypoint}")
        try:
            result = subprocess.run(
                ["docker", "run", "--entrypoint", entrypoint, full_image_name, version_command],
                capture_output=True, text=True, check=True)
            # 假设成功执行命令返回非空结果即为有效
            # dmctl and dumpling output version info to stderr, so we need to check both stdout and stderr
            if result.stdout.strip() or result.stderr.strip():
                version_info = result.stdout.strip() if result.stdout.strip() else result.stderr.strip()
                print(f"Version info ({entrypoint}): {version_info}")
                version_check_passed = check_version(version_info, expected_version, expected_edition,
                                                     expected_commit_hash,
                                                     check_version=image_info[component]["version_check"]["version"],
                                                     check_edition=image_info[component]["version_check"]["edition"],
                                                     check_commit_hash=image_info[component]["version_check"][
                                                         "git_commit_hash"])
                if not version_check_passed:
                    exit(1)
            else:
                print(f"Version info ({entrypoint}): Empty")
                raise Exception("Version info is empty")
        except subprocess.CalledProcessError:
            print(f"Failed to check version info for {entrypoint}")
            raise Exception("Failed to check version info")


def get_image_name(component, version, registry, project, edition, is_rc_build):
    image = image_info[component]["image_name"]
    if is_rc_build:
        version = f"{version}-pre"
    # notice: only push to gccr.io for enterprise edition
    # example: gcr.io/pingcap-public/dbaas/br:v7.1.4 this is enterprise edition
    if edition.lower() == "enterprise" and registry != "gcr.io":
        return f"{registry}/{project}/{image}-enterprise:{version}"

    return f"{registry}/{project}/{image}:{version}"


def main(component, version, edition, registry, project, commit_hash, is_rc_build):
    image = get_image_name(component, version, registry, project, edition, is_rc_build)
    print(f"Checking image: {image}")
    image_exist = check_image_exists(image)
    if not image_exist:
        exit(1)
    get_image_version_info(image, component, version, edition, commit_hash)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Check Docker Image Version")
    parser.add_argument("component", type=str, help="Docker image name(e.g., tiflash)")
    parser.add_argument("version", type=str, help="version to check")
    parser.add_argument("edition", type=str, help="community or enterprise")
    parser.add_argument("--registry", type=str, help="docker registry(e.g., registry.hub.docker.com)")
    parser.add_argument("--project", type=str, help="registry project")
    parser.add_argument("--commit_hash", type=str, help="binary commit hash")
    parser.add_argument("--is_rc_build", action="store_true", help="Flag to indicate if it's an RC build check")

    args = parser.parse_args()
    main(args.component, args.version, args.edition, args.registry, args.project, args.commit_hash, args.is_rc_build)
