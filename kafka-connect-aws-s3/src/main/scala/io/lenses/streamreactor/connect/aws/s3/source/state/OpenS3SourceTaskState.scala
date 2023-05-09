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
package io.lenses.streamreactor.connect.aws.s3.source.state
import cats.implicits._
import io.lenses.streamreactor.connect.aws.s3.auth.AuthResources
import io.lenses.streamreactor.connect.aws.s3.config.ConnectorTaskId
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3PathLocationWithLine
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3RootLocation
import io.lenses.streamreactor.connect.aws.s3.source.WrappedSourceException
import io.lenses.streamreactor.connect.aws.s3.source.config.S3SourceConfig
import io.lenses.streamreactor.connect.aws.s3.source.distribution.PartitionSearcher
import io.lenses.streamreactor.connect.aws.s3.source.reader.ReaderManager
import io.lenses.streamreactor.connect.aws.s3.source.reader.ReaderManagerService
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.source.SourceRecord

import java.time.Clock
import java.util

class OpenS3SourceTaskState(
  latestReaderManagersFn: () => Seq[ReaderManager],
) extends S3SourceTaskState {

  override def close(): S3SourceTaskState = {
    latestReaderManagersFn().foreach(_.close())
    CleanS3SourceTaskState
  }

  override def poll(): Either[Throwable, Seq[SourceRecord]] =
    latestReaderManagersFn()
      .flatMap(_.poll())
      .flatMap(_.toSourceRecordList)
      .asRight[Throwable]

  override def start(
    props:           util.Map[String, String],
    contextOffsetFn: RemoteS3RootLocation => Option[RemoteS3PathLocationWithLine],
  ): Either[Throwable, S3SourceTaskState] = new ConnectException("Cannot start").asLeft

}

object OpenS3SourceTaskState {
  def apply(
    props:           util.Map[String, String],
    contextOffsetFn: RemoteS3RootLocation => Option[RemoteS3PathLocationWithLine],
  ): Either[Throwable, OpenS3SourceTaskState] = {
    implicit val connectorTaskId: ConnectorTaskId = ConnectorTaskId.fromProps(props)

    {
      for {
        config           <- S3SourceConfig.fromProps(props)
        authResources     = new AuthResources(config.s3Config)
        storageInterface <- config.s3Config.awsClient.createStorageInterface(authResources)
        partitionSearcher = new PartitionSearcher(
          config.bucketOptions.map(_.sourceBucketAndPrefix),
          config.partitionSearcher,
        )(
          connectorTaskId,
          storageInterface,
          Clock.systemDefaultZone(),
        )

      } yield {
        val readerManagerCreateFn: (RemoteS3RootLocation, String) => ReaderManager = (root, rootPath) => {
          val sbo = config.bucketOptions.find(sb => sb.sourceBucketAndPrefix == root).getOrElse(
            throw new ConnectException("no root found"),
          )
          ReaderManager(root, sbo)(connectorTaskId, storageInterface, contextOffsetFn)
        }
        val readerManagerService =
          new ReaderManagerService(config.partitionSearcher, partitionSearcher, readerManagerCreateFn)
        new OpenS3SourceTaskState(() => readerManagerService.getReaderManagers)
      }
    }
      .leftMap {
        case s: String => new WrappedSourceException(s)
      }
  }
}
