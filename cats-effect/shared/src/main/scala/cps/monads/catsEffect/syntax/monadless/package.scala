package cps.monads.catsEffect.syntax.monadless

import cats.effect.{MonadCancel, Resource}

import cps.CpsTryMonad
import cps.monads.catsEffect.AsyncScopeInferArg

/**
 * Synonym for `asyncScope`.
 **/
def liftScope[F[_]](using m:CpsTryMonad[[A]=>>Resource[F,A]], mc:MonadCancel[F,Throwable]) = AsyncScopeInferArg(using m, mc)
