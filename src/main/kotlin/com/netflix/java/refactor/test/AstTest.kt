package com.netflix.java.refactor.test

import com.netflix.java.refactor.ast.Tr
import com.netflix.java.refactor.parse.Parser
import com.netflix.java.refactor.parse.StringSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.util.regex.Pattern

abstract class AstTest(var parser: Parser) {
    @JvmField @Rule
    val temp = TemporaryFolder()
    
    fun parse(source: String, whichDependsOn: String) =
        parse(source, listOf(whichDependsOn))
    
    fun parse(source: String, whichDependOn: List<String>) =
        parse(source, *whichDependOn.toTypedArray())
    
    fun parse(source: String, vararg whichDependOn: String): Tr.CompilationUnit {
        fun simpleName(sourceStr: String): String? {
            val classMatcher = Pattern.compile("(class|interface|enum)\\s*(<[^>]*>)?\\s+(\\w+)").matcher(sourceStr)
            return if (classMatcher.find()) classMatcher.group(3) else null
        }
        
        fun sourceFile(source: String): Path {
            val file = File(temp.root, "${simpleName(source)}.java")
            file.writeText(source.trimMargin())
            return file.toPath()
        }

        val dependencies = whichDependOn.map { it.trimMargin() }.map(::sourceFile) 
        val sources = dependencies + listOf(sourceFile(source.trimMargin()))
                
        return parser.parse(sources, StringSource.Builder::fromPath).last()
    }

    fun assertRefactored(cu: Tr.CompilationUnit, refactored: String) {
        assertEquals(refactored.trimMargin(), cu.rawSource.text)
    }
}