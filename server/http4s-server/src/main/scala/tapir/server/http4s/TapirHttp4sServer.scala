package tapir.server.http4s

import cats.Monad
import cats.implicits._
import cats.effect.{ContextShift, Sync}
import org.http4s.{EntityBody, HttpRoutes}
import tapir.Endpoint
import tapir.internal.{ParamsToSeq, SeqToParams}
import tapir.typelevel.{ParamsAsArgs, ReplaceFirstInFn, ReplaceFirstInTuple}

import scala.reflect.ClassTag

trait TapirHttp4sServer {
  implicit class RichHttp4sHttpEndpoint[I, E, O, F[_]](e: Endpoint[I, E, O, EntityBody[F]]) {
    def toRoutes(
        logic: I => F[Either[E, O]])(implicit serverOptions: Http4sServerOptions[F], fs: Sync[F], fcs: ContextShift[F]): HttpRoutes[F] = {
      new EndpointToHttp4sServer(serverOptions).toRoutes(e)(logic)
    }

    def toRouteRecoverErrors(logic: I => F[O])(implicit serverOptions: Http4sServerOptions[F],
                                               fs: Sync[F],
                                               fcs: ContextShift[F],
                                               eIsThrowable: E <:< Throwable,
                                               eClassTag: ClassTag[E]): HttpRoutes[F] = {
      def reifyFailedF(f: F[O]): F[Either[E, O]] = {
        f.map(Right(_): Either[E, O]).recover {
          case e: Throwable if eClassTag.runtimeClass.isInstance(e) => Left(e.asInstanceOf[E]): Either[E, O]
        }
      }

      new EndpointToHttp4sServer(serverOptions).toRoutes(e)(logic.andThen(reifyFailedF))
    }
  }

  implicit class RichToMonadFunction[T, U, F[_]: Monad](a: T => F[U]) {
    def andThenFirst[U_TUPLE, T_TUPLE, O](l: U_TUPLE => F[O])(
        implicit replaceFirst: ReplaceFirstInTuple[T, U, T_TUPLE, U_TUPLE]): T_TUPLE => F[O] = { tTuple =>
      val t = replaceFirst.first(tTuple)
      a(t).flatMap { u =>
        val uTuple = replaceFirst.replace(tTuple, u)
        l(uTuple)
      }
    }
  }

  implicit class RichToMonadOfEitherFunction[T, U, E, F[_]: Monad](a: T => F[Either[E, U]]) {
    def andThenFirstE[U_TUPLE, T_TUPLE, O](l: U_TUPLE => F[Either[E, O]])(
        implicit replaceFirst: ReplaceFirstInTuple[T, U, T_TUPLE, U_TUPLE]): T_TUPLE => F[Either[E, O]] = { tTuple =>
      val t = replaceFirst.first(tTuple)
      a(t).flatMap {
        case Left(e) => implicitly[Monad[F]].point(Left(e))
        case Right(u) =>
          val uTuple = replaceFirst.replace(tTuple, u)
          l(uTuple)
      }
    }
  }
}
