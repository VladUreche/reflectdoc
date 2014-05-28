package org.scalareflect.reflectdoc

import reflectdoc.tools.nsc.doc._
import reflectdoc.tools.nsc.doc.model.TemplateEntity
import scala.reflect.semantic.HostContext
import reflectdoc.tools.nsc.reporters.ConsoleReporter
import scala.reflect.internal.util.FakePos
import scala.reflect.internal.FatalError

/** The main class for scaladoc, a front-end for the Scala compiler
 *  that generates documentation from source files.
 */
object ReflectDoc {
  val versionMsg = "Reflectdoc"

  def createReflectionHost(settings: Settings): HostContext = ???

  def process(args: Array[String]): Boolean = {
    var reporter: ConsoleReporter = null
    val docSettings = new Settings(msg => reporter.error(FakePos("reflectdoc"), msg + "\n  scaladoc -help  gives more information"),
                                   msg => reporter.printMessage(msg))
    reporter = new ConsoleReporter() {
      // need to do this so that the Global instance doesn't trash all the
      // symbols just because there was an error
      override def hasErrors = false
    }

    val host = createReflectionHost(docSettings)
    try { new DocFactory(host, reporter, docSettings).document() }
    catch {
      case ex @ FatalError(msg) =>
        if (docSettings.debug.value) ex.printStackTrace()
        reporter.error(null, "fatal error: " + msg)
    }
    finally reporter.printSummary()

    // not much point in returning !reporter.hasErrors when it has
    // been overridden with constant false.
    true
  }

  def main(args: Array[String]): Unit = sys exit {
    println("Hi! прывітанне! Bună ziua! Bonjour! Grüezi! Ciao!")
    if (process(args)) 0 else 1
  }
}