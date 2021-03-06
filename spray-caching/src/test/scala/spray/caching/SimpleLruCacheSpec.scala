/*
 * Copyright (C) 2011-2013 spray.io
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

package spray.caching

import java.util.Random
import java.util.concurrent.CountDownLatch
import akka.actor.ActorSystem
import scala.concurrent.{ Promise, Future }
import scala.concurrent.duration._
import org.specs2.mutable.Specification
import org.specs2.matcher.Matcher
import spray.util._
import org.specs2.time.NoTimeConversions

class SimpleLruCacheSpec extends Specification with NoTimeConversions {
  implicit val system = ActorSystem()
  import system.dispatcher

  "An LruCache" should {
    "be initially empty" in {
      lruCache().store.toString === "{}"
      lruCache().size === 0
    }
    "store uncached values" in {
      val cache = lruCache[String]()
      cache(1)("A").await === "A"
      cache.size === 1
    }
    "return stored values upon cache hit on existing values" in {
      val cache = lruCache[String]()
      cache(1)("A").await === "A"
      cache(1)(failure("Cached expression was evaluated despite a cache hit"): String).await === "A"
      cache.size === 1
    }
    "return Futures on uncached values during evaluation and replace these with the value afterwards" in {
      val cache = lruCache[String]()
      val latch = new CountDownLatch(1)
      val future1 = cache(1) { (promise: Promise[String]) ⇒
        Future {
          latch.await()
          promise.success("A")
        }
      }
      val future2 = cache(1)("")
      latch.countDown()
      future1.await === "A"
      future2.await === "A"
      cache.size === 1
    }
    "properly limit capacity" in {
      val cache = lruCache[String](maxCapacity = 3, initialCapacity = 3)
      cache(1)("A").await === "A"
      cache(2)(Future.successful("B")).await === "B"
      cache(3)("C").await === "C"
      cache.size === 3
      cache(4)("D")
      Thread.sleep(10)
      cache.size === 3
    }
    "synchronously call eviction handler for removed elements" in {
      var evicted: Option[Future[String]] = None
      def onEvict = { value: Future[String] ⇒ evicted = Some(value) }
      val cache = lruCache[String](maxCapacity = 3, initialCapacity = 3, onEvict = onEvict)
      cache(1)("A").await === "A"
      cache(2)(Future.successful("B")).await === "B"
      cache(3)("C").await === "C"
      cache(4)("D")
      Thread.sleep(10)
      evicted must beSome.like {
        case future ⇒ future.await === "A"
      }
      cache.size === 3
    }
    "not cache exceptions" in {
      val cache = lruCache[String]()
      cache(1)((throw new RuntimeException("Naa")): String).await must throwA[RuntimeException]("Naa")
      cache(1)("A").await === "A"
    }
    "be thread-safe" in {
      val cache = lruCache[Int](maxCapacity = 1000)
      // exercise the cache from 10 parallel "tracks" (threads)
      val views = Future.traverse(Seq.tabulate(10)(identityFunc)) { track ⇒
        Future {
          val array = Array.fill(1000)(0) // our view of the cache
          val rand = new Random(track)
          (1 to 10000) foreach { i ⇒
            val ix = rand.nextInt(1000) // for a random index into the cache
            val value = cache(ix) { // get (and maybe set) the cache value
              Thread.sleep(0)
              rand.nextInt(1000000) + 1
            }.await
            if (array(ix) == 0) array(ix) = value // update our view of the cache
            else if (array(ix) != value) failure("Cache view is inconsistent (track " + track + ", iteration " + i +
              ", index " + ix + ": expected " + array(ix) + " but is " + value)
          }
          array
        }
      }.await
      val beConsistent: Matcher[Seq[Int]] = (
        (ints: Seq[Int]) ⇒ ints.filter(_ != 0).reduceLeft((a, b) ⇒ if (a == b) a else 0) != 0,
        (_: Seq[Int]) ⇒ "consistency check")
      views.transpose must beConsistent.forall
    }
  }

  step(system.shutdown())

  def lruCache[T](maxCapacity: Int = 500, initialCapacity: Int = 16, onEvict: Future[T] ⇒ Unit = LruCache.EmptyEvictionHandler) =
    new SimpleLruCache[T](maxCapacity, initialCapacity, onEvict)

}
