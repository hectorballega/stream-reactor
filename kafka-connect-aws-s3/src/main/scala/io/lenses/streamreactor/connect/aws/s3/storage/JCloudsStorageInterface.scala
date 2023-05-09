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
package io.lenses.streamreactor.connect.aws.s3.storage

import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.lenses.streamreactor.connect.aws.s3.config.ConnectorTaskId
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3PathLocation
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3RootLocation
import org.apache.commons.io.IOUtils
import org.apache.kafka.connect.errors.ConnectException
import org.jclouds.blobstore.BlobStoreContext
import org.jclouds.blobstore.domain.StorageType
import org.jclouds.blobstore.options.ListContainerOptions

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.time.Instant
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class JCloudsStorageInterface(blobStoreContext: BlobStoreContext)(implicit connectorTaskId: ConnectorTaskId)
    extends StorageInterface
    with LazyLogging {

  private val blobStore = blobStoreContext.getBlobStore

  override def uploadFile(source: File, target: RemoteS3PathLocation): Either[UploadError, Unit] = {
    logger.debug(s"[{}] JCLOUDS Uploading file from local {} to s3 {}", connectorTaskId.show, source, target)

    if (!source.exists()) {
      NonExistingFileError(source).asLeft
    } else if (source.length() == 0L) {
      ZeroByteFileError(source).asLeft
    } else {
      Try {
        blobStore.putBlob(
          target.bucket,
          blobStore.blobBuilder(target.path)
            .payload(source)
            .contentLength(source.length())
            .build(),
        )
      } match {
        case Failure(exception) =>
          logger.error(s"[{}] Failed upload from local {} to s3 {}", connectorTaskId.show, source, target, exception)
          UploadFailedError(exception, source).asLeft
        case Success(_) =>
          logger.debug(s"[{}] Completed upload from local {} to s3 {}", connectorTaskId.show, source, target)
          ().asRight
      }
    }
  }

  override def writeStringToFile(target: RemoteS3PathLocation, data: String): Either[UploadError, Unit] = {
    logger.debug(s"[{}] Uploading file from data string ({}) to s3 {}", connectorTaskId.show, data, target)

    if (data.isEmpty) {
      EmptyContentsStringError(data).asLeft
    } else {
      Try {
        blobStore.putBlob(
          target.bucket,
          blobStore.blobBuilder(target.path)
            .payload(data)
            .contentLength(data.length().toLong)
            .build(),
        )
      } match {
        case Failure(exception) =>
          logger.error(s"[{}] Failed upload from data string ({}) to s3 {}",
                       connectorTaskId.show,
                       data,
                       target,
                       exception,
          )
          FileCreateError(exception, data).asLeft
        case Success(_) =>
          logger.debug(s"[{}] Completed upload from data string ({}) to s3 {}", connectorTaskId.show, data, target)
          ().asRight
      }
    }
  }

  override def close(): Unit = blobStoreContext.close()

  def pathExistsInternal(bucketAndPath: RemoteS3PathLocation): Boolean =
    blobStore.list(bucketAndPath.bucket, ListContainerOptions.Builder.prefix(bucketAndPath.path)).size() > 0

  override def pathExists(bucketAndPath: RemoteS3PathLocation): Either[FileLoadError, Boolean] =
    Try {
      pathExistsInternal(bucketAndPath)
    }.toEither.leftMap(FileLoadError(_, bucketAndPath.path))

  private def listInternal(bucketAndPath: RemoteS3PathLocation): List[String] = {

    val options = ListContainerOptions.Builder.recursive().prefix(bucketAndPath.path)

    var pageSetStrings: List[String]   = List()
    var nextMarker:     Option[String] = None
    do {
      nextMarker.foreach(options.afterMarker)
      val pageSet = blobStore.list(bucketAndPath.bucket, options)
      nextMarker = Option(pageSet.getNextMarker)
      pageSetStrings ++= pageSet
        .asScala
        .collect {
          case blobOnly if blobOnly.getType == StorageType.BLOB => blobOnly.getName
        }
    } while (nextMarker.nonEmpty)
    pageSetStrings
  }

  override def list(bucketAndPrefix: RemoteS3PathLocation): Either[FileListError, List[String]] =
    Try(listInternal(bucketAndPrefix))
      .toEither
      .leftMap(e => FileListError(e, bucketAndPrefix.path))

  override def getBlob(bucketAndPath: RemoteS3PathLocation): Either[String, InputStream] =
    Try(blobStore.getBlob(bucketAndPath.bucket, bucketAndPath.path).getPayload.openStream()).toEither.leftMap(
      _.getMessage,
    )

  override def getBlobSize(bucketAndPath: RemoteS3PathLocation): Either[String, Long] =
    Try(blobStore.getBlob(bucketAndPath.bucket, bucketAndPath.path).getMetadata.getSize.toLong).toEither.leftMap(
      _.getMessage,
    )

  override def deleteFiles(bucket: String, files: Seq[String]): Either[FileDeleteError, Unit] = {
    if (files.isEmpty) {
      return ().asRight
    }
    Try(blobStore.removeBlobs(bucket, files.asJava))
      .toEither
      .leftMap(e => FileDeleteError(e, s"issue while deleting $files"))
  }

  override def getBlobAsString(bucketAndPath: RemoteS3PathLocation): Either[FileLoadError, String] =
    for {
      blob <- getBlob(bucketAndPath).leftMap(e => FileLoadError(new IllegalArgumentException(e), bucketAndPath.path))
      asString <- Try(IOUtils.toString(blob, Charset.forName("UTF-8"))).toEither.leftMap(FileLoadError(
        _,
        bucketAndPath.path,
      ))
    } yield asString

  override def getBlobModified(location: RemoteS3PathLocation): Either[String, Instant] =
    Try(blobStore.blobMetadata(location.bucket, location.path).getLastModified.toInstant).toEither.leftMap(_.getMessage)

  override def list(
    bucketAndPrefix: RemoteS3RootLocation,
    lastFile:        Option[RemoteS3PathLocation],
    numResults:      Int,
  ): Either[Throwable, List[String]] = new ConnectException("Source must use AWS client").asLeft

  override def findDirectories(
    bucketAndPrefix:  RemoteS3RootLocation,
    completionConfig: DirectoryFindCompletionConfig,
    exclude:          Set[String],
    continueFrom:     Option[String],
  ): IO[DirectoryFindResults] = IO.raiseError(new ConnectException("Source must use AWS client"))

}
