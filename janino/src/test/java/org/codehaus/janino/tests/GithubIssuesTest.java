
/*
 * Janino - An embedded Java[TM] compiler
 *
 * Copyright (c) 2017 Arno Unkrig. All rights reserved.
 * Copyright (c) 2015-2016 TIBCO Software Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *       following disclaimer.
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *       following disclaimer in the documentation and/or other materials provided with the distribution.
 *    3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
 *       products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.codehaus.janino.tests;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.Map;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.commons.compiler.IClassBodyEvaluator;
import org.codehaus.commons.compiler.IExpressionEvaluator;
import org.codehaus.commons.nullanalysis.Nullable;
import org.codehaus.janino.ClassBodyEvaluator;
import org.codehaus.janino.ExpressionEvaluator;
import org.codehaus.janino.Java.CompilationUnit;
import org.codehaus.janino.Java.IntegerLiteral;
import org.codehaus.janino.Java.Rvalue;
import org.codehaus.janino.Parser;
import org.codehaus.janino.Scanner;
import org.codehaus.janino.ScriptEvaluator;
import org.codehaus.janino.SimpleCompiler;
import org.codehaus.janino.util.DeepCopier;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that reproduce <a href="https://github.com/janino-compiler/janino/issues">the issues reported on GITHUB</a>.
 */
public
class GithubIssuesTest {

    /**
     * A "degenerated" {@link ExpressionEvaluator} that suppresses the loading of the generated bytecodes into a
     * class loader.
     * <p>
     *   {@link ScriptEvaluator}, {@link ClassBodyEvaluator} and {@link SimpleCompiler} should be adaptable in very
     *   much the same way.
     * </p>
     * <p>
     *   The methods of {@link IExpressionEvaluator} that are related to loaded classes must not be used, and all
     *   throw {@link RuntimeException}s. These methods are:
     * </p>
     * <ul>
     *   <li>{@link #createFastEvaluator(java.io.Reader, Class, String[])}</li>
     *   <li>{@link #createFastEvaluator(org.codehaus.janino.Scanner, Class, String[])}</li>
     *   <li>{@link #createFastEvaluator(String, Class, String[])}</li>
     *   <li>{@link #evaluate(Object[])}</li>
     *   <li>{@link #evaluate(int, Object[])}</li>
     *   <li>{@link #getClazz()}</li>
     *   <li>{@link #getMethod()}</li>
     *   <li>{@link #getMethod(int)}</li>
     * </ul>
     *
     * @see #getBytecodes()
     */
    public static
    class ExpressionCompiler extends ExpressionEvaluator {

        @Nullable private Map<String, byte[]> classes;

        /**
         * @return The bytecodes that were generated when {@link #cook(String)} was invoked
         */
        public Map<String, byte[]>
        getBytecodes() {

            Map<String, byte[]> result = this.classes;
            if (result == null) throw new IllegalStateException("Must only be called after \"cook()\"");

            return result;
        }

        // --------------------------------------------------------------------

        // Override this method to prevent the loading of the class files into a ClassLoader.
        @Override public void
        cook(Map<String, byte[]> classes) {

            // Instead of loading the bytecodes into a ClassLoader, store the bytecodes in "this.classes".
            this.classes = classes;
        }

        // Override this method to prevent the retrieval of the generated java.lang.Classes.
        @Override protected void
        cook2(CompilationUnit compilationUnit) throws CompileException {
            this.cook(compilationUnit);
        }
    }

    /**
     * @see <a href="https://github.com/janino-compiler/janino/issues/19">GITHUB issue #19: Get bytecode after
     *      compile</a>
     */
    @Test public void
    testCompileToBytecode() throws CompileException {

        // Set up an ExpressionCompiler and cook the expression.
        ExpressionCompiler ec = new ExpressionCompiler();
        ec.setExpressionType(int.class);

        ec.cook("7");

        // Retrieve the generated bytecode from the ExpressionCompiler. The result is a map from class name
        // to the class's bytecode.
        Map<String, byte[]> result = ec.getBytecodes();
        Assert.assertNotNull(result);

        // verify that exactly _one_ class was generated.
        Assert.assertEquals(1, result.size());

        // Verify the class's name.
        byte[] ba = result.get(IClassBodyEvaluator.DEFAULT_CLASS_NAME);
        Assert.assertNotNull(ba);

        // Verify that the generated bytecode looks "reasonable", i.e. starts with the charcteristic
        // "magic bytes" and has an approximate size.
        Assert.assertArrayEquals(
            new byte[] { (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe },
            Arrays.copyOf(ba, 4)
        );
        Assert.assertTrue(Integer.toString(ba.length), ba.length > 150);
        Assert.assertTrue(Integer.toString(ba.length), ba.length < 300);
    }

    @Test public void
    testExpressionCompilerMethods() throws CompileException, IOException, InvocationTargetException {

        Class<?> interfaceToImplement = Comparable.class;
        String[] parameterNames       = { "o" };
        String   e                    = "7";
        Object[] arguments            = { "" };

        CASES: for (int i = 0;; i++) {

            ExpressionCompiler ec = new ExpressionCompiler();
            ec.setExpressionType(int.class);

            if (i >= 3 && i <= 7) ec.cook(e);

            try {
                switch (i) {

                // All these invocations are expected to throw an IllegalStateException.
                // SUPPRESS CHECKSTYLE LineLength:8
                case 0: ec.createFastEvaluator(new StringReader(e), interfaceToImplement, parameterNames);                    break;
                case 1: ec.createFastEvaluator(new Scanner(null, new StringReader(e)), interfaceToImplement, parameterNames); break;
                case 2: ec.createFastEvaluator(e, interfaceToImplement, parameterNames);                                      break;
                case 3: ec.evaluate(arguments);                                                                               break;
                case 4: ec.evaluate(0, arguments);                                                                            break;
                case 5: ec.getClazz();                                                                                        break;
                case 6: ec.getMethod();                                                                                       break;
                case 7: ec.getMethod(0);                                                                                      break;

                case 8: break CASES;

                default: continue;
                }
                Assert.fail("Exception expected (case " + i + ")");
            } catch (IllegalStateException ise) {
                ;
            }
        }
    }

    @Test public void
    testIssue91() throws Exception {

        // Parse the framework code.
        CompilationUnit cu = (CompilationUnit) new Parser(new Scanner(
            "user expression", // This will appear in stack traces as "file name".
            new StringReader(
                ""
                + "package com.acme.framework;\n"
                + "\n"
                + "public\n"
                + "class Framework {\n"
                + "\n"
                + "    public static void\n"
                + "    frameworkMethod() {\n"
                + "        System.out.println(\"frameworkMethod()\");\n"
                + "    }\n"
                + "\n"
                + "    public static void\n"
                + "    userMethod() {\n"
                + "        System.out.println(77);\n" // <= "77" will be replaced with the user expression!
                + "    }\n"
                + "}\n"
            )
        )).parseAbstractCompilationUnit();

        // Parse the "user expression".
        final Rvalue userExpression = (
            new Parser(new Scanner(
                null,
                new StringReader( // Line numbers will appear in stack traces.
                    ""
                    + "\n"
                    + "\n"
                    + "java.nio.charset.Charset.forName(\"kkk\")\n" // <= Causes an UnsupportedCharsetException
                )
            ))
            .parseExpression()
            .toRvalueOrCompileException()
        );

        // Merge the framework code with the user expression.
        cu = new DeepCopier() {

            @Override public Rvalue
            copyIntegerLiteral(IntegerLiteral subject) throws CompileException {
                if ("77".equals(subject.value)) return userExpression;
                return super.copyIntegerLiteral(subject);
            }
        }.copyCompilationUnit(cu);

        // Compile the code into a ClassLoader.
        SimpleCompiler sc = new SimpleCompiler();
        sc.setDebuggingInformation(true, true, true);
        sc.cook(cu);
        ClassLoader cl = sc.getClassLoader();

        // Find the generated class by name.
        Class<?> c = cl.loadClass("com.acme.framework.Framework");

        // Invoke the class's methods.
        c.getMethod("frameworkMethod").invoke(null);
        try {
            c.getMethod("userMethod").invoke(null);
            Assert.fail("InvocationTargetException expected");
        } catch (InvocationTargetException ite) {
            Throwable te = ite.getTargetException();
            Assert.assertEquals(UnsupportedCharsetException.class, te.getClass());
            StackTraceElement top = te.getStackTrace()[1];
            Assert.assertEquals("userMethod", top.getMethodName());
            Assert.assertEquals("user expression", top.getFileName());
            Assert.assertEquals(3, top.getLineNumber());
        }
    }

    @Test public void
    testIssue113() throws Exception {
        CompilationUnit cu = (CompilationUnit) new Parser(new Scanner(
            "issue113", // This will appear in stack traces as "file name".
            new StringReader(
                ""
                    + "package demo.pkg3;\n"
                    + "public class A$$1 {\n"
                    + "    public static String main() {\n"
                    + "        StringBuilder sb = new StringBuilder();\n"
                    + "        short b = 1;\n"
                    + "        for (int i = 0; i < 4; i++) {\n"
                    + "            ;\n"
                    + "            switch (i) {\n"
                    + "                case 0:\n"
                    + "                    sb.append(\"A\");\n"
                    + "                    break;\n"
                    + "                case 1:\n"
                    + "                    sb.append(\"B\");\n"
                    + "                    break;\n"
                    + "                case 2:\n"
                    + "                    sb.append(\"C\");\n"
                    + "                    break;\n"
                    + "                case 3:\n"
                    + "                    sb.append(\"D\");\n"
                    + "                    break;\n"
                    + "            }\n"
                    + GithubIssuesTest.injectDummyLargeCodeExceedingShort()
                    + "        }\n"
                    + "        return sb.toString();\n"
                    + "    }\n"
                    + "}\n"
            )
        )).parseAbstractCompilationUnit();

        // Compile the code into a ClassLoader.
        SimpleCompiler sc = new SimpleCompiler();
        sc.setDebuggingInformation(true, true, true);
        sc.cook(cu);
        ClassLoader cl = sc.getClassLoader();

        Assert.assertEquals("ABCD", cl.loadClass("demo.pkg3.A$$1").getMethod("main").invoke(null));
    }
//    org.codehaus.janino.InternalCompilerException: Compiling "A$$1" in "issue113": Compiling "main()"; main(): Operand stack inconsistent at offset 18: Previous size 1, now 0
//
//    at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:367)
//    at org.codehaus.janino.UnitCompiler.access$000(UnitCompiler.java:226)
//    at org.codehaus.janino.UnitCompiler$1.visitCompilationUnit(UnitCompiler.java:336)
//    at org.codehaus.janino.UnitCompiler$1.visitCompilationUnit(UnitCompiler.java:333)
//    at org.codehaus.janino.Java$CompilationUnit.accept(Java.java:363)
//    at org.codehaus.janino.UnitCompiler.compileUnit(UnitCompiler.java:333)
//    at org.codehaus.janino.SimpleCompiler.cook(SimpleCompiler.java:235)
//    at org.codehaus.janino.tests.GithubIssuesTest.testIssue113(GithubIssuesTest.java:306)
//    at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
//    at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
//    at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
//    at java.lang.reflect.Method.invoke(Method.java:498)
//    at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:50)
//    at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
//    at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:47)
//    at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
//    at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:325)
//    at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:78)
//    at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:57)
//    at org.junit.runners.ParentRunner$3.run(ParentRunner.java:290)
//    at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:71)
//    at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:288)
//    at org.junit.runners.ParentRunner.access$000(ParentRunner.java:58)
//    at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:268)
//    at org.junit.runners.ParentRunner.run(ParentRunner.java:363)
//    at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
//    at com.intellij.junit4.JUnit4IdeaTestRunner.startRunnerWithArgs(JUnit4IdeaTestRunner.java:68)
//    at com.intellij.rt.junit.IdeaTestRunner$Repeater.startRunnerWithArgs(IdeaTestRunner.java:33)
//    at com.intellij.rt.junit.JUnitStarter.prepareStreamsAndStart(JUnitStarter.java:230)
//    at com.intellij.rt.junit.JUnitStarter.main(JUnitStarter.java:58)
//    Caused by: org.codehaus.janino.InternalCompilerException: Compiling "main()"; main(): Operand stack inconsistent at offset 18: Previous size 1, now 0
//    at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:3448)
//    at org.codehaus.janino.UnitCompiler.compileDeclaredMethods(UnitCompiler.java:1362)
//    at org.codehaus.janino.UnitCompiler.compileDeclaredMethods(UnitCompiler.java:1335)
//    at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:807)
//    at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:410)
//    at org.codehaus.janino.UnitCompiler.access$400(UnitCompiler.java:226)
//    at org.codehaus.janino.UnitCompiler$2.visitPackageMemberClassDeclaration(UnitCompiler.java:389)
//    at org.codehaus.janino.UnitCompiler$2.visitPackageMemberClassDeclaration(UnitCompiler.java:384)
//    at org.codehaus.janino.Java$PackageMemberClassDeclaration.accept(Java.java:1594)
//    at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:384)
//    at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:362)
//        ... 29 more
//    Caused by: org.codehaus.janino.InternalCompilerException: main(): Operand stack inconsistent at offset 18: Previous size 1, now 0
//    at org.codehaus.janino.CodeContext.flowAnalysis(CodeContext.java:478)
//    at org.codehaus.janino.CodeContext.flowAnalysis(CodeContext.java:669)
//    at org.codehaus.janino.CodeContext.flowAnalysis(CodeContext.java:627)
//    at org.codehaus.janino.CodeContext.flowAnalysis(CodeContext.java:627)
//    at org.codehaus.janino.CodeContext.flowAnalysis(CodeContext.java:371)
//    at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:3439)
//        ... 39 more

    private static String
    injectDummyLargeCodeExceedingShort() {
        StringBuilder sb = new StringBuilder();
        sb.append("int a = -1;\n");
        for (int i = 0 ; i < Short.MAX_VALUE / 3 ; i++) {
            sb.append("a = " + i + ";\n");
        }
        return sb.toString();
    }
}
