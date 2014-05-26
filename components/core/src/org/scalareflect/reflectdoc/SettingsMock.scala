package org.scalareflect
package reflectdoc

import language.implicitConversions

class SettingsMock(error: String => Unit) {

  abstract class Setting {
    type T

    private var _helpSyntax = name

    private var _value = default
    private var _isDefault = true

    def name: String
    def helpDescription: String

    def value: T = _value
    def default: T
    def isDefault: Boolean = _isDefault
    def helpSyntax: String = _helpSyntax
  }

  case class StringSetting(name: String, argHint: String, helpDescription: String, val default: String) extends Setting {
    type T = String
  }

  case class BooleanSetting(name: String, helpDescription: String) extends Setting {
    type T = Boolean
    def default = false
  }

  object BooleanSetting {
    implicit def toBoolean(bs: BooleanSetting): Boolean = bs.value
  }

  case class MultiStringSetting(name: String, argHint: String, helpDescription: String) extends Setting {
    type T = List[String]
    def default = List()
  }

  case class IntSetting(name: String, helpDescription: String, val default: Int, val range: Option[(Int, Int)], parser: String => Option[Int]) extends Setting {
    type T = Int
  }

  case class ChoiceSetting(name: String, helpArg: String, descr: String, val choices: List[String], val default: String) extends Setting {
    type T = String
    def helpDescription = descr + choices.mkString(" (", ",", ") default:" + default)
  }
}