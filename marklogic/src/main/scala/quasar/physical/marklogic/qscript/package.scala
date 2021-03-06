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
import quasar.ejson.{Common, Str}
import quasar.fp.{coproductShow, QuasarFreeOps}
import quasar.fp.ski.κ
import quasar.contrib.matryoshka.{freeCataM, interpretM}
import quasar.physical.marklogic.validation._
import quasar.physical.marklogic.xml._
import quasar.physical.marklogic.xquery.{ejson => ejs, _}
import quasar.physical.marklogic.xquery.syntax._
import quasar.qscript._

import eu.timepit.refined.refineV
import matryoshka.{Corecursive, Embed, Fix, Recursive}, Recursive.ops._
import matryoshka.patterns.CoEnv
import scalaz._, Scalaz._

package object qscript {
  type MarkLogicPlanErrT[F[_], A] = EitherT[F, MarkLogicPlannerError, A]

  type MonadPlanErr[F[_]] = MonadError_[F, MarkLogicPlannerError]

  object MonadPlanErr {
    def apply[F[_]](implicit F: MonadPlanErr[F]): MonadPlanErr[F] = F
  }

  type MarkLogicPlanner[F[_], QS[_]] = Planner[F, QS, XQuery]

  object MarkLogicPlanner {
    def apply[F[_], QS[_]](implicit MLP: MarkLogicPlanner[F, QS]): MarkLogicPlanner[F, QS] = MLP

    implicit def qScriptCore[F[_]: QNameGenerator: PrologW: MonadPlanErr, T[_[_]]: Recursive: Corecursive]: MarkLogicPlanner[F, QScriptCore[T, ?]] =
      new QScriptCorePlanner[F, T]

    implicit def constDeadEnd[F[_]: Applicative]: MarkLogicPlanner[F, Const[DeadEnd, ?]] =
      new DeadEndPlanner[F]

    implicit def constRead[F[_]: Applicative]: MarkLogicPlanner[F, Const[Read, ?]] =
      new ReadPlanner[F]

    implicit def constShiftedRead[F[_]: QNameGenerator: PrologW]: MarkLogicPlanner[F, Const[ShiftedRead, ?]] =
      new ShiftedReadPlanner[F]

    implicit def projectBucket[F[_]: Applicative, T[_[_]]]: MarkLogicPlanner[F, ProjectBucket[T, ?]] =
      new ProjectBucketPlanner[F, T]

    implicit def thetajoin[F[_]: QNameGenerator: PrologW: MonadPlanErr, T[_[_]]: Recursive: Corecursive]: MarkLogicPlanner[F, ThetaJoin[T, ?]] =
      new ThetaJoinPlanner[F, T]

    implicit def equiJoin[F[_]: Applicative, T[_[_]]]: MarkLogicPlanner[F, EquiJoin[T, ?]] =
      new EquiJoinPlanner[F, T]
  }

  /** Matches "iterative" FLWOR expressions, those involving at least one `for` clause. */
  object IterativeFlwor {
    def unapply(xqy: XQuery): Option[(NonEmptyList[BindingClause], Option[XQuery], IList[(XQuery, SortDirection)], Boolean, XQuery)] = xqy match {
      case XQuery.Flwor(clauses, filter, order, isStable, result) if clauses.any(BindingClause.forClause.isMatching) =>
        Some((clauses, filter, order, isStable, result))

      case _ => None
    }
  }

  /** Converts the given string to a QName if valid, failing with an error otherwise. */
  def asQName[F[_]: MonadPlanErr: Applicative](s: String): F[QName] =
    refineV[IsNCName](encodeForQName(s)).disjunction
      .map(ncn => QName.local(NCName(ncn)).point[F])
      .getOrElse(invalidQName(s))

  def mapFuncXQuery[T[_[_]]: Recursive: Corecursive, F[_]: QNameGenerator: PrologW: MonadPlanErr](fm: FreeMap[T], src: XQuery): F[XQuery] =
    fm.toCoEnv[T].project match {
      case MapFunc.StaticArray(elements) =>
        for {
          xqyElts <- elements.traverse(mapFuncXQueryP(_, src))
          arrElts <- xqyElts.traverse(ejs.mkArrayElt[F])
          arr     <- ejs.mkArray_[F](mkSeq(arrElts))
        } yield arr

      case MapFunc.StaticMap(entries)    =>
        for {
          xqyKV <- entries.traverse(_.bitraverse({
                     case Embed(Common(Str(s))) => asQName(s) map (qn => xs.QName(qn.xs))
                     case key                   => invalidQName[F, XQuery](key.convertTo[Fix].shows)
                   },
                   mapFuncXQueryP(_, src)))
          elts  <- xqyKV.traverse { case (k, v) => ejs.renameOrWrap[F].apply(k, v) }
          map   <- ejs.mkObject[F] apply mkSeq(elts)
        } yield map

      case other                         => mapFuncXQueryP(other.embed, src)
    }

  def mapFuncXQueryP[T[_[_]]: Recursive: Corecursive, F[_]: QNameGenerator: PrologW: MonadPlanErr](fm: T[CoEnv[Hole, MapFunc[T, ?], ?]], src: XQuery): F[XQuery] =
    planMapFuncP(fm)(κ(src))

  def mergeXQuery[T[_[_]]: Recursive: Corecursive, F[_]: QNameGenerator: PrologW: MonadPlanErr](jf: JoinFunc[T], l: XQuery, r: XQuery): F[XQuery] =
    planMapFunc[T, F, JoinSide](jf) {
      case LeftSide  => l
      case RightSide => r
    }

  def planMapFunc[T[_[_]]: Recursive: Corecursive, F[_]: QNameGenerator: PrologW: MonadPlanErr, A](
    freeMap: FreeMapA[T, A])(
    recover: A => XQuery
  ): F[XQuery] =
    planMapFuncP(freeMap.toCoEnv)(recover)

  def planMapFuncP[T[_[_]]: Recursive, F[_]: QNameGenerator: PrologW: MonadPlanErr, A](
    freeMap: T[CoEnv[A, MapFunc[T, ?], ?]])(
    recover: A => XQuery
  ): F[XQuery] =
    freeMap.cataM(interpretM(recover(_).point[F], MapFuncPlanner[T, F]))

  def rebaseXQuery[T[_[_]]: Recursive: Corecursive, F[_]: QNameGenerator: PrologW: MonadPlanErr](
    fqs: FreeQS[T], src: XQuery
  ): F[XQuery] = {
    import MarkLogicPlanner._
    freeCataM(fqs)(interpretM(κ(src.point[F]), Planner[F, QScriptTotal[T, ?], XQuery].plan))
  }

  ////

  // A string consisting only of digits.
  private val IntegralNumber = "^(\\d+)$".r

  // Applies transformations to string to make them valid QNames
  private val encodeForQName: String => String = {
    case IntegralNumber(n) => "_" + n
    case other             => other
  }

  private implicit def comfTraverse[T[_[_]], A]: Traverse[CoEnv[A, MapFunc[T, ?], ?]] =
    Bitraverse[CoEnv[?, MapFunc[T, ?], ?]].rightTraverse[A]

  private def invalidQName[F[_]: MonadPlanErr, A](s: String): F[A] =
    MonadError_[F, MarkLogicPlannerError].raiseError(
      MarkLogicPlannerError.invalidQName(s))
}
