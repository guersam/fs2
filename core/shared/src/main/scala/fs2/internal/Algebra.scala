package fs2.internal

import scala.collection.mutable.LinkedHashMap
import scala.concurrent.ExecutionContext
import cats.~>
import cats.effect.{ Effect, IO, Sync }
import cats.implicits._

import fs2.{ AsyncPull, Catenable, Segment }
import fs2.async

private[fs2] sealed trait Algebra[F[_],O,R]

private[fs2] object Algebra {

  final case class Output[F[_],O](values: Segment[O,Unit]) extends Algebra[F,O,Unit]
  final case class Run[F[_],O,R](values: Segment[O,R]) extends Algebra[F,O,R]
  final case class Eval[F[_],O,R](value: F[R]) extends Algebra[F,O,R]

  final case class Acquire[F[_],O,R](resource: F[R], release: R => F[Unit]) extends Algebra[F,O,(R,Token)]
  final case class Release[F[_],O](token: Token) extends Algebra[F,O,Unit]
  final case class OpenScope[F[_],O]() extends Algebra[F,O,Scope[F]]
  final case class CloseScope[F[_],O](toClose: Scope[F]) extends Algebra[F,O,Unit]

  final case class UnconsAsync[F[_],X,Y,O](s: FreeC[Algebra[F,O,?],Unit], ec: ExecutionContext)
    extends Algebra[F,X,AsyncPull[F,Option[(Segment[O,Unit],FreeC[Algebra[F,O,?],Unit])]]]

  def output[F[_],O](values: Segment[O,Unit]): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](Output(values))

  def output1[F[_],O](value: O): FreeC[Algebra[F,O,?],Unit] =
    output(Segment.singleton(value))

  def segment[F[_],O,R](values: Segment[O,R]): FreeC[Algebra[F,O,?],R] =
    FreeC.Eval[Algebra[F,O,?],R](Run(values))

  def eval[F[_],O,R](value: F[R]): FreeC[Algebra[F,O,?],R] =
    FreeC.Eval[Algebra[F,O,?],R](Eval(value))

  def acquire[F[_],O,R](resource: F[R], release: R => F[Unit]): FreeC[Algebra[F,O,?],(R,Token)] =
    FreeC.Eval[Algebra[F,O,?],(R,Token)](Acquire(resource, release))

  def release[F[_],O](token: Token): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](Release(token))

  def unconsAsync[F[_],X,Y,O](s: FreeC[Algebra[F,O,?],Unit], ec: ExecutionContext): FreeC[Algebra[F,X,?],AsyncPull[F,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]]] =
    FreeC.Eval[Algebra[F,X,?],AsyncPull[F,Option[(Segment[O,Unit],FreeC[Algebra[F,O,?],Unit])]]](UnconsAsync(s, ec))

  private def openScope[F[_],O]: FreeC[Algebra[F,O,?],Scope[F]] =
    FreeC.Eval[Algebra[F,O,?],Scope[F]](OpenScope())

  private def closeScope[F[_],O](toClose: Scope[F]): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](CloseScope(toClose))

  def scope[F[_],O,R](pull: FreeC[Algebra[F,O,?],R]): FreeC[Algebra[F,O,?],R] =
    openScope flatMap { newScope =>
      FreeC.Bind(pull, (e: Either[Throwable,R]) => e match {
        case Left(e) => closeScope(newScope) flatMap { _ => fail(e) }
        case Right(r) => closeScope(newScope) map { _ => r }
      })
    }

  def pure[F[_],O,R](r: R): FreeC[Algebra[F,O,?],R] =
    FreeC.Pure[Algebra[F,O,?],R](r)

  def fail[F[_],O,R](t: Throwable): FreeC[Algebra[F,O,?],R] =
    FreeC.Fail[Algebra[F,O,?],R](t)

  def suspend[F[_],O,R](f: => FreeC[Algebra[F,O,?],R]): FreeC[Algebra[F,O,?],R] =
    FreeC.suspend(f)

  final class Token

  final class Scope[F[_]] private (private val parent: Option[Scope[F]])(implicit F: Sync[F]) {
    private val monitor = this
    private var closing: Boolean = false
    private var closed: Boolean = false
    private var midAcquires: Int = 0
    private var midAcquiresDone: () => Unit = () => ()
    private val resources: LinkedHashMap[Token, F[Unit]] = new LinkedHashMap[Token, F[Unit]]()
    private var spawns: Catenable[Scope[F]] = Catenable.empty

    def isClosed: Boolean = monitor.synchronized { closed }

    def beginAcquire: Boolean = monitor.synchronized {
      if (closed || closing) false
      else {
        midAcquires += 1; true
      }
    }

    def finishAcquire(t: Token, finalizer: F[Unit]): Unit = monitor.synchronized {
      if (closed) throw new IllegalStateException("FS2 bug: scope cannot be closed while acquire is outstanding")
      resources += (t -> finalizer)
      midAcquires -= 1
      if (midAcquires == 0) midAcquiresDone()
    }

    def cancelAcquire(): Unit = monitor.synchronized {
      midAcquires -= 1
      if (midAcquires == 0) midAcquiresDone()
    }

    def releaseResource(t: Token): Option[F[Unit]] = monitor.synchronized {
      resources.remove(t)
    }

    def open: Scope[F] = {
      val spawn = monitor.synchronized {
        if (closing || closed) None
        else {
          val spawn = new Scope[F](Some(this))
          spawns = spawns :+ spawn
          Some(spawn)
        }
      }
      spawn.getOrElse {
        // This scope is already closed so try to promote the open to an ancestor; this can fail
        // if the root scope has already been closed, in which case, we can safely throw
        openAncestor.fold(_ => throw new IllegalStateException("cannot re-open root scope"), _.open)
      }
    }

    private def closeAndReturnFinalizers(asyncSupport: Option[(Effect[F], ExecutionContext)]): F[Catenable[(Token,F[Unit])]] = monitor.synchronized {
      if (closed || closing) {
        F.pure(Catenable.empty)
      } else {
        closing = true
        def finishClose: F[Catenable[(Token,F[Unit])]] = monitor.synchronized {
          import cats.syntax.traverse._
          import cats.syntax.functor._
          import cats.instances.vector._
          spawns.toVector.reverse.
            traverse(_.closeAndReturnFinalizers(asyncSupport)).
            map(_.foldLeft(Catenable.empty: Catenable[(Token,F[Unit])])(_ ++ _)).
            map { s =>
              monitor.synchronized {
                closed = true
                val result = s ++ Catenable.fromSeq(resources.toVector.reverse)
                resources.clear()
                result
              }
            }
        }
        if (midAcquires == 0) {
          finishClose
        } else {
          asyncSupport match {
            case None => throw new IllegalStateException(s"FS2 bug: closing a scope with midAcquires ${midAcquires} but no async steps")
            case Some((effect, ec)) =>
              val ref = new async.Ref[F,Unit]()(effect, ec)
              midAcquiresDone = () => {
                effect.runAsync(ref.setAsyncPure(()))(_ => IO.unit).unsafeRunSync
              }
              F.flatMap(ref.get) { _ => finishClose }
          }
        }
      }
    }

    def close(asyncSupport: Option[(Effect[F],ExecutionContext)]): F[Either[Throwable,Unit]] = {
      def runAll(sofar: Option[Throwable], finalizers: Catenable[F[Unit]]): F[Either[Throwable,Unit]] = finalizers.uncons match {
        case None => F.pure(sofar.toLeft(()))
        case Some((h, t)) => F.flatMap(F.attempt(h)) { res => runAll(sofar orElse res.fold(Some(_), _ => None), t) }
      }
      F.flatMap(closeAndReturnFinalizers(asyncSupport)) { finalizers =>
        runAll(None, finalizers.map(_._2))
      }
    }

    def openAncestor: Either[Scope[F],Scope[F]] = {
      def loop(s: Scope[F]): Either[Scope[F],Scope[F]] = {
        val opened = s.monitor.synchronized(!(s.closing || s.closed))
        if (opened) Right(s) else s.parent match {
          case Some(s2) => loop(s2)
          case None => Left(s)
        }
      }
      parent.toRight(this).flatMap(loop)
    }

    override def toString: String = ##.toString
  }

  object Scope {
    def newRoot[F[_]: Sync]: Scope[F] = new Scope[F](None)
  }

  def uncons[F[_],X,O](s: FreeC[Algebra[F,O,?],Unit], chunkSize: Int = 1024): FreeC[Algebra[F,X,?],Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] = {
    s.viewL.get match {
      case done: FreeC.Pure[Algebra[F,O,?], Unit] => pure(None)
      case failed: FreeC.Fail[Algebra[F,O,?], _] => fail(failed.error)
      case bound: FreeC.Bind[Algebra[F,O,?],_,Unit] =>
        val f = bound.f.asInstanceOf[Either[Throwable,Any] => FreeC[Algebra[F,O,?],Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F,O,?],_]].fr
        fx match {
          case os: Algebra.Output[F, O] =>
            pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some((os.values, f(Right(())))))
          case os: Algebra.Run[F, O, x] =>
            try {
              def asSegment(c: Catenable[Segment[O,Unit]]): Segment[O,Unit] =
                c.uncons.flatMap { case (h1,t1) => t1.uncons.map(_ => Segment.catenated(c)).orElse(Some(h1)) }.getOrElse(Segment.empty)
              os.values.splitAt(chunkSize) match {
                case Left((r,segments,rem)) =>
                  pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some(asSegment(segments) -> f(Right(r))))
                case Right((segments,tl)) =>
                  pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some(asSegment(segments) -> FreeC.Bind[Algebra[F,O,?],x,Unit](segment(tl), f)))
              }
            } catch { case NonFatal(e) => FreeC.suspend(uncons(f(Left(e)), chunkSize)) }
          case algebra => // Eval, Acquire, Release, OpenScope, CloseScope, UnconsAsync
            FreeC.Bind[Algebra[F,X,?],Any,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](
              FreeC.Eval[Algebra[F,X,?],Any](algebra.asInstanceOf[Algebra[F,X,Any]]),
              (x: Either[Throwable,Any]) => uncons[F,X,O](f(x), chunkSize)
            )
        }
      case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
  }

  /** Left-fold the output of a stream, supporting unconsAsync steps. */
  def runFoldEffect[F[_],O,B](stream: FreeC[Algebra[F,O,?],Unit], init: B)(f: (B, O) => B)(implicit F: Effect[F]): F[B] =
    runFold(stream, Some(F), init)(f)

  /** Left-fold the output of a stream, not supporting unconsAsync steps. */
  def runFoldSync[F[_],O,B](stream: FreeC[Algebra[F,O,?],Unit], init: B)(f: (B, O) => B)(implicit F: Sync[F]): F[B] =
    runFold(stream, None, init)(f)

  private def runFold[F[_],O,B](stream: FreeC[Algebra[F,O,?],Unit], effect: Option[Effect[F]], init: B)(f: (B, O) => B)(implicit F: Sync[F]): F[B] =
    F.delay(Scope.newRoot).flatMap { scope =>
      runFoldScope[F,O,B](scope, effect, None, stream, init)(f).attempt.flatMap {
        case Left(t) => scope.close(None) *> F.raiseError(t)
        case Right(b) => scope.close(None).as(b)
      }
    }

  private[fs2] def runFoldScope[F[_],O,B](scope: Scope[F], effect: Option[Effect[F]], ec: Option[ExecutionContext], stream: FreeC[Algebra[F,O,?],Unit], init: B)(g: (B, O) => B)(implicit F: Sync[F]): F[B] =
    runFoldLoop[F,O,B](scope, effect, ec, init, g, uncons(stream).viewL)

  private def runFoldLoop[F[_],O,B](scope: Scope[F], effect: Option[Effect[F]], ec: Option[ExecutionContext], acc: B, g: (B, O) => B, v: FreeC.ViewL[Algebra[F,O,?], Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]])(implicit F: Sync[F]): F[B] = {
    v.get match {
      case done: FreeC.Pure[Algebra[F,O,?], Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] => done.r match {
        case None => F.pure(acc)
        case Some((hd, tl)) =>
          F.suspend {
            try runFoldLoop[F,O,B](scope, effect, ec, hd.fold(acc)(g).run, g, uncons(tl).viewL)
            catch { case NonFatal(e) => runFoldLoop[F,O,B](scope, effect, ec, acc, g, uncons(tl.asHandler(e)).viewL) }
          }
      }
      case failed: FreeC.Fail[Algebra[F,O,?], _] => F.raiseError(failed.error)
      case bound: FreeC.Bind[Algebra[F,O,?], _, Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] =>
        val f = bound.f.asInstanceOf[
          Either[Throwable,Any] => FreeC[Algebra[F,O,?], Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F,O,?],_]].fr
        fx match {
          case wrap: Algebra.Eval[F, O, _] =>
            F.flatMap(F.attempt(wrap.value)) { e => runFoldLoop(scope, effect, ec, acc, g, f(e).viewL) }

          case acquire: Algebra.Acquire[F,_,_] =>
            val resource = acquire.resource
            val release = acquire.release
            if (scope.beginAcquire) {
              F.flatMap(F.attempt(resource)) {
                case Left(err) =>
                  scope.cancelAcquire
                  runFoldLoop(scope, effect, ec, acc, g, f(Left(err)).viewL)
                case Right(r) =>
                  val token = new Token()
                  lazy val finalizer_ = release(r)
                  val finalizer = F.suspend { finalizer_ }
                  scope.finishAcquire(token, finalizer)
                  runFoldLoop(scope, effect, ec, acc, g, f(Right((r, token))).viewL)
              }
            } else {
              F.raiseError(Interrupted)
            }

          case release: Algebra.Release[F,_] =>
            scope.releaseResource(release.token) match {
              case None => F.suspend { runFoldLoop(scope, effect, ec, acc, g, f(Right(())).viewL) }
              case Some(finalizer) => F.flatMap(F.attempt(finalizer)) { e =>
                runFoldLoop(scope, effect, ec, acc, g, f(e).viewL)
              }
            }

          case c: Algebra.CloseScope[F,_] =>
            F.flatMap(c.toClose.close((effect, ec).tupled)) { e =>
              val scopeAfterClose = c.toClose.openAncestor.fold(identity, identity)
              runFoldLoop(scopeAfterClose, effect, ec, acc, g, f(e).viewL)
            }

          case o: Algebra.OpenScope[F,_] =>
            F.suspend {
              val innerScope = scope.open
              runFoldLoop(innerScope, effect, ec, acc, g, f(Right(innerScope)).viewL)
            }

          case unconsAsync: Algebra.UnconsAsync[F,_,_,_] =>
            effect match {
              case Some(eff) =>
                val s = unconsAsync.s
                val ec = unconsAsync.ec
                type UO = Option[(Segment[_,Unit], FreeC[Algebra[F,Any,?],Unit])]
                val asyncPull: F[AsyncPull[F,UO]] = F.flatMap(async.ref[F,Either[Throwable,UO]](eff, ec)) { ref =>
                  F.map(async.fork {
                    F.flatMap(F.attempt(
                      runFoldScope(
                        scope,
                        effect,
                        Some(ec),
                        uncons(s.asInstanceOf[FreeC[Algebra[F,Any,?],Unit]]).flatMap(output1(_)),
                        None: UO
                      )((_, snd) => snd)
                    )) { o => ref.setAsyncPure(o) }
                  }(eff, ec))(_ => AsyncPull.readAttemptRef(ref))
                }
                F.flatMap(asyncPull) { ap => runFoldLoop(scope, effect, Some(ec), acc, g, f(Right(ap)).viewL) }
              case None =>
                F.raiseError(new IllegalStateException("unconsAsync encountered but stream was run synchronously"))
            }

          case _ => sys.error("impossible Segment or Output following uncons")
        }
      case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
  }

  def translate[F[_],G[_],O,R](fr: FreeC[Algebra[F,O,?],R], u: F ~> G): FreeC[Algebra[G,O,?],R] = {
    def algFtoG[O2]: Algebra[F,O2,?] ~> Algebra[G,O2,?] = new (Algebra[F,O2,?] ~> Algebra[G,O2,?]) { self =>
      def apply[X](in: Algebra[F,O2,X]): Algebra[G,O2,X] = in match {
        case o: Output[F,O2] => Output[G,O2](o.values)
        case Run(values) => Run[G,O2,X](values)
        case Eval(value) => Eval[G,O2,X](u(value))
        case a: Acquire[F,O2,_] => Acquire(u(a.resource), r => u(a.release(r)))
        case r: Release[F,O2] => Release[G,O2](r.token)
        case os: OpenScope[F,O2] => os.asInstanceOf[Algebra[G,O2,X]]
        case cs: CloseScope[F,O2] => cs.asInstanceOf[CloseScope[G,O2]]
        case ua: UnconsAsync[F,_,_,_] =>
          val uu: UnconsAsync[F,Any,Any,Any] = ua.asInstanceOf[UnconsAsync[F,Any,Any,Any]]
          UnconsAsync(uu.s.translate[Algebra[G,Any,?]](algFtoG), uu.ec).asInstanceOf[Algebra[G,O2,X]]
      }
    }
    fr.translate[Algebra[G,O,?]](algFtoG)
  }
}
