#Install fence server
FROM java:8-jre
MAINTAINER bing zhang <bing@illinois.edu>

# expose some properties of the container
EXPOSE 8080

USER root

# Update the repository and install Redis Server
RUN         apt-get update && apt-get install -y redis-server

# add user
RUN useradd -s /bin/bash fence

# copy the build file, this requires sbt dist to be run (will be owned by root)
COPY files entrypoint.sh /home/fence/

ENTRYPOINT ["/home/fence/entrypoint.sh"]

WORKDIR "/home/fence"

#USER fence

