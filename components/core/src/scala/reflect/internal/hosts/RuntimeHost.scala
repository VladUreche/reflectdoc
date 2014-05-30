package scala.reflect
package internal
package hosts

import scala.reflect.{ core => p }
import scala.reflect.semantic._
import scala.collection.immutable.Seq

trait RuntimeHostCrappyPart extends HostContext {

  import scala.reflect.runtime.{ universe => ru }

  protected def classloader: ClassLoader
  protected lazy val mirror = ru.runtimeMirror(classloader)

  import ru._

  abstract trait Converter[T, +R] {
    def convert(t: T): R
  }

  protected def conv[T, R](input: T)(implicit converter: Converter[T, R]): R =
    converter.convert(input)

  implicit object ConvertSymbolToNothing extends Converter[Symbol, Nothing] {
    def convert(t: Symbol): Nothing = ???
  }

  implicit object ConvertType extends Converter[Type, p.Type] {
    def convert(t: Type): p.Type = t match {
      case WildcardType => ???
      case BoundedWildcardType(bounds) => ???
      case NoType => ???
      case NoPrefix => ???
      case ThisType(sym) => ???
      case SuperType(thistpe, supertpe) => ???
      case SingleType(pre, sym) => ???
      case ConstantType(value) => ???
      case t: TypeRef => ConvertTypeRef.convert(t)
      case RefinedType(parents, defs) => ???
      case ExistentialType(tparams, result) => ???
      case AnnotatedType(annots, tp) => ???
      case TypeBounds(lo, hi) => ???
      case ClassInfoType(parents, defs, clazz) => ???
      case MethodType(paramtypes, result) => ???
      case NullaryMethodType(result) => ???
      case PolyType(tparams, result) => ???
    }
  }

  def unreachable = ???

    implicit class RichType(tpe: Type) {
      def depoly: Type = tpe match {
        case PolyType(_, tpe) => tpe.depoly
        case _ => tpe
      }
    }
    private def alias(in: Tree): String = in match {
      case in: NameTree => in.name.toString
      case This(name) => name.toString
    }
    private def isBackquoted(in: Tree): Boolean = in match {
      // TODO: infer isBackquoted
      // TODO: iirc according to Denys, even BackquotedIdentifierAttachment sometimes lies
      case in: Ident => in.isBackquoted
      case _ => false
    }

    implicit class RichSymbol(gsym: Symbol) {
      def precvt(pre: Type, in: Tree): p.Name = {
        gsym.rawcvt(in) // .withScratchpad(pre)
      }
      def rawcvt(in: Tree): p.Name = {
        if (gsym.isTerm) p.Term.Name(alias(in))(isBackquoted = isBackquoted(in)) // .withScratchpad(gsym)
        else if (gsym.isType) p.Type.Name(alias(in))(isBackquoted = isBackquoted(in)) // .withScratchpad(gsym)
        else unreachable
      }
      def eithercvt(in: Tree): p.Name.Either = {
        require(gsym != NoSymbol)
        val gsyms = {
          if (gsym.isModuleClass) List(gsym.typeSignature.termSymbol, NoSymbol)
          else List(NoSymbol, gsym.asClass)
        }
        p.Name.Either(alias(in))(isBackquoted(in)) // .withScratchpad(gsyms)
      }
    }
    implicit class RichSymbols(gsyms: List[Symbol]) {
      def bothcvt(in: Tree): p.Name.Both = {
        val List(gterm, gtype) = gsyms
        require(gterm != NoSymbol || gtype != NoSymbol)
        require(gterm != NoSymbol || gterm.isTerm)
        require(gtype != NoSymbol || gtype.isType)
        p.Name.Both(alias(in))(isBackquoted(in)) // .withScratchpad(gsyms)
      }
    }
    implicit class RichTermSymbol(gsym: TermSymbol) {
      def precvt(pre: Type, in: Tree): p.Term.Name = (gsym: Symbol).precvt(pre, in).asInstanceOf[p.Term.Name]
      def rawcvt(in: Tree, allowNoSymbol: Boolean = false): p.Term.Name = (gsym: Symbol).rawcvt(in).asInstanceOf[p.Term.Name]
    }
    implicit class RichTypeSymbol(gsym: TypeSymbol) {
      def precvt(pre: Type, in: Tree): p.Type.Name = (gsym: Symbol).precvt(pre, in).asInstanceOf[p.Type.Name]
      def rawcvt(in: Tree): p.Type.Name = (gsym: Symbol).rawcvt(in).asInstanceOf[p.Type.Name]
    }
    object ValSymbol { def unapply(gsym: Symbol): Option[TermSymbol] = if (gsym.isTerm && !gsym.isMethod && !gsym.isModule && !gsym.asTerm.isVar) Some(gsym.asTerm) else None }
    object VarSymbol { def unapply(gsym: Symbol): Option[TermSymbol] = if (gsym.isTerm && !gsym.isMethod && !gsym.isModule && gsym.asTerm.isVar) Some(gsym.asTerm) else None }
    object DefSymbol { def unapply(gsym: Symbol): Option[TermSymbol] = if (gsym.isMethod) Some(gsym.asTerm) else None }
    object AbstractTypeSymbol { def unapply(gsym: Symbol): Option[TypeSymbol] = if (gsym.isType && gsym.asType.isAbstractType) Some(gsym.asType) else None }
    object AliasTypeSymbol { def unapply(gsym: Symbol): Option[TypeSymbol] = if (gsym.isType && gsym.asType.isAliasType) Some(gsym.asType) else None }
//    private def paccessqual(gsym: Symbol): Option[p.Mod.AccessQualifier] = {
//      if (gsym.isPrivateThis || gsym.isProtectedThis) Some(This(tpnme.EMPTY).setSymbol(gsym.privateWithin).cvt)
//      else if (gsym.privateWithin == NoSymbol || gsym.privateWithin == null) None
//      else Some(gsym.privateWithin.eithercvt(Ident(gsym.privateWithin))) // TODO: this loses information is gsym.privateWithin was brought into scope with a renaming import
//    }

//      case SingleType(pre, sym) =>
//        // TODO: this loses information if sym was brought into scope with a renaming import
//        require(sym.isTerm)
//        val ref = (pre match {
//          case NoPrefix =>
//            sym.asTerm.rawcvt(Ident(sym))
//          case _: SingletonType =>
//            val p.Type.Singleton(preref) = pre.cvt
//            p.Term.Select(preref, sym.asTerm.precvt(pre, Ident(sym)))
//          case _ =>
//            unreachable
//        }).withScratchpad(in)
//        p.Type.Singleton(ref)
//      case ConstantType(const) =>
//        const.cvt
//      case TypeRef(pre, sym, args) =>
//        // TODO: this loses information if sym was brought into scope with a renaming import
//        require(sym.isType)
//        val ref = (pre match {
//          case NoPrefix =>
//            sym.asType.rawcvt(Ident(sym))
//          case _: SingletonType =>
//            val p.Type.Singleton(preref) = pre.cvt
//            p.Type.Select(preref, sym.asType.precvt(pre, Ident(sym)))
//          case _ =>
//            p.Type.Project(pre.cvt, sym.asType.precvt(pre, Ident(sym)))
//        }).withScratchpad(in)
//        // TODO: infer whether that was Apply, Function or Tuple
//        // TODO: discern Apply and ApplyInfix
//        if (args.isEmpty) ref
//        else p.Type.Apply(ref, args.cvt)
//      case RefinedType(parents, decls) =>
//        val pstmts: Seq[p.Stmt.Refine] = decls.sorted.toList.map({
//          case ValSymbol(sym) => p.Decl.Val(pmods(sym), List(sym.rawcvt(ValDef(sym))), sym.info.depoly.cvt)
//          case VarSymbol(sym) if !sym.isMethod && !sym.isModule && sym.isMutable => p.Decl.Var(pmods(sym), List(sym.rawcvt(ValDef(sym))), sym.info.depoly.cvt)
//          // TODO: infer the difference between Defs and Procedures
//          case DefSymbol(sym) => p.Decl.Def(pmods(sym), sym.rawcvt(DefDef(sym, EmptyTree)), ptparams(sym.typeParams), pexplicitss(sym), pimplicits(sym), sym.info.finalResultType.cvt)
//          case AbstractTypeSymbol(sym) => p.Decl.Type(pmods(sym), sym.rawcvt(TypeDef(sym)), ptparams(sym.typeParams), sym.info.depoly.cvt)
//          case AliasTypeSymbol(sym) => p.Defn.Type(pmods(sym), sym.rawcvt(TypeDef(sym, TypeTree(sym.info))), ptparams(sym.typeParams), sym.info.depoly.cvt)
//        })
//        p.Type.Compound(parents.cvt, pstmts)(hasExplicitRefinement = true) // TODO: infer hasExplicitRefinement
//      // NOTE: these types have no equivalent in Palladium
//      // TODO: not sure whether we can actually do this
//      // e. what's the tpe of List.apply in `List.apply(2)`
//      // maybe p.Type.Singleton(<p.Term.Name/p.Type.Name with the correct signature attached>)?
//      // case ClassInfoType(_, _, _) =>
//      // case MethodType(_, _) =>
//      // case NullaryMethodType(_) =>
//      // case PolyType(_, _) =>
//      // case WildcardType =>
//      // case BoundedWildcardType =>
//      case ExistentialType(quantified, underlying) =>
//        // TODO: infer type placeholders where they were specified explicitly
//        val pstmts: Seq[p.Stmt.Existential] = quantified.map({
//          case ValSymbol(sym) => p.Decl.Val(pmods(sym), List(sym.rawcvt(ValDef(sym))), sym.info.depoly.cvt)
//          case AbstractTypeSymbol(sym) => p.Decl.Type(pmods(sym), sym.rawcvt(TypeDef(sym)), ptparams(sym.typeParams), sym.info.depoly.cvt)
//        })
//        p.Type.Existential(underlyincvt, pstmts)
//      case AnnotatedType(anns, underlying) =>
//        p.Type.Annotate(underlyincvt, panns(anns))
//      case TypeBounds(lo, hi) =>
//        // TODO: infer which of the bounds were specified explicitly by the user
//        p.Aux.TypeBounds(Some(lo.cvt), Some(hi.cvt))
//    }

  implicit object ConvertTypeRef extends Converter[TypeRef, p.Type] {
    def convert(tref: TypeRef): p.Type = tref match {
      case TypeRef(pre, sym, args) =>
        require(sym.isType)
        val ref = pre match {
          case NoPrefix =>
            p.Type.Name(sym.name.toString)(isBackquoted = false)
          case _: SingletonType =>
            val p.Type.Singleton(preref) = convertType(pre)
            p.Type.Select(preref, conv[Symbol, p.Type.Name](sym))
          case _ =>
            p.Type.Project(conv[Type, p.Type](pre), conv[Symbol, p.Type.Name](sym))
        }
        if (args.isEmpty) ref
        else p.Type.Apply(ref, args.map(conv[Type, p.Type](_)))
    }
  }

  implicit object ConvertThisType extends Converter[ThisType, p.Type.Singleton] {
    def convert(tref: ThisType): p.Type.Singleton = tref match {
      case ThisType(sym) =>
        // TODO: infer whether thistpe originally corresponded to Some or None
//        p.Type.Singleton(conv[This, p.Term.Ref](This(sym.name.toTypeName)))
        ???
    }
  }

//      case SuperType(thistpe, supertpe) =>
  implicit object ConvertSuperType extends Converter[SuperType, p.Type.Singleton] {
    def convert(tref: SuperType): p.Type.Singleton = tref match {
      case SuperType(thistpe, supertpe) =>
        val p.Type.Singleton(p.Term.This(pthis)) = conv[Type, p.Type](thistpe)
        require(supertpe.typeSymbol.isType)
        val supersym = supertpe.typeSymbol.asType
        // TODO: infer whether supertpe originally corresponded to Some or None
        p.Type.Singleton(p.Aux.Super(pthis, Some(conv[Symbol, p.Type.Name](supersym))))
    }
  }


  //  protected def convertSymbol[T](sym: Symbol)(): p.Term.Ref = ???

  protected def convertType(tpe: Type): p.Type = tpe match {
    case NoType => ???
    case NoPrefix => ???
  }

  protected def convertArgs(args: List[Tree]): List[p.Arg] = ???

  // shamelessly stolen from:
  // https://github.com/xeno-by/scalahost/blob/0e01656a1b42a009e7ff5136423b3b78859928b3/plugin/src/main/scala/scalahost/HostContext.scala#L289
  protected def mods(sym: Symbol): Seq[p.Mod] = {
    val pmods = scala.collection.mutable.ListBuffer[p.Mod]()
    for (annot <- sym.annotations) {
      // TODO: recover names and defaults (https://github.com/scala/scala/pull/3753/files#diff-269d2d5528eed96b476aded2ea039444R617)
      // TODO: recover multiple argument lists (?!)
      // TODO: infer the difference between @foo and @foo()
      // TODO: support classfile annotation args
      pmods += p.Mod.Annot(convertType(annot.tpe), List(convertArgs(annot.tree.children.tail)))
    }
    if (sym.isPrivate) pmods += p.Mod.Private(if (sym.isPrivateThis) Some(p.Term.This(None)) else None)
    if (sym.isProtected) pmods += p.Mod.Protected(if (sym.isProtectedThis) Some(p.Term.This(None)) else None)
    if (sym.isImplicit) pmods += p.Mod.Implicit()
    if (sym.isFinal) pmods += p.Mod.Final()
    if (sym.isClass) {
      val sym0 = sym.asClass
      if (sym0.isSealed) pmods += p.Mod.Sealed()
      if (sym0.isCaseClass) pmods += p.Mod.Case()
    }
    if (sym.asInstanceOf[scala.reflect.internal.SymbolTable#Symbol].isOverride) pmods += p.Mod.Override()
    if (sym.isAbstract) pmods += p.Mod.Abstract()
    if (sym.isAbstractOverride) { pmods += p.Mod.Abstract(); pmods += p.Mod.Override() }
    if (sym.isType) {
      val sym0 = sym.asType
      if (sym0.isCovariant) pmods += p.Mod.Covariant()
      if (sym0.isContravariant) pmods += p.Mod.Contravariant()
    }
    if (sym.isTerm && sym.asTerm.isLazy) pmods += p.Mod.Lazy()
    if (sym.isMacro) pmods += p.Mod.Macro()
    if (sym.isTerm && sym.asTerm.isVal) pmods += p.Mod.ValParam()
    if (sym.isTerm && sym.asTerm.isVar) pmods += p.Mod.VarParam()
    if (sym.isPackageClass) pmods += p.Mod.Package()
    pmods.toList
  }
}

class RuntimeHost(val classloader: ClassLoader, args: Array[String]) extends RuntimeHostCrappyPart {

  def syntaxProfile: p.SyntaxProfile = ???
  def semanticProfile: SemanticProfile = ???

  def members(scope: p.Scope): Seq[p.Member] = Nil
  def members(scope: p.Scope, name: p.Name): Seq[p.Member] = Nil
  def ctors(scope: p.Scope): Seq[p.Ctor] = Nil

  def owner(term: p.Tree): p.Scope = ???

  def defn(term: p.Term.Ref): Seq[p.Member.Term] = term match {
    case p.Term.Name(name) =>
      val module = mirror.staticModule(name)
      ??? // p.Defn.Object()
    case p.Term.Select(qual, name) => ???
  }
  def defn(tpe: p.Type.Ref): p.Member = ???
  def overrides(member: p.Member.Term): Seq[p.Member.Term] = ???
  def overrides(member: p.Member.Type): Seq[p.Member.Type] = ???

  def <:<(tpe1: p.Type, tpe2: p.Type): Boolean = ???
  def weak_<:<(tpe1: p.Type, tpe2: p.Type): Boolean = ???
  def supertypes(tpe: p.Type): Seq[p.Type] = ???
  def linearization(tpes: Seq[p.Type]): Seq[p.Type] = ???
  def subclasses(tpe: p.Type): Seq[p.Member.Template] = ???
  def self(tpe: p.Type): p.Aux.Self = ???
  def lub(tpes: Seq[p.Type]): p.Type = ???
  def glb(tpes: Seq[p.Type]): p.Type = ???
  def widen(tpe: p.Type): p.Type = ???
  def dealias(tpe: p.Type): p.Type = ???
  def erasure(tpe: p.Type): p.Type = ???

  def attrs(tree: p.Tree): Seq[Attribute] = ???
}