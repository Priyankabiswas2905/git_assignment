#!/bin/sh

# variables that can be set
# DEBUG   : set to echo to print command and not execute
# PUSH    : set to push to push, anthing else not to push. If not set
#           the program will push if master or develop.
# PROJECT : the project to add to the image, default is NCSA

#DEBUG=echo

# make sure PROJECT ends with /
PROJECT=${PROJECT:-"willowspringdockhub"}
SUFFIX="-local"
# copy dist file to docker folder
JARFILE=$( /bin/ls -1rt target/scala-2.11/fence-*.jar 2>/dev/null | tail -1 )

if [ "$JARFILE" = "" ]; then
  echo "Running sbt assembly"
  sbt assembly
  JARFILE=$( /bin/ls -1rt target/scala-2.11/fence-*.jar 2>/dev/null | tail -1 )
  if [ "$JARFILE" = "" ]; then
    echo "not find: " $( basename ${ZIPFILE} .jar )
    exit -1
  fi
fi

${DEBUG} rm -rf docker/files
${DEBUG} mkdir -p docker/files
${DEBUG} cp entrypoint.sh docker/
${DEBUG} cp src/main/resources/application.conf docker/files
${DEBUG} cp target/scala-2.11/$( basename ${JARFILE} ) docker/files/


${DEBUG} docker build -t $PROJECT/fence$SUFFIX docker
