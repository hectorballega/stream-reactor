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
package io.lenses.streamreactor.connect.aws.s3.source.distribution

import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.lenses.streamreactor.connect.aws.s3.config.ConnectorTaskId
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3RootLocation
import io.lenses.streamreactor.connect.aws.s3.source.config.PartitionSearcherOptions
import io.lenses.streamreactor.connect.aws.s3.storage.CompletedDirectoryFindResults
import io.lenses.streamreactor.connect.aws.s3.storage.DirectoryFindCompletionConfig
import io.lenses.streamreactor.connect.aws.s3.storage.PausedDirectoryFindResults
import io.lenses.streamreactor.connect.aws.s3.storage.StorageInterface

import java.time.Clock

class PartitionSearcher(
  roots:    Seq[RemoteS3RootLocation],
  settings: PartitionSearcherOptions,
)(
  implicit
  connectorTaskId:  ConnectorTaskId,
  storageInterface: StorageInterface,
) extends LazyLogging {

  def findNewPartitions(
    lastFound: Seq[PartitionSearcherResponse],
  ): IO[Seq[PartitionSearcherResponse]] =
    if (lastFound.isEmpty && roots.nonEmpty) {
      roots.traverse(findNewPartitionsInRoot(_, settings, Set.empty, Option.empty))
    } else {
      lastFound.traverse {
        prevResponse =>
          findNewPartitionsInRoot(
            prevResponse.root,
            settings,
            prevResponse.allPartitions,
            resumeFrom(prevResponse),
          )
      }
    }

  private def findNewPartitionsInRoot(
    root:       RemoteS3RootLocation,
    settings:   PartitionSearcherOptions,
    exclude:    Set[String],
    resumeFrom: Option[String],
    maybeClock: Option[Clock] = Option.empty,
  ): IO[PartitionSearcherResponse] =
    for {
      originalPartitions <- IO.delay(exclude)
      clock               = maybeClock.getOrElse(Clock.systemDefaultZone())
      foundPartitions <-
        storageInterface.findDirectories(
          root,
          DirectoryFindCompletionConfig.fromSearchOptions(settings, clock),
          originalPartitions,
          resumeFrom,
        )
      _ <- IO(logger.debug("[{}] Found new partitions {} under {}", connectorTaskId.show, foundPartitions, root))

    } yield PartitionSearcherResponse(
      root,
      clock.instant(),
      originalPartitions ++ foundPartitions.partitions,
      foundPartitions,
      Option.empty,
    )

  private def resumeFrom(prevResponse: PartitionSearcherResponse) =
    prevResponse.results match {
      case PausedDirectoryFindResults(_, _, resumeFrom) => resumeFrom.some
      case CompletedDirectoryFindResults(_)             => none
      case _                                            => none
    }
}
