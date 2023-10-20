package com.eason.spark.connector

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.dstream.ReceiverInputDStream
import org.apache.spark.streaming.kinesis.{KinesisInitialPositions, KinesisInputDStream, SparkAWSCredentials}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.joda.time.DateTime

/**
  * Consumes messages from a Amazon Kinesis streams and does wordcount.
  *
  * This example spins up 1 Kinesis Receiver per shard for the given stream.
  * It then starts pulling from the last checkpointed sequence number of the given stream.
  *
  * Usage: KinesisWordCountASL <app-name> <stream-name> <endpoint-url> <region-name>
  *   <app-name> is the name of the consumer app, used to track the read data in DynamoDB
  *   <stream-name> name of the Kinesis stream (ie. mySparkStream)
  *   <endpoint-url> endpoint of the Kinesis service
  *     (e.g. https://kinesis.us-east-1.amazonaws.com)
  *
  *      # run the example
  *      $ SPARK_HOME/bin/run-example  streaming.KinesisWordCountASL myAppName  mySparkStream \
  *              https://kinesis.us-east-1.amazonaws.com
  *
  * There is a companion helper class called KinesisWordProducerASL which puts dummy data
  * onto the Kinesis stream.
  */
object KinesisCassandra {

  var cassandraHost = "127.0.0.1"

  val appName = "Kinesis2Cassandra"
  val streamName = "hqcStream"
  val regionName = "us-west-2"
  val endpointURL = "https://kinesis.%s.amazonaws.com".format(regionName)

  val batchInterval = Seconds(5)

  val ak = "ak"
  val sk = "sk"
  val sparkAwsCredentials = SparkAWSCredentials.builder.basicCredentials(ak, sk).build()
  val basicAWSCredentials = new BasicAWSCredentials(ak, sk)
  val credentials = new AWSStaticCredentialsProvider(basicAWSCredentials)

  def createSSC(): StreamingContext = {
    val sparkConfig = new SparkConf().setAppName(appName)
    sparkConfig.set("spark.cassandra.connection.host", cassandraHost)

    val ssc = new StreamingContext(sparkConfig, batchInterval)
    ssc
  }

  def createInputStreams(ssc: StreamingContext): IndexedSeq[ReceiverInputDStream[Array[Byte]]] = {
    val kinesisClient = AmazonKinesisClientBuilder.standard()
    kinesisClient.setCredentials(credentials)
    val endpointCfg = new AwsClientBuilder.EndpointConfiguration(endpointURL, regionName)
    kinesisClient.setEndpointConfiguration(endpointCfg)
    val numShards = kinesisClient.build().describeStream(streamName).getStreamDescription().getShards().size

    println("stream shard num is %s".format(numShards))

    val kinesisStreams = (0 until numShards).map { i =>
      KinesisInputDStream.builder
        .streamingContext(ssc)
        .streamName(streamName)
        .endpointUrl(endpointURL)
        .regionName(regionName)
//        .initialPosition(new Latest())
        .initialPosition(new KinesisInitialPositions.AtTimestamp(
        new DateTime().minusHours(5).toDate))
//        .initialPosition(new TrimHorizon())
        .checkpointAppName(appName)
        .checkpointInterval(batchInterval)
        .storageLevel(StorageLevel.MEMORY_AND_DISK_2)
        .kinesisCredentials(sparkAwsCredentials)
        .build()
    }
    kinesisStreams
  }

  def main(args: Array[String]): Unit = {
    import org.slf4j.{Logger, LoggerFactory}
    val logger: Logger =
      LoggerFactory.getLogger(getClass)
    logger.info("hqc start process ===")

    if (args.length == 1) {
      cassandraHost = args(0)
    }

    import com.datastax.spark.connector._
    val hostname = java.net.InetAddress.getLocalHost.getHostName
    val hostip = java.net.InetAddress.getLocalHost.getHostAddress

    val ssc = createSSC()
    val msgs = createInputStreams(ssc)
    val unionStreams = ssc.union(msgs)
    val jsons = unionStreams.map(byteArray => new String(byteArray))
    val records= jsons.map(ele => StringUtil.fromJson[StockTrade](ele))
    records.foreachRDD(rdd => {
      println("%s %s hqc rdd %s".format(hostname, hostip, rdd))
      println("%s %s hqc rdd count %s".format(hostname, hostip, rdd.count()))
      if (rdd.count() > 0) {
        rdd.saveToCassandra("monitor", "stock",
          SomeColumns("id", "tickerSymbol", "tradeType", "price", "quantity"))

//        rdd.foreach(ele => {
//          println("hqc ele %s".format(ele))
//          println("hqc ele %s".format(ele.getTickerSymbol))
//        })

      }
    })

    ssc.start()
    ssc.awaitTermination()
  }
}
