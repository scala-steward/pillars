// Copyright (c) 2024-2024 by Raphaël Lemaitre and Contributors
// This software is licensed under the Eclipse Public License v2.0 (EPL-2.0).
// For more information see LICENSE or https://opensource.org/license/epl-2-0

package pillars

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.Codec
import io.circe.derivation.Configuration
import org.http4s.HttpApp
import org.http4s.HttpVersion
import org.http4s.Response
import org.http4s.Status
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.netty.server.NettyServerBuilder
import org.http4s.server.Server
import org.http4s.server.middleware.CORS
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.middleware.Logger
import org.typelevel.otel4s.trace.StatusCode
import org.typelevel.otel4s.trace.Tracer
import pillars.Controller.HttpEndpoint
import pillars.codec.given
import pillars.syntax.all.*
import sttp.capabilities.StreamMaxLengthExceededException
import sttp.monad.MonadError
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsOptions
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.interceptor.exception.ExceptionContext
import sttp.tapir.server.interceptor.exception.ExceptionHandler
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.swagger.SwaggerUIOptions
import sttp.tapir.swagger.bundle.SwaggerInterpreter

object HttpServer:
    def build[F[_]: Async](
        name: String,
        config: Config,
        openApi: Config.OpenAPI,
        infos: AppInfo,
        observability: Observability[F],
        endpoints: List[HttpEndpoint[F]]
    ): Resource[F, Server] =
        val cors: HttpApp[F] => HttpApp[F] = CORS.policy.httpApp[F]

        val errorHandling: HttpApp[F] => HttpApp[F] = ErrorHandling.Custom.recoverWith(_):
            case e: PillarsError =>
                observability.recordException(e).map: _ =>
                    Response(
                      Status.fromInt(e.status.code).getOrElse(Status.InternalServerError),
                      HttpVersion.`HTTP/1.1`
                    )
                        .withEntity(e.view)
            case e: Throwable    =>
                observability.recordException(e).map: _ =>
                    Response(Status.InternalServerError, HttpVersion.`HTTP/1.1`)
                        .withEntity(PillarsError.fromThrowable(e).view)

        val logging =
            if config.logging.enabled then
                Logger.httpApp[F](
                  logHeaders = config.logging.headers,
                  logBody = config.logging.body,
                  logAction = config.logging.logAction
                )
            else identity[HttpApp[F]]

        val options: Http4sServerOptions[F] =
            Http4sServerOptions
                .customiseInterceptors
                .prependInterceptor(observability.interceptor)
                .prependInterceptor(Traces(observability.tracer))
                .exceptionHandler(exceptionHandler(observability.tracer))
                .options

        val openAPIEndpoints = if openApi.enabled then
            SwaggerInterpreter(
              swaggerUIOptions = SwaggerUIOptions(
                pathPrefix = openApi.pathPrefix,
                yamlName = openApi.yamlName,
                contextPath = openApi.contextPath,
                useRelativePaths = openApi.useRelativePaths,
                showExtensions = openApi.showExtensions
              )
            ).fromServerEndpoints(endpoints, name, infos.version)
        else Nil

        val routes = Http4sServerInterpreter[F](options).toRoutes(endpoints ++ openAPIEndpoints).orNotFound

        val app: HttpApp[F] = routes |> logging |> errorHandling |> cors

        NettyServerBuilder[F].withoutSsl.withNioTransport
            .bindHttp(config.port.value, config.host.toString)
            .withHttpApp(app)
            .withoutBanner
            .resource
    end build

    private def exceptionHandler[F[_]: Async](tracer: Tracer[F]): ExceptionHandler[F] =
        new ExceptionHandler[F]:
            override def apply(ctx: ExceptionContext)(implicit
                monad: MonadError[F]
            ): F[Option[ValuedEndpointOutput[?]]] =
                def handlePillarsError(e: PillarsError) =
                    Some(ValuedEndpointOutput(statusCode.and(jsonBody[PillarsError.View]), (e.status, e.view)))
                tracer
                    .currentSpanOrNoop
                    .flatMap: span =>
                        for
                            _ <- span.recordException(ctx.e)
                            _ <- span.addAttributes(Observability.Attributes.fromError(ctx.e))
                            _ <- span.setStatus(StatusCode.Error, ctx.e.getMessage)
                        yield ctx.e match
                            case e: PillarsError                            =>
                                handlePillarsError(e)
                            case StreamMaxLengthExceededException(maxBytes) =>
                                handlePillarsError(PillarsError.PayloadTooLarge(maxBytes))
                            case _                                          =>
                                handlePillarsError(PillarsError.fromThrowable(ctx.e))
            end apply

    final case class Config(
        host: Host,
        port: Port,
        logging: Logging.HttpConfig = Logging.HttpConfig()
    ) extends pillars.Config

    object Config:
        given Configuration = Configuration.default.withKebabCaseMemberNames.withKebabCaseConstructorNames.withDefaults

        given Codec[Config.OpenAPI] = Codec.AsObject.derivedConfigured
        given Codec[Config]         = Codec.AsObject.derivedConfigured

        final case class OpenAPI(
            enabled: Boolean = false,
            pathPrefix: List[String] = List("docs"),
            yamlName: String = "pillars.yaml",
            contextPath: List[String] = Nil,
            useRelativePaths: Boolean = true,
            showExtensions: Boolean = false
        )

    end Config
end HttpServer
