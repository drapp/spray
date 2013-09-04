/*
 * Copyright (C) 2011-2013 spray.io
 * Based on code copyright (C) 2010-2011 by the BlueEyes Web Framework Team (http://github.com/jdegoes/blueeyes)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.http

import java.io.{ FileInputStream, File }
import java.nio.charset.Charset
import scala.util.control.NonFatal
import scala.collection.immutable.VectorBuilder
import scala.annotation.tailrec
import akka.util.ByteString
import akka.spray.createByteStringUnsafe
import spray.util.UTF8

sealed abstract class HttpData {
  /**
   * Determines whether this instance is identical to HttpData.Empty.
   */
  def isEmpty: Boolean = this eq HttpData.Empty

  /**
   * Determines whether this instance is different from HttpData.Empty.
   */
  def nonEmpty: Boolean = this ne HttpData.Empty

  /**
   * Determines whether this instance is or contains data that are not
   * already present in the JVM heap (i.e. instance of HttpData.FileBytes).
   */
  def hasFileBytes: Boolean = this.isInstanceOf[HttpData.FileBytes]

  /**
   * Returns the number of bytes contained in this instance.
   */
  def length: Long

  /**
   * Extracts `span` bytes from this instance starting at `sourceOffset` and copies
   * them to the `xs` starting at `targetOffset`. If `span` is larger than the number
   * of bytes available in this instance after the `sourceOffset` or if `xs` has
   * less space available after `targetOffset` the number of bytes copied is
   * decreased accordingly (i.e. it is not an error to specify a `span` that is
   * too large).
   */
  def copyToArray(xs: Array[Byte], sourceOffset: Long = 0, targetOffset: Int = 0, span: Int = length.toInt): Unit

  /**
   * Returns a slice of this instance's content as a `ByteString`.
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   * If this instance is a `FileBytes` instance containing more than 2GB of data
   * the method will throw an `IllegalArgumentException`.
   */
  def slice(offset: Long = 0, span: Int = length.toInt): ByteString

  /**
   * Copies the contents of this instance into a new byte array.
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   * If this instance is a `FileBytes` instance containing more than 2GB of data
   * the method will throw an `IllegalArgumentException`.
   */
  def toByteArray: Array[Byte]

  /**
   * Same as `toByteArray` but returning a `ByteString` instead.
   * More efficient if this instance is a `Bytes` instance since no data will have
   * to be copied and the `ByteString` will not have to be newly created.
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   * If this instance is a `FileBytes` instance containing more than 2GB of data
   * the method will throw an `IllegalArgumentException`.
   */
  def toByteString: ByteString

  /**
   * Returns the contents of this instance as a `Stream[ByteString]` with each
   * chunk not being larger than the given `maxChunkSize`.
   */
  def toChunkStream(maxChunkSize: Int): Stream[ByteString]

  /**
   * Efficiently prepends this instance with another `HttpData` instance to possibly
   * form a compound instance. No data need to be copied around to do this.
   */
  def +:(other: HttpData): HttpData

  /**
   * Returns the contents of this instance as a string (using UTF-8 encoding).
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   * If this instance is a `FileBytes` instance containing more than 2GB of data
   * the method will throw an `IllegalArgumentException`.
   */
  def asString: String = asString(UTF8)

  /**
   * Returns the contents of this instance as a string.
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   * If this instance is a `FileBytes` instance containing more than 2GB of data
   * the method will throw an `IllegalArgumentException`.
   */
  def asString(charset: HttpCharset): String = asString(charset.nioCharset)

  /**
   * Returns the contents of this instance as a string.
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   * If this instance is a `FileBytes` instance containing more than 2GB of data
   * the method will throw an `IllegalArgumentException`.
   */
  def asString(charset: Charset): String = new String(toByteArray, charset)
}

object HttpData {
  def apply(string: String): HttpData =
    apply(string, HttpCharsets.`UTF-8`)
  def apply(string: String, charset: HttpCharset): HttpData =
    if (string.isEmpty) Empty else new Bytes(createByteStringUnsafe(string getBytes charset.nioCharset))
  def apply(bytes: Array[Byte]): HttpData =
    if (bytes.isEmpty) Empty else new Bytes(ByteString(bytes))
  def apply(bytes: ByteString): HttpData =
    if (bytes.isEmpty) Empty else new Bytes(bytes)

  /**
   * Creates a [[spray.http.HttpData.FileBytes]] instance if the given file exists, is readable,
   * non-empty and the given `length` parameter is non-zero. Otherwise the method returns
   * [[spray.http.HttpData.Empty]].
   * A negative `length` value signifies that the respective number of bytes at the end of the
   * file is to be ommitted, i.e., a value of -10 will select all bytes starting at `offset`
   * except for the last 10.
   * If `length` is greater or equal to "file length - offset" all bytes in the file starting at
   * `offset` are selected.
   */
  def apply(file: File, offset: Long = 0, length: Long = Long.MaxValue): HttpData = {
    val fileLength = file.length
    if (fileLength > 0) {
      require(offset >= 0 && offset < fileLength, s"offset $offset out of range $fileLength")
      if (file.canRead)
        if (length > 0) new FileBytes(file.getAbsolutePath, offset, math.min(fileLength - offset, length))
        else if (length < 0 && length > offset - fileLength) new FileBytes(file.getAbsolutePath, offset, fileLength - offset + length)
        else Empty
      else Empty
    } else Empty
  }

  /**
   * Creates a [[spray.http.HttpData.FileBytes]] instance if the given file exists, is readable,
   * non-empty and the given `length` parameter is non-zero. Otherwise the method returns
   * [[spray.http.HttpData.Empty]].
   * A negative `length` value signifies that the respective number of bytes at the end of the
   * file is to be ommitted, i.e., a value of -10 will select all bytes starting at `offset`
   * except for the last 10.
   * If `length` is greater or equal to "file length - offset" all bytes in the file starting at
   * `offset` are selected.
   */
  def fromFile(fileName: String, offset: Long = 0, length: Long = Long.MaxValue) =
    apply(new File(fileName), offset, length)

  case object Empty extends HttpData {
    def length = 0L
    def copyToArray(xs: Array[Byte], sourceOffset: Long, targetOffset: Int, span: Int) = ()
    def slice(offset: Long, span: Int): ByteString = toByteString
    val toByteArray = Array.empty[Byte]
    def toByteString = ByteString.empty
    def toChunkStream(maxChunkSize: Int) = Stream.empty
    def +:(other: HttpData) = other
    override def asString(charset: Charset) = ""
  }

  sealed abstract class NonEmpty extends HttpData {
    def +:(other: HttpData): NonEmpty =
      other match {
        case Empty                                 ⇒ this
        case x: CompactNonEmpty                    ⇒ Compound(x, this)
        case Compound(head, tail: CompactNonEmpty) ⇒ Compound(head, Compound(tail, this))
        case x: Compound                           ⇒ newBuilder.+=(x).+=(this).result().asInstanceOf[Compound]
      }
    def toByteArray = {
      require(length <= Int.MaxValue, "Cannot create a byte array greater than 2GB")
      val array = new Array[Byte](length.toInt)
      copyToArray(array)
      array
    }
  }

  sealed abstract class CompactNonEmpty extends NonEmpty { _: Product ⇒
    override def toString = s"$productPrefix(<$length bytes>)"
  }

  case class Bytes private[HttpData] (bytes: ByteString) extends CompactNonEmpty {
    def length = bytes.length
    def copyToArray(xs: Array[Byte], sourceOffset: Long = 0, targetOffset: Int = 0, span: Int = length.toInt) = {
      require(sourceOffset >= 0, "sourceOffset must be >= 0 but is " + sourceOffset)
      if (sourceOffset < length)
        bytes.iterator.drop(sourceOffset.toInt).copyToArray(xs, targetOffset, span)
    }
    def slice(offset: Long, span: Int): ByteString = {
      require(offset >= 0, "offset must be >= 0")
      require(span >= 0, "span must be >= 0")
      if (offset < length && span > 0)
        if (offset > 0 || span < length) bytes.slice(offset.toInt, math.min(offset + span, Int.MaxValue).toInt)
        else bytes
      else ByteString.empty
    }
    def toByteString = bytes
    def toChunkStream(maxChunkSize: Int): Stream[ByteString] = {
      require(maxChunkSize > 0, "chunkSize must be > 0")
      val lastChunkStart = length - maxChunkSize
      def nextChunk(ix: Int = 0): Stream[ByteString] = {
        if (ix < lastChunkStart) Stream.cons(slice(ix, maxChunkSize), nextChunk(ix + maxChunkSize))
        else Stream.cons(slice(ix, (length - ix).toInt), Stream.Empty)
      }
      nextChunk()
    }
  }

  case class FileBytes private[HttpData] (fileName: String, offset: Long = 0, length: Long) extends CompactNonEmpty {
    def copyToArray(xs: Array[Byte], sourceOffset: Long = 0, targetOffset: Int = 0, span: Int = length.toInt) = {
      require(sourceOffset >= 0, "sourceOffset must be >= 0 but is " + sourceOffset)
      if (span > 0 && xs.length > 0 && sourceOffset < length) {
        require(0 <= targetOffset && targetOffset < xs.length, s"start must be >= 0 and <= ${xs.length} but is $targetOffset")
        val input = new FileInputStream(fileName)
        try {
          input.skip(offset + sourceOffset)
          val targetEnd = math.min(xs.length, targetOffset + math.min(span, (length - sourceOffset).toInt))
          @tailrec def load(ix: Int = targetOffset): Unit =
            if (ix < targetEnd)
              input.read(xs, ix, targetEnd - ix) match {
                case -1 ⇒ // file length changed since this FileBytes instance was created
                  java.util.Arrays.fill(xs, ix, targetEnd, 0.toByte) // zero out remaining space
                case count ⇒ load(ix + count)
              }
          load()
        } finally input.close()
      }
    }
    def slice(offset: Long, span: Int): ByteString = {
      require(offset >= 0, "offset must be >= 0")
      require(span >= 0, "span must be >= 0")
      if (offset < length && span > 0) {
        val buf = new Array[Byte](math.min(length - offset, span).toInt)
        copyToArray(buf, sourceOffset = offset)
        createByteStringUnsafe(buf)
      } else ByteString.empty
    }
    def toByteString = createByteStringUnsafe(toByteArray)
    def toChunkStream(maxChunkSize: Int): Stream[ByteString] = {
      require(maxChunkSize > 0, "chunkSize must be > 0")
      val input = new FileInputStream(fileName)
      def nextChunk(): Stream[ByteString] =
        try {
          val array = new Array[Byte](maxChunkSize)
          input.read(array, 0, maxChunkSize) match {
            case -1             ⇒ Stream.empty
            case `maxChunkSize` ⇒ Stream.cons(createByteStringUnsafe(array), nextChunk())
            case count          ⇒ Stream.cons(createByteStringUnsafe(array, 0, count), Stream.empty)
          }
        } catch {
          case NonFatal(_) ⇒
            input.close()
            Stream.empty
        }
      nextChunk()
    }
  }

  case class Compound private[HttpData] (head: CompactNonEmpty, tail: NonEmpty) extends NonEmpty {
    val length = head.length + tail.length
    override def hasFileBytes = {
      @tailrec def rec(thiz: NonEmpty = this): Boolean =
        thiz match {
          case Compound(h, t) ⇒ if (h.hasFileBytes) true else rec(t)
          case x              ⇒ x.hasFileBytes
        }
      rec()
    }
    def iterator: Iterator[CompactNonEmpty] =
      new Iterator[CompactNonEmpty] {
        var nxt: HttpData = Compound.this
        def hasNext: Boolean = nxt.nonEmpty
        def next(): CompactNonEmpty =
          nxt match {
            case x: CompactNonEmpty ⇒ nxt = Empty; x
            case Compound(h, t)     ⇒ nxt = t; h
            case Empty              ⇒ throw new NoSuchElementException("next on empty iterator")
          }
      }
    def copyToArray(xs: Array[Byte], sourceOffset: Long = 0, targetOffset: Int = 0, span: Int = length.toInt): Unit = {
      require(sourceOffset >= 0, "sourceOffset must be >= 0 but is " + sourceOffset)
      if (span > 0 && xs.length > 0 && sourceOffset < length) {
        require(0 <= targetOffset && targetOffset < xs.length, s"start must be >= 0 and <= ${xs.length} but is $targetOffset")
        val targetEnd: Int = math.min(xs.length, targetOffset + math.min(span, (length - sourceOffset).toInt))
        val iter = iterator
        @tailrec def rec(sourceOffset: Long = sourceOffset, targetOffset: Int = targetOffset): Unit =
          if (targetOffset < targetEnd && iter.hasNext) {
            val current = iter.next()
            if (sourceOffset < current.length) {
              current.copyToArray(xs, sourceOffset, targetOffset, span = targetEnd - targetOffset)
              rec(0, math.min(targetOffset + current.length - sourceOffset, Int.MaxValue).toInt)
            } else rec(sourceOffset - current.length, targetOffset)
          }
        rec()
      }
    }
    def slice(offset: Long, span: Int): ByteString = {
      require(offset >= 0, "offset must be >= 0")
      require(span >= 0, "span must be >= 0")
      if (offset < length && span > 0) {
        val iter = iterator
        val builder = ByteString.newBuilder
        @tailrec def rec(offset: Long = offset, span: Int = span): ByteString =
          if (span > 0 && iter.hasNext) {
            val current = iter.next()
            if (offset < current.length) {
              builder ++= current.slice(offset, span)
              rec(0, math.max(0, span - current.length).toInt)
            } else rec(offset - current.length, span)
          } else builder.result()
        rec()
      } else ByteString.empty
    }
    def toChunkStream(maxChunkSize: Int): Stream[ByteString] =
      head.toChunkStream(maxChunkSize) append tail.toChunkStream(maxChunkSize)

    override def toString = head.toString + " +: " + tail
    def toByteString = createByteStringUnsafe(toByteArray)
  }

  def newBuilder: Builder = new Builder

  class Builder extends scala.collection.mutable.Builder[HttpData, HttpData] {
    private val b = new VectorBuilder[CompactNonEmpty]
    private var _byteCount = 0L

    def byteCount: Long = _byteCount

    def +=(x: CompactNonEmpty): this.type = {
      b += x
      _byteCount += x.length
      this
    }

    def +=(elem: HttpData): this.type =
      elem match {
        case Empty              ⇒ this
        case x: CompactNonEmpty ⇒ this += x
        case x: Compound ⇒
          @tailrec def append(current: NonEmpty): this.type =
            current match {
              case x: CompactNonEmpty   ⇒ this += x
              case Compound(head, tail) ⇒ this += head; append(tail)
            }
          append(x)
      }

    def clear(): Unit = b.clear()

    def result(): HttpData =
      b.result().foldRight(Empty: HttpData) {
        case (x, Empty)          ⇒ x
        case (x, tail: NonEmpty) ⇒ Compound(x, tail)
      }
  }
}