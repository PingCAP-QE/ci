# First your should have $TOKEN
base_branch=master
new_branch=release-5.0
# Should check if tikv/importer's base branch is master, if not, remove it from repo list and create the tikv/importer's branch by hand
repo_list="pingcap/tidb pingcap/parser tikv/tikv tikv/pd pingcap/tics pingcap/br pingcap/tidb-binlog pingcap/tidb-tools pingcap/tiflow pingcap/dumpling pingcap/tiflash"

set -x
for repo in $repo_list;do
sha=$(curl -H "Authorization: token $TOKEN" https://api.github.com/repos/$repo/git/refs/heads/$base_branch | jq -r '.object.sha')

curl -X POST -H "Authorization: token $TOKEN" \
-d  "{\"ref\": \"refs/heads/$new_branch\",\"sha\": \"$sha\"}" "https://api.github.com/repos/$repo/git/refs"
done
