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

import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3PathLocationWithLine
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3RootLocation
import org.apache.kafka.connect.source.SourceRecord

import java.util

trait S3SourceTaskState {
  def start(
    props:           util.Map[String, String],
    contextOffsetFn: RemoteS3RootLocation => Option[RemoteS3PathLocationWithLine],
  ):           Either[Throwable, S3SourceTaskState]
  def close(): S3SourceTaskState
  def poll():  Either[Throwable, Seq[SourceRecord]]

}
