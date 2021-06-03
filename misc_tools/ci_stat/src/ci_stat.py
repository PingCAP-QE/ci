import sys
import os
import json
import subprocess
from datetime import timedelta, date, datetime
from string import printable
from mysql.connector import connect
from pathlib import Path
from functools import reduce
from bcolor import bcolor, bcolors, WHITE, CYAN, BOLD

env = json.load(open(os.getenv("STAT_ENV_PATH") + "/env.json"))

def get_success_rate(obj):
    if obj.success_cnt + obj.fail_cnt + obj.abort_cnt == 0:
        return 0.0
    return obj.success_cnt / (obj.success_cnt + obj.fail_cnt + obj.abort_cnt)
def get_fail_rate(obj):
    if obj.success_cnt + obj.fail_cnt + obj.abort_cnt == 0:
        return 0.0
    return obj.fail_cnt / (obj.success_cnt + obj.fail_cnt + obj.abort_cnt)
def get_abort_rate(obj):
    if obj.success_cnt + obj.fail_cnt + obj.abort_cnt == 0:
        return 0.0
    return obj.abort_cnt / (obj.success_cnt + obj.fail_cnt + obj.abort_cnt)

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
        # cmd = 'cat /mnt/ci.pingcap.net/jenkins_home/jobs/'+ self.job_name + '/builds/'+ str(self.job_id) + '/log | grep -A 20 "\--------" | grep FAIL: -A 20'
        cmd = 'cat /mnt/ci.pingcap.net/jenkins_home/jobs/'+ self.job_name + '/builds/'+ str(self.job_id) + '/log ' + '| cut -d" " -f2- | grep -v "^\[2021" | grep -A 101 "\--------" | grep "^FAIL:" -A 20'
        ps = subprocess.Popen(cmd,shell=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        raw = ps.communicate()[0].decode("utf-8").split("--\n")
        if len(raw) == 0:
            return [["Message not found", ""]]

        file = open(self.get_fail_file_path(), "w")
        # file.writelines(list(map(lambda x: x.split("FAIL: ")[1] + "\n", filter(lambda x: "FAIL: " in x, raw))))
        for line in list(map(lambda x: x.split("\n\n")[:2], raw)):
            if len(line) != 2 :
                continue
            try:
                file.write("*"*120 + "\n")
                file.write(line[0] + "\n")
                file.write(line[1] + "\n")
            except:
                print(self.job_name, self.job_id) 

        self.fail_info =  list(filter(lambda x: len(x) >= 2, map(lambda x: x.split("\n\n")[:2], raw)))
        # = list(map(lambda x: x.split("FAIL: ")[1], filter(lambda x: "FAIL: " in x, raw)))
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
    
    def log(self, begin="", end="", show_fail_info=False, show_time=True, show_job_name=True, show_status=True, color_pullid=False):
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
            # if len(infos) == 1:
            #     print(bcolors.FAIL + "fail_cause: " + bcolors.ENDC + infos[0], end=" ")
            # elif len(infos) > 1:
            if len(infos) == 0:
                print(bcolors.WARNING + "Error message not found" + bcolors.ENDC, end="")
            else:
                print()
                end=""
                for info in infos:
                    print("\t"*3 + bcolors.FAIL + "fail_cause: " + bcolors.ENDC + info[0])
                    for line in line[1].split("\n"):
                        print("\t"*3 + line)
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
    
    def print_jobs(self, show_fail_info=False):
        print(bcolors.BOLD + "Commit " + bcolors.ENDC, end="")
        print(bcolors.OKBLUE + self.hash[:10] + bcolors.ENDC, end=" ")
        print(bcolors.HEADER + "total_job: " + bcolors.ENDC + str(len(self.jobs)), end=" ")
        print(bcolors.HEADER + "success_cnt: " + bcolors.ENDC + str(self.success_cnt), end=" ")
        print(bcolors.HEADER + "fail_cnt: " + bcolors.ENDC + str(self.fail_cnt), end=" ")
        print(bcolors.HEADER + "abort_cnt: " + bcolors.ENDC + str(self.abort_cnt), end=" ")
        print(bcolors.HEADER + "success_rate: " + bcolors.ENDC + '{:.1%}'.format(get_success_rate(self)), end=" ")
        print(bcolors.HEADER + "fail_rate: " + bcolors.ENDC + '{:.1%}'.format(get_fail_rate(self)), end=" ")
        print(bcolors.HEADER + "abort_rate: " + bcolors.ENDC + '{:.1%}'.format(get_abort_rate(self)), end=" ")
        print()

        for job in sorted(self.jobs, key=lambda x: x.time):
            print("", end="\t\t")
            job.log(show_fail_info=show_fail_info, color_pullid=False)

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
        self.job_cnt = {}

    
    def add_commit_hash(self, commit: Commit):
        self.commit_hashes.append(commit)

    def rerun_cnt(self, job_name):
        if job_name in self.job_cnt:
            return self.job_cnt[job_name]
        else:
            return 0

    def update(self, job: Run):
        self.runs.append(job)

        if job.job_name in self.job_cnt:
            self.job_cnt[job.job_name] += 1
        else:
            self.job_cnt[job.job_name] = 0

        if job.status == "SUCCESS": 
            self.success_cnt += 1
        elif job.status == "ABORTED":
            self.abort_cnt += 1
        elif job.status == "FAILURE":
            self.fail_cnt += 1

    def log(self, indent="", show_commits=True, show_fail=False, color_pr=True, show_runs=False):
        print(indent + bcolors.BOLD + "Pull Request " + bcolors.ENDC, end="")

        if color_pr:
            prefix = bcolor[self.number % len(bcolor)]
        else:
            prefix = bcolors.WHITE
        print(prefix + "#" + str(self.number) + bcolors.ENDC, end=" ")  

        if len(self.commit_hashes) > 1 or show_commits == False:
            print(bcolors.OKCYAN + "total_job: " + bcolors.ENDC + str((self.success_cnt + self.fail_cnt + self.abort_cnt)), end=" ")
            print(bcolors.OKCYAN + "success_cnt: " + bcolors.ENDC + str(self.success_cnt), end=" ")
            print(bcolors.OKCYAN + "fail_cnt: " + bcolors.ENDC + str(self.fail_cnt), end=" ")
            print(bcolors.OKCYAN + "abort_cnt: " + bcolors.ENDC + str(self.abort_cnt), end=" ")
            print(bcolors.OKCYAN + "success_rate: " + bcolors.ENDC + '{:.1%}'.format(get_success_rate(self)), end=" ")
            print(bcolors.OKCYAN + "fail_rate: " + bcolors.ENDC + '{:.1%}'.format(get_fail_rate(self)), end=" ")
            print(bcolors.OKCYAN + "abort_rate: " + bcolors.ENDC + '{:.1%}'.format(get_abort_rate(self)), end=" ")        
        print()

        if show_runs:
            for run in sorted(self.runs, key=lambda x: x.time):
                run.log(begin=indent+"\t", show_fail_info=True, color_pullid=False)

        if show_commits:
            print(indent + self.repo, end=" ")
            print(bcolors.BOLD + self.branch + bcolors.ENDC, end=" ")
            print(bcolors.OKCYAN + "pr_title: " + bcolors.ENDC + ''.join(filter(lambda x: x in printable, self.title)))
            for commit in self.commit_hashes:
                print("", end="\t")
                commit.print_jobs(show_fail_info=True)
            print()
                
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
        self.rerun_cnt = 0
    
    def update(self, job: Run, pr: PR):
        if not pr.number in self.prs:
            self.prs[pr.number] = pr
        
        if not pr.number in self.local_prs:
            self.local_prs[pr.number] = PR(job)
        else:
            self.rerun_cnt += 1
        self.local_prs[pr.number].update(job)
        
        if job.status == "SUCCESS":
            self.success_cnt += 1
        elif job.status == "ABORTED":
            self.abort_cnt += 1
        elif job.status == "FAILURE":
            self.fail_cnt += 1

        self.total_cnt += 1
    def log(self):
        print(bcolors.BOLD + bcolors.FAIL +  "Job Name: " + bcolors.ENDC + bcolors.ENDC, end="")
        print(bcolors.WARNING + self.job_name + bcolors.ENDC, end=" ")  
        print(bcolors.OKCYAN + "runs_raw: " + bcolors.ENDC + str(self.total_cnt), end=" ")
        print("success: "  + bcolors.OKGREEN + str(self.success_cnt) + " " + '{:.1%}'.format(self.success_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")
        print("fail: " + bcolors.FAIL + str(self.fail_cnt) + " " + '{:.1%}'.format(self.fail_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")
        print("abort: " + bcolors.WARNING + str(self.abort_cnt) + " " + '{:.1%}'.format(self.abort_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")     
        print("rerun: " + bcolors.OKCYAN + str(self.rerun_cnt) + " " + '{:.1%}'.format(self.rerun_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")     
        print()


    def print_prs(self, no_success=False):
        print("  "+"-" * 150)
        print()
        print(bcolors.BOLD + bcolors.FAIL +  "  Job Name: " + bcolors.ENDC + bcolors.ENDC, end="")
        print(bcolors.WARNING + self.job_name + bcolors.ENDC, end=" ")  
        if self.total_cnt > 0:
            print(bcolors.OKCYAN + "runs_raw: " + bcolors.ENDC + str(self.total_cnt), end=" ")
            print("success: "  + bcolors.OKGREEN + str(self.success_cnt) + " " + '{:.1%}'.format(self.success_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")
            print("fail: " + bcolors.FAIL + str(self.fail_cnt) + " " + '{:.1%}'.format(self.fail_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")
            print("abort: " + bcolors.WARNING + str(self.abort_cnt) + " " + '{:.1%}'.format(self.abort_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")     
            print("rerun: " + bcolors.WARNING + str(self.rerun_cnt) + " " + '{:.1%}'.format(self.rerun_cnt / (self.total_cnt)) + bcolors.ENDC, end=" ")     

        print()
        if no_success:
            pr_list = list(filter(lambda x: x[1].fail_cnt > 0, self.local_prs.items()))
        else:
            pr_list = self.local_prs.items()

        for pr_num, pr in pr_list:
            pr.log(indent="\t", show_commits=False, show_runs=True, color_pr=False) # TODO , show_line=False, indent="    ")

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
            print(indent + "-" * 150)
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
            for fail_info_detail in fail_infos:
                fail_info = fail_info_detail[0]
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

def sec_to_hours(seconds):
    a=seconds//3600
    b=(seconds%3600)//60
    c=(seconds%3600)%60
    d="{:02d}:{:02d}:{:02d}".format(a, b, c)
    return d
    
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

def get_result(begin, end):
    return Result(get_runs_raw(begin, end) ,begin, end)

def job_list(job_map):
    print("-" * 150)
    print(bcolors.BOLD + bcolors.WHITE + "Jobs: " + bcolors.ENDC + bcolors.ENDC)
    for _, job in sorted(job_map.items(), key=lambda x: x[1].fail_cnt, reverse=True):
        job.print_prs(no_success=True)

def pr_list(pr_map):
    print("-" * 150)
    print(bcolors.BOLD + bcolors.WHITE + "PRs: " + bcolors.ENDC + bcolors.ENDC)
    for _, pr in sorted(pr_map.items(), key=lambda x: x[1].fail_cnt, reverse=True):
        pr.log(show_fail=True, color_pr=False, indent="  ")



if __name__ == "__main__":
    end_time = datetime.today()
    begin_time = end_time - timedelta(hours=6) 
    res = get_result(begin_time, end_time)
    pr_map = res.pr_map
    commit_hash_map = res.commit_hash_map
    job_map = res.job_map
    fail_info_map = res.fail_info_map
    run_list = res.run_list
    miss_cnt = res.miss_cnt

    # for _, fail in sorted(fail_info_map.items(), key=lambda x:len(x[1].run_list), reverse=True):
    #     fail.log()

    print(send(res))

    summary(begin_time, end_time, pr_map, commit_hash_map, job_map, run_list, miss_cnt)

    # for job_name, job in sorted(job_map.items(), key=lambda x: x[1].rerun_cnt, reverse=True):
    #     job.log()
    #     rerun_list = list(filter(lambda x: x[1].rerun_cnt(job_name) > 0, sorted(job.local_prs.items(), key=lambda x: x[1].rerun_cnt(job_name), reverse=True)))
    #     for pr_number, pr in rerun_list:
    #         print(WHITE("\tPull Request ") + pr.repo + " #" + str(pr.number) + CYAN(" rerun cnt: ") + str(pr.rerun_cnt(job_name)))
    #         for run in list(filter(lambda x: x.job_name == job_name, pr.runs)):
    #             run.log(begin="\t\t", show_fail_info=True)

    
            
            


    # summary(begin_time, end_time, pr_map, commit_hash_map, job_map, run_list, miss_cnt)
    # print()
    # job_list(job_map)
    
    # print()
    # pr_list(pr_map)
