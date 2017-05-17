#Install fence server
FROM java:8-jre
MAINTAINER Bing Zhang <bing@illinois.edu>

#Expose some properties of the container
EXPOSE 8080

WORKDIR "/home/fence"

#Copy the build file, this requires sbt dist to be run (will be owned by root)
COPY files /home/fence/

ENTRYPOINT ["/home/fence/entrypoint.sh"]


