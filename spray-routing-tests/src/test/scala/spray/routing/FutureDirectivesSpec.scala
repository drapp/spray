/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
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

package spray.routing

import scala.concurrent.Future
import spray.http.StatusCodes
import spray.util.SingletonException

class FutureDirectivesSpec extends RoutingSpec {

  object TestException extends SingletonException

  "The `onComplete` directive" should {
    "properly unwrap a Future in the success case" in {
      Get() ~> onComplete(Future.successful("yes")) { echoComplete } ~> check {
        responseAs[String] === "Success(yes)"
      }
    }
    "properly unwrap a Future in the failure case" in {
      Get() ~> onComplete(Future.failed(new RuntimeException("no"))) { echoComplete } ~> check {
        responseAs[String] === "Failure(java.lang.RuntimeException: no)"
      }
    }
  }

  "The `onSuccess` directive" should {
    "properly unwrap a Future in the success case" in {
      Get() ~> onSuccess(Future.successful("yes")) { echoComplete } ~> check {
        responseAs[String] === "yes"
      }
    }
    "throw an exception in the failure case" in {
      Get() ~> onSuccess(Future.failed(TestException)) { echoComplete } ~> check {
        status === StatusCodes.InternalServerError
      }
    }
  }

  "The `onFailure` directive" should {
    "properly unwrap a Future in the success case" in {
      Get() ~> onFailure(Future.successful("yes")) { echoComplete } ~> check {
        responseAs[String] === "yes"
      }
    }
    "throw an exception in the failure case" in {
      Get() ~> onFailure(Future.failed[String](TestException)) { echoComplete } ~> check {
        responseAs[String] === "spray.routing.FutureDirectivesSpec$TestException$"
      }
    }
  }

}