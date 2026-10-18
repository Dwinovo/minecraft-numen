package com.dwinovo.numen.api.agent.skill;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 一位玩家的技能。技能是 markdown 目录,她按需用 {@code skill} 工具装进来:把"怎么做某件事"的长篇说明从系统提示挪到工具调用的
 * 对话里,只在相关时才出现。
 *
 * <h2>一个库:内置加我的</h2>
 * 技能是一种库({@link #KIND}),每条是一个目录——{@code skills/<命名空间>/<技能>/SKILL.md} 加它指着的其余文件。玩家看到两组叠在
 * 一起:<b>内置</b>是服务器目录里的(Numen 与各插件随包带来、同步进去的,管理员可改),<b>我的</b>是玩家目录里的;同名时我的优先
 * (复制一份内置的到我的再改,就是 Claude Code 里"任何一层都能盖过随包的技能")。玩家可以关掉某条,开关记在他自己的偏好里。
 * 系统提示的清单、{@code skill} 工具、斜杠命令读的都是这一位玩家的这一份。
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
 *   &lt;skill&gt;
 *     &lt;name&gt;mining_and_ores&lt;/name&gt;
 *     &lt;description&gt;Which pickaxe harvests which block and the height every ore generates at.&lt;/description&gt;
 *   &lt;/skill&gt;
 * &lt;/available_skills&gt;
 * </pre>
 * 照 opencode 的 {@code Skill.fmt(verbose:true)};没有描述的技能故意不列,免得模型盲选。
 *
 * <p>线程:读任何线程。技能从库的内存表里解析出来,库变了(复制、重扫)下一次读自动重新解析;手改磁盘上的文件要 {@code /reload}。
 */
public final class SkillRegistry {

    /** 每个技能目录里必须有的文件。 */
    public static final String SKILL_FILENAME = "SKILL.md";

    /** 技能这种库:一条一个目录,里面有 {@value #SKILL_FILENAME}。 */
    public static final LibraryKind KIND = LibraryKind.directories("skills", SKILL_FILENAME);

    /** 每只同伴的技能库(同一主人的同伴是同一个);由服务端大脑的接线注入,见 {@link #init}。 */
    private static Function<UUID, Library> libraries;

    private final Library library;
    private long parsedAt = -1;
    private Map<String, Parsed> skills = Map.of();

    /** 一个技能连同它在库里的名字(开关按库里的名字记)。 */
    private record Parsed(SkillInfo info, EntryId id) {}

    private SkillRegistry(Library library) {
        this.library = library;
    }

    /** 技能库在哪:服务器给主人的那一份(内置加我的);评测与 GameTest 给这一次运行专用的。 */
    public static void init(Function<UUID, Library> companionLibraries) {
        libraries = companionLibraries;
    }

    /** 这只同伴能用的技能。{@link #init} 之前调用是编程错误。 */
    public static SkillRegistry of(UUID companion) {
        if (libraries == null) {
            throw new IllegalStateException("SkillRegistry.init(...) 还没被调用——服务端该在启动时注入技能库");
        }
        return at(libraries.apply(companion));
    }

    /** 直接给一个技能库。 */
    public static SkillRegistry at(Library library) {
        return new SkillRegistry(library);
    }

    /** 库变了就重新解析;解析不了的一条记一笔并跳过。 */
    private synchronized Map<String, Parsed> skills() {
        long now = library.version();
        if (now != parsedAt) {
            Map<String, Parsed> next = new LinkedHashMap<>();
            for (Library.Entry entry : library.effective()) {
                SkillInfo info = read(entry.path());
                if (info != null) {
                    next.put(info.name(), new Parsed(info, entry.id()));
                }
            }
            skills = next;
            parsedAt = now;
        }
        return skills;
    }

    /** 已知的技能,不论开关:"有没有这个技能"问这里。 */
    public Optional<SkillInfo> find(String name) {
        Parsed parsed = skills().get(name);
        return Optional.ofNullable(parsed == null ? null : parsed.info());
    }

    /** 开着的技能:对 skill 工具与技能命令,关掉的技能不可见。"能不能用"问这里。 */
    public Optional<SkillInfo> get(String name) {
        return find(name).filter(skill -> !isDisabled(name));
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

    /** 附属文件尺寸上限(角色是"给模型看的文本",不是数据仓)。 */
    private static final long SUPPORT_FILE_MAX_BYTES = 256 * 1024;

    /**
     * 三级披露:读取技能目录内的一个附属文件(SKILL.md 正文以相对路径引用,
     * 模型按需拉取)。路径规范化后必须仍在技能目录内——拒绝任何形式的穿越。
     *
     * @return 文件文本;技能不存在、路径越界、文件缺失或超限时抛 IllegalArgumentException
     */
    public String readSupportFile(String skillName, String relPath) {
        SkillInfo info = get(skillName).orElseThrow(() ->
                new IllegalArgumentException("unknown skill: " + skillName));
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("file is required");
        }
        Path dir = info.location().getParent();
        Path target = dir.resolve(relPath).normalize();
        if (!target.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("file must stay inside the skill directory");
        }
        try {
            if (!Files.isRegularFile(target)) {
                throw new IllegalArgumentException("no such file in skill " + skillName + ": " + relPath
                        + "; available: " + String.join(", ", listSupportFiles(skillName)));
            }
            if (Files.size(target) > SUPPORT_FILE_MAX_BYTES) {
                throw new IllegalArgumentException(relPath + " exceeds the "
                        + (SUPPORT_FILE_MAX_BYTES / 1024) + "KB support-file cap");
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + relPath + ": " + e.getMessage(), e);
        }
    }

    /** 技能目录内可引用的附属文件相对路径清单(不含 SKILL.md 本身,字典序)。 */
    public List<String> listSupportFiles(String skillName) {
        SkillInfo info = get(skillName).orElseThrow(() ->
                new IllegalArgumentException("unknown skill: " + skillName));
        Path dir = info.location().getParent();
        List<String> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().equals(SKILL_FILENAME))
                    .forEach(f -> out.add(dir.relativize(f).toString().replace('\\', '/')));
        } catch (IOException e) {
            Constants.LOG.warn("[numen-skill] cannot list support files of {}", skillName, e);
        }
        out.sort(String::compareTo);
        return out;
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
