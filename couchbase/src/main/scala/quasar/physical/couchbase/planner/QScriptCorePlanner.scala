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

package quasar.physical.couchbase.planner

import quasar.Predef._
import quasar.contrib.matryoshka._
import quasar.fp._, eitherT._, ski.κ
import quasar.{ejson, NameGenerator, PhaseResultT}
import quasar.PhaseResult.Detail
import quasar.physical.couchbase._
import quasar.physical.couchbase.N1QL._, Select._
import quasar.physical.couchbase.planner.Planner._
import quasar.Planner.{InternalError, PlannerError}
import quasar.qscript, qscript.{Map => _, Read => _, _}

import matryoshka.{Hole => _, _}, Recursive.ops._
import scalaz._, Scalaz.{ToIdOps => _, _}

final class QScriptCorePlanner[F[_]: Monad: NameGenerator, T[_[_]]: Recursive: Corecursive: ShowT]
  extends Planner[F, QScriptCore[T, ?]] {

  def processFreeMapDefault(f: FreeMap[T], tmpName: String): M[N1QL] =
    freeCataM(f)(interpretM(
      κ(partialQueryString(tmpName).point[M]),
      mapFuncPlanner[F, T].plan))

  def processFreeMap(f: FreeMap[T], tmpName: String): M[N1QL] =
    f.toCoEnv[T].project match {
      case MapFunc.StaticMap(elems) =>
        elems.traverse(_.bitraverse(
          {
            case Embed(ejson.Common(ejson.Str(key))) =>
              key.point[M]
            case key =>
              EitherT(
                (InternalError(s"Unsupported object key: ${key.shows}"): PlannerError)
                  .left[String].point[PhaseResultT[F, ?]])
          },
          v => processFreeMapDefault(v.fromCoEnv, tmpName)
        )) ∘ (m =>
          partialQueryString(m.map { case (k, v) => s""""$k": ${n1ql(v)}""" }.mkString("{", ", ", "}"))
        )
      case _ =>
        processFreeMapDefault(f, tmpName)
    }


  val plan: AlgebraM[M, QScriptCore[T, ?], N1QL] = {
    case qscript.Map(src, f) =>
      for {
        tmpName  <- genName[M]
        srcN1ql  =  n1ql(src)
        ff       <- processFreeMap(f, tmpName)
        ffN1ql   =  n1ql(ff)
        rN1ql    =  src match {
                      case _: Select | _: Read =>
                        select(
                          value         = true,
                          resultExprs   = ffN1ql.wrapNel,
                          keyspace      = src,
                          keyspaceAlias = tmpName)
                      case _ =>
                        selectLet(
                          value       = true,
                          resultExprs = ffN1ql.wrapNel,
                          let         = Map(tmpName -> srcN1ql))
                    }
        rN1qlStr =  n1ql(rN1ql)
        _        <- prtell[M](Vector(Detail(
                     "N1QL Map",
                       s"""  src: ${n1ql(src)}
                          |  f:   $ffN1ql
                          |  n1ql: $rN1qlStr""".stripMargin('|'))))
      } yield rN1ql

    case LeftShift(src, struct, repair) =>
      for {
        tmpName1 <- genName[M]
        tmpName2 <- genName[M]
        s        <- freeCataM(struct)(interpretM(
                      κ(partialQueryString(tmpName1).point[M]),
                      mapFuncPlanner[F, T].plan))
        sN1ql    =  n1ql(s)
        r        <- freeCataM(repair)(interpretM(
                      {
                        case LeftSide  =>
                          partialQueryString(tmpName1).point[M]
                        case RightSide =>
                          (
                            select(
                              value         = true,
                              resultExprs   = sN1ql.wrapNel,
                              keyspace      = src,
                              keyspaceAlias = tmpName2) |>
                            unnest.set(s"$tmpName2 as $tmpName1".some)
                          ).n1ql.point[M]
                      },
                      mapFuncPlanner[F, T].plan))
        rN1ql    =  n1ql(r)
        _        <- prtell[M](Vector(Detail(
                      "N1QL LeftShift",
                      s"""  src:    ${n1ql(src)}
                         |  struct: $sN1ql
                         |  repair: $rN1ql
                         |  n1ql:   $rN1ql""".stripMargin('|'))))

      } yield r

    case qscript.Reduce(src, bucket, reducers, repair) =>
      for {
        tmpName <- genName[M]
        b       <- processFreeMap(bucket, tmpName)
        red     =  reducers.map(
                     _.traverse(
                       red => processFreeMap(red, tmpName)
                     ).flatMap(reduceFuncPlanner[F].plan)
                   )
        rep     <- freeCataM(repair)(interpretM(i => red(i.idx), mapFuncPlanner[F, T].plan))
        repN1ql =  n1ql(rep)
        bN1ql   =  n1ql(b)
        s       =  select(
                     value         = true,
                     resultExprs   = repN1ql.wrapNel,
                     keyspace      = src,
                     keyspaceAlias = tmpName) |>
                     groupBy.set(bN1ql.some)
        sN1ql   =  n1ql(s)
        _       <- prtell[M](Vector(Detail(
                     "N1QL Reduce",
                     s"""  src:      ${n1ql(src)}
                        |  bucket:   $bN1ql
                        |  reducers: $red
                        |  repair:   $repN1ql
                        |  n1ql:     $sN1ql""".stripMargin('|'))))
      } yield s

    case qscript.Sort(src, bucket, order) =>
      for {
        tmpName <- genName[M]
        b       <- processFreeMap(bucket, tmpName)
        o       <- order.traverse { case (or, d) =>
                     val dir = d match {
                       case SortDir.Ascending  => "ASC"
                       case SortDir.Descending => "DESC"
                     }
                     processFreeMap(or, tmpName) ∘ (ord => s"${n1ql(ord)} $dir")
                   }.map(_.mkString(", "))
        bN1ql   =  n1ql(b)
        s       =  select(
                     value         = true,
                     resultExprs   = tmpName.wrapNel,
                     keyspace      = src,
                     keyspaceAlias = tmpName)     |>
                   groupBy.set(bN1ql.some) >>>
                   orderBy.set(o.some)
        _       <- prtell[M](Vector(Detail(
                     "N1QL Sort",
                     s"""  src:    ${n1ql(src)}
                        |  bucket: $bN1ql
                        |  order:  $o
                        |  n1ql:   ${n1ql(s)}""".stripMargin('|'))))

      } yield s

    case qscript.Filter(src, f) =>
      for {
        tmpName  <- genName[M]
        fN1ql    <- processFreeMap(f, tmpName)
        fN1qlStr =  n1ql(fN1ql)
        sel      =  select(
                      value         = true,
                      resultExprs   = tmpName.wrapNel,
                      keyspace      = src,
                      keyspaceAlias = tmpName) |>
                    filter.set(fN1qlStr.some)
        _        <- prtell[M](Vector(Detail(
                      "N1QL Filter",
                      s"""  src:  ${n1ql(src)}
                         |  f:    $fN1qlStr
                         |  n1ql: ${n1ql(sel)}""".stripMargin('|'))))
      } yield sel

    case Union(src, lBranch, rBranch) =>
      for {
        tmpNameLB <- genName[M]
        tmpNameRB <- genName[M]
        lb        <- freeCataM(lBranch)(interpretM(
                       κ(partialQueryString(tmpNameLB).point[M]),
                       Planner[F, QScriptTotal[T, ?]].plan))
        rb        <- freeCataM(rBranch)(interpretM(
                       κ(partialQueryString(tmpNameRB).point[M]),
                       Planner[F, QScriptTotal[T, ?]].plan))
        srcN1ql   =  n1ql(src)
        lbN1ql    =  n1ql(lb)
        rbN1ql    =  n1ql(rb)
        n1qlStr   =  s"($srcN1ql).($lbN1ql) union ($srcN1ql).($rbN1ql)"
        _         <- prtell[M](Vector(Detail(
                       "N1QL Union",
                       s"""  src:     $src
                          |  lBranch: $lb
                          |  rBranch: $rb
                          |  n1ql:    $n1qlStr""".stripMargin('|'))))
      } yield partialQueryString(n1qlStr)

    case qscript.Subset(src, from, op, count) => op match {
      case Drop   => takeOrDrop(src, from, count.right)
      case Take   => takeOrDrop(src, from, count.left)
      case Sample => unimplementedP("Sample")
    }

    case qscript.Unreferenced() =>
      partialQueryString("(select value [])").point[M]
  }

  def takeOrDrop(src: N1QL, from: FreeQS[T], takeOrDrop: FreeQS[T] \/ FreeQS[T]): M[N1QL] =
    for {
      tmpName1 <- genName[M]
      tmpName2 <- genName[M]
      tmpName3 <- genName[M]
      tmpName4 <- genName[M]
      tmpName5 <- genName[M]
      f        <- freeCataM(from)(interpretM(
                    κ(partialQueryString(tmpName1).point[M]),
                    Planner[F, QScriptTotal[T, ?]].plan))
      c        <- freeCataM(takeOrDrop.merge)(interpretM(
                    κ(partialQueryString(tmpName1).point[M]),
                    Planner[F, QScriptTotal[T, ?]].plan))
      sN1ql    =  n1ql(src)
      fN1ql    =  n1ql(f)
      cN1ql    =  n1ql(c)
      ks       =  select(
                    value         = true,
                    resultExprs   = fN1ql.wrapNel,
                    keyspace      = src,
                    keyspaceAlias = tmpName1)
      slice    =  takeOrDrop.bimap(κ(s"0:$tmpName2[0]"), κ(s"$tmpName2[0]:")).merge
      slc      =  select(
                    value         = true,
                    resultExprs   = s"$tmpName3[$slice]".wrapNel,
                    keyspace      = ks,
                    keyspaceAlias = tmpName3) |>
                  let.set(Map(tmpName2 -> cN1ql).some)
      sel     = select(
                    value         = true,
                    resultExprs   = s"$tmpName5".wrapNel,
                    keyspace      = slc,
                    keyspaceAlias = tmpName4)           |>
                unnest.set(s"$tmpName4 $tmpName5".some)
      selN1ql =  n1ql(sel)
      _       <- prtell[M](Vector(Detail(
                   s"""N1QL ${takeOrDrop.bimap(κ("Take"), κ("Drop")).merge}""",
                   s"""  src:   $sN1ql
                      |  from:  $fN1ql
                      |  count: $cN1ql
                      |  n1ql:  $selN1ql""".stripMargin('|'))))
    } yield sel
}
