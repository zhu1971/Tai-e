/*
 * Bamboo - A Program Analysis Framework for Java
 *
 * Copyright (C) 2020 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020 Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * This software is designed for the "Static Program Analysis" course at
 * Nanjing University, and it supports a subset of Java features.
 * Bamboo is only for educational and academic purposes, and any form of
 * commercial use is disallowed.
 */

package bamboo.pta.env.nativemodel;

import bamboo.pta.core.ProgramManager;
import bamboo.pta.element.Field;
import bamboo.pta.element.Method;
import bamboo.pta.element.Type;
import bamboo.pta.element.Variable;
import bamboo.pta.env.EnvObj;
import bamboo.pta.options.Options;
import bamboo.pta.statement.Allocation;
import bamboo.pta.statement.ArrayStore;
import bamboo.pta.statement.Assign;
import bamboo.pta.statement.StaticStore;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

class MethodModel {

    private final ProgramManager pm;
    // Use String as key is to avoid cyclic dependence during the
    // initialization of ProgramManager.
    // TODO: use Method as key to improve performance?
    private final Map<String, Consumer<Method>> handlers;
    /**
     * Counter to give each mock variable an unique name.
     */
    private final AtomicInteger counter;

    MethodModel(ProgramManager pm) {
        this.pm = pm;
        handlers = new HashMap<>();
        counter = new AtomicInteger(0);
        initHandlers();
    }

    void process(Method method) {
        Consumer<Method> handler = handlers.get(method.getSignature());
        if (handler != null) {
            handler.accept(method);
        }
    }

    private void initHandlers() {
        /**********************************************************************
         * java.lang.Object
         *********************************************************************/
        // <java.lang.Object: java.lang.Object clone()>
        // TODO: could throw CloneNotSupportedException
        // TODO: should check if the object is Cloneable.
        // TODO: should return a clone of the heap allocation (not
        //  identity). The behaviour implemented here is based on Soot.
        registerHandler("<java.lang.Object: java.lang.Object clone()>", method ->
            method.getReturnVariables().forEach(ret ->
                    method.addStatement(new Assign(ret, method.getThis())))
        );

        /**********************************************************************
         * java.lang.System
         *********************************************************************/
        // <java.lang.System: void setIn0(java.io.InputStream)>
        registerHandler("<java.lang.System: void setIn0(java.io.InputStream)>", method -> {
            Field systemIn = pm.getUniqueFieldBySignature(
                    "<java.lang.System: java.io.InputStream in>");
            Variable param0 = method.getParam(0).get();
            method.addStatement(new StaticStore(systemIn, param0));
        });

        // <java.lang.System: void setOut0(java.io.PrintStream)>
        registerHandler("<java.lang.System: void setOut0(java.io.PrintStream)>", method -> {
            Field systemOut = pm.getUniqueFieldBySignature(
                    "<java.lang.System: java.io.PrintStream out>");
            Variable param0 = method.getParam(0).get();
            method.addStatement(new StaticStore(systemOut, param0));
        });

        // <java.lang.System: void setErr0(java.io.PrintStream)>
        registerHandler("<java.lang.System: void setErr0(java.io.PrintStream)>", method -> {
            Field systemErr = pm.getUniqueFieldBySignature(
                    "<java.lang.System: java.io.PrintStream err>");
            Variable param0 = method.getParam(0).get();
            method.addStatement(new StaticStore(systemErr, param0));
        });

        /**********************************************************************
         * java.io.FileSystem
         *********************************************************************/
        final List<String> concreteFileSystems = Arrays.asList(
                "java.io.UnixFileSystem",
                "java.io.WinNTFileSystem",
                "java.io.Win32FileSystem"
        );
        // <java.io.FileSystem: java.io.FileSystem getFileSystem()>
        // This API is implemented in Java code since Java 7.
        if (Options.get().jdkVersion() <= 6) {
            registerHandler("<java.io.FileSystem: java.io.FileSystem getFileSystem()>", method -> {
                concreteFileSystems.forEach(fsName -> {
                    pm.tryGetUniqueTypeByName(fsName).ifPresent(fs -> {
                        method.getReturnVariables().forEach(ret -> {
                            Utils.modelAllocation(pm, method, fs, fsName, ret,
                                    "<" + fs.getName() + ": void <init>()>",
                                    "init-file-system");
                        });
                    });
                });
            });
        }

        // <java.io.*FileSystem: java.lang.String[] list(java.io.File)>
        concreteFileSystems.forEach(fsName -> {
            registerHandler("<" + fsName + ": java.lang.String[] list(java.io.File)>", method -> {
                Type string = pm.getUniqueTypeByName("java.lang.String");
                EnvObj elem = new EnvObj("dir-element", string, method);
                Variable temp = newMockVariable(string, method);
                Type stringArray = pm.getUniqueTypeByName("java.lang.String[]");
                EnvObj array = new EnvObj("element-array", stringArray, method);
                method.getReturnVariables().forEach(ret -> {
                    method.addStatement(new Allocation(temp, elem));
                    method.addStatement(new Allocation(ret, array));
                    method.addStatement(new ArrayStore(ret, temp));
                });
            });
        });

        /**********************************************************************
         * sun.misc.Perf
         *********************************************************************/
        // <sun.misc.Perf: java.nio.ByteBuffer createLong(java.lang.String,int,int,long)>
        registerHandler("<sun.misc.Perf: java.nio.ByteBuffer createLong(java.lang.String,int,int,long)>", method -> {
            method.getReturnVariables().forEach(ret -> {
                Type type = pm.getUniqueTypeByName("java.nio.DirectByteBuffer");
                Utils.modelAllocation(pm, method,
                        type, type.getName(), ret,
                        "<java.nio.DirectByteBuffer: void <init>(int)>",
                        "create-long-buffer");
            });
        });
    }

    private void registerHandler(String signature, Consumer<Method> handler) {
        handlers.put(signature, handler);
    }

    private Variable newMockVariable(Type type, Method container) {
        return new MockVariable(type, container,
                "@native-method-mock-var" + counter.getAndIncrement());
    }
}
