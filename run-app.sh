#!/bin/bash
# App run of Java SDK application for sending video streams
# within the docker container
#
if [ "$#" != 5 ]; then
 echo " Usage: ./run-java-app.sh access_key secret_key region kvs_stream kvs_channel"
 exit
fi

ACCESS_KEY=$1
SECRET_KEY=$2
REGION=$3
KVS_STREAM=$4
KVS_CHANNEL=$5

mvn package
# Create a temporary filename in /tmp directory
jar_files=$(mktemp)
# Create classpath string of dependencies from the local repository to a file
mvn -Dmdep.outputFile=$jar_files dependency:build-classpath
cd ../amazon-kinesis-video-streams-producer-sdk-cpp
export LD_LIBRARY_PATH=${pwd}/open-source/local/lib:$LD_LIBRARY_PATH
cd -
classpath_values=$(cat $jar_files)
# Start the app
java -classpath target/kvs-bridge-1.0.0.jar:$classpath_values -Daws.accessKeyId=${ACCESS_KEY} -Daws.secretKey=${SECRET_KEY} -Daws.region=${REGION} -Dkvs-stream=${KVS_STREAM} -Dkvs-channel=${KVS_CHANNEL} -Djava.library.path=$(pwd)/src/main/resources/lib/ubuntu/ com.amazonaws.kinesisvideo.app.AppMain

