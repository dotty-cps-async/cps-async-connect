package midgard

import cats.effect.IO
import cps.*
import cps.monads.catsEffect.{*,given}


import scala.annotation.experimental

@experimental
object HelloWorld {

  def say(): IO[String] = IO.delay("Hello, cats!")

  def sayDirect(using CpsDirect[IO]): String = "Hello, cats[direct]"

}
