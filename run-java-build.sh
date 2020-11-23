#!/bin/bash
# App run of Java SDK application for sending video streams
# within the docker container
#

mvn package
# Create a temporary filename in /tmp directory

jar_files=${PWD}/build.jar
# Create classpath string of dependencies from the local repository to a file
mvn -Dmdep.outputFile=$jar_files dependency:build-classpath
export LD_LIBRARY_PATH=/opt/amazon-kinesis-video-streams-producer-sdk-cpp/open-source/local/lib:$LD_LIBRARY_PATH

