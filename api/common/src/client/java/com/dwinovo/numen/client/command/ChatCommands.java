package com.dwinovo.numen.client.command;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.data.ClientPrefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 斜杠命令的顶层入口:合并来源、解析、补全、分发。
 *
 * <h2>它在哪一层</h2>
 * 命令层是<b>发送路径上的一道拦截</b>,不是一种事件:
 * <pre>
 *   主人打字 → 按发送 → 是 / 开头? → 是:在本地跑完,不往下走
 *                                    否:走原来的"主人说话"那条路
 * </pre>
 * 所以这里不碰输入队列——队列是"主人说话"那条路自己的实现细节。
 *
 * <h2>认不出来就报错</h2>
 * 不做"认不出的斜杠开头就当成自然语言发出去"这种兜底。那种兜底会把打错的命令变成
 * 一句话发给模型,而主人以为自己下了个命令;救它又得反过来手写一套拼写猜测。根子上
 * 就不该有这条捷径。
 */
public final class ChatCommands {

    public static final char PREFIX = '/';

    /** 内置命令表(写死的那几条)。成批的命令走 {@link CommandSource}。 */
    private static final Map<String, ChatCommand> BUILTIN = new LinkedHashMap<>();

    private static final List<CommandSource> SOURCES = new ArrayList<>();

    /** "最近用过"记这么多条。够把常用的顶到前面,又不至于让列表变成一份流水账。 */
    private static final int RECENT_CAP = 5;

    static {
        // 内置表自己也是一个来源——顶层于是只认识"来源",不认识两种东西。
        SOURCES.add(loop -> List.copyOf(BUILTIN.values()));
        SOURCES.add(new SkillCommandSource());
        register(new CompactCommand());
        register(new GoalCommand());
        register(new SkillsCommand());
    }

    private ChatCommands() {}

    // ---- 注册 ----

    /** 登记一条内置命令(mod init 期调用)。同名以后来的为准。 */
    public static synchronized void register(ChatCommand command) {
        if (command != null && command.name() != null && !command.name().isBlank()) {
            BUILTIN.put(command.name().toLowerCase(Locale.ROOT), command);
        }
    }

    /** 登记一个命令来源(mod init 期调用)。 */
    public static synchronized void addSource(CommandSource source) {
        if (source != null) {
            SOURCES.add(source);
        }
    }

    // ---- 解析 ----

    /**
     * 这串输入是不是命令。空白之后以 {@code /} 开头就算——认不认识是下一步的事。
     *
     * <p>光一个 {@code /} 也算:主人打了斜杠就是要用命令,这时候该给他看清单,而不是把
     * 一个裸斜杠当聊天发给她。
     */
    public static boolean isCommand(String text) {
        String t = text == null ? "" : text.strip();
        return !t.isEmpty() && t.charAt(0) == PREFIX;
    }

    /** 命令名与参数。{@code args} 已 trim,没有参数时是空串。 */
    public record Parsed(String name, String args) {}

    /** 拆成名字与参数;不是命令返回 {@code null}。 */
    public static Parsed parse(String text) {
        if (!isCommand(text)) {
            return null;
        }
        String body = text.strip().substring(1);
        int sp = firstSpace(body);
        return sp < 0
                ? new Parsed(body, "")
                : new Parsed(body.substring(0, sp), body.substring(sp).strip());
    }

    // ---- 查 ----

    /** 此刻全部可见的命令,按来源顺序、来源内顺序。 */
    public static List<ChatCommand> all(EntityAgentLoop loop) {
        List<ChatCommand> out = new ArrayList<>();
        List<CommandSource> sources;
        synchronized (ChatCommands.class) {
            sources = List.copyOf(SOURCES);
        }
        for (CommandSource source : sources) {
            List<ChatCommand> batch = source.commands(loop);
            if (batch != null) {
                out.addAll(batch);
            }
        }
        return out;
    }

    /** 按名字找(忽略大小写);没有返回 {@code null}。 */
    public static ChatCommand find(EntityAgentLoop loop, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ChatCommand c : all(loop)) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    // ---- 补全 ----

    /**
     * 输入框里这串文字对应的补全候选。
     *
     * <p>三种局面:还在打命令名(前缀过滤命令表)、命令名已经打完并跟了空格(转交给
     * 那条命令补参数)、根本不是命令(空)。
     */
    public static List<Completion> complete(EntityAgentLoop loop, String text) {
        if (!isCommand(text)) {
            return List.of();
        }
        // 只去掉左边的空白:右边那个空格是"命令名打完了"的唯一信号,strip 掉就分不清
        // "/build" 和 "/build " 了。
        String body = text.stripLeading().substring(1);
        int sp = firstSpace(body);
        if (sp < 0) {
            return commandCompletions(loop, body);
        }
        ChatCommand command = find(loop, body.substring(0, sp));
        return command == null
                ? List.of()
                : command.completeArgs(loop, body.substring(sp).stripLeading());
    }

    private static List<Completion> commandCompletions(EntityAgentLoop loop, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<ChatCommand> matched = new ArrayList<>();
        for (ChatCommand c : all(loop)) {
            if (c.name().toLowerCase(Locale.ROOT).startsWith(p)) {
                matched.add(c);
            }
        }
        // 最近用过的顶到前面。排序是稳定的,所以没用过的保持来源顺序不乱。
        // 这也让"打个 / 直接回车"落在主人刚用过的那条上,而不是碰巧排第一的那条。
        List<String> recent = ClientPrefs.recentCommands();
        matched.sort(java.util.Comparator.comparingInt(c -> recentRank(recent, c.name())));

        List<Completion> out = new ArrayList<>();
        for (ChatCommand c : matched) {
            String hint = c.argHint();
            String why = c.unavailable(loop);
            out.add(new Completion(
                    PREFIX + c.name() + (hint == null ? "" : " "),
                    PREFIX + c.name() + (hint == null ? "" : " " + hint),
                    why != null ? why : c.description(),
                    why == null,
                    c.touchesContext()));
        }
        return out;
    }

    // ---- 分发 ----

    /**
     * 这串输入要打开的面板;不是面板命令(或命令不存在/此刻不可用)返回 {@code null}。
     *
     * <p>打开面板<b>就是</b>这条命令的执行,所以这里记一笔"最近用过"。
     */
    public static com.dwinovo.numen.client.ui.widget.SelectPanel.Page pageFor(
            EntityAgentLoop loop, String text) {
        Parsed p = parse(text);
        if (p == null || p.name().isEmpty()) {
            return null;
        }
        ChatCommand command = find(loop, p.name());
        if (!(command instanceof PageCommand page) || command.unavailable(loop) != null) {
            return null;
        }
        remember(command.name());
        return page.page(loop);
    }

    /**
     * 跑一条命令。<b>只在 {@link #isCommand} 为真时调用。</b>
     *
     * @return 给主人看的话;{@code null} = 这条命令不吭声
     */
    public static String dispatch(EntityAgentLoop loop, String text) {
        Parsed p = parse(text);
        if (p == null || p.name().isEmpty()) {
            return "输入 " + PREFIX + " 看看有哪些命令。";
        }
        ChatCommand command = find(loop, p.name());
        if (command == null) {
            return "没有 " + PREFIX + p.name() + " 这条命令。输入 " + PREFIX + " 看看有哪些。";
        }
        String why = command.unavailable(loop);
        if (why != null) {
            return why;
        }
        remember(command.name());
        try {
            return command.run(loop, p.args());
        } catch (RuntimeException ex) {
            // 不是给外部输入兜底,是防自己人的失误:命令实现抛出来的话,静默失败比多一行
            // 日志难查得多,而主人还会以为命令生效了。
            Constants.LOG.warn("[numen-cmd] {}{} 抛了异常", PREFIX, p.name(), ex);
            return PREFIX + p.name() + " 出错了:" + ex;
        }
    }

    // ---- 最近用过 ----

    /** 记一笔"刚用过"。最新在前、去重、只留 {@value #RECENT_CAP} 条。 */
    public static void remember(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        List<String> recent = new ArrayList<>(ClientPrefs.recentCommands());
        recent.removeIf(n -> n.equalsIgnoreCase(name));
        recent.add(0, name);
        while (recent.size() > RECENT_CAP) {
            recent.remove(recent.size() - 1);
        }
        ClientPrefs.setRecentCommands(recent);
    }

    /** 在"最近用过"里的位次;没用过排到最后。 */
    private static int recentRank(List<String> recent, String name) {
        for (int i = 0; i < recent.size(); i++) {
            if (recent.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** 第一个空白字符的位置;没有返回 -1。 */
    private static int firstSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
