package com.dwinovo.numen.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原版的服务端有 {@code Server.tick()V} 与 {@code Server.remove()V};Paper 的服务端把 {@code remove} 改成返回字符串
 * (照 {@code PlayerList.remove} 的真实改动)。成品里的类继承 {@code Server},覆写并调用它们。
 */
class LinkCheckTest {

    private static final String SERVER = "net/minecraft/Server";
    private static final String SUB = "my/Sub";

    @TempDir
    Path dir;

    @Test
    void passesWhenEverythingResolves() throws IOException {
        List<String> problems = check(
                product(Opcodes.ACC_PUBLIC, "tick", "()V", SERVER, "tick", "()V", false), vanilla(), vanilla());
        assertEquals(List.of(), problems);
    }

    @Test
    void reportsAReferenceThatNoLongerResolves() throws IOException {
        List<String> problems = check(
                product(Opcodes.ACC_PUBLIC, "other", "()V", SERVER, "remove", "()V", false), vanilla(), paper());
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("引用解析不到") && problems.get(0).contains("remove()V"), problems.toString());
    }

    @Test
    void reportsAMethodHandleThatNoLongerResolves() throws IOException {
        List<String> problems = check(
                product(Opcodes.ACC_PUBLIC, "other", "()V", SERVER, "remove", "()V", true), vanilla(), paper());
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("引用解析不到"), problems.toString());
    }

    @Test
    void reportsAnOverrideThatNoLongerOverrides() throws IOException {
        // 覆写了原版的 remove()V,Paper 里这个方法已经变了签名:覆写悄悄失效。不调用它,只算覆写
        List<String> problems = check(
                product(Opcodes.ACC_PUBLIC, "remove", "()V", SERVER, "tick", "()V", false), vanilla(), paper());
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("覆写失效") && problems.get(0).contains("remove()V"), problems.toString());
    }

    @Test
    void anOverloadOfAMethodTheServerNeverHadIsNotAnOverride() throws IOException {
        List<String> problems = check(
                product(Opcodes.ACC_PUBLIC, "remove", "(I)V", SERVER, "tick", "()V", false), vanilla(), paper());
        assertEquals(List.of(), problems);
    }

    // ---- 搭场景 ----

    private List<String> check(Path product, Path vanilla, Path paper) throws IOException {
        return LinkCheck.run(List.of(product), List.of(), vanilla, paper);
    }

    private Path vanilla() throws IOException {
        return server("vanilla.jar", "()V");
    }

    private Path paper() throws IOException {
        return server("paper.jar", "()Ljava/lang/String;");
    }

    private Path server(String file, String removeDesc) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, SERVER, null, "java/lang/Object", null);
        method(cw, "tick", "()V");
        method(cw, "remove", removeDesc);
        cw.visitEnd();
        return jar(file, SERVER, cw.toByteArray());
    }

    private static void method(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        if (desc.endsWith(")V")) {
            mv.visitInsn(Opcodes.RETURN);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
        }
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    /** 成品:{@code my/Sub extends Server},有一个叫 {@code declared} 的方法,它的方法体引用 {@code owner.name desc}(或为它建方法句柄)。 */
    private Path product(int access, String declared, String declaredDesc, String owner, String name, String desc,
                         boolean asHandle) throws IOException {
        byte[] sub = clazz(SUB, SERVER, null);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, SUB, null, SERVER, null);
        MethodVisitor mv = cw.visitMethod(access, declared, declaredDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (asHandle) {
            mv.visitInvokeDynamicInsn("run", "(L" + SERVER + ";)Ljava/lang/Runnable;",
                    new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false),
                    org.objectweb.asm.Type.getMethodType("()V"),
                    new Handle(Opcodes.H_INVOKEVIRTUAL, owner, name, desc, false),
                    org.objectweb.asm.Type.getMethodType("()V"));
            mv.visitInsn(Opcodes.POP);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, desc, false);
            if (!desc.endsWith(")V")) {
                mv.visitInsn(Opcodes.POP);
            }
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return jar("product.jar", SUB, cw.toByteArray());
    }

    private static byte[] clazz(String name, String superName, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, null);
        if (body != null) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "call", "()V", null, null);
            mv.visitCode();
            body.accept(mv);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private Path jar(String file, Object... namesAndBytes) throws IOException {
        Path out = dir.resolve(file);
        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (int i = 0; i < namesAndBytes.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndBytes[i] + ".class"));
                zip.write((byte[]) namesAndBytes[i + 1]);
                zip.closeEntry();
            }
        }
        return out;
    }
}
