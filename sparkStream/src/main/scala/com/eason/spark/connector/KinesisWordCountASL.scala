package com.eason.spark.connector

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.kinesis.AmazonKinesisClient
import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.dstream.DStream.toPairDStreamFunctions
import org.apache.spark.streaming.kinesis.KinesisInitialPositions.Latest
import org.apache.spark.streaming.kinesis.KinesisInputDStream
import org.apache.spark.streaming.{Milliseconds, StreamingContext}

/**
  * Consumes messages from a Amazon Kinesis streams and does wordcount.
  *
  * This example spins up 1 Kinesis Receiver per shard for the given stream.
  * It then starts pulling from the last checkpointed sequence number of the given stream.
  *
  * Usage: KinesisWordCountASL <app-name> <stream-name> <endpoint-url> <region-name>
  *   <app-name> is the name of the consumer app, used to track the read data in DynamoDB
  *   <stream-name> name of the Kinesis stream (i.e. mySparkStream)
  *   <endpoint-url> endpoint of the Kinesis service
  *     (e.g. https://kinesis.us-east-1.amazonaws.com)
  *
  *
  * Example:
  *      # export AWS keys if necessary
  *      $ export AWS_ACCESS_KEY_ID=<your-access-key>
  *      $ export AWS_SECRET_ACCESS_KEY=<your-secret-key>
  *
  *      # run the example
  *      $ SPARK_HOME/bin/run-example  streaming.KinesisWordCountASL myAppName  mySparkStream \
  *              https://kinesis.us-east-1.amazonaws.com
  *
  * There is a companion helper class called KinesisWordProducerASL which puts dummy data
  * onto the Kinesis stream.
  *
  * This code uses the DefaultAWSCredentialsProviderChain to find credentials
  * in the following order:
  *    Environment Variables - AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY
  *    Java System Properties - aws.accessKeyId and aws.secretKey
  *    Credential profiles file - default location (~/.aws/credentials) shared by all AWS SDKs
  *    Instance profile credentials - delivered through the Amazon EC2 metadata service
  * For more information, see
  * http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/credentials.html
  *
  * See http://spark.apache.org/docs/latest/streaming-kinesis-integration.html for more details on
  * the Kinesis Spark Streaming integration.
  */
object KinesisWordCountASL extends Logging {
  def main(args: Array[String]): Unit = {
    // Check that all required args were passed in.
//    if (args.length != 3) {
//      System.err.println(
//        """
//          |Usage: KinesisWordCountASL <app-name> <stream-name> <endpoint-url>
//          |
//          |    <app-name> is the name of the consumer app, used to track the read data in DynamoDB
//          |    <stream-name> is the name of the Kinesis stream
//          |    <endpoint-url> is the endpoint of the Kinesis service
//          |                   (e.g. https://kinesis.us-east-1.amazonaws.com)
//          |
//          |Generate input data for Kinesis stream using the example KinesisWordProducerASL.
//          |See http://spark.apache.org/docs/latest/streaming-kinesis-integration.html for more
//          |details.
//        """.stripMargin)
//      System.exit(1)
//    }

//    StreamingExamples.setStreamingLogLevels()

    // Populate the appropriate variables from the given args
//    val Array(appName, streamName, endpointUrl) = args
    val appName = "hqcApp"
    val streamName = "hqcStream"
    val regionName = "us-west-2"
    val endpointUrl = "https://kinesis.%s.amazonaws.com".format(regionName)


    // Determine the number of shards from the stream using the low-level Kinesis Client
    // from the AWS Java SDK.
    val credentials = new DefaultAWSCredentialsProviderChain().getCredentials()
    require(credentials != null,
      "No AWS credentials found. Please specify credentials using one of the methods specified " +
        "in http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/credentials.html")
    val kinesisClient = new AmazonKinesisClient(credentials)
    kinesisClient.setEndpoint(endpointUrl)
    val numShards = kinesisClient.describeStream(streamName).getStreamDescription().getShards().size


    // In this example, we're going to create 1 Kinesis Receiver/input DStream for each shard.
    // This is not a necessity; if there are less receivers/DStreams than the number of shards,
    // then the shards will be automatically distributed among the receivers and each receiver
    // will receive data from multiple shards.
    val numStreams = numShards

    // Spark Streaming batch interval
    val batchInterval = Milliseconds(2000)

    // Kinesis checkpoint interval is the interval at which the DynamoDB is updated with information
    // on sequence number of records that have been received. Same as batchInterval for this
    // example.
    val kinesisCheckpointInterval = batchInterval

    // Get the region name from the endpoint URL to save Kinesis Client Library metadata in
    // DynamoDB of the same region as the Kinesis stream
//    val regionName = KinesisExampleUtils.getRegionNameByEndpoint(endpointUrl)

    // Setup the SparkConfig and StreamingContext
    val sparkConfig = new SparkConf().setAppName("KinesisWordCountASL")
    val ssc = new StreamingContext(sparkConfig, batchInterval)

    // Create the Kinesis DStreams
    val kinesisStreams = (0 until numStreams).map { i =>
      KinesisInputDStream.builder
        .streamingContext(ssc)
        .streamName(streamName)
        .endpointUrl(endpointUrl)
        .regionName(regionName)
        .initialPosition(new Latest())
        .checkpointAppName(appName)
        .checkpointInterval(kinesisCheckpointInterval)
        .storageLevel(StorageLevel.MEMORY_AND_DISK_2)
        .build()
    }

    // Union all the streams
    val unionStreams = ssc.union(kinesisStreams)

    // Convert each line of Array[Byte] to String, and split into words
    val words = unionStreams.flatMap(byteArray => new String(byteArray).split(" "))

    // Map each word to a (word, 1) tuple so we can reduce by key to count the words
    val wordCounts = words.map(word => (word, 1)).reduceByKey(_ + _)

    // Print the first 10 wordCounts
    wordCounts.print()

    // Start the streaming context and await termination
    ssc.start()
    ssc.awaitTermination()
  }
}
