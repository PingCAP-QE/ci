import re
import logging

from github import Github

fmt="%(levelname)s: %(asctime)s: %(filename)s:%(lineno)d * %(thread)d %(message)s"
datefmt="%m-%d %H:%M:%S"
logging.basicConfig(level=logging.INFO, format=fmt, datefmt=datefmt)
logger = logging.getLogger()

g = Github("XXX")


class repo_info(object):
    def __init__(self, repo_name, repo_addr, release_name_prefix, tag_githash='', version='', possible_title=[]):
        self.repo_name = repo_name
        self.repo_addr = repo_addr
        self.release_name_prefix = release_name_prefix
        self.version = version
        self.tag_githash = tag_githash
        self.possible_title = possible_title

    def release_name(self):
        return self.release_name_prefix + self.version

    def __str__(self):
        return "name: [%s], repo: [%s], release_name: [%s], tag_githash: [%s]" % (self.repo_name, self.repo_addr, self.release_name(), self.tag_githash)


repo_list = [
    repo_info(
        repo_name='TiKV',
        repo_addr='tikv/tikv',
        release_name_prefix='tikv-server ',
        tag_githash='',
        version='',
        possible_title=['TiKV']),
    repo_info(
        repo_name='TiDB',
        repo_addr='pingcap/tidb',
        release_name_prefix='tidb-server ',
        tag_githash='',
        version='',
        possible_title=['TiDB']),
    repo_info(
        repo_name='PD',
        repo_addr='tikv/pd',
        release_name_prefix='pd-server ',
        tag_githash='',
        version='',
        possible_title=['PD']),
    repo_info(
        repo_name='TiFlash',
        repo_addr='pingcap/tics',
        release_name_prefix='TiFlash ',
        tag_githash='',
        version='',
        possible_title=['TiFlash']),
    repo_info(
        repo_name='BR',
        repo_addr='pingcap/br',
        release_name_prefix='br ',
        tag_githash='',
        version='',
        possible_title=['Backup & Restore (BR)', 'Backup and Restore (BR)']),
    repo_info(
        repo_name='binlog',
        repo_addr='pingcap/tidb-binlog',
        release_name_prefix='',
        tag_githash='',
        version='',
        possible_title=['Binlog', 'TiDB Binlog']),
    repo_info(
        repo_name='lightning',
        repo_addr='pingcap/tidb-lightning',
        release_name_prefix='',
        tag_githash='',
        version='',
        possible_title=['TiDB Lightning']),
    repo_info(
        repo_name='importer',
        repo_addr='tikv/importer',
        release_name_prefix='',
        tag_githash='',
        version='',
        possible_title=['Importer']),
    repo_info(
        repo_name='tidb-tools',
        repo_addr='pingcap/tidb-tools',
        release_name_prefix='',
        tag_githash='',
        version='',
        possible_title=[]),
    repo_info(
        repo_name='ticdc',
        repo_addr='pingcap/ticdc',
        release_name_prefix='ticdc ',
        tag_githash='',
        version='',
        possible_title=['TiCDC']),
    repo_info(
        repo_name='dumpling',
        repo_addr='pingcap/dumpling',
        release_name_prefix='',
        tag_githash='',
        version='',
        possible_title=['Dumpling']),
]

def matched(line, repo_obj):
    for x in re.split(r'[\s:：]+', line.lower()):
        if repo_obj.repo_name.lower() == x:
            return True
    return False


def get_version(prm_issue_id):
    repo = g.get_repo('pingcap/tidb-prm')
    issue = repo.get_issue(prm_issue_id)

    version = re.search("v\d+.\d+.\d+", issue.title)
    if version is None:
        raise Exception('Cannot parse version from issue title: "%s"')
    else:
        version = version.group(0)
        logger.debug('Parsed version: "%s"' % version)

    for repo in repo_list:
        repo.version = version


def get_repo_tags(prm_issue_id):
    repo = g.get_repo('pingcap/tidb-prm')
    issue = repo.get_issue(prm_issue_id)

    bodyhash = re.search('(4\. hash[\s\S]*)5\. Documents update', issue.body).group(1)

    for line in bodyhash.split('\r\n'):
        m = re.search('.+?([0-9a-z]+)$', line)
        if m is None:
            m = re.search('.+?([0-9a-z]+)[ （(]', line)
        if m is not None:
            githash = m[1]
            logger.debug('Parsed githash [%s] from line: "%s"' % (githash, line))
            for repo_obj in repo_list:
                if matched(line, repo_obj):
                    repo_obj.tag_githash = githash
                    logger.debug('Set githash [%s] for repo: "%s"' % (githash, repo_obj.repo_name))
                    break


def create_git_tag():
    for repo_info in repo_list:
        repo = g.get_repo(repo_info.repo_addr)

        name = repo_info.repo_name
        release_message = 'There is no release note for this version.'
        try:
            release_message=open('notes/%s.md' % name, 'r').read()
        except:
            logger.warn('Cannot get release message from "%s"' % name.lower() + '.note')

        logger.info("creating release tag: %s", repo_list)
        ret = repo.create_git_tag_and_release(
                tag = repo_info.version,
                tag_message = '',
                release_name = repo_info.release_name(),
                release_message = release_message,
                object = repo_info.tag_githash,
                type = 'commit',
                draft = False
                )
        logger.info("done creating release: %s", ret)


def gen_release_notes(prm_issue_id):

    def is_release_note_file(file):
        return re.search('release-\d+.\d+.\d+.md', file.filename) is not None

    def parse_part(line):
        if line.startswith('## '):
            return line[3:]
        return ''

    def parse_component(line):
        line = line.lstrip(' ')
        if line.startswith('+ '):
            return line[2:].lower()
        return ''

    def parse_release_note(line):
        line = line.lstrip(' ')
        if line.startswith('- '):
            return line[2:]
        if line.startswith('* '):
            return line[2:]
        return ''

    repo = g.get_repo('pingcap/tidb-prm')
    issue = repo.get_issue(prm_issue_id)

    body = issue.body
    res = re.search('https://github.com/pingcap/docs/pull/(\d+)', body)
    doc_pull_id = 0
    try:
        doc_pull_id = int(res[1])
    except Exception as e:
        raise Exception('Cannot parse doc pull ID from issue body: %r', e)
    logger.info("Parsed doc pull ID: %d" % doc_pull_id)

    repo_doc = g.get_repo('pingcap/docs')
    pull = repo_doc.get_pull(doc_pull_id)
    files = list(filter(is_release_note_file, pull.get_files()))
    if len(files) != 1:
        name_list = "\n".join(map(lambda x: x.filename, pull.get_files()))
        raise Exception("Cannot get a proper release note file from pull request files:\n" + name_list)
    patch_notes = files[0].raw_data['patch']
    lines = patch_notes.split('\n')

    content = {}
    part = ''
    component = ''
    for line in lines:
        line = line[1:]  # remove the prefix '+'
        tmp = parse_part(line)
        if tmp != '':
            part = tmp

        tmp = parse_component(line)
        if tmp != '':
            component = tmp

        tmp = parse_release_note(line)
        if tmp != '' and part != '' and component != '':
            if component not in content:
                content[component] = {}

            if part not in content[component]:
                content[component][part] = []

            content[component][part].append(tmp)

    for repo in repo_list:
        for component_title in repo.possible_title:
            lower_name = component_title.lower()
            if lower_name in content:
                parts = content[lower_name]
                with open('notes/%s.md' % repo.repo_name, 'w') as fout:
                    for part, notes in parts.items():
                        fout.write("## " + part + '\n')
                        fout.write('\n')
                        for note in notes:
                            fout.write('* ' + note + '\n')
                        fout.write('\n')


if __name__ == '__main__':
    g = Github("XXX") # personal token 
    prm_id = 38
    get_version(prm_id)
    get_repo_tags(prm_id)
    gen_release_notes(prm_id)
    for r in repo_list:
        logging.info(r)

    g = Github("XXX") # bot token
    create_git_tag()
