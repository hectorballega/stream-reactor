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
package io.lenses.streamreactor.connect.aws.s3.formats

import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3PathLocation
import io.lenses.streamreactor.connect.aws.s3.model.BytesOutputRow
import io.lenses.streamreactor.connect.aws.s3.model.BytesOutputRowTest
import io.lenses.streamreactor.connect.aws.s3.model.BytesWriteMode
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream

class BytesFormatStreamFileReaderTest extends AnyFlatSpec with MockitoSugar with Matchers {

  import BytesOutputRowTest._

  val bucketAndPath: RemoteS3PathLocation = mock[RemoteS3PathLocation]
  val fileContents = "lemonOlivelemonOlive".getBytes

  "read" should "read entire file at once" in {

    val inputStreamFn = () => new ByteArrayInputStream(fileContents)
    val sizeFn        = () => fileContents.length.longValue()
    val target        = new BytesFormatStreamFileReader(inputStreamFn, sizeFn, bucketAndPath, BytesWriteMode.ValueOnly)

    checkRecord(target, BytesOutputRow(None, None, Array.empty[Byte], fileContents))

    target.hasNext should be(false)
  }

  "hasNext" should "return false for empty file" in {

    val inputStreamFn = () => new ByteArrayInputStream(Array[Byte]())
    val sizeFn        = () => 0L
    val target        = new BytesFormatStreamFileReader(inputStreamFn, sizeFn, bucketAndPath, BytesWriteMode.ValueOnly)

    target.hasNext should be(false)
  }

  private def checkRecord(target: BytesFormatStreamFileReader, expectedOutputRow: BytesOutputRow) = {
    target.hasNext should be(true)
    val record = target.next()
    checkEqualsByteArrayValue(record.data, expectedOutputRow)
  }

}
