package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.IToolSpec;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 渐进披露的<b>格式所有者</b>:目录怎么写、展开块怎么渲染、怎么从对话里读回
 * "哪些工具已经展开"——三件事都在这一个类里,因为它们是同一份格式的两头。
 * 拆开放的话,改了渲染忘了改解析,闸就会在无人察觉的情况下永远放行。
 *
 * <h2>为什么要有这套东西</h2>
 * 常驻工具(见 {@link NumenTool.Residency})的完整定义每轮都随请求发出;其余的只在
 * 系统提示里留一行摘要,模型要用时调 {@code find_tools} 取回完整定义。省的是每一轮
 * 的输入,不是一次性的。
 *
 * <h2>展开状态从哪儿看</h2>
 * <b>从对话记录里推导,不另记一份。</b> 判据只有一条:schema 在不在上下文里——而
 * {@code find_tools} 的那条工具结果还在不在,对话自己就是答案。压缩把它总结掉了,
 * {@link #expandedIn} 自然就不再认这个名字,模型也确实看不见那份 schema 了,重新
 * 取一次即可。副本集合会在压缩后骗人:它还记着"展开过",而模型手里已经没有参数
 * 定义,于是照着记忆瞎填。
 */
public final class ToolDisclosure {

    /** 展开块的首行标记。渲染与解析共用这一个常量——格式只有一个主人。 */
    static final String OPEN_PREFIX = "<functions expanded=\"";
    private static final String OPEN_SUFFIX = "\">";
    private static final String CLOSE = "</functions>";

    /** 目录里一行摘要的长度上限——超了截断加省略号,目录是索引不是文档。 */
    static final int SUMMARY_MAX = 96;

    private static final Gson GSON = new Gson();

    private ToolDisclosure() {}

    /**
     * 渲染展开块——{@code find_tools} 的返回值。首行把展开的名字列在
     * {@code expanded} 属性里,{@link #expandedIn} 只认这一行,不去解析下面的 JSON:
     * 解析自己吐出去的 JSON 结构,格式一动就断。
     */
    public static String render(Collection<? extends IToolSpec> tools) {
        List<String> names = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (IToolSpec t : tools) {
            names.add(t.name());
            JsonObject fn = new JsonObject();
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            fn.add("parameters", GSON.toJsonTree(t.parameterSchema()));
            body.append("<function>").append(GSON.toJson(fn)).append("</function>\n");
        }
        return OPEN_PREFIX + String.join(",", names) + OPEN_SUFFIX + "\n" + body + CLOSE;
    }

    /**
     * 从对话记录里推导已展开的工具名。扫 {@code role=tool} 的消息内容,认首行标记。
     *
     * @param conversation 本次请求实际发出去的消息(不是当下的对话)——闸该按
     *                     <b>模型看见了什么</b>判,而不是按之后又长出了什么
     */
    public static Set<String> expandedIn(Collection<ConvoState.Msg> conversation) {
        Set<String> out = new LinkedHashSet<>();
        if (conversation == null) return out;
        for (ConvoState.Msg msg : conversation) {
            // 只认工具结果:展开块是 find_tools 回的东西,出现在别处就不是凭据
            if (msg instanceof ConvoState.Msg.Tool t && t.content() != null) {
                collectNames(t.content(), out);
            }
        }
        return out;
    }

    /** 一段文本里所有展开块标记的名字。同一条消息里可能有多个块。 */
    static void collectNames(String text, Set<String> out) {
        int from = 0;
        while (true) {
            int open = text.indexOf(OPEN_PREFIX, from);
            if (open < 0) return;
            int start = open + OPEN_PREFIX.length();
            int end = text.indexOf(OPEN_SUFFIX, start);
            if (end < 0) return;
            for (String raw : text.substring(start, end).split(",")) {
                String name = raw.strip();
                if (!name.isEmpty()) out.add(name);
            }
            from = end + OPEN_SUFFIX.length();
        }
    }

    /**
     * 目录——进系统提示的恒定区块,一行一个延迟工具。顺序随传入顺序(注册顺序),
     * 同一组工具两次生成必须逐字节相同,否则系统提示不稳定,前缀缓存白瞎。
     */
    public static String catalog(Collection<? extends IToolSpec> deferred) {
        if (deferred.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<deferred_tools>\n");
        for (IToolSpec t : deferred) {
            sb.append(t.name()).append(" — ").append(summaryOf(t)).append('\n');
        }
        return sb.append("</deferred_tools>").toString();
    }

    /**
     * 目录里那一行摘要:取描述的第一句。<b>不新增字段</b>——摘要与描述是同一件事的
     * 两种长度,分开写必然有一天对不上。
     */
    public static String summaryOf(IToolSpec tool) {
        String d = tool.description();
        if (d == null) return "";
        String flat = d.replaceAll("\\s+", " ").strip();
        int cut = flat.length();
        for (int i = 0; i < flat.length() - 1; i++) {
            char c = flat.charAt(i);
            if ((c == '.' || c == '。' || c == ';' || c == '；') && flat.charAt(i + 1) == ' ') {
                cut = i + 1;
                break;
            }
            if (c == '。' || c == '；') {   // 中文标点后面通常不跟空格
                cut = i + 1;
                break;
            }
        }
        String first = flat.substring(0, cut).strip();
        if (first.length() <= SUMMARY_MAX) return first;
        return first.substring(0, SUMMARY_MAX - 1).strip() + "…";
    }

    /**
     * 她调了一个没展开的工具时的回话。<b>先说错在哪,再说怎么办</b>——只报"未知工具"
     * 的话,她会以为名字拼错了,换个写法再试一遍,白费一轮。
     */
    public static String notExpanded(String name) {
        return "工具 " + name + " 的参数定义尚未取回。先调 find_tools(\"select:" + name
                + "\") 拿到它的完整定义,再调用它。";
    }
}
