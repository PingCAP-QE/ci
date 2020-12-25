-- commit run times per day.
drop view if exists commit_run_times;
create view commit_run_times AS SELECT repo, commit, t, MAX(c) AS max_cnt
FROM (
	SELECT repo, commit, date(time) AS t, job
		, COUNT(1) AS c
	FROM ci_data
	WHERE repo != ""
		AND commit != ""
		AND time >= DATE_SUB(curdate(), INTERVAL 30 DAY)
	GROUP BY repo, commit, job, date(time)
) t3
GROUP BY repo, commit, t
ORDER BY t DESC


--- rerun job and job_id
drop view if exists rerun_jobs;
CREATE VIEW rerun_jobs AS SELECT t1.job, t1.job_id, t1.time
FROM ci_data t1
	JOIN (
		SELECT *
		FROM commit_run_times
		WHERE max_cnt > 1
	) t2
	ON t1.repo = t2.repo
		AND t1.commit = t2.commit
WHERE t1.time >= DATE_SUB(curdate(), INTERVAL 30 DAY);