package pillars

import cats.effect.Async
import cats.effect.LiftIO
import cats.syntax.all.*
import io.circe.Codec
import io.circe.derivation.Configuration
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import sttp.tapir.server.interceptor.EndpointInterceptor
import sttp.tapir.server.interceptor.Interceptor
import sttp.tapir.server.metrics.otel4s.Otel4sMetrics

final case class Observability[F[_]](tracer: Tracer[F], metrics: Meter[F], interceptor: Interceptor[F]):
    export metrics.*
    export tracer.span
    export tracer.spanBuilder
end Observability
object Observability:
    def apply[F[_]: Pillars]: Run[F, Observability[F]] = summon[Pillars[F]].observability
    def noop[F[_]: LiftIO: Async]: F[Observability[F]] =
        Observability(Tracer.noop[F], Meter.noop[F], EndpointInterceptor.noop[F]).pure[F]

    def init[F[_]: LiftIO: Async](config: Config): F[Observability[F]] =
        if config.enabled then
            for
                otel4s       <- OtelJava.global
                tracer       <- otel4s.tracerProvider.get(config.serviceName)
                meter        <- otel4s.meterProvider.get(config.serviceName)
                tapirMetrics <- Otel4sMetrics.init[F](meter)
            yield Observability(tracer, meter, tapirMetrics.metricsInterceptor())
        else
            noop
    final case class Config(enabled: Boolean, serviceName: ServiceName = ServiceName("pillars"))

    object Config:
        given Configuration = Configuration.default.withKebabCaseMemberNames.withKebabCaseConstructorNames.withDefaults
        given Codec[Config] = Codec.AsObject.derivedConfigured
    private type ServiceNameConstraint = Not[Blank]
    opaque type ServiceName <: String  = String :| ServiceNameConstraint
    object ServiceName extends RefinedTypeOps[String, ServiceNameConstraint, ServiceName]
end Observability
