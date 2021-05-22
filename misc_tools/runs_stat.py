import sys
import os
import json
from mysql.connector import connect
from datetime import timedelta, date
import subprocess

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
        
        # PR information
        self.repo = row_items[10]
        self.branch = row_items[6]

        self.description = json.loads(row_items[9]) # Not so necessary for now
        if len(self.description) != 19:
            self.pr_number = 0
        else:
            self.pr_number = int(self.description['ghprbPullId'])
            self.author = self.description['ghprbPullAuthorLogin']
            self.pr_link = self.description['ghprbPullLink']

    def get_fail_info(self):
        cmd = 'cat /mnt/ci.pingcap.net/jenkins_home/jobs/'+ self.job_name + '/builds/'+ str(self.job_id) + '/log | grep -A 10 "\--------" | grep FAIL: '
        ps = subprocess.Popen(cmd,shell=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        raw = ps.communicate()[0].decode("utf-8").split("\n")
        return list(map(lambda x: x.split("FAIL: ")[1], filter(lambda x: "FAIL: " in x, raw)))

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
    
    def add_job(self, job: Run):
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
        self.__commit_hashes = []
        self.fail_cnt = 0
        self.success_cnt = 0
        self.abort_cnt = 0

    
    def add_commit_hash(self, commit: Commit):
        self.__commit_hashes.append(commit)

    def update(self, job: Run):
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


    def print_commits(self, job_name = ""):
        if len(job_name) == 0:
            print("-" * 150)
            indent = ""
        else:
            indent = "    "

        print(indent + bcolors.BOLD + "Pull Request " + bcolors.ENDC, end="")
        print(bcolor[self.number % len(bcolor)] + "#" + str(self.number) + bcolors.ENDC, end=" ")  

        if len(self.__commit_hashes) > 1:
            print(bcolors.OKCYAN + "total_job: " + bcolors.ENDC + str((self.success_cnt + self.fail_cnt + self.abort_cnt)), end=" ")
            print(bcolors.OKCYAN + "success_cnt: " + bcolors.ENDC + str(self.success_cnt), end=" ")
            print(bcolors.OKCYAN + "fail_cnt: " + bcolors.ENDC + str(self.fail_cnt), end=" ")
            print(bcolors.OKCYAN + "abort_cnt: " + bcolors.ENDC + str(self.abort_cnt), end=" ")
            print(bcolors.OKCYAN + "success_rate: " + bcolors.ENDC + '{:.1%}'.format(self.get_success_rate()), end=" ")
            print(bcolors.OKCYAN + "fail_rate: " + bcolors.ENDC + '{:.1%}'.format(self.get_fail_rate()), end=" ")
            print(bcolors.OKCYAN + "abort_rate: " + bcolors.ENDC + '{:.1%}'.format(self.get_abort_rate()), end=" ")        
        print()

        print(indent + self.repo, end=" ")
        print(bcolors.BOLD + self.branch + bcolors.ENDC, end=" ")
        print(bcolors.OKCYAN + "pr_title: " + bcolors.ENDC + self.title)
        for commit in self.__commit_hashes:
            print("", end="\t")
            commit.print_jobs(job_name)
        print()
    
class Job:
    def __init__(self, job: Run):
        self.job_name = job.job_name
        self.repo = job.repo
        self.branch = job.branch
        self.prs = {}

        self.fail_cnt = 0
        self.success_cnt = 0
        self.abort_cnt = 0
        self.total_cnt = 0
    
    def update(self, job: Run, pr: PR):
        if not pr.number in self.prs:
            self.prs[pr.number] = pr

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
        total_cnt = self.success_cnt + self.fail_cnt + self.abort_cnt
        if total_cnt > 0:
            print(bcolors.OKCYAN + "runs: " + bcolors.ENDC + str(total_cnt), end=" ")
            print("success: "  + bcolors.OKGREEN + str(self.success_cnt) + " " + '{:.1%}'.format(self.success_cnt / (total_cnt)) + bcolors.ENDC, end=" ")
            print("fail: " + bcolors.FAIL + str(self.fail_cnt) + " " + '{:.1%}'.format(self.fail_cnt / (total_cnt)) + bcolors.ENDC, end=" ")
            print("abort: " + bcolors.WARNING + str(self.abort_cnt) + " " + '{:.1%}'.format(self.abort_cnt / (total_cnt)) + bcolors.ENDC, end=" ")     
        print()
        for pr_num, pr in self.prs.items():
            pr.print_commits(self.job_name)


def summary(begin_time, pr_map, commit_hash_map, job_map, runs, total_job_cnt, success_job_cnt, fail_job_cnt, abort_job_cnt):
    print()
    print("-"*150)

    print(bcolors.BOLD + "Stat Summary" + bcolors.ENDC)
    print("Time: " +  bcolors.WARNING + begin_time.strftime('%Y-%m-%d %H:%M:%S') + bcolors.ENDC + " to " + bcolors.WARNING + end_time.strftime('%Y-%m-%d %H:%M:%S') + bcolors.ENDC)
    print(bcolors.HEADER + "Number of PRs:   " + bcolors.ENDC + str(len(pr_map)).rjust(5))
    print(bcolors.HEADER + "Number of hashes:" + bcolors.ENDC + str(len(commit_hash_map)).rjust(5))
    print(bcolors.HEADER + "Number of jobs:  " + bcolors.ENDC + str(len(job_map)).rjust(5))
    print(bcolors.HEADER + "Number of runs:  " + bcolors.ENDC + str(len(runs)).rjust(5), end=" ")
    print()

    if total_job_cnt > 0:
        print(bcolors.OKCYAN + "success_cnt:  " + bcolors.ENDC + str(success_job_cnt).rjust(5), end="  ")
        print(bcolors.OKCYAN + "fail_cnt:  " + bcolors.ENDC + str(fail_job_cnt).rjust(5), end="  ")
        print(bcolors.OKCYAN + "abort_cnt:  " + bcolors.ENDC + str(abort_job_cnt).rjust(5), end="  ")
        print()
        print(bcolors.OKCYAN + "success_rate: " + bcolors.ENDC + '{:5.1%}'.format(success_job_cnt / total_job_cnt), end="  ")
        print(bcolors.OKCYAN + "fail_rate: " + bcolors.ENDC + '{:5.1%}'.format(fail_job_cnt / total_job_cnt), end="  ")
        print(bcolors.OKCYAN + "abort_rate: " + bcolors.ENDC + '{:5.1%}'.format(abort_job_cnt / total_job_cnt), end="  ")        
    print()

class Fail_Info:
    def __init__(self, info):
        self.info = info
        self.run_list = []
    
    def fail_cnt(self):
        return len(self.run_list)
    
    def update(self, run: Run):
        self.run_list.append(run)
    
    def log(self):
        print("-" * 150)
        print(bcolors.OKCYAN + "Fail Info: " + bcolors.ENDC + self.info)
        print(bcolors.HEADER + "\tFailed runs cnt: " + bcolors.ENDC + str(self.fail_cnt()))
        
        for run in sorted(self.run_list, key=lambda x: x.pr_number):
            print("", end="\t")
            # print(bcolors.HEADER + "PR Number: " + bcolors.ENDC + str(run.pr_number), end=" ")
            run.log(
                show_status=False, color_pullid=False, show_job_name=False,
                begin=bcolors.HEADER + "Author: " + bcolors.ENDC + bcolors.WHITE + run.author.ljust(12, ' ') + bcolors.ENDC + " ", 
                end=bcolors.HEADER + "Link: " + bcolors.ENDC + bcolors.WHITE+ run.pr_link + bcolors.ENDC
            )
        print()
            
def main():
    env = json.load(open(os.getenv("STAT_ENV_PATH") + "/env.json"))
    connection = connect(
            host = env["host"],
            port = env["port"],
            user = env["user"],
            password = env["password"],
            database = env["database"],
        )
    cursor = connection.cursor()
    begin_time = date.today()
    begin_time -= timedelta(days=2)
    end_time = begin_time + timedelta(days=1)
    cursor.execute('select * from ci_data where repo="pingcap/tidb" and time >= %s and time <= %s', (begin_time.strftime('%Y-%m-%d %H:%M:%S'), end_time.strftime('%Y-%m-%d %H:%M:%S')))
    runs = cursor.fetchall()

    pr_map = {}
    commit_hash_map = {}
    job_map = {}
    run_map = {}

    job_list = []
    run_list = []

    total_job_cnt = 0
    fail_job_cnt = 0
    success_job_cnt = 0
    abort_job_cnt = 0

    # for line in runs:
    for record in runs:
        # jobs.append(Job(record))
        run = Run(record)
        run_map[run.job_id] = run
        if run.pr_number == 0:
            continue

        run_list.append(run)

        pr_number = run.pr_number
        commit_hash = run.commit

        if pr_number in pr_map:
            pr = pr_map[pr_number]
        else:
            pr = PR(run)
            pr_map[pr_number] = pr
        
        if commit_hash in commit_hash_map:
            commit = commit_hash_map[commit_hash]
        else:
            commit = Commit(run, pr)
            commit_hash_map[commit_hash] = commit
            pr.add_commit_hash(commit)
        commit.add_job(run)

        if run.job_name in job_map:
            job = job_map[run.job_name]
        else:
            job = Job(run)
            job_map[run.job_name] = job
            job_list.append(job)

        # stat for different runs
        job.update(run, pr)

        # stat for global job situation
        if run.status == "SUCCESS": 
            success_job_cnt += 1
        elif run.status == "ABORTED":
            abort_job_cnt += 1
        elif run.status == "FAILURE":
            fail_job_cnt += 1


    total_job_cnt = len(runs)

    # for pr_number, pr in pr_map.items():
    #     pr.print_commits()
    
    # for job_name, pipeline in job_map.items():
    #     pipeline.print_prs()

    # sorted(job_list, key=lambda job: job.fail_cnt, reverse=True)[0].print_prs()

    # for run in filter(lambda x: x.status == "FAILURE" and x.job_name == "tidb_ghpr_unit_test", run_list):
    #     # run.log(show_fail_info=True)
    #     infos = run.get_fail_info()
    #     for info in infos:
    #         print(run.job_id, end=" ")
    #         print(info)

#################################################
# Fail info belonging analysis
#################################################
    log_list = open(env["log_dir"] + "/cases.log").readlines()

    fail_info_map = {}

    for log in log_list:
        job_id, fail_info = log[:-1].split(" ", 1)
        if not fail_info in fail_info_map:
            fail_info_map[fail_info] = Fail_Info(fail_info)
        fail_info_map[fail_info].update(run_map[int(job_id)])
    
    for _, fail in fail_info_map.items():
        fail.log()

#################################################

    # for run in filter(lambda x: x.status == "FAILURE", run_list):
    #     info = run.get_fail_info()
    #     if len(info) < 1:
    #         run.log(show_fail_info=True)


    # summary(begin_time, pr_map, commit_hash_map, job_map, runs, total_job_cnt, success_job_cnt, fail_job_cnt, abort_job_cnt)



if (__name__ == "__main__"):
    main()
