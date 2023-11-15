

http://fileserver.pingcap.net/download/cicd/daily-cache-code/src-tics.tar.gz

git reset --hard
git clean -ffdx
git status
git pull origin master
git pull
git submodule update --init --recursive
git status
git show --oneline -s