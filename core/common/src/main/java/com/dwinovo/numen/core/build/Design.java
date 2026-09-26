package com.dwinovo.numen.core.build;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.NumenCli;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一份设计:有名字、存盘的一串原语,坐标相对原点 {@code (0,0,0)}。它像一个函数,可以在任何地方、盖任意多次
 * ({@code build at})。
 *
 * <h2>文本就是命令</h2>
 * 设计的正文每行一条原语命令({@code build layer 0 0 0 ### #.# ### --block stone_bricks}),和她当场执行的写法一字不差;
 * 开头几行 {@code #} 注释记着名字、作者、所属主人、创建时间。读与写都过同一个解析器({@link NumenCli#read}):读的时候每一行
 * 按命令树读成原语与参数、画一遍,写不通、画不出来就整份不认,报出是第几行;写的时候先把要写的文本照读的规矩读一遍,
 * 读得回来才写。所以设计文本的语法就是命令的语法,没有第二份。
 *
 * <h2>只存现状</h2>
 * 改了就是新的现状,没有版本。一栋已经盖好的房子由哪些格子组成,是那栋房子自己的记录({@link Built}),不靠设计的旧版推。
 *
 * @param owner     所属主人;只有这位主人的同伴能改、能删。文件里没写主人是 null,谁都改不了,只能照着盖
 * @param ownerName 所属主人的名字,给人看的
 * @param author    写它的那位同伴的名字
 * @param created   创建时间(ISO-8601),给人看的
 * @param steps     每一步的命令行,按顺序
 * @param drawn     按顺序画完的样子,坐标相对原点
 */
public record Design(String name, UUID owner, String ownerName, String author, String created,
                     List<String> steps, Canvas drawn) {

    /** 设计名:小写字母、数字、下划线、连字符,用作文件名,也是建成的房子的名字的前半({@code house#1})。 */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    private static final String HEAD = "# ";
    private static final String OWNER = "owner: ";
    private static final String AUTHOR = "author: ";
    private static final String CREATED = "created: ";
    /** 设计里每一步都是 {@code build} 组的一个原语。 */
    public static final String GROUP = "build";

    /** 合不合设计名的规矩。 */
    public static boolean isName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** 设计名合规就原样返回,否则说清能用什么字。 */
    public static String checkedName(String name) {
        if (!isName(name)) {
            throw new IllegalArgumentException("a design name is lowercase letters, digits, _ and -, starting with a "
                    + "letter or digit, at most 48 long; got \"" + name + "\"");
        }
        return name;
    }

    /** 一份还没有步骤的新设计。 */
    public static Design fresh(String name, UUID owner, String ownerName, String author, String created) {
        return new Design(checkedName(name), owner, ownerName, author, created, List.of(),
                new Canvas(Canvas.Ground.NOTHING));
    }

    /** 同一份设计换一串步骤:每一步照读的规矩读一遍、画一遍,画不出来就不认。 */
    public Design withSteps(List<String> steps) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            labels.add("step " + (i + 1));
        }
        return new Design(name, owner, ownerName, author, created, List.copyOf(steps), drawOf(steps, labels));
    }

    /**
     * 读一行原语,不画:按命令树读成原语与参数,确认是 {@code build} 组的原语。设计文件里的一步、{@code build step} 与
     * {@code build insert} 写来的那一步,都经这里。
     *
     * @throws IllegalArgumentException 读不通,或不是原语
     */
    public static Step step(String line) {
        NumenCli.Reading reading = NumenCli.read(line);
        String[] path = reading.path().split(" ");
        Primitive primitive = path.length == 2 && path[0].equals(GROUP) && reading.args() != null
                ? Primitive.named(path[1]) : null;
        if (primitive == null) {
            throw new IllegalArgumentException("a design step is one build primitive (build set, place, line, layer, "
                    + "cylinder, sphere or copy), not \"" + line + "\"");
        }
        if (reading.args().get(Primitive.Params.INTO) != null) {
            throw new IllegalArgumentException("--into names the design a step goes into; a step inside a design "
                    + "does not carry it");
        }
        return new Step(primitive, reading.args());
    }

    /** 读好的一步:哪个原语、什么参数。 */
    public record Step(Primitive primitive, CommandArgs args) {

        /** 写回一行命令:原语的参数表按声明顺序写,只写这一步给了的。 */
        public String line() {
            return args.write(GROUP + " " + primitive.action, primitive.params());
        }
    }

    /**
     * 这一串步骤按顺序画在一张空的底子上;哪一步读不通、画不出来,报出它是哪一步。
     *
     * @param labels 每一步怎么称呼:文件里是"第几行",改设计时是"第几步"
     */
    private static Canvas drawOf(List<String> steps, List<String> labels) {
        Canvas canvas = new Canvas(Canvas.Ground.NOTHING);
        for (int i = 0; i < steps.size(); i++) {
            try {
                Step step = step(steps.get(i));
                step.primitive().draw(step.args(), canvas);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(labels.get(i) + " (" + steps.get(i) + "): " + e.getMessage(), e);
            }
        }
        return canvas;
    }

    /** 存盘的文本:几行 {@code #} 注释,接着每行一步。 */
    public String text() {
        StringBuilder sb = new StringBuilder(HEAD).append("Numen design ").append(name).append('\n');
        sb.append(HEAD).append(AUTHOR).append(author).append('\n');
        if (owner != null) {
            sb.append(HEAD).append(OWNER).append(owner).append(ownerName.isEmpty() ? "" : " " + ownerName).append('\n');
        }
        sb.append(HEAD).append(CREATED).append(created).append('\n');
        for (String step : steps) {
            sb.append(step).append('\n');
        }
        return sb.toString();
    }

    /**
     * 读一份设计的文本(文件里的,或手改过的):{@code #} 打头的是注释,认得出的几行是主人、作者、创建时间;空行跳过;
     * 其余每行一步。整份读完画完才认;哪一行不行,报出是文件的第几行。
     */
    public static Design parse(String name, String text) {
        UUID owner = null;
        String ownerName = "";
        String author = "";
        String created = "";
        List<String> steps = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                String note = line.substring(1).strip();
                if (note.startsWith(OWNER)) {
                    String[] parts = note.substring(OWNER.length()).strip().split(" ", 2);
                    try {
                        owner = UUID.fromString(parts[0]);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("line " + (i + 1) + ": the owner is a player UUID, got \""
                                + parts[0] + "\"");
                    }
                    ownerName = parts.length > 1 ? parts[1] : "";
                } else if (note.startsWith(AUTHOR)) {
                    author = note.substring(AUTHOR.length()).strip();
                } else if (note.startsWith(CREATED)) {
                    created = note.substring(CREATED.length()).strip();
                }
                continue;
            }
            steps.add(line);
            labels.add("line " + (i + 1));
        }
        return new Design(checkedName(name), owner, ownerName, author, created, List.copyOf(steps),
                drawOf(steps, labels));
    }
}
