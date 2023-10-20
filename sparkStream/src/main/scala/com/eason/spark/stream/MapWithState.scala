package com.eason.spark.stream

import java.net.InetAddress

import org.apache.hadoop.conf.Configuration
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.streaming.dstream.{DStream, MapWithStateDStream, ReceiverInputDStream}
import org.apache.spark.streaming.{Seconds, State, StateSpec, StreamingContext}
import org.apache.spark.util.LongAccumulator

object MapWithState {

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
    var sourceHost = "10.112.116.247"
    var sourcePort = 9999
    for(arg <- args) {
      if (arg.contains("sourceHost=")) {
        sourceHost = arg.replaceAll("sourceHost=", "").trim
      }
      if (arg.contains("sourcePort=")) {
        sourcePort = arg.replaceAll("sourcePort=", "").trim.toInt
      }
    }

    println("sourceHost is %s".format(sourceHost))

    val conf = new SparkConf().setMaster("local[4]").setAppName("MapWithState")
//    val conf = new SparkConf().setAppName("MapWithState")

    val hadoopConf: Configuration = new Configuration()
    val ck_dir = "/tmp/checkpoint"
    val ssc = StreamingContext.getOrCreate(
      ck_dir,
      () => {
        new StreamingContext(conf, Seconds(5))
      }, hadoopConf
    )

    val hostName = InetAddress.getLocalHost.getHostName
    ssc.checkpoint(ck_dir)

    val line: ReceiverInputDStream[String] = ssc.socketTextStream(sourceHost, sourcePort)
    val wordsStream: DStream[(String, Int)] = line.flatMap(_.split(" ")).map((_, 1))

    val wordCount: MapWithStateDStream[String, Int, Int, Any] =
      wordsStream.mapWithState(StateSpec.function(func).timeout(Seconds(300)))

    wordCount.print()
    wordCount.foreachRDD(rdd => {
      val wordsCounter = getInstance(rdd.sparkContext)
      wordsCounter.add(rdd.count())

      println("%s %d".format(hostName, rdd.count()))
    })

    ssc.start()
    ssc.awaitTermination()

  }

  val func = (word: String, option: Option[Int], state: State[Int]) => {
    val hostName = InetAddress.getLocalHost.getHostName
    println("%s word:%s option:%s state:%s".format(hostName, word, option.getOrElse(-1), state.getOption().getOrElse(-1)))
    if(state.isTimingOut()) {
      println(word + " is timeout")
    } else {
      val sum = option.getOrElse(0) + state.getOption().getOrElse(0)
      val wordFreq = (word, sum)
      state.update(sum)
      Thread.sleep(1000)
      wordFreq
    }
  }
}