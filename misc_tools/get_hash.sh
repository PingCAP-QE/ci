#run like:  
# TOKEN=xxx sh get_hash.sh release-5.0 2>/dev/null

branch=$1
# Should check if tikv/importer's base branch is master, if not, remove it from repo list and create the tikv/importer's branch by hand
repo_list="pingcap/tidb pingcap/parser tikv/tikv tikv/pd pingcap/tics pingcap/br pingcap/tidb-binlog pingcap/tidb-lightning pingcap/tidb-tools pingcap/ticdc pingcap/dumpling pingcap/tiflash"

for repo in $repo_list;do
sha=$(curl -H "Authorization: token $TOKEN" https://api.github.com/repos/$repo/git/refs/heads/$branch | jq -r '.object.sha')
echo ${repo} ":" ${sha}
done