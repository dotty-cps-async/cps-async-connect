package midgard

import scala.annotation.experimental
import cats.effect.*
import cps.*
import cps.monads.catsEffect.{*,given}


import munit.CatsEffectSuite

@experimental
class HelloWorldSuite extends CatsEffectSuite {

  test("dotty-cps-async") {
    val run =async[IO] {
      HelloWorld.sayDirect
    }
    run
  }

}
