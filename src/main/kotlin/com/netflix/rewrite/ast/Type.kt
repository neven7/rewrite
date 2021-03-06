/**
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.rewrite.ast

import com.koloboke.collect.map.hash.HashObjObjMaps
import com.koloboke.collect.set.hash.HashObjSet
import com.koloboke.collect.set.hash.HashObjSets
import java.io.Serializable

sealed class Type: Serializable {

    internal fun Type?.deepEquals(t: Type?): Boolean = when(this) {
        null -> t == null
        is Array -> t is Array && this.deepEquals(t)
        is Class -> t is Class && this.deepEquals(t)
        is Cyclic -> this == t
        is GenericTypeVariable -> t is GenericTypeVariable && this.deepEquals(t)
        is Method -> t is Method && this.deepEquals(t)
        is Primitive -> t is Primitive && this == t
        is Var -> t is Var && this.deepEquals(t)
    }

    data class Class private constructor(val fullyQualifiedName: String,
                                         val members: List<Var>,
                                         val supertype: Class?): Type() {

        override fun toString(): String = fullyQualifiedName

        fun className() = fullyQualifiedName.split('.').dropWhile { it[0].isLowerCase() }.joinToString(".")

        fun packageName(): String {
            fun packageNameInternal(fqn: String): String {
                if(!fqn.contains("."))
                    return ""

                val subName = fqn.substringBeforeLast(".")
                return if (subName.substringAfterLast('.').first().isUpperCase()) {
                    packageNameInternal(subName)
                } else {
                    subName
                }
            }

            return packageNameInternal(fullyQualifiedName)
        }

        fun deepEquals(c: Class?): Boolean {
            if(c == null || fullyQualifiedName != c.fullyQualifiedName)
                return false
            val membersEqual = members.size == c.members.size && members.all { m -> c.members.firstOrNull { it.name == m.name }?.deepEquals(m) ?: false }
            val supertypeEqual = supertype.deepEquals(c.supertype)
            return membersEqual && supertypeEqual
        }

        companion object {
            // there shouldn't be too many distinct types represented by the same fully qualified name
            val flyweights = HashObjObjMaps.newMutableMap<String, HashObjSet<Class>>()

            fun build(fullyQualifiedName: String, members: List<Var> = emptyList(), supertype: Class? = null): Type.Class {
                // the variants are the various versions of this fully qualified name, where equality is determined by
                // whether the supertype hierarchy and members through the entire supertype hierarchy are equal
                val test = Class(fullyQualifiedName, members, supertype)

                val variants = flyweights.getOrPut(fullyQualifiedName) {
                    HashObjSets.newMutableSet<Class>(arrayOf(Class(fullyQualifiedName, members, supertype)))
                }

                return variants.find { it.deepEquals(test) } ?: {
                    variants.add(test)
                    test
                }()
            }
        }
    }

    data class Cyclic(val fullyQualifiedName: String) : Type()

    data class Method(val genericSignature: Signature,
                      val resolvedSignature: Signature,
                      val paramNames: List<String>?,
                      val flags: List<Flag>,
                      val declaringType: Class?): Type() {

        fun hasFlags(vararg test: Flag) = test.all { flags.contains(it) }

        data class Signature(val returnType: Type?, val paramTypes: List<Type>)

        internal fun deepEquals(method: Method?): Boolean {
            if(method == null)
                return false

            fun Signature.deepEquals(signature: Signature) =
                    returnType.deepEquals(signature.returnType) &&
                            paramTypes.size == signature.paramTypes.size &&
                            paramTypes.zip(signature.paramTypes).map { (p1, p2) -> p1.deepEquals(p2) }.reduce(Boolean::and)

            return declaringType.deepEquals(method.declaringType) &&
                    genericSignature.deepEquals(method.genericSignature) &&
                    resolvedSignature.deepEquals(method.resolvedSignature) &&
                    flags.all { method.flags.contains(it) } &&
                    paramNames?.equals(method.paramNames) ?: method.paramNames == null
        }
    }
   
    data class GenericTypeVariable(val fullyQualifiedName: String, val bound: Class?): Type() {
        internal fun deepEquals(generic: GenericTypeVariable?): Boolean =
                generic != null && fullyQualifiedName == generic.fullyQualifiedName && bound.deepEquals(generic.bound)
    }
    
    data class Array(val elemType: Type): Type() {
        internal fun deepEquals(array: Array?): Boolean =
                array != null && elemType.deepEquals(array.elemType)
    }
    
    data class Primitive(val keyword: String): Type() {
        companion object {
            val Boolean = Primitive("boolean")
            val Byte = Primitive("byte")
            val Char = Primitive("char")
            val Double = Primitive("double")
            val Float = Primitive("float")
            val Int = Primitive("int")
            val Long = Primitive("long")
            val Short = Primitive("short")
            val Void = Primitive("void")
            val String = Primitive("String")
            val None = Primitive("")
            val Wildcard = Primitive("*")
            val Null = Primitive("null")

            fun build(keyword: String): Primitive = when(keyword) {
                "boolean" -> Boolean
                "byte" -> Byte
                "char" -> Char
                "double" -> Double
                "float" -> Float
                "int" -> Int
                "long" -> Long
                "short" -> Short
                "void" -> Void
                "String" -> String
                "" -> None
                "*" -> Wildcard
                "null" -> Null
                else -> throw IllegalArgumentException("Invalid primitive ordinal")
            }
        }
    }

    data class Var(val name: String, val type: Type?, val flags: List<Flag>): Type() {
        fun hasFlags(vararg test: Flag) = test.all { flags.contains(it) }

        internal fun deepEquals(v: Var): Boolean =
                name != v.name && type.deepEquals(v.type) && flags.all { v.flags.contains(it) }
    }
}

fun Type?.asClass(): Type.Class? = when(this) {
    is Type.Class -> this
    else -> null
}

fun Type?.asArray(): Type.Array? = when(this) {
    is Type.Array -> this
    else -> null
}

fun Type?.asGeneric(): Type.GenericTypeVariable? = when(this) {
    is Type.GenericTypeVariable -> this
    else -> null
}

fun Type?.asMethod(): Type.Method? = when(this) {
    is Type.Method -> this
    else -> null
}

fun Type?.hasElementType(fullyQualifiedName: String): Boolean = when(this) {
    is Type.Array -> this.elemType.hasElementType(fullyQualifiedName)
    is Type.Class -> this.fullyQualifiedName == fullyQualifiedName
    is Type.GenericTypeVariable -> this.fullyQualifiedName == fullyQualifiedName
    else -> false
}