## Amazon Kinesis Video Streams Video Bridge

1. Install Environment
  - Install JDK 11 and register environment value to system
    * Install (Excute below commands one by one)
      $ sudo apt-get update
      $ sudo apt-get install openjdk-11-jdk
    * Register environment value to system
      # Turn on bashrc and insert java path
        $ vi ~/.bashrc
        $ export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
        $ export PATH=/usr/lib/jvm/java-11-openjdk-amd64/bin:$PATH
      # Register 
        $ source ~/.bashrc

  - Install Maven
    * Install (Excute below command)
      $ sudo apt install maven

  - Install AmazonCLI and configure authentication
    * Install (Excute below command)
      $ sudo apt install awscli
    * Configure authentication
      $ aws configure
      $ aws_access_key_id=AKIAY6TQDS7NUDMVKY4L
      $ aws_secret_access_key=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
      $ region=us-east-1
      $ output=json

  - Install Audio Server and Register
    * Install (Excute below command)
      $ sudo apt install pulseaudio
    * Register
      # Turn on bashrc
        $ vi ~/.bashrc
        $ pulseaudio --start
      # Register
        $ source ~/.bashrc

2. Download Source and Libarary
  * Download Source
    $ git clone https://eriksguesev@bitbucket.org/eriksguesev/kvs_bridge.git
  * Download Library
    $ https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp.git
  * After Download Edit run-java-app.sh(kvs_bridge/run-java-app.sh). In run-java-app.sh file change library path in your mind.
    $ export LD_LIBRARY_PATH=/opt/amazon-kinesis-video-streams-producer-sdk-cpp/open-source/local/lib:$LD_LIBRARY_PATH

3. Start Stream
  * Turn on MasterChannel on TestPage or Turn on Vue.js Project
  * After master channel started, excute below command
    $ ./run-java-app.sh AKIAY6TQDS7NUDMVKY4L DugcilM5xifwj/vPx0ycCAOp8R6FpHcM55fGDzXk us-east-1 eriksVstream eriks
  