#run like:
# TOKEN=xxx sh get_hash.sh release-5.0 2>/dev/null

branch=$1

repo_list="pingcap/tidb tikv/tikv tikv/pd pingcap/tics pingcap/br pingcap/tidb-binlog pingcap/tidb-tools pingcap/tiflow tikv/importer"

for repo in $repo_list;do
sha=$(curl -H "Authorization: token $TOKEN" https://api.github.com/repos/$repo/git/refs/heads/$branch | jq -r '.object.sha')
echo ${repo} ":" ${sha}
done

master_base_repo_list="pingcap/dumpling"
branch="master"
for repo in $master_base_repo_list;do
sha=$(curl -H "Authorization: token $TOKEN" https://api.github.com/repos/$repo/git/refs/heads/$branch | jq -r '.object.sha')
echo ${repo} ":" ${sha}
done
