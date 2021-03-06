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

package quasar

import quasar.Predef._
import quasar.contrib.matryoshka._

import matryoshka._, TraverseT.ops._
import matryoshka.patterns._
import monocle.Lens
import scalaz.{Lens => _, _}, Liskov._, Scalaz._
import scalaz.iteratee.EnumeratorT
import scalaz.stream._
import shapeless.{Fin, Nat, Sized, Succ}
import simulacrum.typeclass

sealed trait LowerPriorityTreeInstances {
  implicit def Tuple2RenderTree[A, B](implicit RA: RenderTree[A], RB: RenderTree[B]):
      RenderTree[(A, B)] =
    new RenderTree[(A, B)] {
      def render(t: (A, B)) =
        NonTerminal("tuple" :: Nil, None,
          RA.render(t._1) ::
            RB.render(t._2) ::
            Nil)
    }
}

sealed trait LowPriorityTreeInstances extends LowerPriorityTreeInstances {
  implicit def LeftTuple3RenderTree[A, B, C](implicit RA: RenderTree[A], RB: RenderTree[B], RC: RenderTree[C]):
      RenderTree[((A, B), C)] =
    new RenderTree[((A, B), C)] {
      def render(t: ((A, B), C)) =
        NonTerminal("tuple" :: Nil, None,
          RA.render(t._1._1) ::
            RB.render(t._1._2) ::
            RC.render(t._2) ::
            Nil)
    }
}

sealed trait TreeInstances extends LowPriorityTreeInstances {
  implicit def LeftTuple4RenderTree[A, B, C, D](implicit RA: RenderTree[A], RB: RenderTree[B], RC: RenderTree[C], RD: RenderTree[D]):
      RenderTree[(((A, B), C), D)] =
    new RenderTree[(((A, B), C), D)] {
      def render(t: (((A, B), C), D)) =
        NonTerminal("tuple" :: Nil, None,
           RA.render(t._1._1._1) ::
            RB.render(t._1._1._2) ::
            RC.render(t._1._2) ::
            RD.render(t._2) ::
            Nil)
    }

  implicit def EitherRenderTree[A, B](implicit RA: RenderTree[A], RB: RenderTree[B]):
      RenderTree[A \/ B] =
    new RenderTree[A \/ B] {
      def render(v: A \/ B) =
        v match {
          case -\/ (a) => NonTerminal("-\\/" :: Nil, None, RA.render(a) :: Nil)
          case \/- (b) => NonTerminal("\\/-" :: Nil, None, RB.render(b) :: Nil)
        }
    }

  implicit def OptionRenderTree[A](implicit RA: RenderTree[A]):
      RenderTree[Option[A]] =
    new RenderTree[Option[A]] {
      def render(o: Option[A]) = o match {
        case Some(a) => RA.render(a)
        case None => Terminal("None" :: "Option" :: Nil, None)
      }
    }

  implicit def ListRenderTree[A](implicit RA: RenderTree[A]):
      RenderTree[List[A]] =
    new RenderTree[List[A]] {
      def render(v: List[A]) = NonTerminal(List("List"), None, v.map(RA.render))
    }

  implicit def ListMapRenderTree[K: Show, V](implicit RV: RenderTree[V]):
      RenderTree[ListMap[K, V]] =
    new RenderTree[ListMap[K, V]] {
      def render(v: ListMap[K, V]) =
        NonTerminal("Map" :: Nil, None,
          v.toList.map { case (k, v) =>
            NonTerminal("Key" :: "Map" :: Nil, Some(k.shows), RV.render(v) :: Nil)
          })
    }

  implicit def ListMapEqual[A: Equal, B: Equal]: Equal[ListMap[A, B]] =
    Equal.equalBy(_.toList)

  implicit def VectorRenderTree[A](implicit RA: RenderTree[A]):
      RenderTree[Vector[A]] =
    new RenderTree[Vector[A]] {
      def render(v: Vector[A]) = NonTerminal(List("Vector"), None, v.map(RA.render).toList)
    }

  implicit val BooleanRenderTree: RenderTree[Boolean] =
    RenderTree.fromShow[Boolean]("Boolean")
  implicit val IntRenderTree: RenderTree[Int] =
    RenderTree.fromShow[Int]("Int")
  implicit val DoubleRenderTree: RenderTree[Double] =
    RenderTree.fromShow[Double]("Double")
  implicit val StringRenderTree: RenderTree[String] =
    RenderTree.fromShow[String]("String")

  implicit val SymbolEqual: Equal[Symbol] = Equal.equalA

  implicit def PathRenderTree[B,T,S]: RenderTree[pathy.Path[B,T,S]] =
    new RenderTree[pathy.Path[B,T,S]] {
      // NB: the implicit Show instance in scope here ends up being a circular
      // call, so an explicit reference to pathy's Show is needed.
      def render(v: pathy.Path[B,T,S]) = Terminal(List("Path"), pathy.Path.PathShow.shows(v).some)
    }
}

sealed trait ListMapInstances {
  implicit def seqW[A](xs: Seq[A]): SeqW[A] = new SeqW(xs)
  class SeqW[A](xs: Seq[A]) {
    def toListMap[B, C](implicit ev: A <~< (B, C)): ListMap[B, C] = {
      ListMap(co[Seq, A, (B, C)](ev)(xs) : _*)
    }
  }

  implicit def TraverseListMap[K]:
      Traverse[ListMap[K, ?]] with IsEmpty[ListMap[K, ?]] =
    new Traverse[ListMap[K, ?]] with IsEmpty[ListMap[K, ?]] {
      // FIXME: not sure what is being overloaded here
      @SuppressWarnings(Array("org.wartremover.warts.Overloading"))
      def empty[V] = ListMap.empty[K, V]
      def plus[V](a: ListMap[K, V], b: => ListMap[K, V]) = a ++ b
      def isEmpty[V](fa: ListMap[K, V]) = fa.isEmpty
      override def map[A, B](fa: ListMap[K, A])(f: A => B) = fa.map{case (k, v) => (k, f(v))}
      def traverseImpl[G[_],A,B](m: ListMap[K,A])(f: A => G[B])(implicit G: Applicative[G]): G[ListMap[K,B]] = {
        import G.functorSyntax._
        scalaz.std.list.listInstance.traverseImpl(m.toList)({ case (k, v) => f(v) map (k -> _) }) map (_.toListMap)
      }
    }
}

trait OptionTInstances {
  implicit def optionTCatchable[F[_]: Catchable : Functor]: Catchable[OptionT[F, ?]] =
    new Catchable[OptionT[F, ?]] {
      def attempt[A](fa: OptionT[F, A]) =
        OptionT[F, Throwable \/ A](
          Catchable[F].attempt(fa.run) map {
            case -\/(t)  => Some(\/.left(t))
            case \/-(oa) => oa map (\/.right)
          })

      def fail[A](t: Throwable) =
        OptionT[F, A](Catchable[F].fail(t))
    }
}

trait StateTInstances {
  implicit def stateTCatchable[F[_]: Catchable : Monad, S]: Catchable[StateT[F, S, ?]] =
    new Catchable[StateT[F, S, ?]] {
      def attempt[A](fa: StateT[F, S, A]) =
        StateT[F, S, Throwable \/ A](s =>
          Catchable[F].attempt(fa.run(s)) map {
            case -\/(t)       => (s, t.left)
            case \/-((s1, a)) => (s1, a.right)
          })

      def fail[A](t: Throwable) =
        StateT[F, S, A](_ => Catchable[F].fail(t))
    }
}

trait WriterTInstances {
  implicit def writerTCatchable[F[_]: Catchable : Functor, W: Monoid]: Catchable[WriterT[F, W, ?]] =
    new Catchable[WriterT[F, W, ?]] {
      def attempt[A](fa: WriterT[F, W, A]) =
        WriterT[F, W, Throwable \/ A](
          Catchable[F].attempt(fa.run) map {
            case -\/(t)      => (mzero[W], t.left)
            case \/-((w, a)) => (w, a.right)
          })

      def fail[A](t: Throwable) =
        WriterT(Catchable[F].fail(t).strengthL(mzero[W]))
    }
}

trait ToCatchableOps {
  trait CatchableOps[F[_], A] extends scalaz.syntax.Ops[F[A]] {
    import fp.ski._

    /** A new task which runs a cleanup task only in the case of failure, and
      * ignores any result from the cleanup task.
      */
    final def onFailure(cleanup: F[_])(implicit FM: Monad[F], FC: Catchable[F]):
        F[A] =
      self.attempt.flatMap(_.fold(
        err => cleanup.attempt.flatMap(κ(FC.fail(err))),
        _.point[F]))

    /** A new task that ignores the result of this task, and runs another task
      * no matter what.
      */
    final def ignoreAndThen[B](t: F[B])(implicit FB: Bind[F], FC: Catchable[F]):
        F[B] =
      self.attempt.flatMap(κ(t))
  }

  implicit def ToCatchableOpsFromCatchable[F[_], A](a: F[A]):
      CatchableOps[F, A] =
    new CatchableOps[F, A] { val self = a }
}

trait PartialFunctionOps {
  implicit class PFOps[A, B](self: PartialFunction[A, B]) {
    def |?| [C](that: PartialFunction[A, C]): PartialFunction[A, B \/ C] =
      Function.unlift(v =>
        self.lift(v).fold[Option[B \/ C]](
          that.lift(v).map(\/-(_)))(
          x => Some(-\/(x))))
  }
}

trait JsonOps {
  import argonaut._
  import fp.ski._

  def optional[A: DecodeJson](cur: ACursor): DecodeResult[Option[A]] =
    cur.either.fold(
      κ(DecodeResult(scala.util.Right(None))),
      v => v.as[A].map(Some(_)))

  def orElse[A: DecodeJson](cur: ACursor, default: => A): DecodeResult[A] =
    cur.either.fold(
      κ(DecodeResult(scala.util.Right(default))),
      v => v.as[A]
    )

  def decodeJson[A](text: String)(implicit DA: DecodeJson[A]): String \/ A = \/.fromEither(for {
    json <- Parse.parse(text)
    a <- DA.decode(json.hcursor).result.leftMap { case (exp, hist) => "expected: " + exp + "; " + hist }
  } yield a)


  /* Nicely formatted, order-preserving, single-line. */
  val minspace = PrettyParams(
    "",       // indent
    "", " ",  // lbrace
    " ", "",  // rbrace
    "", " ",  // lbracket
    " ", "",  // rbracket
    "",       // lrbracketsEmpty
    "", " ",  // arrayComma
    "", " ",  // objectComma
    "", " ",  // colon
    true,     // preserveOrder
    false     // dropNullKeys
  )

  /** Nicely formatted, order-preserving, 2-space indented. */
  val multiline = PrettyParams(
    "  ",     // indent
    "", "\n",  // lbrace
    "\n", "",  // rbrace
    "", "\n",  // lbracket
    "\n", "",  // rbracket
    "",       // lrbracketsEmpty
    "", "\n",  // arrayComma
    "", "\n",  // objectComma
    "", " ",  // colon
    true,     // preserveOrder
    false     // dropNullKeys
  )
}

trait QFoldableOps {
  final implicit class ToQFoldableOps[F[_]: Foldable, A](val self: F[A]) {
    final def toProcess: Process0[A] =
      self.foldRight[Process0[A]](Process.halt)((a, p) => Process.emit(a) ++ p)
  }
}

trait DebugOps {
  final implicit class ToDebugOps[A](val self: A) {
    /** Applies some operation to a value and returns the original value. Useful
      * for things like adding debugging printlns in the middle of an
      * expression.
      */
    final def <|(f: A => Unit): A = {
      f(self)
      self
    }
  }
}


package object fp
    extends TreeInstances
    with ListMapInstances
    with OptionTInstances
    with StateTInstances
    with WriterTInstances
    with ToCatchableOps
    with PartialFunctionOps
    with JsonOps
    with ProcessOps
    with QFoldableOps
    with DebugOps
    with CatchableInstances {


  import ski._

  type EnumT[F[_], A] = EnumeratorT[A, F]

  /** An endomorphism is a mapping from a category to itself.
   *  It looks like scalaz already staked out "Endo" for the
   *  lower version.
   */
  type EndoK[F[X]] = scalaz.NaturalTransformation[F, F]

  // TODO generalize this and matryoshka.Delay into
  // `type KleisliK[M[_], F[_], G[_]] = F ~> (M ∘ G)#λ`
  type NTComp[F[X], G[Y]] = scalaz.NaturalTransformation[F, matryoshka.∘[G, F]#λ]

  implicit def ShowShowF[F[_], A: Show, FF[A] <: F[A]](implicit FS: ShowF[F]):
      Show[FF[A]] =
    new Show[FF[A]] { override def show(fa: FF[A]) = FS.show(fa) }

  implicit def ShowFNT[F[_]](implicit SF: ShowF[F]) =
    λ[Show ~> λ[α => Show[F[α]]]](st => ShowShowF(st, SF))

  implicit def EqualEqualF[F[_], A: Equal, FF[A] <: F[A]](implicit FE: EqualF[F]):
      Equal[FF[A]] =
    new Equal[FF[A]] { def equal(fa1: FF[A], fa2: FF[A]) = FE.equal(fa1, fa2) }

  implicit def EqualFNT[F[_]](implicit EF: EqualF[F]):
      Equal ~> λ[α => Equal[F[α]]] =
    new (Equal ~> λ[α => Equal[F[α]]]) {
      def apply[α](eq: Equal[α]): Equal[F[α]] = EqualEqualF(eq, EF)
    }

  def unzipDisj[A, B](ds: List[A \/ B]): (List[A], List[B]) = {
    val (as, bs) = ds.foldLeft((List[A](), List[B]())) {
      case ((as, bs), -\/ (a)) => (a :: as, bs)
      case ((as, bs),  \/-(b)) => (as, b :: bs)
    }
    (as.reverse, bs.reverse)
  }

  /** Accept a value (forcing the argument expression to be evaluated for its
    * effects), and then discard it, returning Unit. Makes it explicit that
    * you're discarding the result, and effectively suppresses the
    * "NonUnitStatement" warning from wartremover.
    */
  def ignore[A](a: A): Unit = ()

  def reflNT[F[_]] = λ[F ~> F](x => x)

  /** `liftM` as a natural transformation
    *
    * TODO: PR to scalaz
    */
  def liftMT[F[_]: Monad, G[_[_], _]: MonadTrans] = λ[F ~> G[F, ?]](_.liftM[G])

  /** `point` as a natural transformation */
  def pointNT[F[_]: Applicative] = λ[Id ~> F](Applicative[F] point _)

  def evalNT[F[_]: Monad, S](initial: S) = λ[StateT[F, S, ?] ~> F](_ eval initial)

  def liftFG[F[_], G[_], A](orig: F[A] => G[A])(implicit F: F :<: G):
      G[A] => G[A] =
    ftf => F.prj(ftf).fold(ftf)(orig)

  def liftFGM[M[_]: Monad, F[_], G[_], A](orig: F[A] => M[G[A]])(implicit F: F :<: G):
      G[A] => M[G[A]] =
    ftf => F.prj(ftf).fold(ftf.point[M])(orig)

  def liftFF[F[_], G[_], A](orig: F[A] => F[A])(implicit F: F :<: G):
      G[A] => G[A] =
    ftf => F.prj(ftf).fold(ftf)(orig.andThen(F.inj))

  implicit final class ListOps[A](val self: List[A]) extends scala.AnyVal {
    final def mapAccumLeft1[B, C](c: C)(f: (C, A) => (C, B)): (C, List[B]) = self.mapAccumLeft(c, f)
  }

  implicit def coproductEqual[F[_], G[_]](implicit F: Delay[Equal, F], G: Delay[Equal, G]) = λ[Equal ~> DelayedFG[F, G]#Equal](eq =>
    Equal equal ((cp1, cp2) =>
      (cp1.run, cp2.run) match {
        case (-\/(f1), -\/(f2)) => F(eq).equal(f1, f2)
        case (\/-(g1), \/-(g2)) => G(eq).equal(g1, g2)
        case (_,       _)       => false
      }
    )
  )
  implicit def coproductShow[F[_], G[_]](implicit F: Delay[Show, F], G: Delay[Show, G]) =
    λ[Show ~> DelayedFG[F, G]#Show](sh => Show show (_.run.fold(F(sh).show, G(sh).show)))

  implicit def constEqual[A: Equal] =
    λ[Equal ~> DelayedA[A]#Equal](_ => Equal equal (_.getConst === _.getConst))

  implicit def constShow[A: Show] =
    λ[Show ~> DelayedA[A]#Show](_ => Show show (Show[A] show _.getConst))

  implicit def sizedEqual[A: Equal, N <: Nat]: Equal[Sized[A, N]] =
    Equal.equal((a, b) => a.unsized ≟ b.unsized)

  implicit def sizedShow[A: Show, N <: Nat]: Show[Sized[A, N]] =
    Show.showFromToString

  implicit def natEqual[N <: Nat]: Equal[N] = Equal.equal((a, b) => true)

  implicit def natShow[N <: Nat]: Show[N] = Show.showFromToString

  implicit def finEqual[N <: Succ[_]]: Equal[Fin[N]] =
    Equal.equal((a, b) => true)

  implicit def finShow[N <: Succ[_]]: Show[Fin[N]] = Show.showFromToString

  implicit final class QuasarFreeOps[F[_], A](val self: Free[F, A]) extends scala.AnyVal {
    type Self    = Free[F, A]
    type Step[X] = F[X] \/ A

    def resumeTwice(implicit F: Functor[F]): Step[Step[Self]] =
      self.resume leftMap (_ map (_.resume))

    def toCoEnv[T[_[_]]: Corecursive](implicit F: Functor[F]): T[CoEnv[A, F, ?]] =
      self ana CoEnv.freeIso[A, F].reverseGet
  }

  def liftCo[T[_[_]], F[_], A](f: F[T[CoEnv[A, F, ?]]] => CoEnv[A, F, T[CoEnv[A, F, ?]]]):
      CoEnv[A, F, T[CoEnv[A, F, ?]]] => CoEnv[A, F, T[CoEnv[A, F, ?]]] =
    co => co.run.fold(κ(co), f)

  def idPrism[F[_]] = PrismNT[F, F](
    λ[F ~> (Option ∘ F)#λ](_.some),
    reflNT[F])

  def coenvPrism[F[_], A] = PrismNT[CoEnv[A, F, ?], F](
    λ[CoEnv[A, F, ?] ~> λ[α => Option[F[α]]]](_.run.toOption),
    λ[F ~> CoEnv[A, F, ?]](fb => CoEnv(fb.right[A])))
}

package fp {
  @typeclass
  trait ShowF[F[_]] {
    def show[A](fa: F[A])(implicit sa: Show[A]): Cord
  }
  @typeclass
  trait EqualF[F[_]] {
    @op("≟", true) def equal[A](fa1: F[A], fa2: F[A])(implicit eq: Equal[A]): Boolean
    @op("≠") def notEqual[A](fa1: F[A], fa2: F[A])(implicit eq: Equal[A]): Boolean = !equal(fa1, fa2)
  }
  @typeclass
  trait SemigroupF[F[_]] {
    @op("⊹", true) def append[A: Semigroup](fa1: F[A], fa2: F[A]): F[A]
  }

  /** Lift a `State` computation to operate over a "larger" state given a `Lens`.
    *
    * NB: Uses partial application of `F[_]` for better type inference, usage:
    *
    *   `zoomNT[F](lens)`
    */
  object zoomNT {
    def apply[F[_]]: Aux[F] =
      new Aux[F]

    final class Aux[F[_]] {
      type ST[S, A] = StateT[F, S, A]
      def apply[A, B](lens: Lens[A, B])(implicit M: Monad[F]): ST[B, ?] ~> ST[A, ?] =
        new (ST[B, ?] ~> ST[A, ?]) {
          def apply[C](s: ST[B, C]) =
            StateT((a: A) => s.run(lens.get(a)).map(_.leftMap(lens.set(_)(a))))
        }
    }
  }
  object Inj {
    def unapply[F[_], G[_], A](g: G[A])(implicit F: F :<: G): Option[F[A]] =
      F.prj(g)
  }

  // type Delay[F[_], G[_]] = F ~> λ[A => F[G[A]]]
  trait DelayedA[A] {
    /** The B is discarded in each case; the type was fixed by A. */
    type Show[B]       = scalaz.Show[Const[A, B]]
    type Equal[B]      = scalaz.Equal[Const[A, B]]
    type RenderTree[B] = quasar.RenderTree[Const[A, B]]
  }
  trait DelayedFG[F[_], G[_]] {
    type Equal[A]      = scalaz.Equal[Coproduct[F, G, A]]
    type Show[A]       = scalaz.Show[Coproduct[F, G, A]]
    type RenderTree[A] = quasar.RenderTree[Coproduct[F, G, A]]
  }
}
