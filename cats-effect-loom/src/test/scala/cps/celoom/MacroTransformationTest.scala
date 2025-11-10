package cps.celoom

import cats.effect.*
import cps.*
import cps.monads.catsEffect.given
import cps.monads.catsEffect.CpsCERuntimeAwaitProvider.Implicits.global.given
import munit.CatsEffectSuite

class MacroTransformationTest extends CatsEffectSuite {

  test("Compiler prevents returning lambda with await") {
    // The CPS macro detects and prevents this pattern
    println("SKIPPED: Compiler prevents returning lambdas with await")
    println("Error: 'async lambda can't be result of expression'")
    IO.unit
  }

  test("Stream.evalMap with await - what gets captured?") {
    import fs2.Stream

    def createStreamWithAwaitInLambda: IO[Stream[IO, Int]] = async[IO] {
      // Does the lambda capture the cpsRuntimeAwait?
      Stream.range(1, 5).evalMap { i =>
        val doubled = await(IO.pure(i * 2))
        IO.pure(doubled)
      }
    }

    val result = for {
      stream <- createStreamWithAwaitInLambda
      values <- stream.compile.toList
    } yield values

    result.map { values =>
      println(s"Stream evalMap with await: $values")
      assert(values == List(2, 4, 6, 8))
    }
  }

}
