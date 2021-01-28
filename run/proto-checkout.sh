#! /bin/sh

IN_REPO_PROTO_LOC="hapi-proto/src/main/proto"
read -p "WARNING: This will overwrite your $IN_REPO_PROTO_LOC dir! Continue (Y/N)? " ANSWER

if [ ! $ANSWER = "Y" ]; then
  exit 0
fi

PROTO_BASE=${2:-"../hedera-protobuf"}
GIT_BRANCH=${1:-"$(git rev-parse --abbrev-ref HEAD)"}
cd $PROTO_BASE
LISTED=$(git branch --list $GIT_BRANCH)
if [ -z "$LISTED" ]; then
  echo "- Checking out new branch '$GIT_BRANCH' from $PROTO_BASE"
  git checkout -b $GIT_BRANCH 
else 
  echo "- Checking out $GIT_BRANCH from $PROTO_BASE"
  git checkout $GIT_BRANCH 
fi
cd - > /dev/null
echo "- Overwriting contents of $IN_REPO_PROTO_LOC from branch"
rm -rf "$IN_REPO_PROTO_LOC/*"
cp -r $PROTO_BASE/src/main/proto/* $IN_REPO_PROTO_LOC
echo "- Done"
