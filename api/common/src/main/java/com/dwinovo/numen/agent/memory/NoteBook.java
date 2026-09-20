package com.dwinovo.numen.agent.memory;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.skill.SkillMarkdown;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

/**
 * 同伴自己写的札记:一条记忆一个文件,跨重进游戏活着。她想记什么记什么——
 * 这里不替她判断什么值得记,也不替她收割任何东西。
 *
 * <pre>
 * companions/&lt;uuid&gt;/memory/
 *   main-base.md
 *   swamp-impassable.md
 * </pre>
 *
 * <h2>索引是算出来的,不另存一份</h2>
 * 进模型的只有索引({@link #formatXml}),它由各文件 frontmatter 的 {@code description}
 * 现算。另存一个索引文件就是第二个真源:改了正文忘了索引,她读到的就是过时的钩子,
 * 而这种错对不上账——正文和索引都"看起来是对的"。
 *
 * <h2>为什么正文不进上下文</h2>
 * 索引一行就是一条记忆的全部钩子。短记忆(一个坐标、一句话)的 {@code description}
 * 本身就是全部事实,她看一眼就够,永远不会去 {@link #read};长记忆才值得她多花一轮
 * 展开。设计自己会降级,不需要在两种记忆之间做选择。
 *
 * <h2>不做的事</h2>
 * 没有检索、没有淘汰、没有自动抽取、没有拿世界复核。记忆是线索不是事实,过期的那条
 * 她走一趟就知道了,回执会告诉她——这是循环本来就在干的事,不必另起一套机制。
 * 条数到顶不拒绝写入,只在索引上把余量摆给她看,由她自己合并或删。
 *
 * <p>纯 JVM:不碰 Minecraft。落点与游戏天数由客户端在启动时注入(见 {@link #init})。
 */
public final class NoteBook {

    /** 索引上摆给她看的余量分母。到顶不拒写,只提醒——删哪条是她的事,不是我们的。 */
    public static final int SOFT_MAX = 50;

    /** 一条记忆。{@code description} 是索引里那一行,{@code content} 要 recall 才读得到。 */
    public record Note(String name, String description, String type, int day, String content) {}

    /** 每只同伴的记忆目录;由客户端注入,见 {@link #init}。 */
    private static Function<UUID, Path> homes;

    /** 现在是游戏第几天;由客户端注入,见 {@link #init}。 */
    private static IntSupplier today;

    /**
     * 一只同伴一本,认 UUID。<b>必须是同一本</b>:{@code remember} 工具和注入那侧各拿各的实例的话,
     * 工具写完只把自己那份的 {@link #revision} 加一,注入那侧永远看不见变化,索引就再也不重贴了。
     */
    private static final java.util.Map<UUID, NoteBook> OPEN = new java.util.concurrent.ConcurrentHashMap<>();

    private final Path dir;

    /** 写一次加一;注入那侧靠它判断"变过没有",不必比对正文。 */
    private int revision;

    private NoteBook(Path dir) {
        this.dir = dir;
    }

    /**
     * 安家的位置与当前天数——{@code CompanionHome} 独占目录布局的知识,这里只接受它给的落点,
     * 不自己拼 {@code companions/<uuid>/}:布局写在两处,改一处就开始对不上。
     */
    public static void init(Function<UUID, Path> companionMemoryDirs, IntSupplier gameDay) {
        homes = companionMemoryDirs;
        today = gameDay;
        OPEN.clear();   // 换了落点,开着的那些本子指向的是上一处
    }

    /** 这只同伴的札记本,同一只同伴永远是同一本。{@link #init} 之前调用是编程错误。 */
    public static NoteBook of(UUID entityUuid) {
        if (homes == null || today == null) {
            throw new IllegalStateException(
                    "NoteBook.init(...) 还没被调用——loader 的客户端入口该在启动时注入落点与天数");
        }
        return OPEN.computeIfAbsent(entityUuid, uuid -> new NoteBook(homes.apply(uuid)));
    }

    // ---- 读 ----

    /** 全部记忆,按天数倒序(新的在前),同天按名字——顺序稳定,注入的字节才稳定。 */
    public List<Note> index() {
        List<Note> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> list = Files.list(dir)) {
            for (Path p : list.toList()) {
                if (!p.getFileName().toString().endsWith(".md")) {
                    continue;   // 别人放进来的东西,不碰
                }
                Note n = parse(p);
                if (n != null) {
                    out.add(n);
                }
            }
        } catch (IOException e) {
            Constants.LOG.warn("[numen-memory] 札记目录读不了 {}: {}", dir, e.toString());
        }
        out.sort(Comparator.comparingInt(Note::day).reversed().thenComparing(Note::name));
        return out;
    }

    /** 读一条的正文;没有这条则 null。 */
    public Note read(String name) {
        String slug = slug(name);
        if (slug.isEmpty()) {
            return null;
        }
        Path p = dir.resolve(slug + ".md");
        return Files.isRegularFile(p) ? parse(p) : null;
    }

    /**
     * 索引块:进模型的就这些,不含正文。空着就不发——她有没有记忆这件事由系统提示词讲,
     * 不靠一个空块去说。
     */
    public String formatXml() {
        List<Note> notes = index();
        if (notes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<memory count=\"").append(notes.size()).append('/').append(SOFT_MAX).append("\">\n");
        for (Note n : notes) {
            sb.append(n.name()).append(" | D").append(n.day()).append(' ').append(n.type())
                    .append(" | ").append(n.description()).append('\n');
        }
        sb.append("</memory>");
        return sb.toString();
    }

    /** 写过几次:注入那侧用它判断要不要重贴。 */
    public int revision() {
        return revision;
    }

    // ---- 写 ----

    /**
     * 记一条(同名覆盖)。天数由这里盖,不让她自己填——她填的是"第几天"这种相对说法时,
     * 过两周就没人知道那是哪天了。
     *
     * @return 落盘后的这一条
     */
    public Note write(String name, String description, String type, String content) {
        String slug = slug(name);
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("name must contain letters or digits");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required — it is the whole index line");
        }
        // 索引行按定义就是一行:她写成多行的话,换行会把 frontmatter 截断,整条记忆就读不回来了。
        String line = description.strip().replaceAll("\\s*\\R\\s*", " ");
        Note note = new Note(slug, line, type, today.getAsInt(),
                content == null ? "" : content.strip());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(slug + ".md"), render(note), StandardCharsets.UTF_8);
            revision++;
        } catch (IOException e) {
            throw new IllegalStateException("记不下来: " + e, e);
        }
        return note;
    }

    /** 忘掉一条;没有这条返回 false。 */
    public boolean forget(String name) {
        String slug = slug(name);
        if (slug.isEmpty()) {
            return false;
        }
        try {
            if (Files.deleteIfExists(dir.resolve(slug + ".md"))) {
                revision++;
                return true;
            }
        } catch (IOException e) {
            Constants.LOG.warn("[numen-memory] 删不掉 {}: {}", slug, e.toString());
        }
        return false;
    }

    // ---- 文件 ----

    private static String render(Note n) {
        return "---\n"
                + "name: " + n.name() + "\n"
                + "description: " + n.description() + "\n"
                + "type: " + n.type() + "\n"
                + "day: " + n.day() + "\n"
                + "---\n\n"
                + n.content() + "\n";
    }

    /** 读一个文件;缺 description 的读不出索引行,跳过并说清楚是哪个文件。 */
    private static Note parse(Path p) {
        String stem = p.getFileName().toString();
        stem = stem.substring(0, stem.length() - ".md".length());
        try {
            SkillMarkdown.Parsed parsed = SkillMarkdown.parse(
                    Files.readString(p, StandardCharsets.UTF_8));
            String description = parsed.frontmatter().get("description");
            if (description == null || description.isBlank()) {
                Constants.LOG.warn("[numen-memory] {} 没有 description:,不进索引", p);
                return null;
            }
            String type = parsed.frontmatter().getOrDefault("type", "world");
            int day = 0;
            try {
                day = Integer.parseInt(parsed.frontmatter().getOrDefault("day", "0").trim());
            } catch (NumberFormatException notANumber) {
                // 手改过的文件:天数读不出就当第 0 天,排到最后,不影响她读到内容
            }
            return new Note(stem, description.strip(), type, day, parsed.content().strip());
        } catch (IOException e) {
            Constants.LOG.warn("[numen-memory] 读不了 {}: {}", p, e.toString());
            return null;
        }
    }

    /**
     * 名字 → 文件名:只留小写字母、数字和连字符。这同时是<b>唯一</b>一道路径闸——
     * 模型写的名字直接当路径用,{@code ../} 就能把文件落到目录外面去。
     */
    private static String slug(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : name.strip().toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                sb.append(c);
            } else if ((c == '-' || c == '_' || c == ' ') && sb.length() > 0
                    && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() > 64 ? sb.substring(0, 64) : sb.toString();
    }
}
