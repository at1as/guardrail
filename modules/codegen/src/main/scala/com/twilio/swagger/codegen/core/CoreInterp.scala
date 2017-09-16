package com.twilio.swagger.codegen
package core

import cats.data.NonEmptyList
import cats.instances.all._
import cats.syntax.either._
import cats.~>
import com.twilio.swagger.codegen.generators.AkkaHttp
import com.twilio.swagger.codegen.terms._
import java.nio.file.Paths
import scala.collection.immutable.Seq
import scala.io.AnsiColor

object CoreTermInterp extends (CoreTerm ~> CoreTarget) {
  def apply[T](x: CoreTerm[T]): CoreTarget[T] = x match {
    case GetDefaultFramework =>
      CoreTarget.pure("akka-http")

    case ExtractGenerator(context) =>
      context.framework match {
        case Some("akka-http") => CoreTarget.pure(AkkaHttp)
        case None => CoreTarget.log(NoFramework)
        case Some(unknown) => CoreTarget.log(UnknownFramework(unknown))
      }

    case ValidateArgs(parsed) =>
      for {
        args <- CoreTarget.fromOption(NonEmptyList.fromList(parsed.filterNot(Args.isEmpty)), NoArgsSpecified)
        args <- if (args.exists(_.printHelp)) CoreTarget.log(PrintHelp) else CoreTarget.pure(args)
      } yield args

    case ParseArgs(args, defaultFramework) => {
      def expandTilde(path: String): String = path.replaceFirst("^~", System.getProperty("user.home"))
      val empty = Args.empty.copy(context=Args.empty.context.copy(framework=Some(defaultFramework)))

      @scala.annotation.tailrec
      def rec(sofar: CoreTarget[(List[Args], List[String])]): CoreTarget[List[Args]] = {
        val step: CoreTarget[(List[Args], List[String])] = sofar.flatMap {
          case (Nil, xs@(_ :: _))                                 => CoreTarget.pure((empty                                                              :: Nil     , xs))
          case (already, Nil)                                     => CoreTarget.pure((         already                                                              , Nil))
          case (sofar :: already, "--client"               :: xs) => CoreTarget.pure((empty :: sofar                                                     :: already , xs))
          case (sofar :: already, "--server"               :: xs) => CoreTarget.pure((empty.copy(kind=CodegenTarget.Server) :: sofar                     :: already , xs))
          case (sofar :: already, "--framework"   :: value :: xs) => CoreTarget.pure((sofar.copy(context     = sofar.context.copy(framework=Some(value))):: already , xs))
          case (sofar :: already, "--help"                 :: xs) => CoreTarget.pure((sofar.copy(printHelp   = true)                                     :: already , List.empty))
          case (sofar :: already, "--specPath"    :: value :: xs) => CoreTarget.pure((sofar.copy(specPath    = Option(expandTilde(value)))               :: already , xs))
          case (sofar :: already, "--tracing"              :: xs) => CoreTarget.pure((sofar.copy(context     = sofar.context.copy(tracing=true))         :: already , xs))
          case (sofar :: already, "--outputPath"  :: value :: xs) => CoreTarget.pure((sofar.copy(outputPath  = Option(expandTilde(value)))               :: already , xs))
          case (sofar :: already, "--packageName" :: value :: xs) => CoreTarget.pure((sofar.copy(packageName = Option(value.trim.split('.').to[Seq]))    :: already , xs))
          case (sofar :: already, "--dtoPackage"  :: value :: xs) => CoreTarget.pure((sofar.copy(dtoPackage  = value.trim.split('.').to[Seq])            :: already , xs))
          case (_, unknown) => CoreTarget.log(UnknownArguments(unknown))
        }

        if (step.isLeft || step.exists(_._2.isEmpty)) {
          step.map(_._1)
        } else {
          rec(step)
        }
      }

      rec(Right((Nil, args.toList)))
    }

    case ProcessArgSet(targetInterpreter, args) =>
      for {
        specPath <- CoreTarget.fromOption(args.specPath, MissingArg(args, Error.ArgName("--specPath")))
        outputPath <- CoreTarget.fromOption(args.outputPath, MissingArg(args, Error.ArgName("--outputPath")))
        pkgName <- CoreTarget.fromOption(args.packageName, MissingArg(args, Error.ArgName("--packageName")))
        kind = args.kind
        dtoPackage = args.dtoPackage
        context = args.context
      } yield {
        ReadSwagger(Paths.get(specPath), { swagger =>
          Common.writePackage(kind, context, swagger, Paths.get(outputPath), pkgName, dtoPackage)
            .foldMap(targetInterpreter)
        })
      }
  }
}