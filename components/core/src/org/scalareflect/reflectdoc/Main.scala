package org.scalareflect.reflectdoc

import scala.tools.nsc.doc._
import scala.reflect.semantic.HostContext
import scala.tools.nsc.doc.model.TemplateEntity

object ReflectDoc {

  def instantiateHost(args: Array[String]): HostContext = ???
  def generateModelFromHost(host: HostContext): TemplateEntity = ???
  def generateWebsiteFromModel(model: TemplateEntity): Unit = ???

  def main(args: Array[String]): Unit = {
    println("прывітанне")
  }
}