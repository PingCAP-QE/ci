import argparse
import json
import os
import subprocess
import requests
from config import COMPONENT_META


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


def check_docker_image(component_info, edition, registry, project, is_rc_build=False):
    component = component_info["name"]
    version = component_info["version"]

    if not COMPONENT_META[component]["image_name"]:
        print(f"Image for component {component} is not defined.")
        return True, None

    if not COMPONENT_META[component]["image_edition"][edition] and registry == "registry.hub.docker.com":
        print(f"Image for component {component} does not have {edition} edition.")
        return True, None

    # Generate the full image name
    image_name = COMPONENT_META[component]["image_name"]
    if edition == "enterprise":
        image_name = f"{image_name}-enterprise"
    full_image = f"{registry}/{project}/{image_name}:{version}"

    args = [
        "python3", "check_docker_images.py",
        component_info["name"],
        component_info["version"],
        edition,
        "--commit_hash", component_info["commit_hash"],
        "--registry", registry,
        "--project", project
    ]
    if is_rc_build:
        args.append("--is_rc_build")

    try:
        subprocess.run(args, check=True)
        return True, None
    except subprocess.CalledProcessError:
        print(f"Failed to check image: {full_image}")
        return False, full_image


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
    # define the command line argument parser
    parser = argparse.ArgumentParser(description="Check Docker images and TiUP packages.")
    parser.add_argument("type", choices=['image', 'tiup'],
                        help="Specify the type of component to check: 'image' for Docker images, 'tiup' for TiUP packages.")
    parser.add_argument("--components_url", type=str, help="The URL to fetch the components information.")
    args = parser.parse_args()

    components = load_components_from_url(args.components_url)
    print(components)

    is_rc_build = os.environ.get("IS_RC_BUILD", "false") == "true"
    print(f"Checking components for {'RC' if is_rc_build else 'GA'} build.")
    if args.type == 'image':
        all_results = []
        failed_images = []
        if is_rc_build:
            for image in components["docker_images"]:
                success, failure = check_docker_image(image, "enterprise", "hub.pingcap.net", "qa", is_rc_build)
                all_results.append(success)
                if not success:
                    failed_images.append(failure)
                success, failure = check_docker_image(image, "community", "hub.pingcap.net", "qa", is_rc_build)
                all_results.append(success)
                if not success:
                    failed_images.append(failure)
        else:
            print("checking docker images on gcr.io")
            for image in components["docker_images"]:
                success, failure = check_docker_image(image, "enterprise", "gcr.io", "pingcap-public/dbaas")
                all_results.append(success)
                if not success:
                    failed_images.append(failure)

            print("checking docker images on uhub.service.ucloud.cn")
            for image in components["docker_images"]:
                success, failure = check_docker_image(image, "community", "uhub.service.ucloud.cn", "pingcap")
                all_results.append(success)
                if not success:
                    failed_images.append(failure)

            print("checking docker images on registry.hub.docker.com")
            for image in components["docker_images"]:
                success, failure = check_docker_image(image, "enterprise", "registry.hub.docker.com", "pingcap")
                all_results.append(success)
                if not success:
                    failed_images.append(failure)
                success, failure = check_docker_image(image, "community", "registry.hub.docker.com", "pingcap")
                all_results.append(success)
                if not success:
                    failed_images.append(failure)

        failed_count = all_results.count(False)
        total_count = len(all_results)
        print(f"\nDocker image check summary:")
        print(f"Total checks: {total_count}")
        print(f"Successful: {total_count - failed_count}")
        print(f"Failed: {failed_count}")

        if failed_count > 0:
            print("\nFailed images:")
            for failed_image in failed_images:
                print(f"- {failed_image}")
            print("\nSome docker image checks failed!")
            exit(1)

    elif args.type == 'tiup':
        for package in components["tiup_packages"]:
            check_tiup_package(package, is_rc_build)
