package example

import squid._
import utils._
import ir._
import utils.Debug.show

/**
  * Created by lptk on 15/09/16.
  */
trait TupleVarOptim extends SimpleRuleBasedTransformer { self =>
  import base.Predef._
  import self.base.InspectableIROps
  import self.base.IntermediateIROps
  
  import squid.lib.Var
  
  rewrite {
    
    case ir"val $tup = Var($init: ($ta, $tb)); $body: $t" =>
      //println((ta,tb)) // makes the rwr not compile -- FIXME
      //println(ta->tb) // makes the rwr not compile
      
      //show(body)
      
      val a = ir"$$a: Var[$ta]"
      val b = ir"$$b: Var[$tb]"
      
      val newBody = body rewrite {
        case ir"($$tup !)._1" => ir"$a !"
        case ir"($$tup !)._2" => ir"$b !"
        case ir"$$tup := ($va: $$ta, $vb: $$tb)" => ir"$a := $va; $b := $vb"
        case ir"$$tup := $vab" => ir"$a := $vab._1; $b := $vab._2"
        case ir"$$tup := null" =>
          ir"$a := null; $b := null" // FIXME proper null boolean encoding
        case ir"$$tup !" => ir"($a.!, $b.!)"
      }
      
      //show(newBody)
      
      val newwBody2 = newBody subs 'tup -> ({
        //println(s"tup is still used! in: ${newBody rep}")
        throw RewriteAbort(s"tup is still used! in: $newBody")} : IR[tup.Typ,{}])
      
      
      //val res = ir" val init = $init; val a = Var(init._1);  val b = Var(init._2);  $newwBody2 "
      // TODO use proper default value (not `null` for value types)
      val res = if (init =~= ir"null") ir" val a = Var[$ta](null);  val b = Var[$tb](null);  $newwBody2 "
      else ir" val init = $init; val a = Var(init._1);  val b = Var(init._2);  $newwBody2 "
      
      //show(res)
      res
      
      
    case ir"val $tup = ($a: $ta, $b: $tb); $body: $t" => // assume ANF, so that a/b are trivial
      
      val newBody = body rewrite {
        case ir"$$tup._1" => ir"$a"
        case ir"$$tup._2" => ir"$b"
      }
      //val newwBody2 = newBody subs 'tup -> ir"($a,$b)"  // can't do that as otherwise the transformer would have no fixed point
      val newwBody2 = newBody subs 'tup -> ({throw RewriteAbort()} : IR[tup.Typ,{}])
      newwBody2
    
  }
  
}


object TupleVarOptimTests extends App {
  object DSL extends SimpleAST
  import DSL.Predef._
  import DSL.Quasicodes._
  
  object Optim extends DSL.SelfTransformer with TupleVarOptim with TopDownTransformer
  
  var pgrm = ir{
    var t = (readInt, readInt)
    if (t._1 > t._2) t = (t._2, t._1)
    t._1 to t._2
  }
  
  show(pgrm)
  
  pgrm = pgrm transformWith Optim
  show(pgrm)
  
  //show(pgrm.run)
  
  
}


object TupleVarOptimTestsANF extends App {
  object DSL extends SimpleANF
  import DSL.Predef._
  import DSL.Quasicodes._
  
  object Optim extends DSL.SelfTransformer with TupleVarOptim with TopDownTransformer
  
  var pgrm = ir{
    var t = (readInt, readInt)
    if (t._1 > t._2) t = (t._2, t._1)
    t._1 to t._2
  }
  
  //show(pgrm rep)
  show(pgrm)
  
  pgrm = pgrm transformWith Optim
  
  //show(pgrm rep)
  show(pgrm)
  
  //show(pgrm.run)
  
  
}



