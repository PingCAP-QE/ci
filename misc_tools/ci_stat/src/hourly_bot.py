import sys
import os
from ci_stat import Result, env
import json
import hmac
import hashlib
import base64
import requests

def get_sign(key:str, ts: int):
    key = "lrMWueGz4s96HofTd3Pj7b"
    ts = int(datetime.now().timestamp())
    ts = 1621941942
    string_to_sign = str(ts) + '\n' + key
    return base64.b64encode(hashlib.sha256(string_to_sign.encode('utf-8')).digest())

def add_content(field, content):
    field["text"]["content"] += content

def send(res: Result):
    card = json.load(open(os.getenv("STAT_ENV_PATH") + "/bot.json", "r", encoding="utf-8"))
    # card = json.load(open("bot.json", encoding="utf-8"))

    fields = card["elements"][0]["fields"]

    add_content(fields[0], res.begin_time.strftime('%Y-%m-%d-%H:%M:%S') + " to " + res.end_time.strftime('%Y-%m-%d-%H:%M:%S'))
    add_content(fields[1], str(len(res.pr_map)))
    add_content(fields[2], str(len(res.job_map)))
    add_content(fields[3], str(len(res.run_list)))
    add_content(fields[4], str(res.fail_cnt))
    failed_prs = list(filter(lambda x: x[1].fail_cnt > 0, sorted(res.pr_map.items(), key=lambda x: x[1].fail_cnt, reverse=True)))
    for _, pr in failed_prs[:4]:
        add_content(fields[6], "  \n**" + pr.repo + " " + pr.branch +"**")
        add_content(fields[6], " **[#" + str(pr.number)+"](" + pr.link +  ") **")
        add_content(fields[6],  " ")
        add_content(fields[6],  "**🟢:** " +  str(pr.success_cnt) + " ")
        add_content(fields[6],  "**🔴:** " +  str(pr.fail_cnt) + " ")
        add_content(fields[6],  "**🟡:** " +  str(pr.abort_cnt) + " ")      
        add_content(fields[6],  "**sum**: " +  str((pr.success_cnt + pr.fail_cnt + pr.abort_cnt)) + " ")
        failed_runs = list(filter(lambda x: x.status == "FAILURE", pr.runs))
        for run in failed_runs[:3]:
            add_content(fields[6], "\n        [" + str(run.job_id) + "](" + run.link + ") ...**" + "_".join(run.job_name.split('_')[-2:]) + "**: ")
            fail_infos = run.get_fail_info()
            if len(fail_infos) == 0:
                add_content(fields[6], "Msg Not Found [View Log](" + run.link + ")")
            elif len(fail_infos) == 1:
                add_content(fields[6], fail_infos[0])
            else:
                for info in fail_infos:
                    add_content(fields[6], "\n             " + info)
        if len(failed_runs) > 3:
            add_content(fields[6], "\n         ... **" + str(len(failed_runs) - 3) + "** *more failed runs.*")
    if len(failed_prs) > 4:
        add_content(fields[6], "\n**" + str(len(failed_prs) - 4) + "** *more pr(s) contain failed runs.*")


    failed_jobs = list(filter(lambda x: x[1].fail_cnt > 0, sorted(res.job_map.items(), key=lambda x: x[1].fail_cnt, reverse=True)))
    for _, job in failed_jobs[:4]:
        add_content(fields[8], "\n**" + job.job_name + "** ")
        add_content(fields[8], "**🟢: **"  + str(job.success_cnt) + " ")
        add_content(fields[8], "**🔴: **" + str(job.fail_cnt) + " ")
        add_content(fields[8], "**🟡: **" + str(job.abort_cnt) + " ")
        local_failed_prs = list(filter(lambda x: x[1].fail_cnt > 0, sorted(job.local_prs.items(), key=lambda x: x[1].fail_cnt, reverse=True)))
        for idx, pair in enumerate(local_failed_prs[:6]):
            local_pr = pair[1]
            if idx % 3 == 0:
                add_content(fields[8], "\n    ")
            else:
                add_content(fields[8], "    ")
            add_content(fields[8], local_pr.repo.split('/')[-1] + "[#" + str(local_pr.number)+"](" + local_pr.link +  ")" + "\\[**" + str(local_pr.fail_cnt) + "** times\\]")
        if len(local_failed_prs) > 6:
            add_content(fields[8], "\n    **" + str(len(local_failed_prs) - 6) + "** *more prs contain failed run of this job.*")
    if len(failed_jobs) > 4:
        add_content(fields[8], "\n... **" + str(len(failed_jobs) - 4) + "** *more jobs contain failed run.*")
            
    print(json.dumps(card))
    return card
    # bot_post(card)

def bot_post(card):
    body = {}
    body["msg_type"] = "interactive"
    body["card"] = card

    res = requests.post("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal/", json={"app_id": env["app_id"], "app_secret": env["app_secret"]})
    token = json.loads(res.text)['tenant_access_token']

    res = requests.post("https://open.feishu.cn/open-apis/chat/v4/list/", 
            json={"page_size": "10"}, 
            headers = {'content-type': 'application/json', 
                      'Authorization': "Bearer " + token},
          )

    for group in json.loads(res.text)["data"]["groups"]:
        chat_id = group["chat_id"]
        if group["name"] != "CI Status Report":
            continue
        res = requests.post("https://open.feishu.cn/open-apis/message/v4/send/", 
            json={"chat_id": group["chat_id"],
                  "msg_type": "interactive",
                  "card": card
                }, 
            headers = {'content-type': 'application/json', 
                      'Authorization': "Bearer " + token},
          )
        print(res.text)
