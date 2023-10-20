package com.eason.spark.connector

import java.net.InetAddress

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.kinesis.model.Shard
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.kinesis.KinesisInitialPositions.Latest
import org.apache.spark.streaming.kinesis.{KinesisInputDStream, SparkAWSCredentials}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.util.LongAccumulator
import org.apache.spark.{SparkConf, SparkContext}
import org.joda.time.DateTime

object KinesisCheckPoint {

  @volatile private var instance: LongAccumulator = null

  def getInstance(sc: SparkContext): LongAccumulator = {
    if (instance == null) {
      synchronized {
        if (instance == null) {
          instance = sc.longAccumulator("DroppedWordsCounter")
        }
      }
    }
    instance
  }

  def main(args: Array[String]): Unit = {
    val appName = "hqc_KinesisCheckPoint"
    val streamName = "hqcStream"
    val regionName = "us-west-2"
    val endpointURL = "https://kinesis.%s.amazonaws.com".format(regionName)

    val batchInterval = Seconds(5)
    val checkpointInterval = Seconds(1)

    val credentialsProvider = new ProfileCredentialsProvider("Exp-Test_eu-central-1")
    val awsCredentials = credentialsProvider.getCredentials
    val sparkCredentials = SparkAWSCredentials.builder.basicCredentials(
      awsCredentials.getAWSAccessKeyId, awsCredentials.getAWSSecretKey).build()

    val shardNum = getShardNumber(credentialsProvider, regionName, streamName)
    val cores = 2 * shardNum
    val sparkConfig = new SparkConf().setMaster(s"local[$cores]").setAppName(appName)

    val ssc = new StreamingContext(sparkConfig, batchInterval)
    val kinesisStreams = (0 until shardNum).map { i =>
      KinesisInputDStream.builder
        .streamingContext(ssc)
        .streamName(streamName)
        .endpointUrl(endpointURL)
        .regionName(regionName)
        .initialPosition(new Latest())
        .checkpointAppName(appName)
        .checkpointInterval(checkpointInterval)
        .storageLevel(StorageLevel.MEMORY_AND_DISK)
        .kinesisCredentials(sparkCredentials)
        .build()
    }
    val unionStreams = ssc.union(kinesisStreams)
    val wordsStream = unionStreams.map(convert).map((_, 1))

    val wordCount = wordsStream.reduceByKey(_ + _)
    wordCount.print()

    val hostName = InetAddress.getLocalHost.getHostName
    wordCount.foreachRDD(rdd => {
      val wordsCounter = getInstance(rdd.sparkContext)
      wordsCounter.add(rdd.count())
      println("%s %s %d".format(DateTime.now(), hostName, wordsCounter.sum))
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

  def convert(byteArray: Array[Byte]): String = {
    val ele = new String(byteArray)
    val trade = StringUtil.fromJson[StockTrade](ele)
//    println("%s ===========".format(trade.getId))
    trade.getTickerSymbol
  }
}
