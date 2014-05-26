package org.scalareflect.reflectdoc

import reflectdoc.tools.nsc.doc._
import reflectdoc.tools.nsc.doc.model.TemplateEntity

import scala.reflect.semantic.HostContext

object ReflectDoc {

  def instantiateHost(args: Array[String]): HostContext = ???
  def generateModelFromHost(host: HostContext): TemplateEntity = ???
  def generateWebsiteFromModel(model: TemplateEntity): Unit = ???

  def main(args: Array[String]): Unit = {
    println("прывітанне")
  }
}