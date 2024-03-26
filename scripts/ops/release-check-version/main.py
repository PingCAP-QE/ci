import argparse
import json
import os
import subprocess
import requests


# 定义命令行参数解析
parser = argparse.ArgumentParser(description="Check Docker images and TiUP packages.")
parser.add_argument("type", choices=['image', 'tiup'], help="Specify the type of component to check: 'image' for Docker images, 'tiup' for TiUP packages.")
parser.add_argument("--components_url", type=str, help="The URL to fetch the components information.")

# 解析命令行输入
args = parser.parse_args()

# # 定义要检查的Docker镜像列表
# docker_images = [
#     ("binlog", "v7.5.0", "Community", "4a2ed99c466e02d0f441b742d7e62a1c67150f52", "registry.hub.docker.com", "pingcap"),
#     ("pd", "v7.5.0", "Community", "ef6ba8551e525a700546d6bdb7ad6766115209cc", "registry.hub.docker.com", "pingcap"),
#     # 添加更多镜像，格式为(name, version, edition, commit_hash, registry, project)
# ]
#
# # 定义要检查的TiUP包列表
# tiup_packages = [
#     ("pd", "v7.5.0", "4a2ed99c466e02d0f441b742d7e62a1c67150f52"),
#     # 添加更多包，格式为(name, version, commit_hash)
# ]


def load_components():
    with open('components.json', 'r') as file:
        return json.load(file)


def load_components_from_url(url):
    try:
        response = requests.get(url)
        response.raise_for_status()
        json_data = response.json()

        print(json_data)
        return json_data
    except requests.exceptions.HTTPError as e:
        print(f"HTTP错误：{e.response.status_code}")
        exit(1)
    except requests.exceptions.RequestException as e:
        print(f"请求错误：{e}")
        exit(1)


def check_docker_image(component, edition, registry, project, is_rc_build=False):
    args = [
        "python3", "check_docker_images.py",
        component["name"],
        component["version"],
        edition,
        "--commit_hash", component["commit_hash"],
        "--registry", registry,
        "--project", project
    ]
    if is_rc_build:
        args.append("--is_rc_build")
    subprocess.run(args, check=True)


def check_tiup_package(component, is_rc_build=False):
    args = [
        "python3", "check_tiup.py",
        component["name"],
        component["version"],
        "--commit_hash", component["commit_hash"]
    ]
    if is_rc_build:
        args.append("--is_tiup_staging")
    subprocess.run(args, check=True)


if __name__ == "__main__":
    components = load_components_from_url(args.components_url)
    print(components)

    is_rc_build = os.environ.get("IS_RC_BUILD", "false") == "true"
    print(f"Checking components for {'RC' if is_rc_build else 'GA'} build.")
    if args.type == 'image':
        if is_rc_build:
            # registry = "hub.pingcap.net"
            # project = "qa"
            for image in components["docker_images"]:
                check_docker_image(image, "enterprise", "hub.pingcap.net", "qa", is_rc_build)
                check_docker_image(image, "community", "hub.pingcap.net", "qa", is_rc_build)
        else:
            for image in components["docker_images"]:
                check_docker_image(image, "enterprise", "registry.hub.docker.com", "pingcap")
                check_docker_image(image, "community", "registry.hub.docker.com", "pingcap")
    elif args.type == 'tiup':
        for package in components["tiup_packages"]:
            check_tiup_package(package, is_rc_build)
