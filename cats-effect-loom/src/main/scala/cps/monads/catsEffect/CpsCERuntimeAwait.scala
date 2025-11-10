package cps.monads.catsEffect

import cps.*
import cats.effect.*
import cats.effect.std.Dispatcher

import scala.concurrent.{ExecutionException, blocking}
import java.util.concurrent.CompletableFuture
import scala.util.control.NonFatal

class CpsCERuntimeAwait[F[_]](dispatcher: Dispatcher[F], async: Async[F]) extends CpsRuntimeAwait[F] {
    
    override def await[A](fa: F[A])(ctx: CpsTryMonadContext[F]): A = {
         import scala.concurrent.ExecutionContext.Implicits.global
         val cf = CompletableFuture[A]()
         
         dispatcher.unsafeToFuture(fa).onComplete{
            case scala.util.Success(a) => cf.complete(a)
            case scala.util.Failure(ex) => cf.completeExceptionally(ex)
         }
         
         blocking {
           try {
             cf.get()
           } catch {
              case ex: ExecutionException =>
                throw ex.getCause()
           }
         }
    }

}

class CpsCERuntimeAwaitProvider[F[_]:Async](dispatcher: Dispatcher[F]) extends CpsRuntimeAwaitProvider[F] {

  def runInVirtualThread[A](op: =>A): F[A] =
    Async[F].async_{ cb =>
      val th = Thread.ofVirtual().start(() => {
        try {
          val r = op
          cb(Right(r))
        }catch{
          case ec: ExecutionException =>
            cb(Left(ec.getCause))
          case NonFatal(ex) =>
            cb(Left(ex))
        }
      })
    }

  override def withRuntimeAwait[A](op: CpsRuntimeAwait[F] => F[A])(using ctx:CpsTryMonadContext[F]): F[A] =
       val ra = CpsCERuntimeAwait(dispatcher, summon[Async[F]])
       ctx.monad.flatten(runInVirtualThread(op(ra)))

}


object CpsCERuntimeAwaitProvider {

  /**
   * Configuration for creating a Dispatcher-based runtime await provider.
   * 
   * Note: Only Parallel Dispatcher is used. Sequential Dispatcher causes thread pool
   * exhaustion deadlock when closures with await escape async blocks.
   * 
   * @param awaitOnShutdown Whether to await completion of all operations on shutdown.
   */
  case class DispatcherConfig(
    awaitOnShutdown: Boolean = false
  )
  
  def fromDispatcher[F[_]: Async](dispatcher: Dispatcher[F]): CpsCERuntimeAwaitProvider[F] =
    new CpsCERuntimeAwaitProvider[F](dispatcher)
  
  /**
   * Create a scoped CpsRuntimeAwaitProvider with a Parallel Dispatcher.
   * 
   * Uses Cats Effect's Parallel Dispatcher which handles concurrent operations safely.
   * This prevents thread pool exhaustion deadlock that can occur with escaped closures.
   */
  def resource[F[_]: Async](config: DispatcherConfig = DispatcherConfig()): Resource[F, CpsRuntimeAwaitProvider[F]] = {
    Dispatcher.parallel[F](config.awaitOnShutdown).map(fromDispatcher[F](_))
  }
  
  /**
   * Convenient imports following Scala 3 and Cats Effect conventions.
   * 
   * Simple usage:
   * {{{
   *   import cps.monads.catsEffect.CpsCERuntimeAwaitProvider.Implicits.global.given
   *   
   *   // Now async[IO] with .await works everywhere
   *   val program = async[IO] {
   *     IO.pure(42).await
   *   }
   * }}}
   * 
   * For custom configuration, use the resource pattern:
   * {{{
   *   CpsCERuntimeAwaitProvider.resource[IO](
   *     DispatcherConfig(awaitOnShutdown = true)
   *   ).use { implicit provider =>
   *     myApp()
   *   }
   * }}}
   */
  object Implicits {
    /**
     * Global given instance for IO.
     * 
     * Automatically initializes a Parallel Dispatcher on first use with default config.
     * The Dispatcher is cleaned up on JVM shutdown.
     * 
     * Import with: `import CpsCERuntimeAwaitProvider.Implicits.global`
     */
     given global:CpsRuntimeAwaitProvider[IO] = GlobalCpsCERuntimeAwaitProvider.globalCpsCERuntimeAwaitProvider

  }
        
}

/**
 * Internal global CpsCERuntimeAwaitProvider for IO.
 * Users should import from CpsCERuntimeAwaitProvider.Implicits.global instead.
 * 
 * Automatically initializes a Parallel Dispatcher with default config on first use.
 * The Dispatcher is shared across the entire application and cleaned up on JVM shutdown.
 */
private[catsEffect] object GlobalCpsCERuntimeAwaitProvider {

  import java.util.concurrent.atomic.AtomicReference
  import cats.effect.unsafe.IORuntime
  
  private val _globalDispatcher = new AtomicReference[Dispatcher[IO]](null)
  
  private def getOrCreateGlobalDispatcher: Dispatcher[IO] = {
    val dispatcher = _globalDispatcher.get()
    if (dispatcher == null) {
      // Lazily allocate Parallel Dispatcher with default config
      val dispatcherResource = Dispatcher.parallel[IO](await = false)
      val (allocated, shutdown) = dispatcherResource.allocated.unsafeRunSync()(IORuntime.global)
      
      // Register JVM shutdown hook to clean up Dispatcher
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        shutdown.unsafeRunSync()(IORuntime.global)
        _globalDispatcher.set(null)
      }))
      
      // Try to set it atomically
      if (_globalDispatcher.compareAndSet(null, allocated)) {
        allocated
      } else {
        // Someone else beat us to it, use theirs
        _globalDispatcher.get()
      }
    } else {
      dispatcher
    }
  }
  
  given globalCpsCERuntimeAwaitProvider: CpsCERuntimeAwaitProvider[IO] = 
    CpsCERuntimeAwaitProvider.fromDispatcher(getOrCreateGlobalDispatcher)
  
}