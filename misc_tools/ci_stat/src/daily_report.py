import ci_stat as stat
import json
import os
import copy
from datetime import datetime,date,timedelta
from hourly_bot import send, bot_post, add_content

def append_rerun(card, res):
    pr_map = res.pr_map
    commit_hash_map = res.commit_hash_map
    job_map = res.job_map
    fail_info_map = res.fail_info_map
    run_list = res.run_list
    miss_cnt = res.miss_cnt

    card["elements"][0]["text"]["content"]="**CI Status Daily Report**"
    fields = card["elements"][0]["fields"]
    temp = copy.deepcopy(fields[0])
    temp["text"]["content"] = ""
    fields.insert(6, temp)
    fields.insert(6, copy.deepcopy(temp))

    add_content(fields[6], "**Top Rerunning Job:**")
    job_list = sorted(job_map.items(), key=lambda x: x[1].rerun_cnt, reverse=True)
    for job_name, job in job_list[:10]:
        add_content(fields[6], "\n**" + job.job_name + "** ")
        add_content(fields[6], "**🟢: **"  + str(job.success_cnt) + " ")
        add_content(fields[6], "**🔴: **" + str(job.fail_cnt) + " ")
        add_content(fields[6], "**🟡: **" + str(job.abort_cnt) + " ")
        add_content(fields[6], "**🔄: **" + str(job.rerun_cnt) + " ")
        rerun_list = list(filter(lambda x: x[1].rerun_cnt(job_name) > 0, sorted(job.local_prs.items(), key=lambda x: x[1].rerun_cnt(job_name), reverse=True)))
        for pr_number, pr in rerun_list[:5]:
            add_content(fields[6], "\n  " + pr.repo + " [#" + str(pr.number) + "]("+pr.link +") **rerun cnt:** " + str(pr.rerun_cnt(job_name)))
            run_list = list(filter(lambda x: x.job_name == job_name and len(x.get_fail_info()) > 0, pr.runs))
            for run in run_list[:3]:
                fail_infos = run.get_fail_info()
                add_content(fields[6], "\n        [" + str(run.job_id) + "](" + run.link + ") ...**" + "_".join(run.job_name.split('_')[-2:]) + "**: ")
                for info in fail_infos:
                    add_content(fields[6], "\n             " + info)
            if len(pr.runs) > 3 and len(run_list) > 0:
                add_content(fields[6], "\n         ... **" + str(pr.rerun_cnt(job_name) + 1 - min(3, len(run_list))) + "** more runs. ")

        if len(rerun_list) > 4:
            add_content(fields[6], "\n... **" + str(len(rerun_list) - 3) + "** *more rerunning prs and totally* **" + str(job.rerun_cnt) + "** *reran runs.*")
    if len(job_list) > 10:
        add_content(fields[6], "\n... **" + str(len(job_list) - 10) + "** *more rerunning jobs.")
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
    bot_post(card)


if __name__ == "__main__":
    main()