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

package spray.can.client

import spray.http._
import spray.io._
import spray.can.Http

private object ResponseChunkAggregation {

  def apply(limit: Int): PipelineStage =
    new PipelineStage {
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
        new Pipelines with DynamicEventPipeline {
          val commandPipeline = commandPL

          val initialEventPipeline: EPL = {
            case Http.MessageEvent(ChunkedResponseStart(response)) ⇒
              eventPipeline.become(aggregating(response, HttpData.newBuilder += response.entity.data))

            case ev ⇒ eventPL(ev)
          }

          def aggregating(response: HttpResponse, builder: HttpData.Builder): EPL = {
            case Http.MessageEvent(MessageChunk(data, _)) ⇒
              if (builder.byteCount + data.length <= limit)
                builder += data
              else closeWithError()

            case Http.MessageEvent(_: ChunkedMessageEnd) ⇒
              val contentType = response.header[HttpHeaders.`Content-Type`] match {
                case Some(x) ⇒ x.contentType
                case None    ⇒ ContentTypes.`application/octet-stream`
              }
              eventPL(Http.MessageEvent(response.copy(entity = HttpEntity(contentType, builder.result()))))
              eventPipeline.become(initialEventPipeline)

            case ev ⇒ eventPL(ev)
          }

          def closeWithError(): Unit = {
            context.log.error("Aggregated response entity greater than configured limit of {} bytes," +
              "closing connection", limit)
            commandPL(Http.Close)
            eventPipeline.become(eventPL) // disable this stage
          }
        }
    }
}