package cps.monads.catsEffect

import cps._
import cats.{Monad, MonadThrow}
import cats.effect.kernel._

import scala.util._
import scala.util.control.NonFatal
import scala.concurrent._

trait CatsMonad[F[_]](using mf: Monad[F]) extends CpsMonad[F]:

  def pure[A](a:A): F[A] =
    mf.pure(a)

  def map[A,B](fa: F[A])(f: A => B): F[B] =
    mf.map(fa)(f)

  def flatMap[A,B](fa: F[A])(f: A => F[B]): F[B] =
    mf.flatMap(fa)(f)


class CatsMonadPure[F[_]](using mf: Monad[F]) extends CatsMonad[F](using mf) with CpsPureMonadInstanceContext[F]


class CatsMonadThrow[F[_]](using MonadThrow[F]) extends CatsMonad[F] with CpsTryMonadInstanceContext[F]:

  def error[A](e: Throwable): F[A] =
    summon[MonadThrow[F]].raiseError(e)

  override def mapTry[A,B](fa:F[A])(f: Try[A]=>B): F[B] =
    summon[MonadThrow[F]].redeem(fa)( ex => f(Failure(ex)), a => f(Success(a)) )

  def flatMapTry[A,B](fa:F[A])(f: Try[A]=>F[B]): F[B] =
    summon[MonadThrow[F]].redeemWith(fa)( ex => f(Failure(ex)), a => f(Success(a)) )


class CatsMonadCancel[F[_]](using F: MonadCancel[F, Throwable]) extends CatsMonadThrow[F] with CpsTryMonadInstanceContext[F]:

  // will be override when Sync become available
  def poorMansDelay[A](a: =>A) = F.map(F.unit)(_ => a)

  def poorMansFlatDelay[A](a: => F[A]) = F.flatMap(F.unit)(_ => a)
  
  override def withAction[A](fa: F[A])(action: => Unit): F[A] = {
    withAsyncAction(fa)(poorMansDelay(action))
  }

  override def withAsyncAction[A](fa: F[A])(action: => F[Unit]): F[A] = {
    //F.guaranteeCase(fa)(_ => poorMansFlatDelay(action))
    import cats.syntax.all.*
    F.uncancelable { poll =>
      F.onCancel(poll(fa), poorMansFlatDelay(action) ).handleErrorWith { ex =>
        action.handleErrorWith { ex1 =>
          ex1.addSuppressed(ex)
          F.raiseError(ex1)
        }.flatMap(_ => F.raiseError(ex))
      }.flatTap { _ =>
        action
      }
    }
  }

  override def withAsyncFinalizer[A](fa: => F[A])(f: => F[Unit]): F[A] = {
    withAsyncAction(tryImpure(fa))(f)
  }
  


object CatsAsyncHelper:

  def adoptCallbackStyle[F[_],A](source: (Try[A]=>Unit) => Unit)(using Async[F]): F[A] = {
    def adoptIOCallback(ioCallback: Either[Throwable, A] => Unit): Try[A] => Unit = {
      case Failure(ex) => ioCallback(Left(ex))
      case Success(a) => ioCallback(Right(a))
    }

    summon[Async[F]].async_ {
      ioCallback => source(adoptIOCallback(ioCallback))
    }
  }

end CatsAsyncHelper


class CatsAsync[F[_]](using Async[F]) extends CatsMonadThrow[F] with CpsAsyncEffectMonadInstanceContext[F]:

  def adoptCallbackStyle[A](source: (Try[A]=>Unit) => Unit): F[A] =
    CatsAsyncHelper.adoptCallbackStyle[F,A](source)

end CatsAsync

trait CatsAsyncCancel[F[_]](using Async[F], MonadCancel[F, Throwable]) extends CatsMonadCancel[F] with CpsAsyncEffectMonadInstanceContext[F]:

  override def poorMansDelay[A](a: => A) = summon[Async[F]].delay(a)

  override def poorMansFlatDelay[A](a: => F[A]) = summon[Async[F]].defer(a)

  override def withAction[A](fa: F[A])(action: => Unit): F[A] = {
    withAsyncAction(fa)(summon[Async[F]].delay(action))
  }

  override def withAsyncAction[A](fa: F[A])(action: => F[Unit]): F[A] = {
    import cats.syntax.all.*
    summon[MonadCancel[F, Throwable]].uncancelable { poll =>
      summon[MonadCancel[F, Throwable]].onCancel(poll(fa), summon[Async[F]].defer(action))
       .handleErrorWith { ex =>
           action.handleErrorWith{ ex1 =>
             ex1.addSuppressed(ex)
             summon[MonadCancel[F,Throwable]].raiseError(ex1)
           }.flatMap{ _ =>
             summon[MonadCancel[F,Throwable]].raiseError(ex)
           }  
       }
       .flatTap { _ => action }
    }
  }


  // inlined to avoid unoptimized virtual calls
  //override def withAction[A](fa: F[A])(action: => Unit): F[A] = {
  //  summon[MonadCancel[F,Throwable]].guaranteeCase(fa) { _ => summon[Async[F]].delay(action) }
  //}

  // inlined to avoid unoptimized virtual calls
  //override def withAsyncAction[A](fa: F[A])(action: => F[Unit]): F[A] = {
  //  summon[MonadCancel[F,Throwable]].guaranteeCase(fa)(_ => summon[Async[F]].defer(action))
  //}

  def adoptCallbackStyle[A](source: (Try[A]=>Unit) => Unit): F[A] =
    CatsAsyncHelper.adoptCallbackStyle(source)


end CatsAsyncCancel


class CatsConcurrent[F[_]](using Concurrent[F], Async[F], MonadCancel[F, Throwable]) extends CatsAsyncCancel[F]
                                                                   with CpsConcurrentEffectMonadInstanceContext[F]:

  type Spawned[A] = Fiber[F,Throwable,A]

  def spawnEffect[A](op: => F[A]): F[Spawned[A]] = 
    summon[Concurrent[F]].start{
      try 
        op
      catch
        case NonFatal(ex) =>
          error(ex)
    }

  def join[A](op: Spawned[A]): F[A] =
     flatMap(op.join){ r =>
       r match
         case Outcome.Succeeded(fa) => fa
         case Outcome.Errored(ex) => error(ex)
         case Outcome.Canceled() => error(new CancellationException("fiber is cancelled"))
     }

  def tryCancel[A](op: Spawned[A]): F[Unit] =
    op.cancel




given catsMonadPure[F[_]](using Monad[F], NotGiven[MonadThrow[F]]): CpsPureMonadInstanceContext[F] = CatsMonadPure[F]()

given catsMonadThrow[F[_]](using MonadThrow[F],  NotGiven[Async[F]]): CpsTryMonadInstanceContext[F] = CatsMonadThrow[F]()

given catsMonadCancel[F[_]](using MonadCancel[F, Throwable], NotGiven[Async[F]]): CatsMonadCancel[F] = CatsMonadCancel[F]()

given catsAsync[F[_]](using Async[F],  NotGiven[Concurrent[F]], NotGiven[MonadCancel[F,Throwable]]): CatsAsync[F] = CatsAsync[F]()

given catsConcurrent[F[_]](using Concurrent[F], Async[F], MonadCancel[F,Throwable]): CpsConcurrentEffectMonadInstanceContext[F] = CatsConcurrent[F]()



