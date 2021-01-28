#!/usr/bin/env bash

. ${REPO}/.circleci/scripts/utils.sh

if [ -f "$PROTO_DIR" ]; then
  ci_echo "Protobufs repo already cloned" \
    | tee -a ${REPO}/test-clients/output/hapi-client.log
else
  cd /
  GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" \
    git clone git@github.com:hashgraph${PROTO_DIR}.git \
        | tee -a ${REPO}/test-clients/output/hapi-client.log
  cd ${PROTO_DIR}
  LISTED=$(git remote show origin | grep $CIRCLE_BRANCH)
  ci_echo "LISTED: |$LISTED|"
  if [ ! -z "$LISTED" ]; then
    git checkout $CIRCLE_BRANCH
  fi

  set +x
  ls -l $PROTO_DIR/src/main/proto/*.proto
  ls -l ${REPO}/hapi-proto/src/main/proto/
  mv $PROTO_DIR/src/main/proto/*.proto ${REPO}/hapi-proto/src/main/proto/
fi
