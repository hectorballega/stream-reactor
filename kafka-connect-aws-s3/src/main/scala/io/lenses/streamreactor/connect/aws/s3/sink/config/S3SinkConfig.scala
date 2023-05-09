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
package io.lenses.streamreactor.connect.aws.s3.sink.config

import cats.syntax.all._
import com.datamountaineer.kcql.Kcql
import com.typesafe.scalalogging.LazyLogging
import io.lenses.streamreactor.connect.aws.s3.config.S3ConfigSettings.SEEK_MAX_INDEX_FILES
import io.lenses.streamreactor.connect.aws.s3.config.S3ConfigSettings.SEEK_MIGRATION
import S3FlushSettings.defaultFlushCount
import S3FlushSettings.defaultFlushInterval
import S3FlushSettings.defaultFlushSize
import io.lenses.streamreactor.connect.aws.s3.config.ConnectorTaskId
import io.lenses.streamreactor.connect.aws.s3.config.FormatSelection
import io.lenses.streamreactor.connect.aws.s3.config.S3Config
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3RootLocation
import io.lenses.streamreactor.connect.aws.s3.model.CompressionCodec
import io.lenses.streamreactor.connect.aws.s3.sink._

import java.util

object S3SinkConfig {

  def fromProps(
    props: util.Map[String, String],
  )(
    implicit
    connectorTaskId: ConnectorTaskId,
  ): Either[Throwable, S3SinkConfig] =
    S3SinkConfig(S3SinkConfigDefBuilder(props))

  def apply(
    s3ConfigDefBuilder: S3SinkConfigDefBuilder,
  )(
    implicit
    connectorTaskId: ConnectorTaskId,
  ): Either[Throwable, S3SinkConfig] =
    for {
      sinkBucketOptions <- SinkBucketOptions(s3ConfigDefBuilder)
      offsetSeekerOptions = OffsetSeekerOptions(
        s3ConfigDefBuilder.getInt(SEEK_MAX_INDEX_FILES),
        s3ConfigDefBuilder.getBoolean(SEEK_MIGRATION),
      )
    } yield S3SinkConfig(
      S3Config(s3ConfigDefBuilder.getParsedValues),
      sinkBucketOptions,
      offsetSeekerOptions,
      s3ConfigDefBuilder.getCompressionCodec(),
    )

}

case class S3SinkConfig(
  s3Config:            S3Config,
  bucketOptions:       Set[SinkBucketOptions] = Set.empty,
  offsetSeekerOptions: OffsetSeekerOptions,
  compressionCodec:    CompressionCodec,
)

object SinkBucketOptions extends LazyLogging {

  def apply(
    config: S3SinkConfigDefBuilder,
  )(
    implicit
    connectorTaskId: ConnectorTaskId,
  ): Either[Throwable, Set[SinkBucketOptions]] =
    config.getKCQL.map { kcql: Kcql =>
      val formatSelection: FormatSelection = FormatSelection.fromKcql(kcql)

      val partitionSelection = PartitionSelection(kcql)
      val namingStrategy = partitionSelection match {
        case Some(partSel) => new PartitionedS3FileNamingStrategy(formatSelection, config.getPaddingStrategy(), partSel)
        case None          => new HierarchicalS3FileNamingStrategy(formatSelection, config.getPaddingStrategy())
      }

      val stagingArea = LocalStagingArea(config)
      stagingArea match {
        case Right(value) => SinkBucketOptions(
            Option(kcql.getSource).filterNot(Set("*", "`*`").contains(_)),
            RemoteS3RootLocation(kcql.getTarget),
            formatSelection    = formatSelection,
            fileNamingStrategy = namingStrategy,
            partitionSelection = partitionSelection,
            commitPolicy       = config.commitPolicy(kcql),
            localStagingArea   = value,
          )
        case Left(exception) => return exception.asLeft[Set[SinkBucketOptions]]
      }
    }.asRight

}

case class SinkBucketOptions(
  sourceTopic:        Option[String],
  bucketAndPrefix:    RemoteS3RootLocation,
  formatSelection:    FormatSelection,
  fileNamingStrategy: S3FileNamingStrategy,
  partitionSelection: Option[PartitionSelection] = None,
  commitPolicy: CommitPolicy = DefaultCommitPolicy(Some(defaultFlushSize.toLong), Some(defaultFlushInterval),
    Some(defaultFlushCount)),
  localStagingArea: LocalStagingArea,
)

case class OffsetSeekerOptions(
  maxIndexFiles: Int,
  migrate:       Boolean,
)
