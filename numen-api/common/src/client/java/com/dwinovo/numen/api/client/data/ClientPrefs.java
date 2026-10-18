package com.dwinovo.numen.api.client.data;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;

import java.nio.file.Path;

/**
 * 主人的客户端偏好——{@code config/numen/client/ui.json}。
 *
 * <pre>
 * { "theme": "light", "talkHint": true, "messageNotices": true, "voiceEnabled": true }
 * </pre>
 *
 * <p>跟 {@code UiTheme} 分开:那个类只管颜色,这里只管主人选了什么。混在一起的话,
 * 它会同时是调色板和配置文件读写器,而"快捷对话提醒"这类跟主题毫无关系的偏好
 * 也会一个接一个往里塞。
 *
 * <p>客户端主线程专用。
 */
public final class ClientPrefs {

    private static JsonDoc doc;

    private static String theme = "light";
    private static boolean talkHint = true;
    private static boolean messageNotices = true;
    private static boolean voiceEnabled = true;
    /** 最近用过的斜杠命令名,最新在前。排序归命令层,这里只负责存。 */
    private static final java.util.List<String> recentCommands = new java.util.ArrayList<>();

    private ClientPrefs() {}

    /** 客户端启动读一次;文件缺失或损坏都退回默认值,不阻断启动。 */
    public static void init(Path numenConfigDir) {
        doc = new JsonDoc(numenConfigDir.resolve("ui.json"), JsonDoc.Format.of(1), NumenIo.inline("numen-prefs"));
        try {
            JsonObject o = doc.read().orElse(null);
            if (o == null) {
                return;
            }
            if (o.has("theme")) theme = o.get("theme").getAsString();
            if (o.has("talkHint")) talkHint = o.get("talkHint").getAsBoolean();
            if (o.has("messageNotices")) messageNotices = o.get("messageNotices").getAsBoolean();
            if (o.has("voiceEnabled")) voiceEnabled = o.get("voiceEnabled").getAsBoolean();
            if (o.has("recentCommands") && o.get("recentCommands").isJsonArray()) {
                for (var el : o.getAsJsonArray("recentCommands")) recentCommands.add(el.getAsString());
            }
        } catch (Exception e) {
            Constants.LOG.warn("[numen-prefs] ui.json 读不了,用默认值", e);
        }
    }

    public static String theme() {
        return theme;
    }

    public static void setTheme(String id) {
        theme = id;
        persist();
    }

    /** 快捷对话提醒:准星指着同伴时浮「按 [键] 对话」。 */
    public static boolean talkHint() {
        return talkHint;
    }

    public static void setTalkHint(boolean enabled) {
        talkHint = enabled;
        persist();
    }

    /** 消息通知:她说了话、主人没在面板里看着那个会话时,右下角弹一张小卡。 */
    public static boolean messageNotices() {
        return messageNotices;
    }

    public static void setMessageNotices(boolean enabled) {
        messageNotices = enabled;
        persist();
    }

    /** 语音总开关:关了,她不论绑没绑声线都不出声(这台电脑自己的设置,不跟着她走)。 */
    public static boolean voiceEnabled() {
        return voiceEnabled;
    }

    public static void setVoiceEnabled(boolean enabled) {
        voiceEnabled = enabled;
        persist();
    }

    /** 最近用过的斜杠命令,最新的在前。补全列表按它排序。 */
    public static java.util.List<String> recentCommands() {
        return java.util.List.copyOf(recentCommands);
    }

    public static void setRecentCommands(java.util.List<String> names) {
        recentCommands.clear();
        if (names != null) recentCommands.addAll(names);
        persist();
    }

    private static void persist() {
        if (doc == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("theme", theme);
        o.addProperty("talkHint", talkHint);
        o.addProperty("messageNotices", messageNotices);
        o.addProperty("voiceEnabled", voiceEnabled);
        com.google.gson.JsonArray recent = new com.google.gson.JsonArray();
        for (String name : recentCommands) recent.add(name);
        o.add("recentCommands", recent);
        doc.write(o);
    }
}
