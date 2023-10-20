package com.eason.spark

//import jdk.internal.net.http.frame.DataFrame
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

object MovieCount {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder()
      .appName("MovieCount")
      .getOrCreate()

    val basicPath = "/tmp/data"
    val sourceCSV = "%s/ml-25m/%s".format(basicPath, args(0))
    val targetCSV = "%s/%s".format(basicPath, args(1))

//    val sourceCSV = args(0)
//    val targetCSV = args(1)
//    val partitionSize = args(2).toInt
    val partitionSize = 10

    val df: DataFrame = spark.read.
      option("header", true).csv(sourceCSV)

    println("hqc == partition size %d".format(df.rdd.getNumPartitions))

    val processStart = System.currentTimeMillis()

    import org.apache.spark.sql.functions._
    def myUdf = udf((array: Seq[String]) =>
      array.map(tag => tag.size).sum)
    val muDf = df.groupBy("movieId", "userId")
      .agg(myUdf(collect_list("tag")).as("tagSize"))

    val movieUserCountDf = muDf.select("movieId","tagSize")
    val targetDF = movieUserCountDf.coalesce(partitionSize)
    println("hqc == partition size %d".format(targetDF.rdd.getNumPartitions))


    targetDF.write.mode(SaveMode.Overwrite).option("header", "true").csv(targetCSV)

    val processEnd = System.currentTimeMillis()
    println("total cost time is %d".format(processEnd-processStart))

    Thread.sleep(20*60*1000)
  }

}
