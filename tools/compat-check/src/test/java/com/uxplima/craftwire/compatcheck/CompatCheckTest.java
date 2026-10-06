package com.uxplima.craftwire.compatcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class CompatCheckTest {
    @TempDir Path dir;

    /** lib/Base has `inherited()`, lib/Game extends it and has `kept()` and field `size`. */
    private Path library(boolean withRemoved) throws IOException {
        ClassWriter base = new ClassWriter(0);
        base.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "lib/Base", null, "java/lang/Object", null);
        method(base, "inherited");
        ClassWriter game = new ClassWriter(0);
        game.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "lib/Game", null, "lib/Base", null);
        method(game, "kept");
        if (withRemoved) method(game, "removed");
        game.visitField(Opcodes.ACC_PUBLIC, "size", "I", null, null).visitEnd();
        return jar("lib.jar", Map.of("lib/Base.class", base.toByteArray(), "lib/Game.class", game.toByteArray()));
    }

    /** The agent calls kept(), inherited() and removed(), reads size, and uses lib/Gone. */
    private Path agent() throws IOException {
        ClassWriter c = new ClassWriter(0);
        c.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/uxplima/craftwire/Agent", null, "java/lang/Object", null);
        MethodVisitor m = c.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Llib/Game;)V", null, null);
        m.visitCode();
        for (String name : List.of("kept", "inherited", "removed")) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "lib/Game", name, "()V", false);
        }
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, "lib/Game", "size", "I");
        m.visitInsn(Opcodes.POP);
        m.visitTypeInsn(Opcodes.NEW, "lib/Gone");
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(2, 1);
        m.visitEnd();
        return jar("agent.jar", Map.of("com/uxplima/craftwire/Agent.class", c.toByteArray()));
    }

    @Test
    void reportsMissingMembersAndClassesButFollowsInheritance() throws IOException {
        List<String> problems;
        try (JarFile lib = new JarFile(library(false).toFile()); JarFile agent = new JarFile(agent().toFile())) {
            problems = new CompatCheck(List.of(lib)).check(agent, "v26_3");
        }
        assertEquals(List.of(
                "com.uxplima.craftwire.Agent.run: missing class lib.Gone",
                "com.uxplima.craftwire.Agent.run: missing method lib.Game.removed()V"), problems);
    }

    @Test
    void passesWhenEverythingResolves() throws IOException {
        List<String> problems;
        try (JarFile lib = new JarFile(library(true).toFile()); JarFile agent = new JarFile(agent().toFile())) {
            problems = new CompatCheck(List.of(lib)).check(agent, "v26_3");
        }
        assertEquals(List.of("com.uxplima.craftwire.Agent.run: missing class lib.Gone"), problems);
    }

    @Test
    void checksOnlyTheMatchingCompatPackage() {
        assertTrue(CompatCheck.applies("com/uxplima/craftwire/fabric/Agent.class", "v26_3"));
        assertTrue(CompatCheck.applies("com/uxplima/craftwire/fabric/compat/v26_3/X.class", "v26_3"));
        assertFalse(CompatCheck.applies("com/uxplima/craftwire/fabric/compat/v26_2/X.class", "v26_3"));
        assertTrue(CompatCheck.applies("com/uxplima/craftwire/fabric/compat/ClientCompat.class", "v26_3"));
    }

    private static void method(ClassWriter c, String name) {
        MethodVisitor m = c.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 1);
        m.visitEnd();
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(p); JarOutputStream jar = new JarOutputStream(out)) {
            for (var e : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(e.getKey()));
                jar.write(e.getValue());
                jar.closeEntry();
            }
        }
        return p;
    }
}
