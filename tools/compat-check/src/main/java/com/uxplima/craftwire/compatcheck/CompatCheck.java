package com.uxplima.craftwire.compatcheck;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Checks that an agent jar links against one Minecraft version: every class, method and field it uses from the
 * game, the loader or the server exists there (looked up through superclasses and interfaces, as the JVM does).
 * Game tests only reach the code paths they exercise; this covers every instruction.
 *
 * <p>Usage: {@code CompatCheck <agent.jar> <version id, e.g. v26_3> <classpath entries...>}. The classes of the jar
 * are checked except other versions' compat packages; references to Craftwire's own classes and to the JDK are
 * not checked. Exits 1 and lists every broken reference.
 */
public final class CompatCheck {
    private static final String OWN = "com/uxplima/craftwire/";

    private final Map<String, Optional<ClassNode>> library = new HashMap<>();
    private final List<JarFile> classpath;

    CompatCheck(List<JarFile> classpath) {
        this.classpath = classpath;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: CompatCheck <agent.jar> <version id> <classpath...>");
            System.exit(2);
        }
        List<JarFile> cp = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            Path p = Path.of(args[i]);
            if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) cp.add(new JarFile(p.toFile()));
        }
        List<String> problems = new CompatCheck(cp).check(new JarFile(args[0]), args[1]);
        if (problems.isEmpty()) {
            System.out.println("compat-check " + args[1] + ": OK");
            return;
        }
        System.err.println("compat-check " + args[1] + ": " + problems.size() + " broken reference(s)");
        problems.forEach(p -> System.err.println("  " + p));
        System.exit(1);
    }

    /** Broken references of the jar's classes for version `versionId`, sorted, as "Class.method: missing X". */
    List<String> check(JarFile agent, String versionId) throws IOException {
        TreeSet<String> problems = new TreeSet<>();
        for (JarEntry e : java.util.Collections.list(agent.entries())) {
            String name = e.getName();
            if (!name.endsWith(".class") || !applies(name, versionId)) continue;
            ClassNode c = read(agent.getInputStream(e));
            String where = c.name.replace('/', '.');
            if (c.superName != null) requireClass(c.superName, where, problems);
            for (String i : c.interfaces) requireClass(i, where, problems);
            for (FieldNode f : c.fields) requireDescriptor(Type.getType(f.desc), where + "." + f.name, problems);
            for (MethodNode m : c.methods) {
                String at = where + "." + m.name;
                requireDescriptor(Type.getMethodType(m.desc), at, problems);
                for (AbstractInsnNode insn : m.instructions) checkInstruction(insn, at, problems);
            }
        }
        return List.copyOf(problems);
    }

    /** Common classes always; compat classes only for their own version. */
    static boolean applies(String entry, String versionId) {
        int at = entry.indexOf("/compat/v");
        if (at < 0) return true;
        String rest = entry.substring(at + "/compat/".length());
        return rest.startsWith(versionId + "/");
    }

    private void checkInstruction(AbstractInsnNode insn, String at, TreeSet<String> problems) {
        switch (insn) {
            case MethodInsnNode m -> requireMethod(m.owner, m.name, m.desc, at, problems);
            case FieldInsnNode f -> requireField(f.owner, f.name, f.desc, at, problems);
            case TypeInsnNode t -> requireType(Type.getObjectType(t.desc), at, problems);
            case MultiANewArrayInsnNode a -> requireType(Type.getType(a.desc), at, problems);
            case LdcInsnNode l when l.cst instanceof Type t -> requireType(t, at, problems);
            case InvokeDynamicInsnNode d -> {
                for (Object arg : d.bsmArgs) {
                    if (arg instanceof Handle h) requireHandle(h, at, problems);
                }
            }
            default -> {}
        }
    }

    private void requireHandle(Handle h, String at, TreeSet<String> problems) {
        if (h.getTag() <= Opcodes.H_PUTSTATIC) requireField(h.getOwner(), h.getName(), h.getDesc(), at, problems);
        else requireMethod(h.getOwner(), h.getName(), h.getDesc(), at, problems);
    }

    private void requireDescriptor(Type t, String at, TreeSet<String> problems) {
        if (t.getSort() == Type.METHOD) {
            requireType(t.getReturnType(), at, problems);
            for (Type a : t.getArgumentTypes()) requireType(a, at, problems);
        } else {
            requireType(t, at, problems);
        }
    }

    private void requireType(Type t, String at, TreeSet<String> problems) {
        if (t.getSort() == Type.ARRAY) t = t.getElementType();
        if (t.getSort() == Type.OBJECT) requireClass(t.getInternalName(), at, problems);
    }

    private void requireClass(String owner, String at, TreeSet<String> problems) {
        if (skipped(owner)) return;
        if (lookup(owner).isEmpty()) problems.add(at + ": missing class " + owner.replace('/', '.'));
    }

    private void requireMethod(String owner, String name, String desc, String at, TreeSet<String> problems) {
        if (owner.startsWith("[") || skipped(owner)) return;
        if (lookup(owner).isEmpty()) {
            problems.add(at + ": missing class " + owner.replace('/', '.'));
        } else if (!hasMethod(owner, name, desc)) {
            problems.add(at + ": missing method " + owner.replace('/', '.') + "." + name + desc);
        }
    }

    private void requireField(String owner, String name, String desc, String at, TreeSet<String> problems) {
        if (skipped(owner)) return;
        if (lookup(owner).isEmpty()) {
            problems.add(at + ": missing class " + owner.replace('/', '.'));
        } else if (!hasField(owner, name, desc)) {
            problems.add(at + ": missing field " + owner.replace('/', '.') + "." + name + " " + desc);
        }
    }

    private boolean hasMethod(String owner, String name, String desc) {
        Optional<ClassNode> c = lookup(owner);
        if (c.isEmpty()) return jdkHas(owner, name, desc, true);
        for (MethodNode m : c.get().methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
        // Signature-polymorphic and inherited methods: walk up like the JVM's method resolution.
        if (c.get().superName != null && hasMethod(c.get().superName, name, desc)) return true;
        for (String i : c.get().interfaces) if (hasMethod(i, name, desc)) return true;
        return false;
    }

    private boolean hasField(String owner, String name, String desc) {
        Optional<ClassNode> c = lookup(owner);
        if (c.isEmpty()) return jdkHas(owner, name, desc, false);
        for (FieldNode f : c.get().fields) if (f.name.equals(name) && f.desc.equals(desc)) return true;
        for (String i : c.get().interfaces) if (hasField(i, name, desc)) return true;
        return c.get().superName != null && hasField(c.get().superName, name, desc);
    }

    /** A member reached through a library class that inherits from the JDK (e.g. Object.hashCode), via reflection. */
    private static boolean jdkHas(String owner, String name, String desc, boolean method) {
        Class<?> c;
        try {
            c = Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
        java.util.ArrayDeque<Class<?>> todo = new java.util.ArrayDeque<>(List.of(c));
        while (!todo.isEmpty()) {
            Class<?> k = todo.pop();
            if (method && name.equals("<init>")) {
                for (var ctor : k.getDeclaredConstructors()) if (Type.getConstructorDescriptor(ctor).equals(desc)) return true;
                return false;
            }
            if (method) {
                for (var m : k.getDeclaredMethods()) if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) return true;
            } else {
                for (var f : k.getDeclaredFields()) if (f.getName().equals(name) && Type.getDescriptor(f.getType()).equals(desc)) return true;
            }
            if (k.getSuperclass() != null) todo.add(k.getSuperclass());
            todo.addAll(List.of(k.getInterfaces()));
        }
        return false;
    }

    private static boolean skipped(String owner) {
        return owner.startsWith(OWN) || isJdk(owner);
    }

    private static boolean isJdk(String owner) {
        if (owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/")) {
            return true;
        }
        try {
            Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private Optional<ClassNode> lookup(String owner) {
        return library.computeIfAbsent(owner, o -> {
            String entry = o + ".class";
            for (JarFile jar : classpath) {
                JarEntry e = jar.getJarEntry(entry);
                if (e == null) continue;
                try {
                    return Optional.of(read(jar.getInputStream(e)));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            return Optional.empty();
        });
    }

    private static ClassNode read(InputStream in) throws IOException {
        try (in) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
