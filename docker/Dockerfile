#Install fence server
FROM java:8-jre
MAINTAINER Bing Zhang <bing@illinois.edu>

#Expose some properties of the container
EXPOSE 8080

USER root

#Update the repository and install Redis Server
RUN apt-get update && apt-get install -y redis-server

#Add user
RUN useradd -s /bin/bash fence

#Copy the build file, this requires sbt dist to be run (will be owned by root)
COPY files /home/fence/

ENTRYPOINT ["/home/fence/entrypoint.sh"]
WORKDIR "/home/fence"
#USER fence
