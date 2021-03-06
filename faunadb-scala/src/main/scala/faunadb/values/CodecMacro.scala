package faunadb.values

import scala.reflect.macros.blackbox

class CodecMacro(val c: blackbox.Context) {
  import c.universe._

  private val M = q"_root_.faunadb.values"

  def recordImpl[T: WeakTypeTag]: Tree = {
    val tpe = weakTypeOf[T]

    q"""new $M.RecordCodec[$tpe] {
      def decode(value: $M.Value, path: $M.FieldPath): $M.Result[$tpe] =
        ${getDecodedObject(tpe)}

      def encode(value: $tpe): $M.Value =
        ${getEncodedObject(tpe)}
    }: $M.RecordCodec[$tpe]"""
  }

  private def getEncodedObject(tpe: Type): Tree = {
    val isTuple = tpe.typeSymbol.fullName.startsWith("scala.Tuple")

    if (isTuple) {
      val fields = getFields(tpe) map { field =>
        val variable = q"value.${varName(field).toTermName}"

        if (isOption(field._2)) {
          q"($variable.map(v => v: $M.Value).getOrElse($M.NullV))"
        } else {
          q"($variable: $M.Value)"
        }
      }

      q"$M.ArrayV(..$fields)"
    } else {
      val fields = getFields(tpe) map { field =>
        val variable = q"value.${varName(field).toTermName}"

        if (isOption(field._2)) {
          q"(${varName(field).toString}, ($variable.map(v => v: $M.Value).getOrElse($M.NullV)))"
        } else {
          q"(${varName(field).toString}, ($variable: $M.Value))"
        }
      }

      q"$M.ObjectV(..$fields)"
    }
  }

  private def getDecodedObject(tpe: Type): Tree = {
    val fields = getFields(tpe)

    val isTuple = tpe.typeSymbol.fullName.startsWith("scala.Tuple")

    val fieldsFragments = fields.zipWithIndex map { case (field, idx) =>
      val variable = if (isTuple)
        q"value($idx)"
      else
        q"value(${varName(field).toString})"

      val decoder = q"implicitly[$M.Decoder[${field._2}]]"
      val path = q"path ++ ${varName(field).toString}"

      if (isOption(field._2)) {
        fq"${varName(field)} <- $decoder.decode($variable.getOrElse($M.NullV), $path)"
      } else {
        fq"${varName(field)} <- $decoder.decode($variable, $path)"
      }
    }

    if (fieldsFragments.nonEmpty) {
      q"for (..$fieldsFragments) yield new $tpe(..${fields.map(varName)})"
    } else {
      q"new $tpe"
    }
  }

  def unionImpl[T: WeakTypeTag](tagField: Tree)(variants: Tree*) = {
    val tpe = weakTypeOf[T]
    val tagLit = tagField match {
      case Literal(Constant(str: String)) => str
      case _ => c.abort(c.enclosingPosition, s"tagField `$tagField` is not a literal String.")
    }

    val (tagImpls, codecImpls, vtypes) = getVariantDefs(variants).unzip3
    val tags = tagImpls map { _ => TermName(c.freshName("tagval")) }
    val codecs = codecImpls map { _ => TermName(c.freshName("subcodec")) }

    val tagDefs = tags zip tagImpls map { case (n, t) => q"private val $n = $M.Value($t)" }
    val codecDefs = codecs zip codecImpls map { case (n, c) => q"private val $n = $c" }

    val decodes = tags zip codecs map { case (t, c) => cq"`$t` => $c.decode(value, path)" }
    val encodes = vtypes zip tags zip codecs map { case ((vt, t), c) => cq"v: $vt => ($t, $c.encode(v))" }

    val expectedMsg = {
      val values = tagImpls match {
        case Seq(a) => s"$a"
        case Seq(a, b) => s"$a or $b"
        case init :+ last => s"${init mkString ", "}, or $last"
      }
      s"Union tag: $values"
    }

    q"""new $M.UnionCodec[$tpe] {
      ..$tagDefs
      ..$codecDefs

      def decode(value: $M.Value, path: $M.FieldPath): $M.Result[$tpe] = {
        value($tagLit) flatMap {
          case ..$decodes
          case v => $M.Result.Unexpected(v, $expectedMsg, path ++ $tagLit)
        }
      }

      def encode(value: $tpe): $M.Value = {
        val (tag, obj) = value match {
          case ..$encodes
        }

        obj match {
          case $M.ObjectV(fields) => $M.ObjectV(fields + (($tagLit, tag)))
          case v => throw new RuntimeException(s"Invalid Union variant: $$v must encode as type ObjectV.")
        }
      }
    }: $M.UnionCodec[$tpe]"""
  }

  private def typed(expr: Tree) = c.typecheck(expr, c.TYPEmode, silent = false).tpe

  private def isOption(tpe: Type) =
    tpe.typeConstructor =:= weakTypeOf[Option[_]].typeConstructor

  private def varName(field: (Symbol, Type)): Name =
    field._1.name.decodedName

  private def getFields(tpe: Type): List[(Symbol, Type)] = {
    if (tpe.typeSymbol.isClass && !tpe.typeSymbol.asClass.isCaseClass) {
      c.abort(c.enclosingPosition, s"type `$tpe` is not a case class")
    }

    val params = tpe.decl(termNames.CONSTRUCTOR).asMethod.paramLists.flatten
    val genericType = tpe.typeConstructor.typeParams.map { _.asType.toType }

    params.map { field =>
      val index = genericType.indexOf(field.typeSignature)
      val fieldType = if (index >= 0) tpe.typeArgs(index) else field.typeSignature

      (field, fieldType)
    }
  }

  private def getVariantDefs(defTrees: Seq[Tree]) =
    defTrees map {
      case q"scala.Predef.ArrowAssoc[$_]($t).->[$_]($c)" => (t, c)
      case q"($t, $c)"                                   => (t, c)
      case x => c.abort(c.enclosingPosition, s"Invalid variant definition.")
    } map {
      case (tag, codec) =>
        val ctype = typed(codec).baseType(typed(tq"$M.Codec").typeSymbol)
        if (ctype == NoType) c.abort(c.enclosingPosition, s"$codec is not a $M.Codec")
        (tag, codec, ctype.typeArgs.head)
    }
}
