package com.eason.spark.stream

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.kinesis.model.Shard
import com.amazonaws.services.s3.model.Region
import com.datastax.spark.connector.rdd.ReadConf
import com.eason.spark.connector.KinesisCassandra.cassandraHost
import com.eason.spark.connector.{NewStockTrade, StringUtil}
import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.kinesis.{KinesisInitialPositions, KinesisInputDStream, SparkAWSCredentials}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.joda.time.DateTime

import scala.util.Random


object StreamLimit {

  def main(args: Array[String]): Unit = {
    val appName = "KinesisStreamLimit"
    val streamName = "hqcStream"
    val regionName = Region.US_West_2.toString
    val endpointURL = "https://kinesis.%s.amazonaws.com".format(regionName)

    var cassandraHost = "10.196.242.112"
    if (args.length > 0) {
      cassandraHost = args(0)
    }

    val batchInterval = Seconds(10)
    val checkpointInterval = Seconds(1)

    val credentialsProvider = new ProfileCredentialsProvider("Exp-Test_eu-central-1")
    val awsCredentials = credentialsProvider.getCredentials
    val sparkCredentials = SparkAWSCredentials.builder.basicCredentials(
      awsCredentials.getAWSAccessKeyId, awsCredentials.getAWSSecretKey).build()

    val shardNum = getShardNumber(credentialsProvider, regionName, streamName)
    val cores = shardNum * 2
//    val sparkConfig = new SparkConf().setMaster(s"local[$cores]").setAppName(appName)
    val sparkConfig = new SparkConf().setAppName(appName)

    sparkConfig.set("spark.cassandra.input.metrics", "true")
    sparkConfig.set("spark.cassandra.output.metrics", "true")

    sparkConfig.set("spark.streaming.receiver.maxRate", "100")
//    sparkConfig.set("spark.streaming.backpressure.initialRate", "10")
//    sparkConfig.set("spark.streaming.backpressure.enabled", "true")

    sparkConfig.set("spark.cassandra.connection.host", cassandraHost)
    sparkConfig.set("spark.cassandra.auth.username", "cassandra")
    sparkConfig.set("spark.cassandra.auth.password", "cassandra")
//    sparkConfig.set("spark.cassandra.output.batch.size.rows", "1")
    sparkConfig.set("spark.cassandra.output.throughput_mb_per_sec", "0.001")

    val ssc = new StreamingContext(sparkConfig, batchInterval)
    val instance = ssc.sparkContext.longAccumulator("DroppedWordsCounter")

    val kinesisStreams = (0 until shardNum).map { i =>
      KinesisInputDStream.builder
        .streamingContext(ssc)
        .streamName(streamName)
        .endpointUrl(endpointURL)
        .regionName(regionName)
        .initialPosition(new KinesisInitialPositions.AtTimestamp(
          new DateTime().minusHours(5).toDate))
//        .initialPosition(new Latest())
        .checkpointAppName(appName)
        .checkpointInterval(checkpointInterval)
        .storageLevel(StorageLevel.MEMORY_AND_DISK)
        .kinesisCredentials(sparkCredentials)
        .build()
    }
    val unionStreams = ssc.union(kinesisStreams)
    val wordsStream = unionStreams.map(convert)

    import com.datastax.spark.connector._
    wordsStream.foreachRDD(rdd => {
      if (rdd.count() > 0) {
        println(rdd.map(trade => trade.getId).max())
        instance.add(rdd.count())

        rdd.saveToCassandra("monitor", "hqc",
          SomeColumns("id", "symbol", "trade", "price", "quantity", "data"))
        println("%s %s %s %s".format(rdd.count(), rdd.getNumPartitions, DateTime.now(), instance.sum))
      }

    })

    ssc.start()
    ssc.awaitTermination()
  }

  def getShardNumber(credentials: AWSCredentialsProvider, regionName: String,
                     streamName: String): Int = {
    val kinesisClientBuilder = AmazonKinesisClientBuilder.standard()
    kinesisClientBuilder.setCredentials(credentials)
    kinesisClientBuilder.setRegion(regionName)
    val desc = kinesisClientBuilder.build().describeStream(streamName)
      .getStreamDescription()

    val shards = desc.getShards()
    var numShards = 0
    for(shard <- shards.toArray()) {
      if(shard.asInstanceOf[Shard].getSequenceNumberRange().getEndingSequenceNumber() == null) {
        numShards += 1
      }
    }

    println("stream active shard num is %s".format(numShards))
    val arn = desc.getStreamARN
    println("stream arn is %s".format(arn))
    numShards
  }

  def convert(byteArray: Array[Byte]): NewStockTrade = {
    val trade = StringUtil.fromJson[NewStockTrade](new String(byteArray))
    trade.setData(Random.nextString(1024))
    trade
  }

}

