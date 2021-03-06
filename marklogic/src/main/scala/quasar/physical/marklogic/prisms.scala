/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.physical.marklogic

import quasar.Predef._

import java.util.Base64

import monocle.Prism
import org.threeten.bp._
import org.threeten.bp.format._
import org.threeten.bp.temporal.{TemporalAccessor, TemporalQuery}
import scalaz._

object prisms {
  val base64Bytes = Prism[String, ImmutableArray[Byte]](
    s => \/.fromTryCatchNonFatal(Base64.getDecoder.decode(s))
           .map(ImmutableArray.fromArray)
           .toOption
  )((Base64.getEncoder.encodeToString(_)) compose (_.toArray))

  val durationInSeconds = Prism[String, Duration] {
    case DurationEncoding(secs, nanos) =>
      \/.fromTryCatchNonFatal(Duration.ofSeconds(secs.toLong, nanos.toLong)).toOption

    case DurationEncoding(secs) =>
      \/.fromTryCatchNonFatal(Duration.ofSeconds(secs.toLong)).toOption

    case _ => None
  } (d => s"${d.getSeconds}.${d.getNano}")

  val isoInstant:   Prism[String, Instant]   = temporal(Instant.FROM  , DateTimeFormatter.ISO_INSTANT)
  val isoLocalDate: Prism[String, LocalDate] = temporal(LocalDate.FROM, DateTimeFormatter.ISO_DATE)
  val isoLocalTime: Prism[String, LocalTime] = temporal(LocalTime.FROM, DateTimeFormatter.ISO_TIME)

  ////

  private val DurationEncoding = "(-?\\d+)(?:\\.(\\d+))?".r

  private def temporal[T <: TemporalAccessor](q: TemporalQuery[T], fmt: DateTimeFormatter): Prism[String, T] =
    Prism[String, T](s => \/.fromTryCatchNonFatal(fmt.parse(s, q)).toOption)(fmt.format)
}
