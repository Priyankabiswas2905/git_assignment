#!/bin/bash

USERNAME=""
PASSWORD=""
TESTS=10

if [ "$1" == "DEV" ]; then
  SERVER="https://bd-api-dev.ncsa.illinois.edu"
  OUT="dev"
else
  SERVER="https://bd-api.ncsa.illinois.edu"
  OUT="prod"
fi

DATE="$(date +"%Y-%m-%dT%H:%M:%S")"
XML="/tmp/results-${OUT}.xml"
LOG="/tmp/results-${OUT}.txt"

cd src/test/python

#Manual test
pytest -n ${TESTS} --host ${SERVER} --username ${USERNAME} --password ${PASSWORD} --junitxml=${XML}

#Hourly tests
#git pull &> ${LOG}
#rsync -a ../../main/web/tests/ /usr/share/nginx/html >> ${LOG}
#pytest -n ${TESTS} --host ${SERVER} --username ${USERNAME} --password ${PASSWORD} --junitxml=${XML} >> ${LOG}
#python post_results.py --junitxml=${XML} --mailserver=localhost --mongo_host=mongo.ncsa.illinois.edu --mongo_db=browndog --mongo_collection=test_results --server ${OUT} --watchers watchers-${OUT}.yml --url http://bd-test.ncsa.illinois.edu >> ${LOG}
