/* NSC -- new Scala compiler -- Copyright 2007-2013 LAMP/EPFL */

package reflectdoc
package tools.nsc
package doc
package model

import base.comment._
import diagram._
import scala.collection._
import scala.util.matching.Regex
import scala.reflect.macros.internal.macroImpl
import io._
import model.{ RootPackage => RootPackageEntity }
import scala.reflect.{ core => c }
import scala.reflect.semantic._
import scala.util.Try

/** This trait extracts all required information for documentation from compilation units */
class ModelFactory(implicit val host: HostContext, val settings: doc.Settings) {
  thisFactory: ModelFactory
               with ModelFactoryImplicitSupport
               with ModelFactoryTypeSupport
               with DiagramFactory
               with CommentFactory
               with TreeFactory
               with MemberLookup =>

  import org.scalareflect.errors.handlers.throwExceptions

  // temporary class for empty comments:
  class EmptyComment(comment: String) extends Comment {
    def body: Body = Body(List(Paragraph(Text(comment))))
    def authors: List[Body] = Nil
    def see: List[Body] = Nil
    def result: Option[Body] = None
    def throws: Map[String, Body] = Map.empty
    def valueParams: Map[String, Body] = Map.empty
    def typeParams: Map[String, Body] = Map.empty
    def version: Option[Body] = None
    def since: Option[Body] = None
    def todo: List[Body] = Nil
    def deprecated: Option[Body] = None
    def note: List[Body] = Nil
    def example: List[Body] = Nil
    def constructor: Option[Body] = None
    def inheritDiagram: List[String] = Nil
    def contentDiagram: List[String] = Nil
    def group: Option[String] = None
    def groupDesc: Map[String,Body] = Map.empty
    def groupNames: Map[String,String] = Map.empty
    def groupPrio: Map[String,Int] = Map.empty
  }

//  import global._
//  import definitions.{ ObjectClass, NothingClass, AnyClass, AnyValClass, AnyRefClass, ListClass }
//  import rootMirror.{ RootPackage, RootClass, EmptyPackage }

  // Defaults for member grouping, that may be overridden by the template
  val defaultGroup = "Ungrouped"
  val defaultGroupName = "Ungrouped"
  val defaultGroupDesc = None
  val defaultGroupPriority = 1000

  def templatesCount = docTemplatesCache.count(_._2.isDocTemplate) - droppedPackages.size

  private var _modelFinished = false
  def modelFinished: Boolean = _modelFinished
  private var universe: Universe = null

  def makeModel: Option[Universe] = {
    val universe = new Universe { thisUniverse =>
      thisFactory.universe = thisUniverse
      val settings = thisFactory.settings
      val rootPackage = modelCreation.createRootPackage
      val host: HostContext = ModelFactory.this.host
    }
    _modelFinished = true

    // complete the links between model entities, everything that couldn't have been done before
    universe.rootPackage.completeModel()

    Some(universe)
  }

  val RootPackage = c.Term.Name("_root_")(false).defns.head
  val AnyClass = c.Term.Select(c.Term.Name("scala")(false), c.Term.Name("Any")(false)).defns.head
  val AnyRefClass = c.Term.Select(c.Term.Name("scala")(false), c.Term.Name("AnyRef")(false)).defns.head
  val NothingClass = c.Term.Select(c.Term.Name("scala")(false), c.Term.Name("Nothing")(false)).defns.head
  val EmptyPackage = c.Term.Name("_empty_")(false).defns.head
  val ObjectClass = c.Term.Select(c.Term.Select(c.Term.Name("java")(false), c.Term.Name("lang")(false)), c.Term.Name("Object")(false)).defns.head

  // state:
  var ids = 0
  private val droppedPackages = mutable.Set[PackageImpl]()
  protected val docTemplatesCache = new mutable.LinkedHashMap[c.Member, DocTemplateImpl]
  protected val noDocTemplatesCache = new mutable.LinkedHashMap[c.Member, NoDocTemplateImpl]
  def packageDropped(tpl: DocTemplateImpl) = tpl match {
    case p: PackageImpl => droppedPackages(p)
    case _ => false
  }

  def optimize(str: String): String =
    if (str.length < 16) str.intern else str

  implicit class RichMember(mbr: c.Member) {
    def isAbstractType = mbr.isType && mbr.isAbstract
    def isAliasType = mbr.isType && !mbr.isAbstract
    private def ownerOpt(implicit host: HostContext): Option[c.Member.Template] =
      host.owner(mbr) match {
        case t: c.Member.Template => Some(t)
        case _ => None
      }
    def owner(implicit host: HostContext): c.Member.Template = ownerOpt.get
    def ownerChain(implicit host: HostContext): List[c.Member.Template] =
      ownerOpt.map(own => own :: own.ownerChain).getOrElse(Nil)
    def isConstructor = false
  }

  /* ============== IMPLEMENTATION PROVIDING ENTITY TYPES ============== */

  abstract class EntityImpl(val sym: c.Member, val inTpl: TemplateImpl) extends Entity {

    protected val reflName: c.Name = sym match {
      case d: c.Has.Name => d.name
    }

    val name: String = optimize(reflName.value)
    val universe = thisFactory.universe
    def inTemplate: TemplateImpl = inTpl
    def toRoot: List[EntityImpl] = this :: inTpl.toRoot
    def qualifiedName = name
    def annotations = List() // TODO: sym.annotations.filterNot(_.tpe =:= typeOf[macroImpl]).map(makeAnnotation)
    def inPackageObject: Boolean = sym.isPkgObject
    def isType = reflName.isInstanceOf[c.Type.Name]
  }


  trait TemplateImpl extends EntityImpl with TemplateEntity {
    override def qualifiedName: String =
      if (inTemplate == null || inTemplate.isRootPackage) name else optimize(inTemplate.qualifiedName + "." + name)
    def isPackage = sym.isPkg
    def isTrait = sym.isTrait
    def isClass = sym.isClass && !sym.isTrait
    def isObject = sym.isObject && !sym.isPkgObject
    def isCaseClass = sym.isCase
    def isRootPackage = false
    def selfType = None // TODO: if (sym.thisSym == sym) None else Some(makeType(sym.thisSym.typeOfThis, this))
  }

  abstract class MemberImpl(sym: c.Member, inTpl: DocTemplateImpl) extends EntityImpl(sym, inTpl) with MemberEntity {
    // If the current tpl is a DocTemplate, we consider itself as the root for resolving link targets (instead of the
    // package the class is in) -- so people can refer to methods directly [[foo]], instead of using [[MyClass.foo]]
    // in the doc comment of MyClass
    def linkTarget: DocTemplateImpl = inTpl

    lazy val comment: Option[Comment] = {
//      TODO:
//      val documented = if (sym.hasAccessorFlag) sym.accessed else sym
//      thisFactory.comment(documented, linkTarget, inTpl)
      Some(new EmptyComment("No such thing..."))
    }

    def group = comment flatMap (_.group) getOrElse defaultGroup
    override def inTemplate = inTpl
    override def toRoot: List[MemberImpl] = this :: inTpl.toRoot
    def inDefinitionTemplates =
        if (inTpl == null)
          docTemplatesCache(RootPackage) :: Nil
        else
          makeTemplate(sym.owner) :: (sym.overrides map { inhSym => makeTemplate(inhSym.owner) }).toList
    def visibility = {
      if (sym.isPrivate) PrivateInInstance()
      else if (sym.isProtected) ProtectedInInstance()
      else {
        val qual =
          if (sym.isPrivate || sym.isProtected) {
            val accessQual = sym.mods.collect({ case mod: c.Mod.Private => mod.within; case mod: c.Mod.Protected => mod.within }).flatten.head
            val where = accessQual match {
              case t: c.Term.This => ???
              case n: c.Name => n.defns.head
            }
            Some(makeTemplate(where))
          } else
            None
        if (sym.isPrivate) PrivateInTemplate(inTpl)
        else if (sym.isProtected) ProtectedInTemplate(qual getOrElse inTpl)
        else qual match {
          case Some(q) => PrivateInTemplate(q)
          case None => Public()
        }
      }
    }
    def flags = {
      val fgs = mutable.ListBuffer.empty[Paragraph]
      if (sym.isImplicit) fgs += Paragraph(Text("implicit"))
      if (sym.isSealed) fgs += Paragraph(Text("sealed"))
      if (!sym.isTrait && (sym.isAbstract)) fgs += Paragraph(Text("abstract"))
      /* Resetting the DEFERRED flag is a little trick here for refined types: (example from scala.collections)
       * {{{
       *     implicit def traversable2ops[T](t: scala.collection.GenTraversableOnce[T]) = new TraversableOps[T] {
       *       def isParallel = ...
       * }}}
       * the type the method returns is TraversableOps, which has all-abstract symbols. But in reality, it couldn't have
       * any abstract terms, otherwise it would fail compilation. So we reset the DEFERRED flag. */
      if (!sym.isTrait && !isImplicitlyInherited) fgs += Paragraph(Text("abstract"))
      if (!sym.isObject && sym.isFinal) fgs += Paragraph(Text("final"))
      if (sym.isMacro) fgs += Paragraph(Text("macro"))
      fgs.toList
    }
    def deprecation =
      None
//      TODO:
//      if (sym.isDeprecated)
//        Some((sym.deprecationMessage, sym.deprecationVersion) match {
//          case (Some(msg), Some(ver)) => parseWiki("''(Since version " + ver + ")'' " + msg, NoPosition, inTpl)
//          case (Some(msg), None) => parseWiki(msg, NoPosition, inTpl)
//          case (None, Some(ver)) =>  parseWiki("''(Since version " + ver + ")''", NoPosition, inTpl)
//          case (None, None) => Body(Nil)
//        })
//      else
//        comment flatMap { _.deprecated }
    def migration =
      None
//      TODO:
//      if(sym.hasMigrationAnnotation)
//        Some((sym.migrationMessage, sym.migrationVersion) match {
//          case (Some(msg), Some(ver)) => parseWiki("''(Changed in version " + ver + ")'' " + msg, NoPosition, inTpl)
//          case (Some(msg), None) => parseWiki(msg, NoPosition, inTpl)
//          case (None, Some(ver)) =>  parseWiki("''(Changed in version " + ver + ")''", NoPosition, inTpl)
//          case (None, None) => Body(Nil)
//        })
//      else
//        None

    def resultType = {
//      def resultTpe(tpe: Type): Type = tpe match { // similar to finalResultType, except that it leaves singleton types alone
//        case PolyType(_, res) => resultTpe(res)
//        case MethodType(_, res) => resultTpe(res)
//        case NullaryMethodType(res) => resultTpe(res)
//        case _ => tpe
//      }
//      val tpe = byConversion.fold(sym.tpe) (_.toType memberInfo sym)
//      makeTypeInTemplateContext(resultTpe(tpe), inTemplate, sym)
      ???
    }
    def isDef = false
    def isVal = false
    def isLazyVal = false
    def isVar = false
    def isConstructor = false
    def isAliasType = false
    def isAbstractType = false
    def isAbstract =
      // for the explanation of conversion == null see comment on flags
      ((!sym.isTrait && sym.isAbstract && (!isImplicitlyInherited)) || (sym.isAbstract  && sym.isType))

    def signature =
      ???
      // TODO:
      // externalSignature(sym)
    lazy val signatureCompat = {

//      def defParams(mbr: Any): String = mbr match {
//        case d: MemberEntity with Def =>
//          val paramLists: List[String] =
//            if (d.valueParams.isEmpty) Nil
//            else d.valueParams map (ps => ps map (_.resultType.name) mkString ("(",",",")"))
//          paramLists.mkString
//        case _ => ""
//      }
//
//      def tParams(mbr: Any): String = mbr match {
//        case hk: HigherKinded if !hk.typeParams.isEmpty =>
//          def boundsToString(hi: Option[TypeEntity], lo: Option[TypeEntity]): String = {
//            def bound0(bnd: Option[TypeEntity], pre: String): String = bnd match {
//              case None => ""
//              case Some(tpe) => pre ++ tpe.toString
//            }
//            bound0(hi, "<:") ++ bound0(lo, ">:")
//          }
//          "[" + hk.typeParams.map(tp => tp.variance + tp.name + tParams(tp) + boundsToString(tp.hi, tp.lo)).mkString(", ") + "]"
//        case _ => ""
//      }
//
//      (name + tParams(this) + defParams(this) +":"+ resultType.name).replaceAll("\\s","") // no spaces allowed, they break links
      ""
    }
    // these only apply for NonTemplateMemberEntities
    def useCaseOf: Option[MemberImpl] = None
    def byConversion: Option[ImplicitConversionImpl] = None
    def isImplicitlyInherited = false
    def isShadowedImplicit    = false
    def isAmbiguousImplicit   = false
    def isShadowedOrAmbiguousImplicit = false
  }

  /** A template that is not documented at all. The class is instantiated during lookups, to indicate that the class
   *  exists, but should not be documented (either it's not included in the source or it's not visible)
   */
  class NoDocTemplateImpl(sym: c.Member, inTpl: TemplateImpl) extends EntityImpl(sym, inTpl) with TemplateImpl with HigherKindedImpl with NoDocTemplate {
    assert(modelFinished, this)
    assert(!(noDocTemplatesCache isDefinedAt sym), (sym, noDocTemplatesCache(sym)))
    noDocTemplatesCache += (sym -> this)
    def isDocTemplate = false
  }

  /** An inherited template that was not documented in its original owner - example:
   *  in classpath:  trait T { class C } -- T (and implicitly C) are not documented
   *  in the source: trait U extends T -- C appears in U as a MemberTemplateImpl -- that is, U has a member for it
   *  but C doesn't get its own page
   */
  abstract class MemberTemplateImpl(sym: c.Member, inTpl: DocTemplateImpl) extends MemberImpl(sym, inTpl) with TemplateImpl with HigherKindedImpl with MemberTemplateEntity {
    // no templates cache for this class, each owner gets its own instance
    def isDocTemplate = false
    lazy val definitionName = optimize(inDefinitionTemplates.head.qualifiedName + "." + name)
    def valueParams: List[List[ValueParam]] = Nil /** TODO, these are now only computed for DocTemplates */

    def parentTypes =
      Nil
//      TODO:
//      if (sym.isPkg || sym == AnyClass) List() else {
//        val tps = (this match {
//          case a: AliasType => sym.tpe.dealias.parents
//          case a: AbstractType => sym.info.bounds match {
//            case TypeBounds(lo, RefinedType(parents, decls)) => parents
//            case TypeBounds(lo, hi) => hi :: Nil
//            case _ => Nil
//          }
//          case _ => sym.tpe.parents
//        }) map { _.asSeenFrom(sym.thisType, sym) }
//        makeParentTypes(RefinedType(tps, EmptyScope), Some(this), inTpl)
//      }
  }

   /** The instantiation of `TemplateImpl` triggers the creation of the following entities:
    *  All ancestors of the template and all non-package members.
    */
  abstract class DocTemplateImpl(sym: c.Member, inTpl: DocTemplateImpl) extends MemberTemplateImpl(sym, inTpl) with DocTemplateEntity {
    assert(!modelFinished, (sym, inTpl))
    assert(!(docTemplatesCache isDefinedAt sym), sym)
    docTemplatesCache += (sym -> this)

    if (settings.verbose)
      println("Creating doc template for " + sym)

    override def linkTarget: DocTemplateImpl = this
    override def toRoot: List[DocTemplateImpl] = this :: inTpl.toRoot

    protected def reprSymbol: c.Member = sym

    def inSource =
      None
//      TODO:
//      if (reprSymbol.sourceFile != null && ! reprSymbol.isSynthetic)
//        Some((reprSymbol.sourceFile, reprSymbol.pos.line))
//      else
//        None

    def sourceUrl =
      None
//      TODO:
//    {
//      def fixPath(s: String) = s.replaceAll("\\" + java.io.File.separator, "/")
//      val assumedSourceRoot  = fixPath(settings.sourcepath.value) stripSuffix "/"
//
//      if (!settings.docsourceurl.isDefault)
//        inSource map { case (file, _) =>
//          val filePath = fixPath(file.path).replaceFirst("^" + assumedSourceRoot, "").stripSuffix(".scala")
//          val tplOwner = this.inTemplate.qualifiedName
//          val tplName = this.name
//          val patches = new Regex("""€\{(FILE_PATH|TPL_OWNER|TPL_NAME)\}""")
//          def substitute(name: String): String = name match {
//            case "FILE_PATH" => filePath
//            case "TPL_OWNER" => tplOwner
//            case "TPL_NAME" => tplName
//          }
//          val patchedString = patches.replaceAllIn(settings.docsourceurl.value, m => java.util.regex.Matcher.quoteReplacement(substitute(m.group(1))) )
//          new java.net.URL(patchedString)
//        }
//      else None
//    }

//    private def templateAndType(ancestor: Symbol): (TemplateImpl, TypeEntity) = (makeTemplate(ancestor), makeType(reprSymbol.info.baseType(ancestor), this))
    lazy val (linearizationTemplates, linearizationTypes) =
      (Nil, Nil)
//      TODO:
//      (reprSymbol.ancestors map templateAndType).unzip

    /* Subclass cache */
    private lazy val subClassesCache = (
      if (sym == AnyRefClass) null
      else mutable.ListBuffer[DocTemplateEntity]()
    )
    def registerSubClass(sc: DocTemplateEntity): Unit = {
      if (subClassesCache != null)
        subClassesCache += sc
    }
    def directSubClasses = if (subClassesCache == null) Nil else subClassesCache.toList

    /* Implicitly convertible class cache */
    private var implicitlyConvertibleClassesCache: mutable.ListBuffer[(DocTemplateImpl, ImplicitConversionImpl)] = null
    def registerImplicitlyConvertibleClass(dtpl: DocTemplateImpl, conv: ImplicitConversionImpl): Unit = {
      if (implicitlyConvertibleClassesCache == null)
        implicitlyConvertibleClassesCache = mutable.ListBuffer[(DocTemplateImpl, ImplicitConversionImpl)]()
      implicitlyConvertibleClassesCache += ((dtpl, conv))
    }

    def incomingImplicitlyConvertedClasses: List[(DocTemplateImpl, ImplicitConversionImpl)] =
      if (implicitlyConvertibleClassesCache == null)
        List()
      else
        implicitlyConvertibleClassesCache.toList

    // the implicit conversions are generated eagerly, but the members generated by implicit conversions are added
    // lazily, on completeModel
    val conversions: List[ImplicitConversionImpl] =
      Nil
//      TODO:
//      if (settings.docImplicits) makeImplicitConversions(sym, this) else Nil

    // members as given by the compiler
    lazy val memberSyms      = Nil
//      TODO:
//      sym.info.members.filter(s => membersShouldDocument(s, this)).toList

    // the inherited templates (classes, traits or objects)
    val memberSymsLazy  = memberSyms.filter(t => templateShouldDocument(t, this) && !inOriginalOwner(t, this))
    // the direct members (methods, values, vars, types and directly contained templates)
    val memberSymsEager = memberSyms.filter(!memberSymsLazy.contains(_))
    // the members generated by the symbols in memberSymsEager
    val ownMembers      = (memberSymsEager.flatMap(makeMember(_, None, this)))

    // all the members that are documentented PLUS the members inherited by implicit conversions
    var members: List[MemberImpl] = ownMembers

    def templates       = members collect { case c: TemplateEntity with MemberEntity => c }
    def methods         = members collect { case d: Def => d }
    def values          = members collect { case v: Val => v }
    def abstractTypes   = members collect { case t: AbstractType => t }
    def aliasTypes      = members collect { case t: AliasType => t }

    /**
     * This is the final point in the core model creation: no DocTemplates are created after the model has finished, but
     * inherited templates and implicit members are added to the members at this point.
     */
    def completeModel(): Unit = {
      // DFS completion
      // since alias types and abstract types have no own members, there's no reason for them to call completeModel
      if (!sym.isType)
        for (member <- members)
          member match {
            case d: DocTemplateImpl => d.completeModel()
            case _ =>
          }

      members :::= memberSymsLazy.map(modelCreation.createLazyTemplateMember(_, this))

      outgoingImplicitlyConvertedClasses

//      TODO:
//      for (pt <- sym.info.parents; parentTemplate <- findTemplateMaybe(pt.typeSymbol)) parentTemplate registerSubClass this
//
//      // the members generated by the symbols in memberSymsEager PLUS the members from the usecases
//      val allMembers = ownMembers ::: ownMembers.flatMap(_.useCaseOf).distinct
//      implicitsShadowing = makeShadowingTable(allMembers, conversions, this)
//      // finally, add the members generated by implicit conversions
//      members :::= conversions.flatMap(_.memberImpls)
    }

    var implicitsShadowing = Map[MemberEntity, ImplicitMemberShadowing]()

    lazy val outgoingImplicitlyConvertedClasses: List[(TemplateEntity, TypeEntity, ImplicitConversionImpl)] =
      Nil
//      TODO:
//      conversions flatMap (conv =>
//        if (!implicitExcluded(conv.conversionQualifiedName))
//          conv.targetTypeComponents map {
//            case (template, tpe) =>
//              template match {
//                case d: DocTemplateImpl if (d != this) => d.registerImplicitlyConvertibleClass(this, conv)
//                case _ => // nothing
//              }
//              (template, tpe, conv)
//          }
//        else List()
//      )

    override def isDocTemplate = true

    private[this] lazy val companionSymbol =
//      TODO: Virtual classes
//      if (sym.isAliasType || sym.isAbstractType) {
//        inTpl.sym.info.member(sym.name.toTermName) match {
//          case NoSymbol => NoSymbol
//          case s =>
//            s.info match {
//              case ot: OverloadedType =>
//                NoSymbol
//              case _ =>
//                // that's to navigate from val Foo: FooExtractor to FooExtractor :)
//                s.info.resultType.typeSymbol
//            }
//        }
//      }
//      else
        Try(Some(sym.asInstanceOf[c.Member.Template].companion)).getOrElse(None)

    def companion =
      companionSymbol match {
        case None => None
        case Some(comSym) if !isEmptyJavaObject(comSym) && (comSym.isClass || comSym.isObject) =>
          makeTemplate(comSym) match {
            case d: DocTemplateImpl => Some(d)
            case _ => None
          }
        case _ => None
      }

    def constructors: List[MemberImpl with Constructor] =
//      TODO:
//      if (isClass) members collect { case d: Constructor => d } else Nil
      Nil

    def primaryConstructor: Option[MemberImpl with Constructor] =
//      TODO:
//      if (isClass) constructors find { _.isPrimary } else None
      None

    override def valueParams =
//      TODO:
//      // we don't want params on a class (non case class) signature
//      if (isCaseClass) primaryConstructor match {
//        case Some(const) => const.sym.paramss map (_ map (makeValueParam(_, this)))
//        case None => List()
//      }
//      else List.empty
      Nil

    // These are generated on-demand, make sure you don't call them more than once
    def inheritanceDiagram = None // TODO: makeInheritanceDiagram(this)
    def contentDiagram = None // TODO: makeContentDiagram(this)

    def groupSearch[T](extractor: Comment => Option[T]): Option[T] = {
//      TODO:
//      val comments = comment +: linearizationTemplates.collect { case dtpl: DocTemplateImpl => dtpl.comment }
//      comments.flatten.map(extractor).flatten.headOption orElse {
//        Option(inTpl) flatMap (_.groupSearch(extractor))
//      }
      None
    }

    def groupDescription(group: String): Option[Body] = groupSearch(_.groupDesc.get(group)) orElse { if (group == defaultGroup) defaultGroupDesc else None }
    def groupPriority(group: String): Int = groupSearch(_.groupPrio.get(group)) getOrElse { if (group == defaultGroup) defaultGroupPriority else 0 }
    def groupName(group: String): String = groupSearch(_.groupNames.get(group)) getOrElse { if (group == defaultGroup) defaultGroupName else group }
  }

  abstract class PackageImpl(sym: c.Member, inTpl: PackageImpl) extends DocTemplateImpl(sym, inTpl) with Package {
    override def inTemplate = inTpl
    override def toRoot: List[PackageImpl] = this :: inTpl.toRoot
//    TODO:
//    override def reprSymbol = sym.info.members.find (_.isPackageObject) getOrElse sym

    def packages = members collect { case p: PackageImpl if !(droppedPackages contains p) => p }
  }

  abstract class RootPackageImpl(sym: c.Member) extends PackageImpl(sym, null) with RootPackageEntity

  abstract class NonTemplateMemberImpl(sym: c.Member, conversion: Option[ImplicitConversionImpl],
                                       override val useCaseOf: Option[MemberImpl], inTpl: DocTemplateImpl)
           extends MemberImpl(sym, inTpl) with NonTemplateMemberEntity {
//    TODO:
//    override lazy val comment = {
//      def nonRootTemplate(sym: Symbol): Option[DocTemplateImpl] =
//        if (sym == RootPackage) None else findTemplateMaybe(sym)
//      /* Variable precendence order for implicitly added members: Take the variable defifinitions from ...
//       * 1. the target of the implicit conversion
//       * 2. the definition template (owner)
//       * 3. the current template
//       */
//      val inRealTpl = conversion.flatMap { conv =>
//        nonRootTemplate(conv.toType.typeSymbol)
//      } orElse nonRootTemplate(sym.owner) orElse Option(inTpl)
//      inRealTpl flatMap { tpl =>
//        thisFactory.comment(sym, tpl, tpl)
//      }
//    }

    override def inDefinitionTemplates = useCaseOf.fold(super.inDefinitionTemplates)(_.inDefinitionTemplates)

    override def qualifiedName = optimize(inTemplate.qualifiedName + "#" + name)
    lazy val definitionName = {
      val qualifiedName = conversion.fold(inDefinitionTemplates.head.qualifiedName)(_.conversionQualifiedName)
      optimize(qualifiedName + "#" + name)
    }
    def isUseCase = useCaseOf.isDefined
    override def byConversion: Option[ImplicitConversionImpl] = conversion
    override def isImplicitlyInherited = { assert(modelFinished); conversion.isDefined }
    override def isShadowedImplicit    = isImplicitlyInherited && inTpl.implicitsShadowing.get(this).map(_.isShadowed).getOrElse(false)
    override def isAmbiguousImplicit   = isImplicitlyInherited && inTpl.implicitsShadowing.get(this).map(_.isAmbiguous).getOrElse(false)
    override def isShadowedOrAmbiguousImplicit = isShadowedImplicit || isAmbiguousImplicit
  }

  abstract class NonTemplateParamMemberImpl(sym: c.Member, conversion: Option[ImplicitConversionImpl],
                                            useCaseOf: Option[MemberImpl], inTpl: DocTemplateImpl)
           extends NonTemplateMemberImpl(sym, conversion, useCaseOf, inTpl) {
    def valueParams =
//    TODO:
//    {
//      val info = conversion.fold(sym.info)(_.toType memberInfo sym)
//      info.paramss map { ps => (ps.zipWithIndex) map { case (p, i) =>
//        if (p.nameString contains "$") makeValueParam(p, inTpl, optimize("arg" + i)) else makeValueParam(p, inTpl)
//      }}
//    }
      Nil
  }

  abstract class ParameterImpl(val sym: c.Member, val inTpl: TemplateImpl) extends ParameterEntity {
    val name = optimize(sym.asInstanceOf[c.Has.Name].name.value)
  }

  private trait AliasImpl {
    def sym: c.Member
    def inTpl: TemplateImpl
    def alias = ??? //makeTypeInTemplateContext(sym.tpe.dealias, inTpl, sym)
  }

  private trait TypeBoundsImpl {
    def sym: c.Member
    def inTpl: TemplateImpl
    def lo =
//      TODO:
//      sym.info.bounds match {
//        case TypeBounds(lo, hi) if lo.typeSymbol != NothingClass =>
//          Some(makeTypeInTemplateContext(appliedType(lo, sym.info.typeParams map {_.tpe}), inTpl, sym))
//        case _ => None
//      }
      None
    def hi =
//      TODO:
//      sym.info.bounds match {
//        case TypeBounds(lo, hi) if hi.typeSymbol != AnyClass =>
//          Some(makeTypeInTemplateContext(appliedType(hi, sym.info.typeParams map {_.tpe}), inTpl, sym))
//        case _ => None
//      }
      None
  }

  trait HigherKindedImpl extends HigherKinded {
    def sym: c.Member
    def inTpl: TemplateImpl
    def typeParams =
//      TODO:
//      sym.typeParams map (makeTypeParam(_, inTpl))
      Nil
  }

  /* ============== MAKER METHODS ============== */

  /** This method makes it easier to work with the different kinds of symbols created by scalac by stripping down the
   * package object abstraction and placing members directly in the package.
   *
   * Here's the explanation of what we do. The code:
   *
   * package foo {
   *   object `package` {
   *     class Bar
   *   }
   * }
   *
   * will yield this Symbol structure:
   *                                       +---------+ (2)
   *                                       |         |
   * +---------------+         +---------- v ------- | ---+                              +--------+ (2)
   * | package foo#1 <---(1)---- module class foo#2  |    |                              |        |
   * +---------------+         | +------------------ | -+ |         +------------------- v ---+   |
   *                           | | package object foo#3 <-----(1)---- module class package#4  |   |
   *                           | +----------------------+ |         | +---------------------+ |   |
   *                           +--------------------------+         | | class package$Bar#5 | |   |
   *                                                                | +----------------- | -+ |   |
   *                                                                +------------------- | ---+   |
   *                                                                                     |        |
   *                                                                                     +--------+
   * (1) sourceModule
   * (2) you get out of owners with .owner
   *
   * and normalizeTemplate(Bar.owner) will get us the package, instead of the module class of the package object.
   */
  def normalizeTemplate(aSym: c.Member): c.Member = aSym match {
      case null =>
        normalizeTemplate(RootPackage)
      case x if x == EmptyPackage =>
        normalizeTemplate(RootPackage)
      case ObjectClass =>
        normalizeTemplate(AnyRefClass)
//    Shouldn't happen:
//      case _ if aSym.isPackageObject =>
//        normalizeTemplate(aSym.owner)
//      case _ if aSym.isModuleClass =>
//        normalizeTemplate(aSym.sourceModule)
      case _ =>
        aSym
    }

  /**
   * These are all model construction methods. Please do not use them directly, they are calling each other recursively
   * starting from makeModel. On the other hand, makeTemplate, makeAnnotation, makeMember, makeType should only be used
   * after the model was created (modelFinished=true) otherwise assertions will start failing.
   */
  object modelCreation {

    def createRootPackage: PackageImpl = docTemplatesCache.get(RootPackage) match {
      case Some(root: PackageImpl) => root
      case _ => modelCreation.createTemplate(RootPackage, null) match {
        case Some(root: PackageImpl) => root
        case _ => sys.error("Scaladoc: Unable to create root package!")
      }
    }

    /**
     *  Create a template, either a package, class, trait or object
     */
    def createTemplate(aSym: c.Member, inTpl: DocTemplateImpl): Option[MemberImpl] = {
      // don't call this after the model finished!
      assert(!modelFinished, (aSym, inTpl))

      def createRootPackageComment: Option[Comment] =
        None
//        if(settings.docRootContent.isDefault) None
//        else {
//          import Streamable._
//          Path(settings.docRootContent.value) match {
//            case f : File => {
//              val rootComment = closing(f.inputStream())(is => parse(slurp(is), "", NoPosition, inTpl))
//              Some(rootComment)
//            }
//            case _ => None
//          }
//        }

      def createDocTemplate(bSym: c.Member, inTpl: DocTemplateImpl): DocTemplateImpl = {
        assert(!modelFinished, (bSym, inTpl)) // only created BEFORE the model is finished
        if (bSym.isAliasType && bSym != AnyRefClass)
          new DocTemplateImpl(bSym, inTpl) with AliasImpl with AliasType { override def isAliasType = true }
        else if (bSym.isAbstractType)
          new DocTemplateImpl(bSym, inTpl) with TypeBoundsImpl with AbstractType { override def isAbstractType = true }
        else if (bSym.isObject)
          new DocTemplateImpl(bSym, inTpl) with Object {}
        else if (bSym.isTrait)
          new DocTemplateImpl(bSym, inTpl) with Trait {}
        else if (bSym.isClass || bSym == AnyRefClass)
          new DocTemplateImpl(bSym, inTpl) with Class {}
        else
          sys.error("'" + bSym + "' isn't a class, trait or object thus cannot be built as a documentable template.")
      }

      val bSym = normalizeTemplate(aSym)
      if (docTemplatesCache isDefinedAt bSym)
        return Some(docTemplatesCache(bSym))

      /* Three cases of templates:
       * (1) root package -- special cased for bootstrapping
       * (2) package
       * (3) class/object/trait
       */
      if (bSym == RootPackage) // (1)
        Some(new RootPackageImpl(bSym) {
          override lazy val comment = createRootPackageComment
          override val name = "root"
          override def inTemplate = this
          override def toRoot = this :: Nil
          override def qualifiedName = "_root_"
          override def isRootPackage = true
          override lazy val memberSyms =
            Nil
//            TODO:
//            (bSym.info.members ++ EmptyPackage.info.members).toList filter { s =>
//              s != EmptyPackage && s != RootPackage
//            }
        })
      else if (bSym.isPkg) // (2)
//        TODO:
//        if (settings.skipPackage(makeQualifiedName(bSym)))
//          None
//        else
          inTpl match {
            case inPkg: PackageImpl =>
              val pack = new PackageImpl(bSym, inPkg) {}
              // Used to check package pruning works:
              //println(pack.qualifiedName)
              if (pack.templates.filter(_.isDocTemplate).isEmpty && pack.memberSymsLazy.isEmpty) {
                droppedPackages += pack
                None
              } else
                Some(pack)
            case _ =>
              sys.error("'" + bSym + "' must be in a package")
          }
      else {
        // no class inheritance at this point
        assert(inOriginalOwner(bSym, inTpl), bSym + " in " + inTpl)
        Some(createDocTemplate(bSym, inTpl))
      }
    }

    /**
     *  After the model is completed, no more DocTemplateEntities are created.
     *  Therefore any c.Member that still appears is:
     *   - MemberTemplateEntity (created here)
     *   - NoDocTemplateEntity (created in makeTemplate)
     */
    def createLazyTemplateMember(aSym: c.Member, inTpl: DocTemplateImpl): MemberImpl = {

      // Code is duplicate because the anonymous classes are created statically
      def createNoDocMemberTemplate(bSym: c.Member, inTpl: DocTemplateImpl): MemberTemplateImpl = {
        assert(modelFinished) // only created AFTER the model is finished
        if (bSym.isObject) // || (bSym.isAliasType && bSym.tpe.typeSymbol.isModule))
          new MemberTemplateImpl(bSym, inTpl) with Object {}
        else if (bSym.isTrait) // || (bSym.isAliasType && bSym.tpe.typeSymbol.isTrait))
          new MemberTemplateImpl(bSym, inTpl) with Trait {}
        else if (bSym.isClass) // || (bSym.isAliasType && bSym.tpe.typeSymbol.isClass))
          new MemberTemplateImpl(bSym, inTpl) with Class {}
        else
          sys.error("'" + bSym + "' isn't a class, trait or object thus cannot be built as a member template.")
      }

      assert(modelFinished)
      val bSym = normalizeTemplate(aSym)

      if (docTemplatesCache isDefinedAt bSym)
        docTemplatesCache(bSym)
      else
        docTemplatesCache.get(bSym.owner) match {
          case Some(inTpl) =>
            val mbrs = inTpl.members.collect({ case mbr: MemberImpl if mbr.sym == bSym => mbr })
            assert(mbrs.length == 1)
            mbrs.head
          case _ =>
            // move the class completely to the new location
            createNoDocMemberTemplate(bSym, inTpl)
        }
    }
  }

  // TODO: Should be able to override the type
  def makeMember(aSym: c.Member, conversion: Option[ImplicitConversionImpl], inTpl: DocTemplateImpl): List[MemberImpl] = {

    def makeMember0(bSym: c.Member, useCaseOf: Option[MemberImpl]): Option[MemberImpl] = {
      if (bSym.isLazy)
          Some(new NonTemplateMemberImpl(bSym, conversion, useCaseOf, inTpl) with Val {
            override def isLazyVal = true
          })
      else if (bSym.isVar)
        Some(new NonTemplateMemberImpl(bSym, conversion, useCaseOf, inTpl) with Val {
          override def isVar = true
        })
      else if (bSym.isDef) {
        val cSym = { // This unsightly hack closes issue #4086.
//          if (bSym == definitions.Object_synchronized) {
//            val cSymInfo = (bSym.info: @unchecked) match {
//              case PolyType(ts, MethodType(List(bp), mt)) =>
//                val cp = bp.clonec.Member.setPos(bp.pos).setInfo(definitions.byNameType(bp.info))
//                PolyType(ts, MethodType(List(cp), mt))
//            }
//            bSym.clonec.Member.setPos(bSym.pos).setInfo(cSymInfo)
//          }
//          else
            bSym
        }
        Some(new NonTemplateParamMemberImpl(cSym, conversion, useCaseOf, inTpl) with HigherKindedImpl with Def {
          override def isDef = true
        })
      }
//      TODO: How do we handle constructors?
//      else if (bSym.isConstructor)
//        if (conversion.isDefined)
//          None // don't list constructors inherted by implicit conversion
//        else
//          Some(new NonTemplateParamMemberImpl(bSym, conversion, useCaseOf, inTpl) with Constructor {
//            override def isConstructor = true
//            def isPrimary = sym.isPrimaryConstructor
//          })
      else if (bSym.isVal) // Scala field accessor or Java field
        Some(new NonTemplateMemberImpl(bSym, conversion, useCaseOf, inTpl) with Val {
          override def isVal = true
        })
      else if (bSym.isAbstractType && !typeShouldDocument(bSym, inTpl))
        Some(new MemberTemplateImpl(bSym, inTpl) with TypeBoundsImpl with AbstractType {
          override def isAbstractType = true
        })
      else if (bSym.isAliasType && !typeShouldDocument(bSym, inTpl))
        Some(new MemberTemplateImpl(bSym, inTpl) with AliasImpl with AliasType {
          override def isAliasType = true
        })
      else if (!modelFinished && (bSym.isPkg || templateShouldDocument(bSym, inTpl)))
        modelCreation.createTemplate(bSym, inTpl)
      else
        None
    }

    if (!localShouldDocument(aSym)) // TODO: shouldn't occur || aSym.isModuleClass || aSym.isPackageObject || aSym.isMixinConstructor)
      Nil
    else {
//      TODO: We don't support usecases:
//      val allSyms = useCases(aSym, inTpl.sym) map { case (bSym, bComment, bPos) =>
//        docComments.put(bSym, DocComment(bComment, bPos)) // put the comment in the list, don't parse it yet, closes SI-4898
//        bSym
//      }

      val member = makeMember0(aSym, None)
//      if (allSyms.isEmpty)
        member.toList
//      else
//        // Use cases replace the original definitions - SI-5054
//        allSyms flatMap { makeMember0(_, member) }
    }
  }

  def findMember(aSym: c.Member, inTpl: DocTemplateImpl): Option[MemberImpl] = {
    normalizeTemplate(aSym.owner)
    inTpl.members.find(_.sym == aSym)
  }

  def findTemplateMaybe(aSym: c.Member): Option[DocTemplateImpl] = {
    assert(modelFinished)
    docTemplatesCache.get(normalizeTemplate(aSym)).filterNot(packageDropped(_))
  }

  def makeTemplate(aSym: c.Member): TemplateImpl = makeTemplate(aSym, None)

  def makeTemplate(aSym: c.Member, inTpl: Option[TemplateImpl]): TemplateImpl = {
    assert(modelFinished)

    def makeNoDocTemplate(aSym: c.Member, inTpl: TemplateImpl): NoDocTemplateImpl =
      noDocTemplatesCache getOrElse (aSym, new NoDocTemplateImpl(aSym, inTpl))

    findTemplateMaybe(aSym) getOrElse {
      val bSym = normalizeTemplate(aSym)
      makeNoDocTemplate(bSym, inTpl getOrElse makeTemplate(bSym.owner))
    }
  }

// TODO: Annotations
//  def makeAnnotation(annot: c.Mod.Annot): scala.tools.nsc.doc.model.Annotation = {
//    val aSym = annot.tpe
//    new EntityImpl(aSym, makeTemplate(aSym.owner)) with scala.tools.nsc.doc.model.Annotation {
//      lazy val annotationClass =
//        makeTemplate(annot.symbol)
//      val arguments = {
//        val paramsOpt: Option[List[ValueParam]] = annotationClass match {
//          case aClass: DocTemplateEntity with Class =>
//            val constr = aClass.constructors collectFirst {
//              case c: MemberImpl if c.sym == annot.original.c.Member => c
//            }
//            constr flatMap (_.valueParams.headOption)
//          case _ => None
//        }
//        val argTrees = annot.args map makeTree
//        paramsOpt match {
//          case Some (params) =>
//            params zip argTrees map { case (param, tree) =>
//              new ValueArgument {
//                def parameter = Some(param)
//                def value = tree
//              }
//            }
//          case None =>
//            argTrees map { tree =>
//              new ValueArgument {
//                def parameter = None
//                def value = tree
//              }
//            }
//        }
//      }
//    }
//  }

  /** */
  def makeTypeParam(aSym: c.Member, inTpl: TemplateImpl): TypeParam =
    new ParameterImpl(aSym, inTpl) with TypeBoundsImpl with HigherKindedImpl with TypeParam {
      def variance: String = {
        if (sym.isCovariant) "+"
        else if (sym.isContravariant) "-"
        else ""
      }
    }

  /** */
  def makeValueParam(aSym: c.Member, inTpl: DocTemplateImpl): ValueParam = {
    makeValueParam(aSym, inTpl, aSym.asInstanceOf[c.Has.Name].name.value)
  }


  /** */
  def makeValueParam(aSym: c.Member, inTpl: DocTemplateImpl, newName: String): ValueParam =
    new ParameterImpl(aSym, inTpl) with ValueParam {
      override val name = newName
      def defaultValue =
//        TODO: Default values
//        if (aSym.hasDefault) {
//          // units.filter should return only one element
//          (currentRun.units filter (_.source.file == aSym.sourceFile)).toList match {
//            case List(unit) =>
//              // SI-4922 `sym == aSym` is insufficent if `aSym` is a clone of c.Member
//              //         of the parameter in the tree, as can happen with type parametric methods.
//              def isCorrespondingParam(sym: c.Member) = (
//                sym != null &&
//                sym != Noc.Member &&
//                sym.owner == aSym.owner &&
//                sym.name == aSym.name &&
//                sym.isParamWithDefault
//              )
//              unit.body find (t => isCorrespondingParam(t.c.Member)) collect {
//                case ValDef(_,_,_,rhs) if rhs ne EmptyTree  => makeTree(rhs)
//              }
//            case _ => None
//          }
//        }
//        else
          None
      def resultType =
//        TODO:
//        makeTypeInTemplateContext(aSym.tpe, inTpl, aSym)
        ???
      def isImplicit = aSym.isImplicit
    }

//  /** */
//  def makeTypeInTemplateContext(aType: c.Type, inTpl: TemplateImpl, dclSym: c.Member): TypeEntity = {
//    def ownerTpl(sym: c.Member): c.Member =
//      if (sym.isClass || sym.isObject || sym == NoSymbol) sym else ownerTpl(sym.owner)
//    val tpe =
//      if (thisFactory.settings.useStupidTypes) aType else {
//        def ownerTpl(sym: c.Member): c.Member =
//          if (sym.isClass || sym.isObject || sym == NoSymbol) sym else ownerTpl(sym.owner)
//        val fixedSym = if (inTpl.sym.isModule) inTpl.sym.moduleClass else inTpl.sym
//        aType.asSeenFrom(fixedSym.thisType, ownerTpl(dclSym))
//      }
//    makeType(tpe, inTpl)
//  }

//  /** Get the types of the parents of the current class, ignoring the refinements */
//  def makeParentTypes(aType: Type, tpl: Option[MemberTemplateImpl], inTpl: TemplateImpl): List[(TemplateEntity, TypeEntity)] = aType match {
//    case RefinedType(parents, defs) =>
//      val ignoreParents = Set[c.Member](AnyClass, AnyRefClass, ObjectClass)
//      val filtParents =
//        // we don't want to expose too many links to AnyRef, that will just be redundant information
//        tpl match {
//          case Some(tpl) if (!tpl.sym.isModule && parents.length < 2) || (tpl.sym == AnyValClass) || (tpl.sym == AnyRefClass) || (tpl.sym == AnyClass) => parents
//          case _ => parents.filterNot((p: Type) => ignoreParents(p.typec.Member))
//        }
//
//      /** Returns:
//       *   - a DocTemplate if the type's c.Member is documented
//       *   - a NoDocTemplateMember if the type's c.Member is not documented in its parent but in another template
//       *   - a NoDocTemplate if the type's c.Member is not documented at all */
//      def makeTemplateOrMemberTemplate(parent: Type): TemplateImpl = {
//        def noDocTemplate = makeTemplate(parent.typec.Member)
//        findTemplateMaybe(parent.typec.Member) match {
//          case Some(tpl) => tpl
//          case None => parent match {
//            case TypeRef(pre, sym, args) =>
//              findTemplateMaybe(pre.typec.Member) match {
//                case Some(tpl) => findMember(parent.typec.Member, tpl).collect({case t: TemplateImpl => t}).getOrElse(noDocTemplate)
//                case None => noDocTemplate
//              }
//            case _ => noDocTemplate
//          }
//        }
//      }
//
//      filtParents.map(parent => {
//        val templateEntity = makeTemplateOrMemberTemplate(parent)
//        val typeEntity = makeType(parent, inTpl)
//        (templateEntity, typeEntity)
//      })
//    case _ =>
//      List((makeTemplate(aType.typec.Member), makeType(aType, inTpl)))
//  }
//
//  def makeQualifiedName(sym: c.Member, relativeTo: Option[c.Member] = None): String = {
//    val stop = relativeTo map (_.ownerChain.toSet) getOrElse Set[c.Member]()
//    var sym1 = sym
//    val path = new StringBuilder()
//    // var path = List[c.Member]()
//
//    while ((sym1 != NoSymbol) && (path.isEmpty || !stop(sym1))) {
//      val sym1Norm = normalizeTemplate(sym1)
//      if (!sym1.sourceModule.isPackageObject && sym1Norm != RootPackage) {
//        if (path.length != 0)
//          path.insert(0, ".")
//        path.insert(0, sym1Norm.nameString)
//        // path::= sym1Norm
//      }
//      sym1 = sym1.owner
//    }
//
//    optimize(path.toString)
//    //path.mkString(".")
//  }

  def inOriginalOwner(aSym: c.Member, inTpl: TemplateImpl): Boolean =
    normalizeTemplate(aSym.owner) == normalizeTemplate(inTpl.sym)

  def templateShouldDocument(aSym: c.Member, inTpl: DocTemplateImpl): Boolean =
    (aSym.isTrait || aSym.isClass || aSym.isObject || typeShouldDocument(aSym, inTpl)) &&
    localShouldDocument(aSym) &&
    !isEmptyJavaObject(aSym) &&
    // either it's inside the original owner or we can document it later:
    (!inOriginalOwner(aSym, inTpl) || aSym.isPkg)

  def membersShouldDocument(sym: c.Member, inTpl: TemplateImpl) = {
    // pruning modules that shouldn't be documented
    // Why c.Member.isInitialized? Well, because we need to avoid exploring all the space available to scaladoc
    // from the classpath -- scaladoc is a hog, it will explore everything starting from the root package unless we
    // somehow prune the tree. And isInitialized is a good heuristic for prunning -- if the package was not explored
    // during typer and refchecks, it's not necessary for the current application and there's no need to explore it.
    localShouldDocument(sym) &&
    // Only this class's constructors are part of its members, inherited constructors are not.
    (!sym.isConstructor || sym.owner == inTpl.sym)
    // If the @bridge annotation overrides a normal member, show it
//    !isPureBridge(sym)
  }

  def isEmptyJavaObject(aSym: c.Member): Boolean =
    aSym.isObject && aSym.isJava //&&
//    aSym.info.members.exists(s => localShouldDocument(s) && (!s.isConstructor || s.owner == aSym))

  def localShouldDocument(aSym: c.Member): Boolean =
    !aSym.isPrivate

//  /** Filter '@bridge' methods only if *they don't override non-bridge methods*. See SI-5373 for details */
//  def isPureBridge(sym: c.Member) = sym.isBridge && sym.allOverriddenc.Members.forall(_.isBridge)

  // the classes that are excluded from the index should also be excluded from the diagrams
  def classExcluded(clazz: TemplateEntity): Boolean = settings.hardcoded.isExcluded(clazz.qualifiedName)

  // the implicit conversions that are excluded from the pages should not appear in the diagram
  def implicitExcluded(convertorMethod: String): Boolean = settings.hiddenImplicits(convertorMethod)

  // whether or not to create a page for an {abstract,alias} type
  def typeShouldDocument(bSym: c.Member, inTpl: DocTemplateImpl) =
    false
//    TODO:
//    (settings.docExpandAllTypes && (bSym.sourceFile != null)) ||
//    (bSym.isAliasType || bSym.isAbstractType) &&
//    { val rawComment = global.expandedDocComment(bSym, inTpl.sym)
//      rawComment.contains("@template") || rawComment.contains("@documentable") }
}
