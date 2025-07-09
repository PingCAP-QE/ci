import re
import sys
import logging


from github import Github

fmt="%(levelname)s: %(asctime)s: %(filename)s:%(lineno)d * %(thread)d %(message)s"
datefmt="%m-%d %H:%M:%S"
logging.basicConfig(level=logging.INFO, format=fmt, datefmt=datefmt)
logger = logging.getLogger()


class repo_info(object):
    def __init__(self, repo_name, repo_addr, tag_githash='', version=''):
        self.repo_name = repo_name
        self.repo_addr = repo_addr
        self.version = version
        self.tag_githash = tag_githash


repo_list = [
    repo_info(
        repo_name='TiKV',
        repo_addr='tikv/tikv',
        tag_githash='',
        version='',),
    repo_info(
        repo_name='TiDB',
        repo_addr='pingcap/tidb',
        tag_githash='',
        version=''),
    repo_info(
        repo_name='PD',
        repo_addr='tikv/pd',
        tag_githash='',
        version=''),
    repo_info(
        repo_name='TiFlash',
        repo_addr='pingcap/tics',
        tag_githash='',
        version=''),
    repo_info(
        repo_name='BR',
        repo_addr='pingcap/br',
        tag_githash='',
        version=''),
    repo_info(
        repo_name='binlog',
        repo_addr='pingcap/tidb-binlog',
        tag_githash='',
        version=''),
    repo_info(
        repo_name='importer',
        repo_addr='tikv/importer',
        tag_githash='',
        version=''),
    repo_info(
        repo_name='tidb-tools',
        repo_addr='pingcap/tidb-tools',
        tag_githash='',
        version=''),
    repo_info(
        repo_name='ticdc',
        repo_addr='pingcap/tiflow',
        tag_githash='',
        version=''),
    repo_info(
        repo_name='dumpling',
        repo_addr='pingcap/dumpling',
        tag_githash='',
        version=''),
]



def get_repo_tags():
    for repo_obj in repo_list:
        repo_obj.tag_githash = get_githash(repo_obj.repo_addr)

def get_githash(repo):
    r = g.get_repo(repo).get_branch("release-5.0")
    return r.commit

def create_git_tag():
    for repo_info in repo_list:
        repo = g.get_repo(repo_info.repo_addr)

        name = repo_info.repo_name
        release_message = 'There is no release note for this version.'

        logger.info("creating release tag:%s %s %s",repo_info.repo_name, repo_info.version, repo_info.tag_githash)
        ret = repo.create_git_ref('refs/tags/{}'.format("v5.0.0-nightly"),repo_info.tag_githash.sha)
        logger.info("done creating release: %s", ret)


if __name__ == '__main__':
    token = sys.argv[1]
    g = Github(token) # personal token
    get_repo_tags()
    create_git_tag()
