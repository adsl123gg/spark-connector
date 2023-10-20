package com.eason.spark.connector

import java.io.IOException

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper

object StockTradeHqc {
  val JSON = new ObjectMapper with ScalaObjectMapper

  def fromJsonAsBytes(bytes: Array[Byte]): StockTrade = try
    JSON.readValue(bytes, classOf[StockTrade])
  catch {
    case e: IOException =>
      println(e)
      null
  }

  def fromJsonAsString(json: String): StockTrade = try
    JSON.readValue(json, JSON.constructType[StockTrade])
  catch {
    case e: IOException =>
      println(e)
      null
  }

  try
    JSON.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  catch {
    case e: IOException =>
      println(e)
      null
  }

}

case class StockTrade(tickerSymbol: String,
                 tradeType: String,
                 price: Double,
                 quantity: Long,
                 id: String) {

  def getTickerSymbol: String = tickerSymbol

  def getTradeType: String = tradeType

  def getPrice: Double = price

  def getQuantity: Long = quantity

  def getId: String = id

  def toJsonAsString: String = try
    StockTradeHqc.JSON.writeValueAsString(this)
  catch {
    case e: IOException =>
      println(e)
      null
  }

  def toJsonAsBytes: Array[Byte] = try
    StockTradeHqc.JSON.writeValueAsBytes(this)
  catch {
    case e: IOException =>
      println(e)
      null
  }

  override def toString: String = "ID %s: %s %d shares of %s for $%.02f".format(
    id, tradeType, quantity, tickerSymbol, price)
}

