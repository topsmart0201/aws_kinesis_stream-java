FROM ubuntu:18.04
RUN apt-get update
RUN apt-get install -y git && \
    apt-get install -y vim  && \
    apt-get install -y curl && \
    apt-get install -y xz-utils && \
    apt-get install -y byacc  && \
    apt-get install -y g++ && \
    apt-get install -y python2.7 && \
    apt-get install -y pkg-config && \
    apt-get install -y cmake && \
    apt-get install -y maven && \
    apt-get install -y openjdk-11-jdk && \
    apt-get install -y m4
RUN DEBIAN_FRONTEND="noninteractive" apt-get -y install tzdata
RUN apt-get install -y pkg-config
RUN apt-get install -y awscli
RUN rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64/
WORKDIR /opt
# Checkout latest Kinesis Video Streams Producer SDK (CPP)
RUN git clone https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp.git
# Build the Producer SDK (CPP)

WORKDIR /opt/amazon-kinesis-video-streams-producer-sdk-cpp/
RUN git submodule update --init
RUN mkdir -p /opt/amazon-kinesis-video-streams-producer-sdk-cpp/build
WORKDIR /opt/amazon-kinesis-video-streams-producer-sdk-cpp/build
RUN cmake .. -DBUILD_JNI=TRUE
RUN make

WORKDIR /opt/
# COPY SOURCE
RUN mkdir -p /opt/kvs_bridge
COPY . /opt/kvs_bridge
WORKDIR /opt/kvs_bridge

RUN mvn package

ENV AWS_KEY_ID=
ENV AWS_SECRET=
ENV REGION=us-east-1
ENV KVS_STREAM=
ENV KVS_CHANNEL=
# Start the application to send video streams
COPY run-java-app.sh /opt/kvs_bridge
RUN chmod a+x /opt/kvs_bridge/run-java-app.sh

