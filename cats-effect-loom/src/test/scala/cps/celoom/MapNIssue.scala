
import cats.*
import cats.implicits.*
import cats.effect.*
import cats.effect.implicits.*
import cps.*  
import cps.monads.catsEffect.given

object MapNProblemExample:

  //Error.. "Required:cps.AsyncShift[cats.syntax.Tuple2SemigroupalOps[Option, Int, Int]]) .."
  // def notWork = async[IO]:
  //   (Option(1), Option(1)).mapN((a, b) => IO(a + b).await).getOrElse(42)

  def work = async[IO]:
    (Option(1), Option(1)).match
      case (Some(a), Some(b)) => IO(a + b).await
      case _ => 42


