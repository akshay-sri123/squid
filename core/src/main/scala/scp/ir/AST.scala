package scp
package ir

import utils.MacroUtils.StringOps

import scala.collection.mutable
import lang._

import scala.reflect.runtime.{universe => ru}

object AST {
  import scala.tools.reflect.ToolBox
  lazy val toolBox = ru.runtimeMirror(getClass.getClassLoader).mkToolBox() // Q: necessary 'lazy'? (objects are already lazy)
}

/** Main language trait, encoding second order lambda calculus with records, let-bindings and ADTs */
trait AST extends Base         with ScalaTyping { // TODO rm dep to ScalaTyping
  import AST._
  
  
  object ConstQ extends ConstAPI {
    override def unapply[A: ru.TypeTag, S](x: Q[A, S]): Option[A] = x.rep match {
      case Const(v) if ru.typeOf[A] <:< x.rep.typ.asInstanceOf[ScalaTyping#TypeRep].typ => Some(v.asInstanceOf[A])
      case _ => None
    }
  }
  
  
  
  protected def runRep(r: Rep): Any = {
    val t = toTree(r)
    println("Compiling tree: "+t)
    toolBox.eval(t)
  }
  protected def toTree(r: Rep): ru.Tree = { // TODO remember (lazy val in Rep)
    import ru._
    r match {
      case Const(v) => ru.Literal(ru.Constant(v))
      case Var(n) => q"${TermName(n)}"
      case App(f, a) => q"(${toTree(f)})(${toTree(a)})"
      case Ascribe(v) => toTree(v)
      case a: Abs =>
        val tag = a.ptyp.asInstanceOf[ScalaTyping#TypeRep].tag // TODO adapt API
        q"(${TermName(a.pname)}: $tag) => ${toTree(a.body)}"
      case DSLMethodApp(self, mtd, targs, argss, tp) =>
        val self2 = self map toTree getOrElse {
          val access = mtd.owner.fullName.splitSane('.').foldLeft(q"_root_": Tree) {
            case (acc, n) => q"$acc.${TermName(n)}"
          }
          //println(access)
          //q"${mtd.owner}"
          access
        }
        val argss2 = argss map (_ map toTree)
        q"$self2 ${mtd.name} ...$argss2"
      case HoleExtract(n) => throw new Exception(s"Trying to build an open term! Variable '$n' is free.")
    }
  }
  
  
  sealed trait Rep {
    val typ: TypeRep
    
    def subs(xs: (String, Rep)*): Rep = subs(xs.toMap)
    def subs(xs: Map[String, Rep]): Rep =
      if (xs isEmpty) this else transformPartial(this) { case r @ HoleExtract(n) => xs getOrElse (n, r) }
    
    /** TODO check right type extrusion...
      * 
      * problem: what if 2 vars with same name?
      *   (x:Int) => (x:String) => ...
      * 
      * */
    def extract(t: Rep): Option[Extract] = { // TODO check types
      //println(s"$this << $t")
      
      //val reps = mutable.Buffer[(String, SomeRep)]
      //transform(this){
      //  case HoleExtract(name)
      //  case r => r
      //}
      val r: Option[Extract] = (this, t) match {
        case (HoleExtract(name), _) => // Note: will also extract holes... is it ok?
          //Some(Map(name -> t) -> Map()) 
          // TODO replace extruded symbols
          //???
          //val dom = binds.values.toSet
          //val revBinds = binds.map(_.swap)
          //val extruded = transform(t){
          //  //case s: Symbol if dom(s) => HoleExtract(s.name)(TypeEv(s.tp)) // FIXME: that's not the right name!
          //  case s: Symbol if revBinds isDefinedAt s => HoleExtract(revBinds(s).name)(TypeEv(s.tp)) // FIXME: that's not the right name!
          //  case t => t
          //}
          //val extruded = t subs (revBinds mapValues(_.name))
          
          //val extruded = t subs (binds.mapValues(_.name).map(_.swap))
          //Some(Map(name -> extruded) -> Map())
          Some(Map(name -> t) -> Map())
          
        case (Var(n1), HoleExtract(n2)) if n1 == n2 => Some(EmptyExtract) // FIXME safe?
        case (_, HoleExtract(_)) => None
          
        case (v1: Var, v2: Var) =>
          if (v1.name == v2.name) Some(EmptyExtract) else None // TODO check they have the same type?!

        case (_, a @ Ascribe(v)) => extract(v) // TODO test type?
          
        //case (Const(v1), Const(v2)) if v1 == v2 => Some(EmptyExtract)
        case (Const(v1), Const(v2)) => if (v1 == v2) Some(EmptyExtract) else None
        case (App(f1,a1), App(f2,a2)) => for (e1 <- f1 extract f2; e2 <- a1 extract a2; m <- merge(e1, e2)) yield m
        //case (Abs(v1,b1), Abs(v2,b2)) => b1.extract(b2)(binds + (v1 -> v2))
        case (a1: Abs, a2: Abs) =>
          //a1.body.extract(a2.fun(a1.param))
          a1.body.extract(a2.fun(a1.param.toHole))
        case (DSLMethodApp(self1,mtd1,targs1,args1,tp1), DSLMethodApp(self2,mtd2,targs2,args2,tp2)) 
          if mtd1 == mtd2
        =>
          //println(s"$self1")
          assert(args1.size == args2.size)
          assert(targs1.size == targs2.size)
          
          //if (tp1 <:< tp2) // no, if tp1 contains type holes it won't be accepted!
          
          for {
            //s <- (self1, self2) match { //for (s1 <- self1; s2 <- self2) yield s1
            //  case (Some(s1), Some(s2)) => Some(s1 extract s2)
            //  case (None, None) => Some(None)
            //  case _ => None
            //}
            s <- (self1, self2) match {
              case (Some(s1), Some(s2)) => s1 extract s2
              case (None, None) => Some(EmptyExtract)
              case _ => None
            }
            // TODOne check targs & tp
            t <- {
              val targs = (targs1 zip targs2) map { case (a,b) => a extract b }
              (Some(EmptyExtract) :: targs).reduce[Option[Extract]] { // `reduce` on non-empty; cf. `Some(EmptyExtract)`
                case (acc, a) => for (acc <- acc; a <- a; m <- merge(acc, a)) yield m }
            }
            
            //ao <- (args1 zip args2) flatMap { case (as,bs) => assert(as.size==bs.size); (as zip bs) map { case (a,b) =>  a extract b } }
            //a <- ao
            a <- {
              val args = (args1 zip args2) flatMap {
                case (as,bs) => assert(as.size==bs.size); (as zip bs) map { case (a,b) => a extract b } }
              //val fargs = args.flatten
              //if (fargs.size == args.size) fargs else None
              //args.foldLeft(EmptyExtract){case(acc,a) => merge(acc,a) getOrElse (return None)}
              (Some(EmptyExtract) :: args).reduce[Option[Extract]] { // `reduce` on non-empty; cf. `Some(EmptyExtract)`
                case (acc, a) => for (acc <- acc; a <- a; m <- merge(acc, a)) yield m }
            }
            m0 <- merge(s, t)
            m1 <- merge(m0, a)
          } yield m1
        case _ => None
      }
      //println(s">> $r")
      r
    }
    
    
    /* // FIXME creates stack overflow in tests
    override def equals(that: Any) = that match { // TODO override hashCode...
      case that: Rep =>
        //if (super.equals(that)) true else repEq(this, that)
        repEq(this, that)
      case _ => false
    }
    */
  }
  //sealed trait TypeRep
  
  def extract(xtor: Rep, t: Rep): Option[Extract] = xtor.extract(t)//(Map())
  
  
  def const[A: TypeEv](value: A): Rep = Const(value)
  //def abs[A: TypeEv, B: TypeEv](name: String, fun: Rep => Rep): Rep = {
  //  val v = Var(name)(typeRepOf[A])
  //  Abs(v, fun(v))
  //}
  def abs[A: TypeEv, B: TypeEv](name: String, fun: Rep => Rep): Rep = Abs(name, typeRepOf[A], fun)
  def app[A: TypeEv, B: TypeEv](fun: Rep, arg: Rep): Rep = App[A,B](fun, arg)
  
  override def ascribe[A: TypeEv](value: Rep): Rep = Ascribe[A](value)
  
  //def dslMethodApp[A,S](self: Option[SomeRep], mtd: DSLDef, targs: List[SomeTypeRep], args: List[List[SomeRep]], tp: TypeRep[A], run: Any): Rep[A,S]
  def dslMethodApp(self: Option[Rep], mtd: DSLSymbol, targs: List[TypeRep], argss: List[List[Rep]], tp: TypeRep): Rep =
    DSLMethodApp(self, mtd, targs, argss, tp)
  
  def hole[A: TypeEv](name: String) = HoleExtract[A](name)
  //def hole[A: TypeEv](name: String) = Var(name)(typeRepOf[A])
  
  // TODO rename to Hole
  /**
    * In xtion, represents an extraction hole
    * In ction, represents a free variable
    */
  case class HoleExtract[+A: TypeEv](name: String) extends Rep {
    val typ = typeEv[A].rep
    override def toString = s"($$$$$name: $typ)"
  
    //override def equals(that: Any): Boolean = that match {
    //  case HoleExtract(n) => n == name // TODO check type
    //  case _ => false
    //}
  }
  
  
  case class Var(val name: String)(val typ: TypeRep) extends Rep {
    def toHole = HoleExtract(name)(TypeEv(typ))
    override def toString = s"($name: $typ)"
  }
  
  case class Const[A: TypeEv](value: A) extends Rep {
    val typ = typeEv[A].rep
    override def toString = s"$value"
  }
  //case class Abs(param: Var, body: Rep) extends Rep {
  //  def inline(arg: Rep): Rep = ??? //body withSymbol (param -> arg)
  //case class Abs[A:TypeEv,B](name: String, fun: Rep => Rep) extends Rep {
  //  val param = Var(name)
  case class Abs(pname: String, ptyp: TypeRep, fun: Rep => Rep) extends Rep {
    val param = Var(pname)(ptyp)
    val body = fun(param)
    
    val typ = body.typ
    
    def inline(arg: Rep): Rep = ??? //body withSymbol (param -> arg)
    override def toString = body match {
      case that: Abs => s"{ $param, ${that.print(false)} }"
      case _ => print(true)
    }
    def print(paren: Boolean) =
    if (paren) s"{ ($param) => $body }" else s"($param) => $body"
  }
  case class App[A,B: TypeEv](fun: Rep, arg: Rep) extends Rep {
    val typ = typeEv[B].rep
    override def toString = s"$fun $arg"
  }
  
  case class Ascribe[A: TypeEv](value: Rep) extends Rep {
    val typ = typeEv[A].rep
    override def toString = s"$value::$typ" //s"($value: $typ)"
    
    override def extract(t: Rep): Option[Extract] = {
      val r0 = value.extract(t) getOrElse (return None)
      (typ extract t.typ) flatMap (m => merge(r0, m))
    }
    
    //override def equals(that: Any) = value == that
  }
  
  case class DSLMethodApp(self: Option[Rep], sym: DSLSymbol, targs: List[TypeRep], argss: List[List[Rep]], typ: TypeRep) extends Rep {
    val mtd = DSLDef(sym.fullName, sym.info.toString, self.isEmpty)
    override def toString = self.fold(mtd.path.last+".")(_.toString+".") + mtd.shortName +
      (targs.mkString("[",",","]")*(targs.size min 1)) +
      (argss map (_ mkString("(",",",")")) mkString)
  }
  
  
  /**
    * Note: may not work well with FVs (represented as holes!)
    * EDIT: actually, it seems to work since I added the 'xs == ys' test!
    * wonTODO: add && a.freeVars == b.freeVars
    */
  //def repEq(a: Rep, b: Rep): Boolean = (a extract b isDefined) && (b extract a isDefined)
  def repEq(a: Rep, b: Rep): Boolean = {
    //val e1 = a extract b
    //lazy val e2 = b extract a
    //if (e1 isDefined && e)
    (a,b) match {
      case (HoleExtract(n1), HoleExtract(n2)) => true
      case (HoleExtract(_), _) => false
      case (_, HoleExtract(_)) => false
      case _ =>
        (a extract b, b extract a) match {
          case (Some(xs), Some(ys)) =>
            xs == ys // Map comparison; not enough because of things like Ascribe... unless we override equals!
          case _ => false
        }
    }
  }
  
  //def typEq(a: TypeRep, b: TypeRep): Boolean = ???
  
  //implicit def funType[A: TypeEv, B: TypeEv]: TypeEv[A => B] = ???
  
  
  
  def transformPartial(r: Rep)(f: PartialFunction[Rep, Rep]): Rep =
    //transform(r){ case r if f isDefinedAt r => f(r) case r => r}
    transform(r)(r => f applyOrElse (r, identity[Rep]))
  
  def transform(r: Rep)(f: Rep => Rep): Rep = {
    //println(s"Traversing $r")
    val tr = (r: Rep) => transform(r)(f)
    val ret = f(r match {
      //case Abs(p, b) => Abs(p, tr(b))
      case a: Abs => Abs(a.pname, a.ptyp, (x: Rep) => tr(a.fun(x)))
      case a: Ascribe[_] => Ascribe(tr(a.value))(TypeEv(a.typ))
      case ap @ App(fun, a) => App(tr(fun), tr(a))(TypeEv(ap.typ))
      case HoleExtract(name) => r //HoleExtract(name)
      case DSLMethodApp(self, mtd, targs, argss, tp) => DSLMethodApp(self map tr, mtd, targs, argss map (_ map tr), tp)
      //case s: Symbol => s
      case v: Var => v
      case Const(_) => r
    })
    //println(s"Traversing $r, getting $ret")
    //println(s"=> $ret")
    ret
  }
  
  def typ(r: Rep): TypeRep = null.asInstanceOf[TypeRep] // TODO
  
  
  
}














