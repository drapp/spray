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

package spray.httpx.unmarshalling

import spray.http._

sealed trait FormFieldExtractor {
  type Field <: FormField
  def field(name: String): Field
}

object FormFieldExtractor {
  def apply(form: HttpForm): FormFieldExtractor = form match {
    case FormData(fields) ⇒ new FormFieldExtractor {
      type Field = UrlEncodedFormField
      def field(name: String) = new UrlEncodedFormField(name, fields.find(_._1 == name).map(_._2))
    }
    case multiPartData: MultipartFormData ⇒ new FormFieldExtractor {
      type Field = MultipartFormField
      def field(name: String) = new MultipartFormField(name, multiPartData.get(name))
    }
  }
}

sealed abstract class FormField {
  type Raw
  def name: String
  def rawValue: Option[Raw]
  def exists = rawValue.isDefined
  def as[T: FormFieldConverter]: Deserialized[T]

  protected def fail(fieldName: String, expected: String) =
    Left(UnsupportedContentType("Field '%s' can only be read from '%s' form content".format(fieldName, expected)))
}

class UrlEncodedFormField(val name: String, val rawValue: Option[String]) extends FormField {
  type Raw = String
  def as[T](implicit ffc: FormFieldConverter[T]) = ffc.urlEncodedFieldConverter match {
    case Some(conv) ⇒ conv(rawValue)
    case None ⇒
      ffc.multipartFieldConverter match {
        case Some(conv) ⇒
          conv(rawValue.map(value ⇒ BodyPart(HttpEntity(value)))) match {
            case Left(UnsupportedContentType(msg)) ⇒
              Left(UnsupportedContentType(msg + " but tried to read from application/x-www-form-urlencoded encoded field '" +
                name + "' which provides only text/plain values."))
            case x ⇒ x
          }
        case None ⇒ fail(name, "multipart/form-data")
      }
  }
}

class MultipartFormField(val name: String, val rawValue: Option[BodyPart]) extends FormField {
  type Raw = BodyPart
  def as[T](implicit ffc: FormFieldConverter[T]) = ffc.multipartFieldConverter match {
    case Some(conv) ⇒ conv(rawValue)
    case None ⇒
      ffc.urlEncodedFieldConverter match {
        case Some(conv) ⇒
          rawValue match {
            case Some(BodyPart(HttpEntity.NonEmpty(tpe, data), _)) if tpe.mediaRange.matches(MediaTypes.`text/plain`) ⇒
              conv(Some(data.asString))
            case None | Some(BodyPart(HttpEntity.Empty, _)) ⇒ conv(None)
            case Some(BodyPart(HttpEntity.NonEmpty(tpe, _), _)) ⇒
              Left(UnsupportedContentType(s"Field '$name' can only be read from " +
                s"'application/x-www-form-urlencoded' form content but was '${tpe.mediaRange}'"))
          }
        case None ⇒ fail(name, "application/x-www-form-urlencoded")
      }
  }
}

import spray.httpx.unmarshalling.{ FromStringOptionDeserializer ⇒ FSOD, FromBodyPartOptionUnmarshaller ⇒ FBPOU }

sealed abstract class FormFieldConverter[T] { self ⇒
  def urlEncodedFieldConverter: Option[FSOD[T]]
  def multipartFieldConverter: Option[FBPOU[T]]
  def withDefault(default: T): FormFieldConverter[T] =
    new FormFieldConverter[T] {
      lazy val urlEncodedFieldConverter = self.urlEncodedFieldConverter.map(_.withDefaultValue(default))
      lazy val multipartFieldConverter = self.multipartFieldConverter.map(_.withDefaultValue(default))
    }
}

object FormFieldConverter extends FfcLowerPrioImplicits {
  implicit def dualModeFormFieldConverter[T: FSOD: FBPOU] = new FormFieldConverter[T] {
    lazy val urlEncodedFieldConverter = Some(implicitly[FSOD[T]])
    lazy val multipartFieldConverter = Some(implicitly[FBPOU[T]])
  }
  def fromFSOD[T](fsod: FSOD[T])(implicit feou: FBPOU[T] = null) =
    if (feou == null) urlEncodedFormFieldConverter(fsod) else dualModeFormFieldConverter(fsod, feou)
}

private[unmarshalling] abstract class FfcLowerPrioImplicits extends FfcLowerPrioImplicits2 {
  implicit def urlEncodedFormFieldConverter[T: FSOD] = new FormFieldConverter[T] {
    lazy val urlEncodedFieldConverter = Some(implicitly[FSOD[T]])
    def multipartFieldConverter = None
  }

  implicit def multiPartFormFieldConverter[T: FBPOU] = new FormFieldConverter[T] {
    def urlEncodedFieldConverter = None
    lazy val multipartFieldConverter = Some(implicitly[FBPOU[T]])
  }
}

private[unmarshalling] abstract class FfcLowerPrioImplicits2 {
  implicit def liftToTargetOption[T](implicit ffc: FormFieldConverter[T]) = new FormFieldConverter[Option[T]] {
    lazy val urlEncodedFieldConverter = ffc.urlEncodedFieldConverter.map(Deserializer.liftToTargetOption(_))
    lazy val multipartFieldConverter = ffc.multipartFieldConverter.map(Deserializer.liftToTargetOption(_))
  }
}
