import ci_stat as stat
import json
import os
import copy
from datetime import datetime,date,timedelta
from hourly_bot import send, bot_post, add_content

def append_rerun(card, res):
    card["elements"][0]["text"]["content"]="**CI Status Daily Report**"
    fields = card["elements"][0]["fields"]
    temp = copy.deepcopy(fields[0])
    temp["text"]["content"] = ""
    fields.insert(6, temp)
    fields.insert(6, copy.deepcopy(temp))

    add_content(fields[6], "**Top Rerunning Job:**\n")
    
    return card

def main():
    # env = json.load(open(os.getenv("STAT_ENV_PATH") + "/env.json"))

    end_time = date.today()
    begin_time = end_time - timedelta(days=1)
    res = stat.get_result(begin_time, end_time)

    pr_map = res.pr_map
    commit_hash_map = res.commit_hash_map
    job_map = res.job_map
    fail_info_map = res.fail_info_map
    run_list = res.run_list
    miss_cnt = res.miss_cnt

    stat.summary(begin_time, end_time, pr_map, commit_hash_map, job_map, run_list, miss_cnt)
    print()
    stat.job_list(job_map)
    
    print()
    stat.pr_list(pr_map)

    card = append_rerun(send(res), res)
    print(json.dumps(card))
    # bot_post(card)


if __name__ == "__main__":
    main()