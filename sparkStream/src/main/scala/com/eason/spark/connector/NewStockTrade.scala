package com.eason.spark.connector

import java.io.IOException

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper

object NewStockTradeHqc {

  def main(args: Array[String]): Unit = {
    val json = "{\"symbol\":\"AAPL\",\"trade\":\"SELL\",\"price\":20.0,\"quantity\":12,\"id\":123}"
    val trade = StringUtil.fromJson[StockTrade](json)
    println(trade.getId)
  }

  val JSON = new ObjectMapper with ScalaObjectMapper

  def fromJsonAsBytes(bytes: Array[Byte]): NewStockTrade = try
    JSON.readValue(bytes, classOf[NewStockTrade])
  catch {
    case e: IOException =>
      println(e)
      null
  }

  def fromJsonAsString(json: String): NewStockTrade = try
    JSON.readValue(json, JSON.constructType[NewStockTrade])
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

case class NewStockTrade(symbol: String,
                         trade: String,
                 price: Double,
                 quantity: Long,
                 id: String) {

  def getSymbol: String = symbol

  def getTrade: String = trade

  def getPrice: Double = price

  def getQuantity: Long = quantity

  def getId: String = id

  def toJsonAsString: String = try
    NewStockTradeHqc.JSON.writeValueAsString(this)
  catch {
    case e: IOException =>
      println(e)
      null
  }

  def toJsonAsBytes: Array[Byte] = try
    NewStockTradeHqc.JSON.writeValueAsBytes(this)
  catch {
    case e: IOException =>
      println(e)
      null
  }

  var data = ""
  def setData(str: String): Unit = {
    data = str
  }

  override def toString: String = "ID %s: %s %d shares of %s for $%.02f".format(
    id, trade, quantity, symbol, price)
}

