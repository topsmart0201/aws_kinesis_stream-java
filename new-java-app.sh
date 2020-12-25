#!/bin/bash
# App run of Java SDK application for sending video streams
# within the docker container
#

if [ "$#" != 8 ]; then
	 echo " Usage: ./new-java-app.sh access_key secret_key region aws_sqs"
	  exit
  fi

  echo "111111111111111111111111111111111111111111"
  aws configure set aws_access_key_id $1
  aws configure set aws_secret_access_key $2
  aws configure set region $3

  ACCESS_KEY=$1
  SECRET_KEY=$2
  REGION=$3
  QUEUE=$4
  CHANNEL=$5
  STREAM=$6
  SESSION=$7
  EMAIL=$8

  jar_files=${PWD}/build.jar
  classpath_values=$(cat $jar_files)
  # Start the app
  #java -classpath target/kvs-bridge-1.0.0.jar:$classpath_values -Daws.accessKeyId=${ACCESS_KEY} -Daws.secretKey=${SECRET_KEY} -Daws.region=${REGION} -Dkvs-stream=${KVS_STREAM} -Dkvs-channel=${KVS_Channel} -Djava.library.path=/opt/kvs_bridge/src/main/resources/lib/ubuntu/ com.amazonaws.kinesisvideo.app.AppMain
  java -classpath target/kvs-bridge1-1.0.0.jar:$classpath_values -Daws.accessKeyId=${ACCESS_KEY} -Daws.secretKey=${SECRET_KEY} -Daws.region=${REGION} -Daws.sqs=${QUEUE} -Daws.channel=${CHANNEL} -Daws.stream=${STREAM} -Daws.session=${SESSION} -Daws.email=${EMAIL} -Djava.library.path=${PWD}/src/main/resources/lib/ubuntu/ com.amazonaws.kinesisvideo.app.AppMain

