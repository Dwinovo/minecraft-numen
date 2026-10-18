package com.dwinovo.numen.api.text;

import com.dwinovo.numen.api.platform.Services;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 替没装我们客户端的玩家把原版客户端认不得的可翻译文字翻好。原版客户端不认识 {@code numen.*} 这些键,收到带键的消息只会显示键本身;
 * 装了我们的客户端的玩家照旧收带键的消息,由他的客户端按他的语言(与资源包)翻。
 *
 * <p>语言表就是客户端用的那份:每个已加载模组 jar 里的 {@code assets/<模组 id>/lang/<语言>.json},不论模组叫什么
 * (Numen API 的由数据生成产出)。英文打底,玩家客户端报上来的语言盖在上面——缺的键退回英文,和客户端自己的规矩一样。
 * 只有原版自己的那份({@link #VANILLA} 命名空间)不读:原版客户端认得,原样留着让它自己翻。
 *
 * <p>线程:任何线程。
 */
public final class ServerLang {

    /** 原版 {@code TranslatableContents} 的占位符写法:{@code %s}、{@code %1$s}、{@code %%}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");
    /** 原版客户端自带语言文件的那个命名空间。 */
    private static final String VANILLA = "minecraft";
    private static final String FALLBACK_LOCALE = "en_us";

    private static final Map<String, ServerLang> BY_LOCALE = new ConcurrentHashMap<>();

    private final Map<String, String> table;

    /** @param table 键到译文,已经叠好(打底的英文加这一种语言) */
    public ServerLang(Map<String, String> table) {
        this.table = Map.copyOf(table);
    }

    /** 这种语言的表(第一次要时从各模组 jar 里读,之后复用)。 */
    public static ServerLang of(String locale) {
        return BY_LOCALE.computeIfAbsent(locale.toLowerCase(Locale.ROOT), ServerLang::read);
    }

    /**
     * 要发给这位玩家的话,按他客户端报上来的语言翻好。没有 Numen 的键就是原对象。
     *
     * <p><b>平台挂点。</b>模组上由 {@code MixinServerCommonPacketListener} 在系统聊天包出站时调,改写包再放行;Paper 上把
     * {@link #translate} 注册成 Adventure 的翻译源({@code GlobalTranslator}),Paper 编码组件时按玩家语言渲染,原版的可翻译组件也走它。
     * 调用方保证:只对没装 Numen 客户端的真玩家调(装了的由他自己的客户端翻);任何线程。
     */
    public static Component forPlayer(ServerPlayer player, Component message) {
        return of(player.clientInformation().language()).translate(message);
    }

    /** 这个键在表里有译文。 */
    public boolean has(String key) {
        return table.containsKey(key);
    }

    /**
     * 这个键的译文改写成 {@link java.text.MessageFormat} 的写法({@code %s}、{@code %2$s} 变成 {@code {0}}、{@code {1}},
     * 单引号与花括号转义),给自带 MessageFormat 渲染的文字库用(Paper 的 Adventure)。占位符的语法与 {@link #translate} 同一套:
     * 不认识的写法与没有这个键一样,返回 {@code null}。
     */
    public String asMessageFormat(String key) {
        String template = table.get(key);
        if (template == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        Matcher placeholder = PLACEHOLDER.matcher(template);
        int next = 0;
        int plain = 0;
        while (placeholder.find()) {
            out.append(escapeForMessageFormat(template.substring(plain, placeholder.start())));
            plain = placeholder.end();
            String kind = placeholder.group(2);
            if ("%".equals(kind)) {
                out.append('%');
            } else if ("s".equals(kind)) {
                int index = placeholder.group(1) != null ? Integer.parseInt(placeholder.group(1)) - 1 : next++;
                if (index < 0) {
                    return null;
                }
                out.append('{').append(index).append('}');
            } else {
                return null;
            }
        }
        return out.append(escapeForMessageFormat(template.substring(plain))).toString();
    }

    private static String escapeForMessageFormat(String text) {
        return text.replace("'", "''").replace("{", "'{'").replace("}", "'}'");
    }

    /**
     * 把话里 Numen 的键换成译文,样式与后续部分原样带着。没有要换的就返回<b>同一个对象</b>,调用方凭它知道有没有变。
     * 译文的占位符不合格式(与原版同样看待)时那一处原样留着带键的样子。
     */
    public Component translate(Component message) {
        List<Component> siblings = new ArrayList<>(message.getSiblings().size());
        boolean changed = false;
        for (Component sibling : message.getSiblings()) {
            Component translated = translate(sibling);
            changed |= translated != sibling;
            siblings.add(translated);
        }
        ComponentContents contents = message.getContents();
        if (contents instanceof TranslatableContents key) {
            Object[] args = key.getArgs();
            Object[] translatedArgs = new Object[args.length];
            boolean argsChanged = false;
            for (int i = 0; i < args.length; i++) {
                translatedArgs[i] = args[i] instanceof Component arg ? translate(arg) : args[i];
                argsChanged |= translatedArgs[i] != args[i];
            }
            String template = table.get(key.getKey());
            if (template != null) {
                MutableComponent text = fill(template, translatedArgs);
                if (text != null) {
                    return finish(text, message, siblings);
                }
            }
            if (argsChanged) {
                return finish(MutableComponent.create(new TranslatableContents(key.getKey(), key.getFallback(),
                        translatedArgs)), message, siblings);
            }
        }
        return changed ? finish(MutableComponent.create(contents), message, siblings) : message;
    }

    private static MutableComponent finish(MutableComponent text, Component original, List<Component> siblings) {
        text.setStyle(original.getStyle());
        siblings.forEach(text::append);
        return text;
    }

    /** 往译文的占位符里填参数;格式不对是 {@code null}。 */
    private static MutableComponent fill(String template, Object[] args) {
        MutableComponent out = Component.empty();
        Matcher placeholder = PLACEHOLDER.matcher(template);
        int next = 0;
        int plain = 0;
        while (placeholder.find()) {
            if (placeholder.start() > plain) {
                out.append(Component.literal(template.substring(plain, placeholder.start())));
            }
            plain = placeholder.end();
            String kind = placeholder.group(2);
            if ("%".equals(kind)) {
                out.append(Component.literal("%"));
                continue;
            }
            if (!"s".equals(kind)) {
                return null;
            }
            int index = placeholder.group(1) != null ? Integer.parseInt(placeholder.group(1)) - 1 : next++;
            if (index < 0 || index >= args.length) {
                return null;
            }
            out.append(args[index] instanceof Component arg ? arg : Component.literal(String.valueOf(args[index])));
        }
        if (plain < template.length()) {
            out.append(Component.literal(template.substring(plain)));
        }
        return out;
    }

    // ---- 从 jar 里读 ----

    private static ServerLang read(String locale) {
        return read(locale, Services.platform().modIds(), Services.platform()::modResource);
    }

    /** @param resource 模组 id 与 jar 内路径到文件;没有是 {@code null} */
    static ServerLang read(String locale, List<String> modIds, BiFunction<String, String, Path> resource) {
        Map<String, String> table = new HashMap<>(load(FALLBACK_LOCALE, modIds, resource));
        if (!locale.equals(FALLBACK_LOCALE)) {
            table.putAll(load(locale, modIds, resource));
        }
        return new ServerLang(table);
    }

    private static Map<String, String> load(String locale, List<String> modIds,
                                            BiFunction<String, String, Path> resource) {
        Map<String, String> out = new HashMap<>();
        for (String modId : modIds) {
            if (modId.equals(VANILLA)) {
                continue;
            }
            Path file = resource.apply(modId, "assets/" + modId + "/lang/" + locale + ".json");
            if (file == null) {
                continue;
            }
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, JsonElement> e : JsonParser.parseReader(in).getAsJsonObject().entrySet()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(modId + " 的 " + locale + " 语言文件读不出来", e);
            }
        }
        return out;
    }
}
