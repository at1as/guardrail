package com.twilio.swagger.codegen

import _root_.io.swagger.models._
import cats.Id
import cats.data.NonEmptyList
import cats.free.Free
import cats.instances.all._
import cats.syntax.all._
import com.twilio.swagger.codegen.generators.ScalaParameter
import com.twilio.swagger.codegen.terms.server._
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.meta._

case class Servers(servers: Seq[Server], frameworkImports: Seq[Import])
case class Server(pkg: NonEmptyList[String], extraImports: Seq[Import], src: Seq[Stat])
case class ServerRoute(path: String, method: HttpMethod, operation: Operation)
case class RenderedRoute(route: Term, methodSig: Decl.Def)
object ServerGenerator {
  import NelShim._

  type ServerGenerator[A] = ServerTerm[A]

  def formatClassName(str: String): String = s"${str.capitalize}Resource"
  def formatHandlerName(str: String): String = s"${str.capitalize}Handler"

  def fromSwagger[F[_]](context: Context, swagger: Swagger)(implicit S: ServerTerms[F]): Free[F, Servers] = {
    import S._

    val paths: List[(String, Path)] = Option(swagger.getPaths).map(_.asScala.toList).getOrElse(List.empty)
    val basePath: Option[String] = Option(swagger.getBasePath)

    for {
      routes <- extractOperations(paths)
      classNamedRoutes <- routes.map(route => getClassName(route.operation).map(_ -> route)).sequenceU
      groupedRoutes = classNamedRoutes.groupBy(_._1).mapValues(_.map(_._2)).toList
      frameworkImports <- getFrameworkImports(context.tracing)
      extraImports <- getExtraImports(context.tracing)
      servers <- groupedRoutes.map({ case (className, routes) =>
          for {
            renderedRoutes <- routes.map({ case sr@ServerRoute(path, method, operation) =>
              for {
                tracingFields <- buildTracingFields(operation, className, context.tracing)
                rendered <- generateRoute(className, basePath, tracingFields)(sr)
              } yield rendered
            }).sequenceU
            routeTerms = renderedRoutes.map(_.route)
            combinedRouteTerms <- combineRouteTerms(routeTerms)
            methodSigs = renderedRoutes.map(_.methodSig)
            handlerSrc <- renderHandler(formatHandlerName(className.last), methodSigs)
            extraRouteParams <- getExtraRouteParams(context.tracing)
            classSrc <- renderClass(formatClassName(className.last), formatHandlerName(className.last), combinedRouteTerms, extraRouteParams)
          } yield {
            Server(className, frameworkImports ++ extraImports, Seq(SwaggerUtil.escapeTree(handlerSrc), SwaggerUtil.escapeTree(classSrc)))
          }
        }).sequenceU
    } yield Servers(servers, frameworkImports)
  }
}