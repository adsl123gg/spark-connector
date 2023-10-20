package com.eason.spark

import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

object WordCount {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setAppName("wc")
    val sc = new SparkContext(conf)

    sc.setLogLevel("ERROR")
    // 指定RDD分区为 3
    val rdd: RDD[String] = sc.textFile(args(0), 3)

    val word: RDD[String] = rdd.flatMap(_.split(" "))

    val words: RDD[(String, Int)] = word.map((_, 1))
    // shuffle算子
    val wordCount: RDD[(String, Int)] = words.reduceByKey(_ + _)

    // 第一个job
    wordCount.foreach(println)
    // 第二个job
    val count: Long = wordCount.map(_._2).count()

    println(count)

    Thread.sleep(1000* 300)
  }

}
