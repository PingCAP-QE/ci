import os
import requests
import re
import json


GITHUB_API = "https://api.github.com"
TOKEN = os.environ.get("GITHUB_API_TOKEN")
HEADERS = {
    'Accept': 'application/vnd.github.v3+json',
    'Authorization': f'token {TOKEN}',
}
NOTIFY_URL = os.environ.get("WEBHOOK_URL")

base_sha = os.environ.get('base_sha')
base_ref = os.environ.get("base_ref")
tidb_repo = "pingcap/tidb"
tidb_test_repo = "PingCAP-QE/tidb-test"


def get_commit_msg(repo, sha):
    url = f'{GITHUB_API}/repos/{repo}/commits/{sha}'
    response = requests.get(url, headers=HEADERS)
    commit_info = response.json()
    return commit_info['commit']['message']


def parse_pr_num_from_commit_message(commit_message):
    pattern = re.compile(r'\(#(\d+)\)')

    match = pattern.findall(commit_message)
    pr_number = ""
    if match:
        pr_number = match[-1]  # 提取最后一个匹配的PR number
    return pr_number


def extract_dependent_pr_number(pr_title):
    match = re.search(r'\|\s*tidb-test=pr/(\d+)', pr_title)
    pr_number = -1
    if match:
        pr_number = match.group(1)
        print("tidb-test pr {}".format(pr_number))
        print("tidb origin commit message: {}".format(pr_title))
    else:
        # no tidb-test PR specified, exit
        print("no tidb-test PR specified")
    return pr_number


def get_pr_info(repo, pr_number):
    url = f'{GITHUB_API}/repos/{repo}/pulls/{pr_number}'
    response = requests.get(url, headers=HEADERS)
    pr_info = response.json()

    return pr_info


def comment_pr(repo, pr_number, msg):
    data = {'body': msg}
    url = f'{GITHUB_API}/repos/{repo}/issues/{pr_number}/comments'
    response = requests.post(url, headers=HEADERS, json=data)
    return response.status_code == 201


def merge_pr(repo, pr_number):
    data = {'merge_method': 'squash'}
    url = f'{GITHUB_API}/repos/{repo}/pulls/{pr_number}/merge'
    response = requests.put(url, headers=HEADERS, json=data)
    return response.status_code == 200


def send_alert(message):
    card_content = {
      "config": {
        "wide_screen_mode": True
      },
      "elements": [
        {
          "tag": "div",
          "text": {
            "content": message,
            "tag": "lark_md"
          }
        }
      ],
      "header": {
        "template": "yellow",
        "title": {
          "content": "tidb-test PR 合并通知",
          "tag": "plain_text"
        }
      }
    }

    data = {
        "msg_type": "interactive",
        "card": json.dumps(card_content)
    }

    response = requests.post(
        NOTIFY_URL,
        headers={'Content-Type': 'application/json'},
        data=json.dumps(data))

    print(response.content)


def send_success_notify(message):
    print(message)
    card_content = {
      "config": {
        "wide_screen_mode": True
      },
      "elements": [
        {
          "tag": "div",
          "text": {
            "content": message,
            "tag": "lark_md"
          }
        }
      ],
      "header": {
        "template": "turquoise",
        "title": {
          "content": "tidb-test PR 合并通知",
          "tag": "plain_text"
        }
      }
    }

    data = {
        "msg_type": "interactive",
        "card": json.dumps(card_content)
    }

    response = requests.post(
        NOTIFY_URL,
        headers={'Content-Type': 'application/json'},
        data=json.dumps(data))

    print(response.content)


def main():
    commit_msg = get_commit_msg(tidb_repo, base_sha)
    tidb_pr_number = parse_pr_num_from_commit_message(commit_msg)
    tidb_pr_info = get_pr_info(tidb_repo, tidb_pr_number)

    tidb_test_pr_number = extract_dependent_pr_number(tidb_pr_info["title"])
    if tidb_test_pr_number == -1:
        print("no tidb-test PR specified")
        exit(0)
        
    tidb_test_pr_info = get_pr_info(tidb_test_repo, tidb_test_pr_number)
    if tidb_test_pr_info['base']['ref'] != base_ref:
        # require tidb pr and tidb-test pr owns the same base branch
        send_alert(f"base branch of tidb-test PR [#{tidb_test_pr_number}](https://github.com/{tidb_test_repo}/pull/{tidb_test_pr_number}) is **{tidb_test_pr_info['base']['ref']}**, but expected **{base_ref}**\n\n"
                   f"tidb PR: **{tidb_pr_info['title']}**[#{tidb_pr_number}](https://github.com/{tidb_repo}/pull/{tidb_pr_number})\n")
        exit(1)

    if tidb_test_pr_info['state'] != 'open':
        send_alert(f"tidb-test PR [#{tidb_test_pr_number}](https://github.com/{tidb_test_repo}/pull/{tidb_test_pr_number}) is not open, current state is {tidb_test_pr_info['state']}\n\n"
                   f"tidb PR: **{tidb_pr_info['title']}**[#{tidb_pr_number}](https://github.com/{tidb_repo}/pull/{tidb_pr_number})\n"
                   f"target branch: **{base_ref}**")
        exit(1)

    comment = f'The dependent TiDB PR https://github.com/{tidb_repo}/pull/{tidb_pr_number} has been merged. The bot is merging this PR.'
    if comment_pr(tidb_test_repo, tidb_test_pr_number, comment) and merge_pr(tidb_test_repo, tidb_test_pr_number):
        success_message = (f"tidb-test PR 自动合并成功\n\n\ntidb-test PR [#{tidb_test_pr_number}](https://github.com/{tidb_test_repo}/pull/{tidb_test_pr_number})\n"
                           f"tidb PR: **{tidb_pr_info['title']}**[#{tidb_pr_number}](https://github.com/{tidb_repo}/pull/{tidb_pr_number})\n"
                           f"target branch: **{base_ref}**")
        send_success_notify(success_message)
    else:
        send_alert(f"Failed to comment and merge tidb PR [#{tidb_test_pr_number}](https://github.com/{tidb_test_repo}/pull/{tidb_test_pr_number})")


if __name__ == '__main__':
    main()
