import jenkins
import os
import datetime
import re
import csv
import json

# Jenkins server configuration
JENKINS_URL = ''
JENKINS_USERNAME = ''
JENKINS_PASSWORD = ''
JOB_NAME = 'pingcap/tidb/ghpr_build'
OUTPUT_DIR = 'failed_builds_output'  # save the console output of failed builds
CSV_FILE = 'failed_builds_summary.csv'  # save the summary of failed builds

ERROR_PATTERNS = [
    (r'compilepkg: nogo: errors found by nogo during build-time code analysis', 'lint check failed'),
    (r'compilepkg: error running subcommand', 'compile failed with syntax issue'),
]


def save_console_output(job_name, build_number, console_output):
    # ensure the output directory exists
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # handle illegal characters in the filename
    safe_job_name = job_name.replace('/', '_')
    filename = f"{safe_job_name}-{build_number}.log"

    filepath = os.path.join(OUTPUT_DIR, filename)
    with open(filepath, 'w') as file:
        file.write(console_output)

    print(f"Saved the console output of {job_name} build #{build_number} to {filepath}")


def clean_filename(filename):
    """Clean the filename to ensure it is friendly to the file system."""
    return re.sub(r'[\\/*?:"<>|]', '_', filename)


def analyze_console_output(console_output):
    """Analyze the console output to identify specific types of errors."""
    errors_detected = []
    for pattern, error_type in ERROR_PATTERNS:
        if re.search(pattern, console_output, re.MULTILINE):
            errors_detected.append(error_type)
    return errors_detected


def get_all_builds(server, job_name):
    """
    get all builds of the job, handle Jenkins pagination
    """
    all_builds = []
    next_build_number = server.get_job_info(job_name)['nextBuildNumber']

    while True:
        builds_info = server.get_job_info(job_name, depth=1, fetch_all_builds=True)
        builds = builds_info['builds']
        if not builds:
            break
        all_builds.extend(builds)
        # 检查是否已获取所有构建
        if builds[-1]['number'] == 1 or len(builds) < next_build_number:
            break
        next_build_number = builds[-1]['number']
    return all_builds


def analyze_builds(server, job_name):
    # get the timestamp of one week ago
    weeks_ago = datetime.datetime.now() - datetime.timedelta(weeks=3)

    # get all builds of the job
    builds = get_all_builds(server, job_name)
    print(f"Found {len(builds)} builds for {job_name}")
    failed_builds = []

    for build in builds:
        build_info = server.get_build_info(job_name, build['number'])
        build_time = datetime.datetime.fromtimestamp(build_info['timestamp'] / 1000)

        # only analyze builds within the last week
        if build_time < weeks_ago:
            break

        time_str = build_time.strftime('%Y-%m-%d %H:%M:%S')

        # only analyze failed builds
        if build_info['result'] == 'FAILURE':
            # get the parameters of the build

            actions = build_info.get('actions', [])
            parameters = next((action['parameters'] for action in actions if 'parameters' in action), None)
            if parameters:
                job_spec_params = next((param['value'] for param in parameters if param['name'] == 'JOB_SPEC'), None)
                job_spec_params = json.loads(job_spec_params)

                job_type = job_spec_params["type"]
                pull_url = job_spec_params["refs"]["pulls"][0]["link"]
                pull_author = job_spec_params["refs"]["pulls"][0]["author"]
                pull_commit_url = job_spec_params["refs"]["pulls"][0]["commit_link"]
            else:
                continue

            # get the console output of the build
            console_output = server.get_build_console_output(job_name, build['number'])
            errors_detected = analyze_console_output(console_output)
            if errors_detected:
                print(time_str, f"{job_name}: {build['number']}", build_info['result'], pull_url)
                failed_builds.append({
                    'job_name': job_name,
                    'build_url': build_info['url'],
                    'build_time': time_str,
                    "pull_url": pull_url,
                    "pull_author": pull_author,
                    "pull_commit_url": pull_commit_url,
                    'errors_detected': ", ".join(errors_detected)
                })

                save_console_output(job_name, build['number'], console_output)

    if failed_builds:
        with open(CSV_FILE, 'w', newline='', encoding='utf-8') as file:
            fieldnames = ['job_name', 'build_url', 'build_time', 'pull_url', 'pull_author', 'pull_commit_url', 'errors_detected']
            writer = csv.DictWriter(file, fieldnames=fieldnames)
            writer.writeheader()
            for build in failed_builds:
                writer.writerow(build)

        print(f"saved {len(failed_builds)} failed builds to {CSV_FILE}")


def main():
    server = jenkins.Jenkins(JENKINS_URL, username=JENKINS_USERNAME, password=JENKINS_PASSWORD)

    analyze_builds(server, JOB_NAME)


if __name__ == '__main__':
    main()
