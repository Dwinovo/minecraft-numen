package com.dwinovo.numen.api.agent.skill;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 一位玩家的技能。技能是 markdown 目录,她按需用 {@code skill} 工具装进来:把"怎么做某件事"的长篇说明从系统提示挪到工具调用的
 * 对话里,只在相关时才出现。
 *
 * <h2>两处来源</h2>
 * <b>她自己的</b>是主人客户端上的库({@link #KIND},每条一个目录:{@code skills/<命名空间>/<技能>/SKILL.md} 加它指着的其余文件);
 * <b>随模组与插件发布的</b>在服务端,她这里只有名字与说明({@link Bundled}),正文与附属文件用到时才向服务端取。同名时她自己的优先
 * (复制一份内置的到自己的再改,就是 Claude Code 里"任何一层都能盖过随包的技能")。玩家可以关掉某条,开关记在他自己的偏好里
 * ({@code Library#setEnabled};随发布的那些记在 {@value #BUNDLED_SPACE} 名字空间下)。系统提示的清单、{@code skill} 工具、斜杠命令读的
 * 都是这一份。
 *
 * <h2>目录里的样子</h2>
 * {@code SKILL.md} 必须有,它的 {@code name:}/{@code description:} 开头是模型看到的东西;目录里可以带正文指着的其他材料。
 * 目录名只是版面,技能的名字是 {@code name:} 这一项。
 * <pre>
 * &lt;名字空间&gt;/&lt;技能&gt;/
 *   SKILL.md       (必须——YAML 开头加 markdown 正文)
 *   references/    (可选)
 * </pre>
 *
 * <h2>送进系统提示的清单</h2>
 * {@link #formatXml()} 每轮生成一块:
 * <pre>
 * Skills are reference knowledge: how the game and the mods in this world work, their numbers and ids.
 * Use the skill tool to read a skill when what you are about to do touches its topic.
 * &lt;available_skills&gt;
 *   &lt;skill&gt;&lt;name&gt;…&lt;/name&gt;&lt;description&gt;…&lt;/description&gt;&lt;/skill&gt;
 * &lt;/available_skills&gt;
 * </pre>
 *
 * <p>线程:读任何线程。她自己的技能从库的内存表里解析出来,库变了(复制、重扫)下一次读自动重新解析;手改磁盘上的文件要重扫。
 */
public final class SkillRegistry {

    /** 每个技能目录里必须有的文件。 */
    public static final String SKILL_FILENAME = "SKILL.md";

    /** 技能这种库:一条一个目录,里面有 {@value #SKILL_FILENAME}。 */
    public static final LibraryKind KIND = LibraryKind.directories("skills", SKILL_FILENAME);

    /** 随发布的技能在开关表里的名字空间。 */
    public static final String BUNDLED_SPACE = "bundled";

    /** 随模组与插件发布的技能,在服务端:名字与说明已知,正文与附属文件用到时才取。 */
    public interface Bundled {

        /** 有哪几份:名字与说明({@link SkillInfo#content} 是 null)。 */
        List<SkillInfo> list();

        /**
         * 一份的正文;在服务端,所以是答复来了才有。没有这一份,答复是 {@link IllegalArgumentException}。
         */
        CompletableFuture<SkillInfo> get(String name);

        /**
         * 一份的一个附属文件。没有这份技能、路径越界、文件缺失或超限,答复是 {@link IllegalArgumentException}。
         */
        CompletableFuture<String> file(String name, String relPath);

        /** 一份的附属文件清单。 */
        CompletableFuture<List<String>> files(String name);

        /** 一份都没有。 */
        Bundled NONE = new Bundled() {
            @Override
            public List<SkillInfo> list() {
                return List.of();
            }

            @Override
            public CompletableFuture<SkillInfo> get(String name) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("unknown skill: " + name));
            }

            @Override
            public CompletableFuture<String> file(String name, String relPath) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("unknown skill: " + name));
            }

            @Override
            public CompletableFuture<List<String>> files(String name) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("unknown skill: " + name));
            }
        };
    }

    private final Library library;
    private final Bundled bundled;

    private long parsedAt = -1;
    private int bundledStamp = -1;
    private Map<String, Parsed> skills = Map.of();

    /** 一个技能连同它在开关表里的名字、是不是随发布的。 */
    private record Parsed(SkillInfo info, EntryId id, boolean shipped) {}

    private SkillRegistry(Library library, Bundled bundled) {
        this.library = library;
        this.bundled = bundled;
    }

    /**
     * 一位玩家的技能。
     *
     * @param library 她自己的技能库
     * @param bundled 随模组与插件发布的技能
     */
    public static SkillRegistry of(Library library, Bundled bundled) {
        return new SkillRegistry(library, bundled);
    }

    /** 只有她自己的技能(单测、评测)。 */
    public static SkillRegistry at(Library library) {
        return new SkillRegistry(library, Bundled.NONE);
    }

    /** 库或随发布的清单变了就重新解析;解析不了的一条记一笔并跳过。 */
    private synchronized Map<String, Parsed> skills() {
        long now = library.version();
        List<SkillInfo> shipped = bundled.list();
        if (now != parsedAt || shipped.hashCode() != bundledStamp) {
            Map<String, Parsed> next = new LinkedHashMap<>();
            for (SkillInfo info : shipped) {
                next.put(info.name(), new Parsed(info, new EntryId(BUNDLED_SPACE, info.name()), true));
            }
            for (Library.Entry entry : library.entries()) {
                SkillInfo info = read(entry.path());
                if (info != null) {
                    next.put(info.name(), new Parsed(info, entry.id(), false));
                }
            }
            skills = next;
            parsedAt = now;
            bundledStamp = shipped.hashCode();
        }
        return skills;
    }

    /** 已知的技能,不论开关:"有没有这个技能"问这里。随发布的技能正文是 null,要读正文用 {@link #get}。 */
    public Optional<SkillInfo> find(String name) {
        Parsed parsed = skills().get(name);
        return Optional.ofNullable(parsed == null ? null : parsed.info());
    }

    /** 开着的技能(随发布的正文是 null):对 skill 工具与技能命令,关掉的技能不可见。"能不能用"问这里。 */
    public Optional<SkillInfo> get(String name) {
        Parsed parsed = skills().get(name);
        return parsed == null || !library.enabled(parsed.id()) ? Optional.empty() : Optional.of(parsed.info());
    }

    /** 开着的技能,连正文:随发布的在服务端,答复来了才有。没有这一份或被关着,答复是 {@link IllegalArgumentException}。 */
    public CompletableFuture<SkillInfo> read(String name) {
        Parsed parsed = skills().get(name);
        if (parsed == null || !library.enabled(parsed.id())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown skill: " + name));
        }
        return parsed.shipped() ? bundled.get(name) : CompletableFuture.completedFuture(parsed.info());
    }

    /** 所有已知的技能,含关掉的。 */
    public Collection<SkillInfo> all() {
        return skills().values().stream().map(Parsed::info).toList();
    }

    /**
     * 她能用的技能:有描述的(模型对一份什么都不知道的技能挑不出来,同 opencode),玩家没关掉的。系统提示的
     * {@code <available_skills>} 索引与点错名字时列出的"有哪些"都是这一份。
     */
    public List<SkillInfo> available() {
        return skills().values().stream()
                .filter(p -> p.info().description() != null && !p.info().description().isBlank())
                .filter(p -> library.enabled(p.id()))
                .map(Parsed::info)
                .toList();
    }

    public int size() {
        return skills().size();
    }

    /** 这个技能是不是被玩家关着。 */
    public boolean isDisabled(String name) {
        Parsed parsed = skills().get(name);
        return parsed != null && !library.enabled(parsed.id());
    }

    /**
     * 每轮追加进系统提示的那块 XML。没有带描述的技能时返回空串,调用方可以无条件追加。
     * 照 opencode {@code Skill.fmt(verbose:true)} 原样,去掉 {@code <location>}(那是 opencode 给 IDE 显示用的,模型不需要)。
     */
    public String formatXml() {
        List<SkillInfo> described = available();
        if (described.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(256);
        sb.append("Skills are reference knowledge: how the game and the mods in this world work, their numbers and ids.\n");
        sb.append("Use the ").append(com.dwinovo.numen.api.agent.tool.SkillTool.NAME)
                .append(" tool to read a skill when what you are about to do touches its topic.\n");
        sb.append("<available_skills>\n");
        for (SkillInfo s : described) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(escapeXml(s.name())).append("</name>\n");
            sb.append("    <description>").append(escapeXml(s.description())).append("</description>\n");
            sb.append("  </skill>\n");
        }
        sb.append("</available_skills>");
        return sb.toString();
    }

    /**
     * 三级披露:读取技能目录内的一个附属文件(SKILL.md 正文以相对路径引用,模型按需拉取)。
     *
     * @return 文件文本;技能不存在、路径越界、文件缺失或超限时答复是 IllegalArgumentException
     */
    public CompletableFuture<String> readSupportFile(String skillName, String relPath) {
        Parsed parsed = skills().get(skillName);
        if (parsed == null || !library.enabled(parsed.id())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown skill: " + skillName));
        }
        if (parsed.shipped()) {
            return bundled.file(skillName, relPath);
        }
        try {
            return CompletableFuture.completedFuture(
                    SkillFiles.read(parsed.info().location().getParent(), skillName, relPath));
        } catch (IllegalArgumentException refused) {
            return CompletableFuture.failedFuture(refused);
        }
    }

    /** 读一个技能目录的 {@code SKILL.md};读不了或没有 {@code name:} 记一笔并返回 {@code null}。 */
    public static SkillInfo read(Path skillDir) {
        Path file = skillDir.resolve(SKILL_FILENAME);
        try {
            SkillInfo info = parse(Files.readString(file, StandardCharsets.UTF_8), file.toAbsolutePath());
            if (info == null) {
                Constants.LOG.warn("[numen-skill] {} missing required 'name:' frontmatter; skipping", file);
            }
            return info;
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-skill] failed to parse {}: {}", file, ex.toString());
            return null;
        }
    }

    /**
     * 把 {@code SKILL.md} 的文字解析成 {@link SkillInfo};没有 {@code name:} 就是 {@code null}。
     */
    private static SkillInfo parse(String raw, Path location) {
        SkillMarkdown.Parsed parsed = SkillMarkdown.parse(raw);
        String name = parsed.frontmatter().get("name");
        if (name == null || name.isBlank()) return null;
        String description = parsed.frontmatter().get("description");
        if (description != null && description.isBlank()) description = null;
        return new SkillInfo(name.trim(), description, parsed.content(), location);
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
