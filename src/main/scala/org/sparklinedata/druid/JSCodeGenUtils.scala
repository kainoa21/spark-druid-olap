/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sparklinedata.druid

import org.apache.spark.Logging
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.util.TypeUtils
import org.apache.spark.sql.types._
import org.sparklinedata.druid.metadata._
import scala.collection.mutable

case class JSCode(jsFn: String, inParams: List[String])

object JSCodeGenUtils extends Logging {
  def genJSFn(dqb: DruidQueryBuilder,
              e: Expression, mulInParamsAllowed: Boolean): Option[JSCode] = {
    val cGen = new JSExprCodeGen(dqb, mulInParamsAllowed)
    for (fnb <- cGen.genExprCode(e) if cGen.inParams.nonEmpty;
         rStmt <- cGen.genCastExprCode(fnb.getCodeRef, e.dataType, StringType)) yield {
      val fnParams: List[String] = cGen.inParams.toList
      JSCode(
        s"""function (${fnParams.mkString(", ")}) {
            ${fnb.linesSoFar}

            return(${rStmt});
            }""".stripMargin,
        fnParams)
    }
  }
}


private case class JSExprCode(val fnVar: Option[String], val linesSoFar: String,
                              val curLine: String, val fnDT: DataType) {
  def this(curLine: String, fnDT: DataType) = this(None, "", curLine, fnDT)

  def getCodeRef: String = {
    if (fnVar.isDefined) fnVar.get else curLine
  }
}

private class JSExprCodeGen(dqb: DruidQueryBuilder, mulInParamsAllowed: Boolean) {
  var inParams: mutable.HashSet[String] = mutable.HashSet()
  var toDateVar: mutable.HashSet[String] = mutable.HashSet()

  private var uid: Int = 0

  def makeUniqueVarName: String = {
    uid += 1
    "v" + uid
  }

  def genExprCode(e: Expression): Option[JSExprCode] = {
    e match {
      case AttributeReference(nm, dT, _, _) =>
        for (dD <- dqb.druidColumn(nm)
             if (dD.isInstanceOf[DruidDimension] || dD.isInstanceOf[DruidTimeDimension]) &&
               validInParams(nm)) yield {
          val v = if (dD.isInstanceOf[DruidTimeDimension]) dqb.druidColumn(nm).get.name else nm
          new JSExprCode(v, e.dataType)
        }
      case Literal(value, dataType) => {
        var valStr: Option[String] = None
        dataType match {
          case IntegerType | LongType | ShortType | DoubleType | FloatType  =>
            valStr = Some(value.toString)
          case StringType => valStr = Some(s""""${e.toString}"""")
          case NullType => valStr = Some("null")
          case _ => valStr = genCastExprCode(value.toString, StringType, dataType)
        }
        if (valStr.nonEmpty) Some(new JSExprCode(valStr.get, dataType)) else None
      }
      case Cast(s, dt) => Some(genCastExprCode(s, dt)).flatten

      case Concat(eLst) =>
        // TODO: If second gencode failed then this should return none
        var cJSFn: Option[JSExprCode] = None
        for (ex <- eLst; exCode <- genExprCode(ex)) {
          if (cJSFn.isEmpty) {
            cJSFn = Some(exCode)
          } else {
            cJSFn = Some(JSExprCode(None,
              s"${cJSFn.get.linesSoFar} ${exCode.linesSoFar} ",
              s"((${cJSFn.get.getCodeRef}).concat(${exCode.getCodeRef}))",
              StringType))
          }
        }
        cJSFn
      case Upper(u) =>
        for (exCode <- genExprCode(u) if u.dataType.isInstanceOf[StringType]) yield
          JSExprCode(None,
            exCode.linesSoFar, s"(${exCode.getCodeRef}).toUpperCase()", StringType)
      case Lower(l) =>
        for (exCode <- genExprCode(l) if l.dataType.isInstanceOf[StringType]) yield
          JSExprCode(None,
            exCode.linesSoFar, s"(${exCode.getCodeRef}).toLowerCase()", StringType)
      case Substring(str, pos, len) =>
        for (strcode <- genExprCode(str); posL <- genExprCode(pos)
             if pos.isInstanceOf[Literal]; lenL <- genExprCode(len)
             if len.isInstanceOf[Literal]) yield
          JSExprCode(None, s"${strcode.linesSoFar} ${posL.linesSoFar}",
            s"(${strcode.getCodeRef}).substring(${posL.getCodeRef}, ${lenL.getCodeRef})",
            StringType)
      case ToDate(de) =>
        for (fn <- genExprCode(de); dtFn <- getJSDateExprCode(fn.fnDT, fn.getCodeRef)) yield {
          val v1 = makeUniqueVarName
          val v2 = makeUniqueVarName
          toDateVar += v2
          JSExprCode(Some(v2),
            fn.linesSoFar +
              s"""| var $v1 = $dtFn;
                  | var $v2 = new Date($v1.getFullYear(), $v1.getMonth(), $v1.getDate());
            """.stripMargin, "", DateType)
        }
      case DateAdd(sd, d) => getDateArithmetic(sd, d, "+")
      case DateSub(sd, d) => getDateArithmetic(sd, d, "-")
      case Hour(h) => getDateFieldsCode(h, "getHours()", IntegerType)
      case Minute(m) => getDateFieldsCode(m, "getMinutes()", IntegerType)
      case Second(s) => getDateFieldsCode(s, "getSeconds()", IntegerType)
      case Year(y) => getDateFieldsCode(y, "getFullYear()", IntegerType)
      case Month(mo) => getDateFieldsCode(mo, "getMonth()", IntegerType)

      case Add(l, r) => Some(genBArithmeticExprCode(l, r, e, "+")).flatten
      case Subtract(l, r) => Some(genBArithmeticExprCode(l, r, e, "-")).flatten
      case Multiply(l, r) => Some(genBArithmeticExprCode(l, r, e, "*")).flatten
      case Divide(l, r) => Some(genBArithmeticExprCode(l, r, e, "/")).flatten
      case Remainder(l, r) => Some(genBArithmeticExprCode(l, r, e, "%")).flatten
      case _ => None.flatten
    }
  }

  private def genBArithmeticExprCode(l: Expression, r: Expression, ce: Expression,
                                     op: String): Option[JSExprCode] = {
    for (lc <- genExprCode(l); rc <- genExprCode(r)) yield
      JSExprCode(None,
        s"${lc.linesSoFar} ${rc.linesSoFar} ",
        s"((${lc.getCodeRef}) $op (${rc.getCodeRef}))", ce.dataType)
  }

  private def genCastExprCode(e: Expression, dt: DataType): Option[JSExprCode] = {
    for (fn <- genExprCode(e);
         cs <- genCastExprCode(fn.getCodeRef, fn.fnDT, dt,
           fn.fnVar.nonEmpty)) yield
      JSExprCode(None, fn.linesSoFar, cs, dt)
  }

  def genCastExprCode(inExprStr: String, inDT: DataType, outDT: DataType,
                      inExprStrVName: Boolean = false): Option[String] = {
    if (TypeUtils.checkForSameTypeInputExpr(List(inDT, outDT), "JSCastFN arg checks").isFailure) {
      outDT match {
        case FloatType | DoubleType =>
          inDT match {
            case StringType => Some(s"parseFloat($inExprStr)")
            case FloatType | DoubleType | IntegerType | LongType | ShortType =>
              Some(s"Number($inExprStr)")
            case _ => None
          }
        case IntegerType | LongType | ShortType =>
          inDT match {
            case StringType => Some(s"parseInt($inExprStr)")
            case FloatType | DoubleType | IntegerType | LongType | ShortType =>
              Some(s"Number($inExprStr)")
            case _ => None
          }
        case BooleanType => Some(s"Boolean(${inExprStr})")
        case StringType =>
          inDT match {
            case FloatType | DoubleType | IntegerType |
                 LongType | ShortType | TimestampType => Some(s"($inExprStr).toString()")
            case DateType => {
              // This is a hack. TO_DATE is supposed to get only date pieces (except time)
              // However Date.toISOString adds 00:00:00. Here we remove it
              val v = if (inExprStrVName && toDateVar.contains(inExprStr)) ".slice(10)" else ""
              Some(s"($inExprStr).toISOString()${v}")
            }
            case _ => None
          }
        case TimestampType =>
          inDT match {
            case DateType => Some(s"(($inExprStr).getTime())")
            case StringType | IntegerType | LongType | ShortType =>
              Some(s"((new Date($inExprStr)).getTime())")
            case _ => None
          }
        case DateType =>
          inDT match {
            case StringType => Some(s"(new Date($inExprStr))")
            case TimestampType | IntegerType | LongType =>
              Some(s"(new Date(parseFloat($inExprStr)))")
            case _ => None
          }
        case _ => None
      }
    } else {
      Some(inExprStr)
    }
  }

  private def getJSDateExprCode(exDT: DataType, exJSStr: String): Option[String] = {
    exDT match {
      case DateType => Some(exJSStr)
      case StringType | TimestampType | IntegerType | LongType | ShortType =>
        Some(s"(new Date($exJSStr))")
      case _ => None
    }
  }

  private def getDateFieldsCode(e: Expression, f: String, outDT: DataType): Option[JSExprCode] = {
    for (sdE <- genExprCode(e)) yield {
      JSExprCode(None, sdE.linesSoFar,
        s"(${getJSDateExprCode(e.dataType, sdE.getCodeRef)})." + f, outDT)
    }
  }

  private def validInParams(inParam: String): Boolean = {
    if (!mulInParamsAllowed && ((inParams += inParam).size > 1)) false else true
  }

  private def getDateArithmetic(sd: Expression, d: Expression, op: String): Option[JSExprCode] = {
    for (sdE <- genExprCode(sd); dE <- genExprCode(d)) yield {
      val v1 = makeUniqueVarName
      JSExprCode(Some(v1),
        s"""
           |${sdE.linesSoFar} ${dE.linesSoFar}
           |var ${v1} = new Date(${sdE.getCodeRef});
           |${v1}.setDate(${v1}.getDate() ${op} (${dE.getCodeRef}));
      """.stripMargin, "", DateType)
    }
  }
}