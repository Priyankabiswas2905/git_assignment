#!/bin/bash
set -e
echo "++++++++++++++++++++++++++++++++++"
echo "Fence Configuration Information"
echo $(cat /home/fence/application.conf)
echo ""
echo "+++++++++++++++++++++++++++++++++++"

echo 'redis-server --daemonize yes'
redis-server --daemonize yes

java -Dconfig.file=/home/fence/application.conf -jar /home/fence/fence-assembly-0.1.0.jar

