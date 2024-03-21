import requests
import json
import csv

from datetime import datetime


today = datetime.now().strftime("%Y-%m-%d")

# Use the date in the summary and stats CSV filename
summary_csv_filename = f'prow_job_summary_stats_{today}.csv'
stats_csv_filename = f'prow_job_stats_{today}.csv'


def main():
    url = "https://prow.tidb.net/prowjobs.js?var=allBuilds&omit=annotations,labels,decoration_config,pod_spec"
    response = requests.get(url)
    text = response.text
    # Remove the first string 'var allBuilds = ' and also remove the last string ';' to get valid json string
    list_json = text.replace("var allBuilds = ", "").rstrip(';')
    data = json.loads(list_json)
    runs = data['items']

    grouped_runs = {}
    for run in runs:
        if 'refs' not in run['spec']:
            continue
        key = f"{run['spec']['refs']['org']}/{run['spec']['refs']['repo']}/{run['spec']['refs']['base_ref']}|{run['spec']['job']}"
        if key not in grouped_runs:
            grouped_runs[key] = []
        grouped_runs[key].append(run)

    # Collecting stats
    stats = []
    for runs in grouped_runs.values():
        full_repo = f"{runs[0]['spec']['refs']['org']}/{runs[0]['spec']['refs']['repo']}"
        branch = runs[0]['spec']['refs']['base_ref']
        job_type = runs[0]['spec']['type']
        job_name = runs[0]['spec']['job']
        grouped_state = {"success": [], "failure": []}
        for run in runs:
            state = run['status']['state']
            if state not in ["success", "failure"]:
                continue
            grouped_state[state].append(run)

        success_runs = grouped_state["success"]
        failed_runs = grouped_state["failure"]
        success_rate = len(success_runs) / (len(success_runs) + len(failed_runs)) if success_runs else 0
        total_time_costs = sum((datetime.strptime(run['status']['completionTime'],
                                                  "%Y-%m-%dT%H:%M:%SZ") - datetime.strptime(
            run['status']['startTime'], "%Y-%m-%dT%H:%M:%SZ")).total_seconds() for run in success_runs)
        avg_time_cost_minutes = total_time_costs / 60 / len(success_runs) if success_runs else 0

        stats.append(
            [full_repo, branch, job_type, job_name, len(success_runs), len(failed_runs), round(success_rate, 2),
             int(avg_time_cost_minutes)])

    # Sorting stats by repo, job_type, branch
    sorted_stats = sorted(stats, key=lambda x: (x[0], x[2], x[1]))

    # Writing to CSV
    cols = ["repo", "branch", "job_type", "job_name", "success_count", "failure_count", "success_rate",
            "avg_timecost_minutes"]
    with open(stats_csv_filename, 'w', newline='') as csvfile:
        csvwriter = csv.writer(csvfile)
        csvwriter.writerow(cols)
        for row in sorted_stats:
            csvwriter.writerow(row)
    print("The statistics have been written to '{}'".format(stats_csv_filename))

    # Calculate summary stats for each repo and job_type
    summary_stats = {}
    for stat in stats:
        repo, branch, job_type, job_name, success_count, failure_count, success_rate, _ = stat
        key = (repo, job_type)
        if key not in summary_stats:
            summary_stats[key] = {'success_count': 0, 'failure_count': 0, 'total_runs': 0}
        summary_stats[key]['success_count'] += success_count
        summary_stats[key]['failure_count'] += failure_count
        summary_stats[key]['total_runs'] += success_count + failure_count

    # Calculating success rates and preparing summary data for CSV
    summary_data = []
    for (repo, job_type), counts in summary_stats.items():
        success_rate = counts['success_count'] / (counts['success_count'] +
                                                  counts['failure_count']) if (counts['success_count'] + counts['failure_count']) > 0 else 0
        summary_data.append([repo, job_type, counts['success_count'], counts['failure_count'], round(success_rate, 2)])

    # Sorting summary data by repo
    sorted_summary_data = sorted(summary_data, key=lambda x: x[0])

    # Writing summary statistics to CSV
    summary_cols = ["repo", "job_type", "total_success_count", "total_failure_count", "success_rate"]
    with open(summary_csv_filename, 'w', newline='') as summary_csvfile:
        csvwriter = csv.writer(summary_csvfile)
        csvwriter.writerow(summary_cols)
        for row in sorted_summary_data:
            csvwriter.writerow(row)

    print("Summary statistics have been written to '{}'".format(summary_csv_filename))


if __name__ == "__main__":
    main()
