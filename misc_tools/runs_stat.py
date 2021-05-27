import sys
import os
import json
from datetime import timedelta, date, datetime
from string import printable
from mysql.connector import connect
from pathlib import Path
from functools import reduce
import subprocess
from apscheduler.schedulers.blocking import BlockingScheduler
import hmac
import hashlib
import base64
import requests

class bcolors:
    HEADER = '\033[95m'
    OKBLUE = '\033[94m'
    OKCYAN = '\033[96m'
    OKGREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'
    UNDERLINE = '\033[4m'
    WHITE = '\033[37m'

bcolor = ['\033[31m', '\033[32m', '\033[96m', '\033[92m', '\033[93m', '\033[91m', '\033[31m', '\033[34m', '\033[35m', '\033[37m']

def sec_to_hours(seconds):
    a=seconds//3600
    b=(seconds%3600)//60
    c=(seconds%3600)%60
    d="{:02d}:{:02d}:{:02d}".format(a, b, c)
    return d

class Run:
    def __init__(self, row_items):
        # Commit information
        self.commit = row_items[5]

        # Job information
        self.job_id = row_items[1]
        self.job_name = row_items[0]

        # Run information
        self.status = row_items[2]
        self.time = row_items[4]
        self.duration = row_items[3] 
        self.comment = row_items[7]
        self.analysis_res = row_items[8]
        self.link = "https://ci.pingcap.net/blue/organizations/jenkins/" + self.job_name + "/detail/" + self.job_name + "/" + str(self.job_id) + "/pipeline/"
        
        # PR information
        self.repo = row_items[10]
        self.branch = row_items[6]

        self.fail_info = []

        self.description = json.loads(row_items[9]) # Not so necessary for now
        if len(self.description) != 19:
            self.pr_number = 0
        else:
            self.pr_number = int(self.description['ghprbPullId'])
            self.author = self.description['ghprbPullAuthorLogin']
            self.pr_link = self.description['ghprbPullLink']

    def get_fail_file_path(self):
        dir_path = env["log_dir"] + "fails/" + self.job_name + "/"
        Path(dir_path).mkdir(parents=True, exist_ok=True)
        return dir_path + str(self.job_id)

    def get_fail_info(self):
        # fail_log_dir_path = env["log_dir"] + "fails/" + self.job_name + "/"
        # Path(fail_log_dir_path).mkdir(parents=True, exist_ok=True)

        # if os.path.isfile(self.get_fail_file_path()):
        #     return list(map(lambda x: x[:-1], open(self.get_fail_file_path()).readlines()))
        # else:
        if len(self.fail_info) > 0:
            return self.fail_info
        cmd = 'cat /mnt/ci.pingcap.net/jenkins_home/jobs/'+ self.job_name + '/builds/'+ str(self.job_id) + '/log | grep -A 10 "\--------" | grep FAIL: '
        ps = subprocess.Popen(cmd,shell=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        raw = ps.communicate()[0].decode("utf-8").split("\n")

        file = open(self.get_fail_file_path(), "w")
        file.writelines(list(map(lambda x: x.split("FAIL: ")[1] + "\n", filter(lambda x: "FAIL: " in x, raw))))
        self.fail_info = list(map(lambda x: x.split("FAIL: ")[1], filter(lambda x: "FAIL: " in x, raw)))
        return self.fail_info

    def get_status_log(self):
        res = ""
        if self.status == "SUCCESS":
            res += bcolors.OKGREEN
        elif self.status == "ABORTED":
            res += bcolors.WARNING
        else:
            res += bcolors.FAIL
        res += self.status + bcolors.ENDC
        return res
    
    def log(self, begin="", end="", show_fail_info=False, show_time=True, show_job_name=True, show_status=True, color_pullid=True):
        print("", end=begin)
        print(bcolors.HEADER + "Run id:" + bcolors.ENDC + str(self.job_id).rjust(5, ' '), end=" ")
        print(bcolors.OKBLUE + self.commit[:9] + bcolors.ENDC, end=" ")
        print(self.repo + " " + self.branch, end=" ")
        if color_pullid:
            print(bcolor[self.pr_number % len(bcolor)] + "#" + str(self.pr_number) + bcolors.ENDC, end=" ")
        else:
            print(bcolors.WHITE + "#" + str(self.pr_number) + bcolors.ENDC, end=" ")
        if show_time:
            print(bcolors.HEADER + "duration: " + bcolors.ENDC + sec_to_hours(self.duration//1000) + " " + bcolors.HEADER + "time: " + bcolors.ENDC + str(self.time).split()[1], end=" ")
        if show_status:
            print(self.get_status_log(), end=" ")
        if show_job_name:
            print(bcolors.HEADER + "job_name: " + bcolors.ENDC + self.job_name, end=" ")
        if show_fail_info and self.status == "FAILURE":
            infos = self.get_fail_info()
            if len(infos) == 1:
                print(bcolors.FAIL + "fail_cause: " + bcolors.ENDC + infos[0], end=" ")
            elif len(infos) > 1:
                print()
                for info in infos:
                    print("\t"*3 + bcolors.FAIL + "fail_cause: " + bcolors.ENDC + info)
        print(end)

class Commit:
    def __init__(self, job: Run, pr):
        self.hash = job.commit
        self.repo = job.repo
        self.branch = job.branch
        self.pr_number = job.pr_number
        self.pr = pr
        self.jobs = []
        self.fail_cnt = 0
        self.success_cnt = 0
        self.abort_cnt = 0
        pr.add_commit_hash(self)
    
    def add_run(self, job: Run):
        self.jobs.append(job)
        self.pr.update(job)
        if job.status == "SUCCESS": 
            self.success_cnt += 1
        elif job.status == "ABORTED":
            self.abort_cnt += 1
        elif job.status == "FAILURE":
            self.fail_cnt += 1
        
    def get_success_rate(self):
        if len(self.jobs) == 0:
            return 0.0
        return self.success_cnt / len(self.jobs)
    def get_fail_rate(self):
        if len(self.jobs) == 0:
            return 0.0
        return self.fail_cnt / len(self.jobs)
    def get_abort_rate(self):
        if len(self.jobs) == 0:
            return 0.0
        return self.abort_cnt / len(self.jobs)
    
    def print_jobs(self, job_name = ""):
        print(bcolors.BOLD + "Commit " + bcolors.ENDC, end="")
        print(bcolors.OKBLUE + self.hash[:10] + bcolors.ENDC, end=" ")
        print(bcolors.HEADER + "total_job: " + bcolors.ENDC + str(len(self.jobs)), end=" ")
        print(bcolors.HEADER + "success_cnt: " + bcolors.ENDC + str(self.success_cnt), end=" ")
        print(bcolors.HEADER + "fail_cnt: " + bcolors.ENDC + str(self.fail_cnt), end=" ")
        print(bcolors.HEADER + "abort_cnt: " + bcolors.ENDC + str(self.abort_cnt), end=" ")
        print(bcolors.HEADER + "success_rate: " + bcolors.ENDC + '{:.1%}'.format(self.get_success_rate()), end=" ")
        print(bcolors.HEADER + "fail_rate: " + bcolors.ENDC + '{:.1%}'.format(self.get_fail_rate()), end=" ")
        print(bcolors.HEADER + "abort_rate: " + bcolors.ENDC + '{:.1%}'.format(self.get_abort_rate()), end=" ")
        print()

        for job in list(filter(lambda job: len(job_name) == 0 or job.job_name == job_name, self.jobs)):
            print("", end="\t\t")
            if len(job_name) == 0 or job.status != "FAILURE":
                job.log()
            else:
                job.log(show_fail_info=True)

class PR:
    def __init__(self, job):
        self.number = int(job.description['ghprbPullId'])
        self.link = job.description['ghprbPullLink']
        self.title = job.description['ghprbPullTitle']
        self.repo = job.repo
        self.branch = job.branch
        self.fail_cnt = 0
        self.success_cnt = 0
        self.abort_cnt = 0
        self.fail_info_map = {} # fail_info to Fail_Info Map
        self.commit_hashes = []
        self.runs = []
        self.job = job

    
    def add_commit_hash(self, commit: Commit):
        self.commit_hashes.append(commit)

    def update(self, job: Run):
        self.runs.append(job)
        if job.status == "SUCCESS": 
            self.success_cnt += 1
        elif job.status == "ABORTED":
            self.abort_cnt += 1
        elif job.status == "FAILURE":
            self.fail_cnt += 1
        
    def get_success_rate(self):
        if self.success_cnt + self.fail_cnt + self.abort_cnt == 0:
            return 0.0
        return self.success_cnt / (self.success_cnt + self.fail_cnt + self.abort_cnt)
    def get_fail_rate(self):
        if self.success_cnt + self.fail_cnt + self.abort_cnt == 0:
            return 0.0
        return self.fail_cnt / (self.success_cnt + self.fail_cnt + self.abort_cnt)
    def get_abort_rate(self):
        if self.success_cnt + self.fail_cnt + self.abort_cnt == 0:
            return 0.0
        return self.abort_cnt / (self.success_cnt + self.fail_cnt + self.abort_cnt)


    def log(self, indent="", show_commits=True, show_fail=False, color_pr=True, show_runs=False):

        print(indent + "-" * 150)
        print(indent + bcolors.BOLD + "Pull Request " + bcolors.ENDC, end="")
        if color_pr:
            print(bcolor[self.number % len(bcolor)] + "#" + str(self.number) + bcolors.ENDC, end=" ")  
        else:
            print(bcolors.WHITE + "#" + str(self.number) + bcolors.ENDC, end=" ")

        if len(self.commit_hashes) > 1 or show_commits == False:
            print(bcolors.OKCYAN + "total_job: " + bcolors.ENDC + str((self.success_cnt + self.fail_cnt + self.abort_cnt)), end=" ")
            print(bcolors.OKCYAN + "success_cnt: " + bcolors.ENDC + str(self.success_cnt), end=" ")
            print(bcolors.OKCYAN + "fail_cnt: " + bcolors.ENDC + str(self.fail_cnt), end=" ")
            print(bcolors.OKCYAN + "abort_cnt: " + bcolors.ENDC + str(self.abort_cnt), end=" ")
            print(bcolors.OKCYAN + "success_rate: " + bcolors.ENDC + '{:.1%}'.format(self.get_success_rate()), end=" ")
            print(bcolors.OKCYAN + "fail_rate: " + bcolors.ENDC + '{:.1%}'.format(self.get_fail_rate()), end=" ")
            print(bcolors.OKCYAN + "abort_rate: " + bcolors.ENDC + '{:.1%}'.format(self.get_abort_rate()), end=" ")        
        print()

        if show_runs:
            for run in self.runs:
                run.log(begin=indent+"\t")


        if show_commits:
            try:
                print(indent + self.repo, end=" ")
                print(bcolors.BOLD + self.branch + bcolors.ENDC, end=" ")
                print(bcolors.OKCYAN + "pr_title: " + bcolors.ENDC + ''.join(filter(lambda x: x in printable, self.title)))
                for commit in self.commit_hashes:
                    print("", end="\t")
                    commit.print_jobs()
                print()
            except:
                print(str(self.job.description).encode('utf-8'))
                
        if show_fail:
            for fail_info, fail in self.fail_info_map.items():
                fail.log(indent="\t", show_line=False)
        
    
    def fail_update(self, fail_info, run):
        if not fail_info in self.fail_info_map:
            self.fail_info_map[fail_info] = Fail_Info(fail_info)
        fail = self.fail_info_map[fail_info]
        fail.update(run)
    
class Job:
    def __init__(self, job: Run):
        self.job_name = job.job_name
        self.repo = job.repo
        self.branch = job.branch
        self.prs = {}

        # TODO refractor after
        self.local_prs = {}

        self.fail_cnt = 0
        self.success_cnt = 0
        self.abort_cnt = 0
        self.total_cnt = 0
    
    def update(self, job: Run, pr: PR):
        if not pr.number in self.prs:
            self.prs[pr.number] = pr
        
        if not pr.number in self.local_prs:
            self.local_prs[pr.number] = PR(job)
        self.local_prs[pr.number].update(job)
        
        if job.status == "SUCCESS":
            self.success_cnt += 1
        elif job.status == "ABORTED":
            self.abort_cnt += 1
        elif job.status == "FAILURE":
            self.fail_cnt += 1
        self.total_cnt += 1

    def print_prs(self):
        print("-" * 150)
        print()
        print(bcolors.BOLD + bcolors.FAIL +  "Job Name: " + bcolors.ENDC + bcolors.ENDC, end="")
        print(bcolors.WARNING + self.job_name + bcolors.ENDC, end=" ")  
        if self.total_cnt > 0:
            print(bcolors.OKCYAN + "runs_raw: " + bcolors.ENDC + str(self.total_cnt), end=" ")
            print("success: "  + bcolors.OKGREEN + str(self.success_cnt) + " " + '{:.1%}'.format(self.success_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")
            print("fail: " + bcolors.FAIL + str(self.fail_cnt) + " " + '{:.1%}'.format(self.fail_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")
            print("abort: " + bcolors.WARNING + str(self.abort_cnt) + " " + '{:.1%}'.format(self.abort_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")     
        print()
        for pr_num, pr in self.local_prs.items():
            pr.log(indent="\t", show_commits=False, show_runs=True) # TODO , show_line=False, indent="    ")

class Fail_Info:
    def __init__(self, info):
        self.info = info
        self.run_list = []
    
    def fail_cnt(self):
        return len(self.run_list)
    
    def update(self, run: Run):
        self.run_list.append(run)
    
    def log(self, indent="", show_line=True):
        if show_line:
            print("-" * 150)
        print(indent + bcolors.OKCYAN + "Fail Info: " + bcolors.ENDC + self.info)
        print(bcolors.HEADER + "\tFailed runs_raw cnt: " + bcolors.ENDC + str(self.fail_cnt()))
        
        for run in sorted(self.run_list, key=lambda x: x.pr_number):
            print(indent, end="\t")
            # print(bcolors.HEADER + "PR Number: " + bcolors.ENDC + str(run.pr_number), end=" ")
            run.log(
                show_status=False, color_pullid=False, show_job_name=False,
                begin=bcolors.HEADER + "Author: " + bcolors.ENDC + bcolors.WHITE + run.author.ljust(12, ' ') + bcolors.ENDC + " ", 
                end=bcolors.HEADER + "Link: " + bcolors.ENDC + bcolors.WHITE+ run.pr_link + bcolors.ENDC
            )
        print()

class RedirectStdStreams(object):
    def __init__(self, stdout=None, stderr=None):
        self._stdout = stdout or sys.stdout
        self._stderr = stderr or sys.stderr

    def __enter__(self):
        self.old_stdout, self.old_stderr = sys.stdout, sys.stderr
        self.old_stdout.flush(); self.old_stderr.flush()
        sys.stdout, sys.stderr = self._stdout, self._stderr

    def __exit__(self, exc_type, exc_value, traceback):
        self._stdout.flush(); self._stderr.flush()
        sys.stdout = self.old_stdout
        sys.stderr = self.old_stderr

def ilen(iterable):
    return reduce(lambda sum, element: sum + 1, iterable, 0)

def summary(begin_time, end_time, pr_map, commit_hash_map, job_map, run_list, miss_cnt):
    success_cnt = ilen(filter(lambda x: x.status == "SUCCESS", run_list))
    fail_cnt = ilen(filter(lambda x: x.status == "FAILURE", run_list))
    abort_cnt = ilen(filter(lambda x: x.status == "ABORTED", run_list))
    total_cnt = len(run_list)

    print()
    print("-"*150)

    print(bcolors.BOLD + "Stat Summary" + bcolors.ENDC)
    print("Time: " +  bcolors.WARNING + begin_time.strftime('%Y-%m-%d %H:%M:%S') + bcolors.ENDC + " to " + bcolors.WARNING + end_time.strftime('%Y-%m-%d %H:%M:%S') + bcolors.ENDC)
    print(bcolors.HEADER + "Number of PRs:   " + bcolors.ENDC + str(len(pr_map)).rjust(5))
    # print(bcolors.HEADER + "Number of hashes:" + bcolors.ENDC + str(len(commit_hash_map)).rjust(5))
    print(bcolors.HEADER + "Number of jobs:  " + bcolors.ENDC + str(len(job_map)).rjust(5))
    print(bcolors.HEADER + "Number of runs:  " + bcolors.ENDC + str(len(run_list)).rjust(5), end=" ")
    print()

    if total_cnt > 0:
        print(bcolors.OKCYAN + "success_cnt:  " + bcolors.ENDC + str(success_cnt).rjust(5), end="  ")
        print(bcolors.OKCYAN + "fail_cnt:  " + bcolors.ENDC + str(fail_cnt).rjust(5), end="  ")
        print(bcolors.OKCYAN + "abort_cnt:  " + bcolors.ENDC + str(abort_cnt).rjust(5), end="  ")
        print()
        print(bcolors.OKCYAN + "success_rate: " + bcolors.ENDC + '{:5.1%}'.format(success_cnt / total_cnt), end="  ")
        print(bcolors.OKCYAN + "fail_rate: " + bcolors.ENDC + '{:5.1%}'.format(fail_cnt / total_cnt), end="  ")
        print(bcolors.OKCYAN + "abort_rate: " + bcolors.ENDC + '{:5.1%}'.format(abort_cnt / total_cnt), end="  ")
        print()
        print(bcolors.OKCYAN + "fail_info not found: " + bcolors.ENDC + str(miss_cnt), end="  ")   

    # TODO 
    # Top PR
    # TOP JOB     
    print()

def get_runs_raw(begin_time, end_time):
    connection = connect(
            host = env["host"],
            port = env["port"],
            user = env["user"],
            password = env["password"],
            database = env["database"],
        )
    cursor = connection.cursor()
    
    begin_time_str = begin_time.strftime('%Y-%m-%d')

    cursor.execute('select * from ci_data where time >= %s and time <= %s', (begin_time.strftime('%Y-%m-%d %H:%M:%S'), end_time.strftime('%Y-%m-%d %H:%M:%S')))
    runs_raw = cursor.fetchall()
    connection.close()
    return runs_raw

class Result:
    def __init__(self, runs_raw, begin_time,end_time):
        pr_map = {}
        commit_hash_map = {}
        job_map = {}
        fail_info_map = {}
        run_list = []
        miss_cnt = 0

        for record in runs_raw:
            run = Run(record)
            run_list.append(run)

            if run.pr_number == 0:
                continue

            pr_number = run.pr_number
            commit_hash = run.commit

            if not pr_number in pr_map:
                pr_map[pr_number] = PR(run)
            pr = pr_map[pr_number]
            
            if not commit_hash in commit_hash_map:
                commit_hash_map[commit_hash] = Commit(run, pr)
            commit = commit_hash_map[commit_hash]
            commit.add_run(run)

            if not run.job_name in job_map:
                job_map[run.job_name] = Job(run)
            job = job_map[run.job_name]
            job.update(run, pr)

            if run.status != "FAILURE":
                continue
        
            fail_infos = run.get_fail_info()
            if len(fail_infos) < 1:
                miss_cnt += 1
            for fail_info in fail_infos:
                if not fail_info in fail_info_map:
                    fail_info_map[fail_info] = Fail_Info(fail_info)
                fail = fail_info_map[fail_info]
                fail.update(run)
                pr.fail_update(fail_info, run)

        self.pr_map = pr_map
        self.commit_hash_map = commit_hash_map
        self.job_map = job_map
        self.fail_info_map = fail_info_map
        self.run_list = run_list
        self.miss_cnt = miss_cnt
        self.begin_time = begin_time
        self.end_time = end_time
        self.success_cnt = ilen(filter(lambda x: x.status == "SUCCESS", run_list))
        self.fail_cnt = ilen(filter(lambda x: x.status == "FAILURE", run_list))
        self.abort_cnt = ilen(filter(lambda x: x.status == "ABORTED", run_list))
        self.total_cnt = len(run_list)

def main(begin_time, end_time, hour_report=False):
    runs_raw = get_runs_raw(begin_time, end_time)

    res = Result(runs_raw, begin_time, end_time)

    pr_map = res.pr_map
    commit_hash_map = res.commit_hash_map
    job_map = res.job_map
    fail_info_map = res.fail_info_map
    run_list = res.run_list
    miss_cnt = res.miss_cnt

    #################################################
    # Per Pull Request analysis
    #################################################

    # pr_log_dir = env["log_dir"] + "prs/"
    # with RedirectStdStreams(stdout=open(pr_log_dir + begin_time_str + ".log", "w")):
    #     for pr_number, pr in pr_map.items():
    #         pr.log()
    

    # #################################################
    # # Per Job analysis
    # #################################################
    # job_log_dir = env["log_dir"] + "jobs/"
    # with RedirectStdStreams(stdout=open(job_log_dir + begin_time_str + ".log", "w")):
    #     for job_name, pipeline in job_map.items():
    #         pipeline.print_prs()

    #################################################
    # Fail info belonging analysis
    #################################################

    # Fails Per Fail Cases
    # fail_cases_log = env["log_dir"] + "fail_cases/"
    # with RedirectStdStreams(stdout=open(fail_cases_log + begin_time_str + ".log", "w")):
    #     for _, fail in fail_info_map.items():
    #         fail.log()

    #################################################

    # for run in filter(lambda x: x.status == "FAILURE", run_list):
    #     info = run.get_fail_info()
    #     if len(info) < 1:
    #         run.log(show_fail_info=True)
    summary(begin_time, end_time, pr_map, commit_hash_map, job_map, run_list, miss_cnt)
    print()
    print("-" * 150)
    print(bcolors.BOLD + bcolors.WHITE + "Jobs: " + bcolors.ENDC + bcolors.ENDC)
    for _, job in sorted(job_map.items(), key=lambda x: x[1].fail_cnt, reverse=True):
        job.print_prs()
    
    print()
    print("-" * 150)
    print(bcolors.BOLD + bcolors.WHITE + "PRs: " + bcolors.ENDC + bcolors.ENDC)
    for _, pr in sorted(pr_map.items(), key=lambda x: x[1].fail_cnt, reverse=True):
        pr.log(show_fail=True, color_pr=False, )


    if res.fail_cnt > 3:
        send(res)

    return res


def report():
    now = datetime.now()
    begin_time = now - timedelta(hours=1, minutes=now.minute, seconds=now.second)
    end_time = begin_time + timedelta(hours=1)
    

    print("Logging from" + begin_time.strftime('%Y-%m-%d-%H:%M:%S') + " to " + end_time.strftime('%Y-%m-%d-%H:%M:%S'))

    Path("/tmp/ci_analysis/").mkdir(parents=True, exist_ok=True)
    tmp_res = "/tmp/ci_analysis/" + begin_time.strftime('%Y-%m-%d-%H:%M:%S') + ".log"
    with RedirectStdStreams(stdout=open(tmp_res, "w")):
        res = main(begin_time, end_time)


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
    bot_post(card)

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
        res = requests.post("https://open.feishu.cn/open-apis/message/v4/send/", 
            json={"chat_id": group["chat_id"],
                  "msg_type": "interactive",
                  "card": card
                }, 
            headers = {'content-type': 'application/json', 
                      'Authorization': "Bearer " + token},
          )
        print(res.text)


if (__name__ == "__main__"):
    env = json.load(open(os.getenv("STAT_ENV_PATH") + "/env.json"))    
    # env = json.load(open("env.json"))    

    scheduler = BlockingScheduler()
    scheduler.add_job(report, 'interval', hours=1)

    try:
        scheduler.start()
    except (KeyboardInterrupt, SystemExit):
        pass

    # now = datetime.now()
    # main(now - timedelta(hours=3), now)