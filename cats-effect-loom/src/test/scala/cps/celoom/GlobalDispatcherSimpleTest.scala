package cps.celoom

import cats.effect.*
import cps.*
import cps.monads.catsEffect.given
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider.Implicits.global.given

import munit.CatsEffectSuite

class GlobalDispatcherSimpleTest extends CatsEffectSuite {

  test("simple async with global dispatcher") {
    println("[TEST] Starting simple async test")
    async[IO] {
      println("[TEST] Inside async block")
      val x = IO.pure(10).await
      println(s"[TEST] Got x = $x")
      val y = IO.pure(20).await
      println(s"[TEST] Got y = $y")
      assertEquals(x + y, 30)
      println("[TEST] Test passed")
    }
  }

  test("just IO without await") {
    println("[TEST] Starting simple IO test without await")
    IO.pure(42).map { x =>
      println(s"[TEST] Got result: $x")
      assertEquals(x, 42)
    }
  }
}
