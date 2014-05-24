package org.scalareflect
package reflectdoc

import language.implicitConversions

class SettingsMock(error: String => Unit) {

  class Setting(val name: String, val helpDescription: String) {
    type T

    private var _helpSyntax = name

    private var _value = default
    private var _isDefault = true

    def value: T = _value
    def default: T
    def isDefault: Boolean
    def helpSyntax: String = _helpSyntax
  }

  case class StringSetting(name: String, argHint: String, helpDescription: String, val default: String) extends Setting(name, helpDescription) {
    type T = String
  }

  case class BooleanSetting(name: String, helpDescription: String) extends Setting(name, helpDescription) {
    type T = Boolean
    def default = false
  }

  object BooleanSetting {
    implicit def toBoolean(bs: BooleanSetting): Boolean = bs.value
  }

  case class MultiStringSetting(name: String, argHint: String, helpDescription: String) extends Setting(name, helpDescription) {
    type T = List[String]
    def default = List()
  }

  case class IntSetting(name: String, descr: String, val default: Int, val range: Option[(Int, Int)], parser: String => Option[Int]) extends Setting(name, descr) {
    type T = Int
  }

  case class ChoiceSetting(name: String, helpArg: String, descr: String, override val choices: List[String], val default: String) extends Setting(name, descr + choices.mkString(" (", ",", ") default:" + default)) {
    type T = String
  }


}