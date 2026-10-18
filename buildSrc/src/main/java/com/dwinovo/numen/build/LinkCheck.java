package com.dwinovo.numen.build;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 链接检查:成品里对服务端类的每一处方法 / 字段引用与覆写,在真正要跑的 Paper 服务端 jar 里解析得到吗。
 *
 * <p>Paper 改过不少原版方法的签名(例:{@code PlayerList.remove} 从 {@code void} 改成返回 {@code Component}),对着别的版本编出来的
 * 字节码在 Paper 上会 {@code NoSuchMethodError},覆写会悄悄失效;源码对着开发包重编只保证编译那一刻的 Paper 是对的,
 * 开发包之外的字节码(预编译的依赖)、没写 {@code @Override} 的覆写、换了一版 Paper 服务端以后都可能出这种"编得过但不对"。
 * 两类问题:
 * <ol>
 *   <li><b>引用解析不到</b>:指令里的方法 / 字段引用(含方法句柄,lambda 与方法引用走的就是它),属主是原版或 Paper 的服务端类
 *       (原版 jar 或 Paper jar 里有这个类),沿属主的类、父类、接口往上找,在 Paper jar 的世界里找不到;</li>
 *   <li><b>覆写失效</b>:成品里的类有一个方法,在原版里覆写着某个父类型的方法,在 Paper 里却不再覆写任何东西。</li>
 * </ol>
 * 继承链走到谁都不认识的类(不在成品、两个服务端 jar、依赖库、JDK 里)按失败报:判不了的不放行。
 */
public final class LinkCheck {

    /** 一个类的结构:父类、接口、成员(方法 {@code M 名+描述},字段 {@code F 名:描述})。 */
    record Info(String name, String superName, String[] interfaces, Set<String> members) {}

    /** 一层类索引;服务端 jar 的、依赖库的、成品自己的各一层,按顺序查,先到先得。 */
    static final class Classes {
        private final List<Map<String, Info>> layers = new ArrayList<>();

        Classes(List<Map<String, Info>> layers) {
            this.layers.addAll(layers);
        }

        /** 类的结构;谁都不认识是 null。JDK 的类按需从运行时读。 */
        Info get(String name) {
            for (Map<String, Info> layer : layers) {
                Info info = layer.get(name);
                if (info != null) {
                    return info;
                }
            }
            return jdk(name);
        }

        private static final Map<String, Info> JDK = new HashMap<>();

        private static synchronized Info jdk(String name) {
            if (JDK.containsKey(name)) {
                return JDK.get(name);
            }
            Info info = null;
            try (InputStream in = Object.class.getResourceAsStream("/" + name + ".class")) {
                if (in != null) {
                    info = read(in);
                }
            } catch (IOException e) {
                throw new IllegalStateException("读不出 JDK 的类 " + name, e);
            }
            JDK.put(name, info);
            return info;
        }
    }

    private enum Resolution { FOUND, MISSING, UNKNOWN }

    /**
     * 属主 {@code owner} 上的 {@code member}({@code M 名+描述} 或 {@code F 名:描述})沿类、父类、接口往上找得到吗。
     * 链上有谁都不认识的类、又没找到,是 UNKNOWN。
     */
    private static Resolution resolve(Classes classes, String owner, String member) {
        boolean[] unknown = {false};
        return found(classes, owner, member, new HashSet<>(), unknown) ? Resolution.FOUND
                : unknown[0] ? Resolution.UNKNOWN : Resolution.MISSING;
    }

    private static boolean found(Classes classes, String owner, String member, Set<String> seen, boolean[] unknown) {
        if (owner == null || !seen.add(owner)) {
            return false;
        }
        Info info = classes.get(owner);
        if (info == null) {
            unknown[0] = true;
            return false;
        }
        if (info.members().contains(member)) {
            return true;
        }
        if (found(classes, info.superName(), member, seen, unknown)) {
            return true;
        }
        for (String itf : info.interfaces()) {
            if (found(classes, itf, member, seen, unknown)) {
                return true;
            }
        }
        return false;
    }

    /** 这个类往上的继承链里有没有在 {@code roots} 里的类(含自己)。 */
    private static boolean reaches(Classes classes, String name, Map<String, Info> roots) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(name));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!seen.add(next)) {
                continue;
            }
            if (roots.containsKey(next)) {
                return true;
            }
            Info info = classes.get(next);
            if (info != null) {
                if (info.superName() != null) {
                    queue.add(info.superName());
                }
                queue.addAll(Arrays.asList(info.interfaces()));
            }
        }
        return false;
    }

    /**
     * @param product       要检查的成品(jar 里的类)
     * @param libraries     成品依赖的其余类(依赖库、Paper 的 API、另一个插件的 jar),只用来补全继承链
     * @param vanillaServer 原版服务端(Mojang 名)jar:"本来是覆写"的依据
     * @param paperServer   要对着解析的 Paper 服务端 jar
     * @return 每个问题一行,空表是通过
     */
    public static List<String> run(Collection<Path> product, Collection<Path> libraries, Path vanillaServer,
                                   Path paperServer) throws IOException {
        Map<String, Info> own = new HashMap<>();
        List<byte[]> ownBytes = new ArrayList<>();
        for (Path jar : product) {
            forEachClass(jar, (name, bytes) -> {
                own.put(name, read(bytes));
                ownBytes.add(bytes);
            });
        }
        Map<String, Info> libs = new HashMap<>();
        for (Path jar : libraries) {
            forEachClass(jar, (name, bytes) -> libs.putIfAbsent(name, read(bytes)));
        }
        Map<String, Info> vanilla = indexServer(vanillaServer);
        Map<String, Info> paper = indexServer(paperServer);

        Classes inVanilla = new Classes(List.of(own, vanilla, libs));
        Classes inPaper = new Classes(List.of(own, paper, libs));
        // 引用的属主算"服务端类":原版或 Paper 的服务端 jar 里有它,或者成品里的类继承自它们
        Map<String, Info> serverSide = new HashMap<>(vanilla);
        serverSide.putAll(paper);

        TreeSet<String> problems = new TreeSet<>();
        for (byte[] bytes : ownBytes) {
            ClassReader reader = new ClassReader(bytes);
            Info self = own.get(reader.getClassName());
            reader.accept(new Scanner(self, own, serverSide, inVanilla, inPaper, problems), ClassReader.SKIP_FRAMES);
        }
        return new ArrayList<>(problems);
    }

    /** 扫一个成品类:引用在 Paper 里解析得到吗,方法的覆写在 Paper 里还成立吗。 */
    private static final class Scanner extends ClassVisitor {
        private final Info self;
        private final Map<String, Info> own;
        private final Map<String, Info> serverSide;
        private final Classes inVanilla;
        private final Classes inPaper;
        private final Set<String> problems;

        Scanner(Info self, Map<String, Info> own, Map<String, Info> serverSide, Classes inVanilla, Classes inPaper,
                Set<String> problems) {
            super(Opcodes.ASM9);
            this.self = self;
            this.own = own;
            this.serverSide = serverSide;
            this.inVanilla = inVanilla;
            this.inPaper = inPaper;
            this.problems = problems;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0 && !name.startsWith("<")) {
                checkOverride(name, desc);
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                    checkReference(owner, "M " + n + d, owner + "." + n + d);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String n, String d) {
                    checkReference(owner, "F " + n + ":" + d, owner + "." + n);
                }

                @Override
                public void visitInvokeDynamicInsn(String n, String d, Handle bootstrap, Object... args) {
                    for (Object arg : args) {
                        if (arg instanceof Handle handle) {
                            checkHandle(handle);
                        }
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Handle handle) {
                        checkHandle(handle);
                    }
                }

                private void checkHandle(Handle handle) {
                    boolean field = handle.getTag() <= Opcodes.H_PUTSTATIC;
                    String member = field ? "F " + handle.getName() + ":" + handle.getDesc()
                            : "M " + handle.getName() + handle.getDesc();
                    checkReference(handle.getOwner(), member, handle.getOwner() + "." + handle.getName());
                }

                private void checkReference(String owner, String member, String shown) {
                    if (owner.startsWith("[") || !isServerSide(owner)) {
                        return;
                    }
                    switch (resolve(inPaper, owner, member)) {
                        case FOUND -> { }
                        case MISSING -> problems.add("引用解析不到  " + shown + "   <- " + self.name());
                        case UNKNOWN -> problems.add("继承链不完整,判不了  " + shown + "   <- " + self.name());
                    }
                }
            };
        }

        /** 属主是服务端类:两个服务端 jar 里有它,或成品里这个类的继承链上有服务端类。 */
        private boolean isServerSide(String owner) {
            return serverSide.containsKey(owner) || own.containsKey(owner) && reaches(inPaper, owner, serverSide);
        }

        private void checkOverride(String name, String desc) {
            String member = "M " + name + desc;
            List<String> supers = new ArrayList<>(Arrays.asList(self.interfaces()));
            if (self.superName() != null) {
                supers.add(self.superName());
            }
            boolean inOld = false;
            boolean inNew = false;
            for (String parent : supers) {
                inOld |= resolve(inVanilla, parent, member) == Resolution.FOUND;
                inNew |= resolve(inPaper, parent, member) == Resolution.FOUND;
            }
            if (inOld && !inNew) {
                problems.add("覆写失效  " + self.name() + "." + name + desc);
            }
        }
    }

    // ---- 读类与 jar ----

    private static Map<String, Info> indexServer(Path jar) throws IOException {
        Map<String, Info> index = new HashMap<>();
        forEachClass(jar, (name, bytes) -> index.put(name, read(bytes)));
        return index;
    }

    interface ClassSink {
        void accept(String internalName, byte[] bytes) throws IOException;
    }

    /** jar 里的每个类。 */
    private static void forEachClass(Path jar, ClassSink sink) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        sink.accept(name.substring(0, name.length() - ".class".length()), in.readAllBytes());
                    }
                }
            }
        }
    }

    private static Info read(byte[] bytes) {
        return read(new ClassReader(bytes));
    }

    private static Info read(InputStream in) throws IOException {
        return read(new ClassReader(in));
    }

    private static Info read(ClassReader reader) {
        Set<String> members = new HashSet<>();
        Info[] out = new Info[1];
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            String name;
            String superName;
            String[] interfaces;

            @Override
            public void visit(int version, int access, String n, String signature, String sup, String[] itf) {
                name = n;
                superName = sup;
                interfaces = itf == null ? new String[0] : itf;
            }

            @Override
            public MethodVisitor visitMethod(int access, String n, String desc, String signature, String[] exceptions) {
                members.add("M " + n + desc);
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String n, String desc, String signature, Object value) {
                members.add("F " + n + ":" + desc);
                return null;
            }

            @Override
            public void visitEnd() {
                out[0] = new Info(name, superName, interfaces, members);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out[0];
    }
}
