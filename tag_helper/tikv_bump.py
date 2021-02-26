import git
import logging
import re

from github import Github

logger = logging.getLogger()

major_version = ""
minor_version = ""
patch_version = ""


def parse_version(version_string):
    version_string = version_string.rstrip().strip('"')
    if version_string[0] == 'v':
        version_string = version_string[1:]
    major_version, minor_version, patch_version = [int(x) for x in version_string.split(".")]
    return major_version, minor_version, patch_version


def handle_cargo_toml():
    import toml
    parsed_toml = toml.load('tikv/Cargo.toml')
    prev_major, prev_minor, prev_patch = parse_version(parsed_toml['package']['version'])

    assert patch_version == prev_patch + 1
    parsed_toml['package']['version'] = "%d.%d.%d" % (major_version, minor_version, patch_version)

    with open('tikv/Cargo.toml', 'w') as fout:
        toml.dump(parsed_toml, fout)


def handle_internal(filename, pattern):
    lines = []
    in_package = False
    name = ""
    with open(filename, 'r') as fin:
        for line in fin.readlines():
            match = re.search(pattern, line)
            if match:
                if match.group(1) == 'package':
                    in_package = True
                else:
                    in_package = False
                name = ""

            if in_package:
                match = re.search('name = "([\S\s]+)"', line)
                if match:
                    name = match.group(1)
                if name == "tikv" and line[:10] == "version = ":
                    prev_major, prev_minor, prev_patch = parse_version(line[10:])
                    assert major_version == prev_major, "major version: %s, previous major version: %s" % (major_version, prev_major)
                    assert minor_version == prev_minor
                    assert patch_version == prev_patch + 1

                    line = 'version = "%d.%d.%d"\n' % (major_version, minor_version, patch_version)

            lines.append(line)

    with open(filename, 'w') as fout:
        for line in lines:
            fout.write(line)


def handle_cargo_toml_manually():
    handle_internal('tikv/Cargo.toml', '\[([\s\S]+)\]')


def handle_cargo_lock_manually():
    handle_internal('tikv/Cargo.lock', '\[\[([\s\S]+)\]\]')


def handle_change_log(release_notes):
    appended = False
    lines = []
    with open('tikv/CHANGELOG.md', 'r') as fin:
        for line in fin.readlines():
            if not appended and line.startswith('##'):
                lines = lines + release_notes
                appended = True
            lines.append(line)

    with open('tikv/CHANGELOG.md', 'w') as fout:
        for line in lines:
            fout.write(line)


def read_notes(note_filename):
    import datetime
    today = datetime.date.today()
    formatted_today = today.strftime('%Y-%m-%d')

    lines = []
    lines.append('## [%d.%d.%d] - %s\n' % (major_version, minor_version, patch_version, formatted_today))
    lines.append('\n')
    with open(note_filename, 'r') as fout:
        for line in fout.readlines():
            if line.startswith('##'):
                lines.append('+' + line[2:])
            elif line.startswith('*'):
                lines.append(' +' + line[1:])
            # ignore blank lines
    lines.append('\n')
    return lines


def push_to_my_repo(repo, version):
    if repo is not None:
        branch_name = "bump_" + version
        current = repo.create_head(branch_name)
        current.checkout()
        if repo.index.diff(None):
            repo.git.add(A=True)
            repo.git.commit(m='*: Bump version ' + version)
            repo.git.push('--set-upstream', 'mine', current)
            print('git push done')


def create_pull_request(version):
    g = Github("XXX") # my github token
    repo = g.get_repo('tikv/tikv')
    repo.create_pull(
            title="*: Bump version " + version,
            head="bb7133/bump_" + version,
            base="release-4.0",
            body="This PR is used to bump TiKV version to " + version + ".",
            maintainer_can_modify=True,
            draft=False)


def bump_version(version, note_filename):
    global major_version, minor_version, patch_version
    major_version, minor_version, patch_version = parse_version(version)

    branch_path = "master"
    if major_version == 5:
        branch_path = "release-5.0-rc"
    if major_version == 4:
        branch_path = "release-4.0"
    elif major_version == 3:
        branch_path = "release-3.0"

    repo = git.Repo.init("tikv")
    # remote = repo.remote()

    # switch the branch
    repo.git.checkout(branch_path, force=True)
    g = git.cmd.Git("tikv")
    g.pull("origin")

    release_notes = read_notes(note_filename)
    print(''.join(release_notes))

    handle_cargo_toml_manually()
    handle_cargo_lock_manually()
    handle_change_log(release_notes)

    push_to_my_repo(repo, version)
    create_pull_request(version)

if __name__ == '__main__':
    bump_version('v4.0.10', 'notes/TiKV.md')
