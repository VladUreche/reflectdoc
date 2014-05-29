/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author  Martin Odersky
 */

package reflectdoc.tools.nsc.doc

import scala.reflect.semantic.HostContext

/**
 * Class to hold common dependencies across Scaladoc classes.
 * @author Pedro Furlanetto
 * @author Gilles Dubochet
 */
trait Universe {
  def settings: Settings
  def rootPackage: model.Package
  def host: HostContext
}
