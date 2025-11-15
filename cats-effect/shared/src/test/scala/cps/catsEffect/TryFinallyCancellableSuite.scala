package cps.catsEffect

import cats.effect.{IO, SyncIO}
import cats.effect.kernel.MonadCancel
import cats.effect.unsafe.IORuntime
import munit.CatsEffectSuite

import concurrent.duration.*
import cps.*
import cps.monads.catsEffect.given

import scala.concurrent.{CancellationException, ExecutionContext}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal


class TryFinallyCancellableSuite extends CatsEffectSuite {

  test("L00:  0000 ensure that finally sync block is executed") {
    var x = 0
    var finalizerCalled = false
    val run = async[IO] {
      try {
        x = 1
        x = x + 1
        x = x + 1
        x
      } finally {
        x = x + 1
        finalizerCalled = true
      }
    }
    run.map { _ =>
      assert(finalizerCalled)
      assert(x == 4)
    }
  }

  //given cps.macros.flags.PrintCode.type = cps.macros.flags.PrintCode


  test("L001: 1200 ensure that finally blok is executed after cancelled") {

    var finalizerCalled = false
    val run = async[IO] {
      try {
        IO.canceled.await
      } finally {
        finalizerCalled = true
      }
    }

    // ironically, CatsEffectSuite does not have method to check cancellation.
    //
    //interceptIO[CancellationException](run).map { _ =>
    //  assert(finalizerCalled)
    //}
    // not working, because Cancellation omit exception handlers.
    given IORuntime = munitIORuntime

    val outcomeFuture = run.unsafeToFuture().transform {
      case Success(_) =>
        assert(finalizerCalled)
        Success(Success(()))
      case Failure(ex) =>
        assert(finalizerCalled)
        Success(Failure(ex))
    }
    IO.fromFuture(IO.pure(outcomeFuture)).map { result =>
      result match
        case Failure(ex: CancellationException) =>
        //println(s"L001, CancellationException")
        case Failure(ex) =>
          assert(false, s"should have CancellationException we have $ex")
      assert(finalizerCalled)
    }


  }


  test("L002: 1210 ensure that async finally blok is executed after cancelled with async finalizer") {

    var finalizerCalled = false
    var x = 0
    var y = 0
    val run = async[IO] {
      try {
        x = await(IO.delay(1))
        IO.canceled.await
      } finally {
        //println("in async finalizer")
        y = await(IO.delay(2))
        finalizerCalled = true
      }
    }


    given IORuntime = munitIORuntime

    val outcomeFuture = run.unsafeToFuture().transform {
      case Success(_) =>
        Failure(new RuntimeException("computation should be cancelled"))
      case Failure(ex) =>
        assert(ex.isInstanceOf[CancellationException], s"unexpected exception: $ex")
        Success(())
    }
    IO.fromFuture(IO.pure(outcomeFuture)).map { _ =>
      assert(finalizerCalled)
      assert(x == 1)
      assert(y == 2)
    }
  }


  test("L003: 1211 ensure that async finally blok is executed after cancelled, and exception from it is exists somewhere") {

    var finalizerCalled = false
    var x = 0
    val run = async[IO] {
      try {
        IO.canceled.await
      } finally {
        x = await(IO.delay(1))
        finalizerCalled = true
        throw new RuntimeException("AAA")
      }
    }

    var runtimeExceptionCatched = false
    //try
    val defaultCompute = munitIORuntime.compute
    var exceptionCatcher: (Throwable => Boolean) = (ex) => false
    val myDelegateExecutionContext = new ExecutionContext {
      override def execute(runnable: Runnable): Unit = {
        defaultCompute.execute(
          new Runnable {
            def run(): Unit =
              try
                runnable.run()
              catch
                case NonFatal(ex) =>
                  reportFailure(ex)
                  // let default also reports it
                  throw ex
          }
        )
      }

      override def reportFailure(cause: Throwable): Unit = {
        if (exceptionCatcher(cause)) {
          runtimeExceptionCatched = true
        }
        //println(s"myDelegateExecutionContext: exception: $cause")
      }
    }
    val outcome = {
      given IORuntime = IORuntime(
        compute = myDelegateExecutionContext,
        blocking = _root_.cats.effect.CatsEffectBackDoor.blocking(munitIORuntime),
        scheduler = munitIORuntime.scheduler,
        shutdown = munitIORuntime.shutdown,
        config = munitIORuntime.config
      )

      exceptionCatcher = (ex) => ex.getMessage() == "AAA"
      run.unsafeToFuture().transform {
        case Success(_) =>
          Failure(new RuntimeException("computation should be cancelled"))
        case Failure(ex) =>
          assert(ex.isInstanceOf[CancellationException], s"unexpected exception: $ex")
          Success(ex)
      }
    }
    println(s"outcome: $outcome")
    //catch
    //  case ex: CancellationException =>
    //    //
    //    println(s"ex: $ex")
    //  case ex: RuntimeException =>
    //    runtimeExceptionCatched = true
    //    println(s"ex: $ex")
    IO.fromFuture(IO.pure(outcome)).map { _ =>
      assert(x == 1)
      assert(finalizerCalled)
      assert(runtimeExceptionCatched)
    }

  }

  test("L004:  1200 check that finally executed after the main block with cancellation") {
    var x = 0
    val run = async[IO] {
      try {
        x = 2
        IO.canceled.await
      } finally {
        if (x == 2) then
          x = 3
        else
          x = 1
      }
    }

    given IORuntime = munitIORuntime

    val runFuture = run.unsafeToFuture().transform {
      case Success(_) =>
        Failure(new RuntimeException("computation should be cancelled"))
      case Failure(ex) =>
        assert(ex.isInstanceOf[CancellationException], s"unexpected exception: $ex")
        assert(x == 3)
        Success(ex)
    }
    IO.fromFuture(IO.pure(runFuture))

  }

  test("L005: 1000 check that finally executed after the async main block without exception") {

    //given cps.macros.flags.PrintCode.type = cps.macros.flags.PrintCode

    var x = 0
    val run = async[IO] {
      try {
        val _ = await(IO.delay(1))
        x = 2
      } finally {
        if (x == 2) then
          x = 3
        else
          x = 1
      }
    }

    run.map(_ => assert(x == 3))

  }

  test("L006: 0000 check that finally executed after the sync main block without exception") {

    //given cps.macros.flags.PrintCode.type = cps.macros.flags.PrintCode

    var x = 0
    var mainBlockCalls = 0
    var finalizerCalls = 0
    val run = async[IO] {
      try {
        mainBlockCalls = mainBlockCalls + 1
        x = 2
      } finally {
        finalizerCalls = finalizerCalls + 1
        if (x == 2) then
          x = 3
        else
          x = 1
      }
    }

    run.map { _ =>
      assert(x == 3)
      assert(mainBlockCalls == 1)
      assert(finalizerCalls == 1)
    }

  }


  test("L007: 1010 check that finally executed after the ascync main block without exception with async finalizer") {

    given cps.macros.flags.PrintCode.type = cps.macros.flags.PrintCode

    @volatile var x = 0
    @volatile var y = 0
    @volatile var nMainCalls = 0
    @volatile var nFinalizerCalls = 0
    val run = async[IO] {
      try {
        x = 2
        await(IO.delay(1))
        nMainCalls = nMainCalls + 1
      } finally {
        nFinalizerCalls = nFinalizerCalls + 1
        y = await(IO.delay(2))
        if (x == 2) then
          x = 3
        else
          println(s"L007: unexpected x: $x")
          x = 1
      }
    }


    val r1 = run.map { _ =>
      if (nFinalizerCalls != 1) {
        println(s"L007: nFinalizerCalls = $nFinalizerCalls")
      }
      assert(nFinalizerCalls == 1)
      assert(nMainCalls == 1)
      assert(x == 3)
      assert(y == 2)
    }

    r1


  }

  test("L008: 1100 check that finally executed after the async main block with exception") {
    @volatile var x = 0
    @volatile var mainBlockCalled = 0
    @volatile var finalizerCalled = 0
    val run = async[IO] {
      try {
        x = 2
        mainBlockCalled = mainBlockCalled + 1
        await(IO.raiseError(new RuntimeException("AAA")))
      } finally {
        finalizerCalled = finalizerCalled + 1
        if (x == 2) then
          x = 3
        else
          x = 1
      }
    }

    run.intercept[RuntimeException].map { _ =>
      if (x != 3) {
        println(s"x = $x, mainBlockCalled = $mainBlockCalled, finalizerCalled = $finalizerCalled")
      }
      assert(x == 3)
      assert(mainBlockCalled == 1)
      assert(finalizerCalled == 1)
    }

  }

  test("L009: check that finalizer on exception called twice without syntax sugar") {
    @volatile var finalizerCalls = 0

    summon[MonadCancel[IO, Throwable]].guaranteeCase {
      IO.raiseError(new RuntimeException("AAA"))
    } {
      case _ =>
        IO.pure(()).map { _ =>
          finalizerCalls = finalizerCalls + 1
        }
    }.intercept[RuntimeException].map { _ =>
      assert(finalizerCalls == 1)
    }


  }

  test("L008: 1110 check that async finally executed after the async main block with exception") {

    @volatile var x = 0
    @volatile var y = 0
    @volatile var mainBlockCalled = 0
    @volatile var finalizerCalled = 0
    val run = async[IO] {
      try {
        x = await(IO.delay(2))
        mainBlockCalled = mainBlockCalled + 1
        throw RuntimeException("AAA")
      } finally {
        y = await(IO.delay(1))
        finalizerCalled = finalizerCalled + 1
        if (x == 2) then
          x = 3
        else
          x = 1
      }
    }

    run.intercept[RuntimeException].map { _ =>
      assert(x == 3)
      assert(y == 1)
      assert(mainBlockCalled == 1)
      assert(finalizerCalled == 1)
    }

  }

  test("L009  check that during normal execution errors from the finalizer are propagated (async boddy") {
    @volatile var finalizerCalls = 0
    val run = async[IO] {
      try {
        await(IO.pure(()))
      } finally {
        finalizerCalls = finalizerCalls + 1
        throw new RuntimeException("AAA")
      }
    }
    run.redeem(
      ex => assert(ex.getMessage() == "AAA"),
      _ => assert(false, "exception should be thrown from finalizer")
    ).map { _ =>
      assert(finalizerCalls == 1)
    }
  }

  test("L010  check that during error handling errors from the finalizer are propagated (async body)") {
     @volatile var finalizerCalls = 0
     val run = async[IO] {
        try {
          await(IO.raiseError(new RuntimeException("BBB")))
        } finally {
          finalizerCalls = finalizerCalls + 1
          throw new RuntimeException("AAA")
        }
     }
     run.redeem(
       ex => {
         println(s"ex=${ex}")
         //ex.printStackTrace()
         assert(ex.getMessage() == "AAA")
         assert(ex.getSuppressed().length == 1)
         assert(ex.getSuppressed()(0).getMessage() == "BBB")
       } ,
       _ => assert(false, "exception should be thrown from finalizer")
     ).map { _ =>
       assert(finalizerCalls == 1)
     }

  }

  test("L009  check that during normal execution errors from the finalizer are propagated (sync boddy)") {
    @volatile var finalizerCalls = 0
    val run = async[IO] {
      try {
        1
      } finally {
        finalizerCalls = finalizerCalls + 1
        throw new RuntimeException("AAA")
      }
    }
    run.redeem(
      ex => assert(ex.getMessage() == "AAA"),
      _ => assert(false, "exception should be thrown from finalizer")
    ).map { _ =>
      assert(finalizerCalls == 1)
    }
  }

  test("L010  check that during error handling errors from the finalizer are propagated (sync body)") {
      @volatile var finalizerCalls = 0
      val run = async[IO] {
          try {
            throw new RuntimeException("BBB")
          } finally {
            finalizerCalls = finalizerCalls + 1
            throw new RuntimeException("AAA")
          }
      }
      run.redeem(
        ex => {
          println(s"ex=${ex}")
          //ex.printStackTrace()
          assert(ex.getMessage() == "AAA")
          assert(ex.getSuppressed().length == 1)
          assert(ex.getSuppressed()(0).getMessage() == "BBB")
        } ,
        _ => assert(false, "exception should be thrown from finalizer")
      ).map { _ =>
        assert(finalizerCalls == 1)
      }
  }

}
