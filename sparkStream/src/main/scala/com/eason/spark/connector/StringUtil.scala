package com.eason.spark.connector

import java.util.Base64

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import net.liftweb.json.JsonAST._

object StringUtil {
  def isNullOrEmpty(s: String): Boolean = s == null || s.isEmpty

  private val mapper = new ObjectMapper() with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  def decodeString(s: String): Array[Byte] = Base64.getDecoder.decode(s)

  def encodeString(s: Array[Byte]): String =
    new String(Base64.getEncoder.encode(s))

  def toJson[T](t: T): String = mapper.writeValueAsString(t)

  def fromJson[T: Manifest](json: String): T =
    mapper.readValue(json, mapper.constructType[T])

  def isJsonEleEmpty(jval: JValue): Boolean = {
    jval match {
      case JNothing      => true
      case JNull         => true
      case array: JArray => array.children.isEmpty
      case str: JString  => StringUtil.isNullOrEmpty(str.s)
      case _             => false
    }
  }

  def abbreviateMiddle(
      str: String,
      keepStartLength: Int,
      keepEndLength: Int,
      middleMark: String = "*.*"
  ): String = {
    if (isNullOrEmpty(str)) {
      return ""
    }
    if (keepStartLength < 0 || keepEndLength < 0) {
      throw new IllegalArgumentException(
        "keepStartLength and keepEndLength should large than or equal to 0"
      )
    }
    val usedMiddleMark = if (isNullOrEmpty(middleMark)) "" else middleMark
    if (str.length <= keepStartLength + keepEndLength + usedMiddleMark.length) {
      return str
    }
    val start = str.substring(0, keepStartLength)
    val end = str.substring(str.length - keepEndLength)
    s"$start$usedMiddleMark$end"
  }
}
