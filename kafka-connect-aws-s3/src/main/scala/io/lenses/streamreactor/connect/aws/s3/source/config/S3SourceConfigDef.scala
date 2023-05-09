/*
 * Copyright 2017-2023 Lenses.io Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lenses.streamreactor.connect.aws.s3.source.config

import cats.implicits.catsSyntaxEitherId
import com.datamountaineer.streamreactor.common.config.base.traits._
import com.typesafe.scalalogging.LazyLogging
import io.lenses.streamreactor.connect.aws.s3.config.S3ConfigSettings._
import io.lenses.streamreactor.connect.aws.s3.config._
import io.lenses.streamreactor.connect.aws.s3.config.processors.ConfigDefProcessor
import io.lenses.streamreactor.connect.aws.s3.config.processors.DeprecationConfigDefProcessor
import io.lenses.streamreactor.connect.aws.s3.config.processors.LowerCaseKeyConfigDefProcessor
import io.lenses.streamreactor.connect.aws.s3.config.processors.YamlProfileProcessor
import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.common.config.ConfigDef.Importance
import org.apache.kafka.common.config.ConfigDef.Type

import java.util
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters._

object S3SourceConfigDef {

  val config: ConfigDef = CommonConfigDef.config
    .define(
      SOURCE_PARTITION_EXTRACTOR_TYPE,
      Type.STRING,
      null,
      Importance.LOW,
      SOURCE_PARTITION_EXTRACTOR_TYPE_DOC,
      "Source",
      1,
      ConfigDef.Width.MEDIUM,
      SOURCE_PARTITION_EXTRACTOR_TYPE,
    )
    .define(
      SOURCE_PARTITION_EXTRACTOR_REGEX,
      Type.STRING,
      null,
      Importance.LOW,
      SOURCE_PARTITION_EXTRACTOR_REGEX_DOC,
      "Source",
      2,
      ConfigDef.Width.MEDIUM,
      SOURCE_PARTITION_EXTRACTOR_REGEX,
    )
    .define(
      SOURCE_PARTITION_SEARCH_RECURSE_LEVELS,
      Type.INT,
      SOURCE_PARTITION_SEARCH_RECURSE_LEVELS_DEFAULT,
      Importance.LOW,
      SOURCE_PARTITION_SEARCH_RECURSE_LEVELS_DOC,
      "Source",
      3,
      ConfigDef.Width.MEDIUM,
      SOURCE_PARTITION_SEARCH_RECURSE_LEVELS,
    )
    .define(
      SOURCE_PARTITION_SEARCH_BLOCK,
      Type.BOOLEAN,
      SOURCE_PARTITION_SEARCH_BLOCK_DEFAULT,
      Importance.LOW,
      SOURCE_PARTITION_SEARCH_BLOCK_DOC,
      "Source",
      4,
      ConfigDef.Width.MEDIUM,
      SOURCE_PARTITION_SEARCH_BLOCK,
    )
    .define(
      SOURCE_PARTITION_SEARCH_INTERVAL_MILLIS,
      Type.LONG,
      SOURCE_PARTITION_SEARCH_INTERVAL_MILLIS_DEFAULT,
      Importance.LOW,
      SOURCE_PARTITION_SEARCH_INTERVAL_MILLIS_DOC,
      "Source",
      5,
      ConfigDef.Width.MEDIUM,
      SOURCE_PARTITION_SEARCH_INTERVAL_MILLIS,
    )
    .define(
      SOURCE_PARTITION_SEARCH_PAUSE_AFTER_COUNT,
      Type.INT,
      SOURCE_PARTITION_SEARCH_PAUSE_AFTER_COUNT_DEFAULT,
      Importance.LOW,
      SOURCE_PARTITION_SEARCH_PAUSE_AFTER_COUNT_DOC,
      "Source",
      6,
      ConfigDef.Width.MEDIUM,
      SOURCE_PARTITION_SEARCH_PAUSE_AFTER_COUNT,
    )
    .define(
      SOURCE_PARTITION_SEARCH_PAUSE_AFTER_MILLIS,
      Type.LONG,
      SOURCE_PARTITION_SEARCH_PAUSE_AFTER_MILLIS_DEFAULT,
      Importance.LOW,
      SOURCE_PARTITION_SEARCH_PAUSE_AFTER_MILLIS_DOC,
      "Source",
      7,
      ConfigDef.Width.MEDIUM,
      SOURCE_PARTITION_SEARCH_PAUSE_AFTER_MILLIS,
    )
}

class S3SourceConfigDef() extends ConfigDef with LazyLogging {

  private val processorChain: List[ConfigDefProcessor] =
    List(new LowerCaseKeyConfigDefProcessor, new DeprecationConfigDefProcessor, new YamlProfileProcessor)

  override def parse(jProps: util.Map[_, _]): util.Map[String, AnyRef] = {
    val scalaProps: Map[Any, Any] = jProps.asScala.toMap
    processProperties(scalaProps) match {
      case Left(exception) => throw exception
      case Right(value)    => super.parse(value.asJava)
    }
  }

  private def processProperties(scalaProps: Map[Any, Any]): Either[Throwable, Map[Any, Any]] = {
    val stringProps    = scalaProps.collect { case (k: String, v: AnyRef) => (k.toLowerCase, v) }
    val nonStringProps = scalaProps -- stringProps.keySet
    processStringKeyedProperties(stringProps) match {
      case Left(exception)         => exception.asLeft[Map[Any, Any]]
      case Right(stringKeyedProps) => (nonStringProps ++ stringKeyedProps).asRight
    }
  }

  def writeInOrder(remappedProps: Map[String, Any]): ListMap[String, Any] =
    ListMap(remappedProps.toSeq.sortBy(_._1): _*)

  def processStringKeyedProperties(stringProps: Map[String, Any]): Either[Throwable, Map[String, Any]] = {
    var remappedProps: Map[String, Any] = stringProps
    for (proc <- processorChain) {
      logger.info("START: Executing ConfigDef processor {} with props {}",
                  proc.getClass.getSimpleName,
                  writeInOrder(remappedProps),
      )
      proc.process(remappedProps) match {
        case Left(exception)   => return exception.asLeft[Map[String, AnyRef]]
        case Right(properties) => remappedProps = properties
      }
      logger.info("END: Executing ConfigDef processor {} with props {}",
                  proc.getClass.getSimpleName,
                  writeInOrder(remappedProps),
      )
    }
    remappedProps.asRight
  }

}

case class S3SourceConfigDefBuilder(props: util.Map[String, String])
    extends BaseConfig(S3ConfigSettings.CONNECTOR_PREFIX, S3SourceConfigDef.config, props)
    with KcqlSettings
    with ErrorPolicySettings
    with NumberRetriesSettings
    with UserSettings
    with ConnectionSettings
    with CompressionCodecSettings
    with SourcePartitionSearcherSettings {

  def getParsedValues: Map[String, _] = values().asScala.toMap

}
