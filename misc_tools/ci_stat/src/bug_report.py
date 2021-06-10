import ci_stat as stat
from ci_stat import get_result
from datetime import timedelta, date, datetime

end_time = datetime.today()
begin_time = end_time - timedelta(days=100) 
res = get_result(begin_time, end_time, ["tidb-unit-test-nightly"])

for run in filter(lambda x: x.case_mark, res.special_list):
    print(run.case)


