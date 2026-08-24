package com.dwinovo.numen.client.data;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.event.EventQueue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 主人的客户端偏好——{@code config/numen/ui.json}。
 *
 * <pre>
 * { "theme": "light", "talkHint": true, "initiative": 3 }
 * </pre>
 *
 * <p>跟 {@code UiTheme} 分开:那个类只管颜色,这里只管主人选了什么。混在一起的话,
 * 它会同时是调色板和配置文件读写器,而"快捷对话提醒"这类跟主题毫无关系的偏好
 * 也会一个接一个往里塞。
 *
 * <p>客户端主线程专用。
 */
public final class ClientPrefs {

    private static Path file;

    private static String theme = "light";
    private static boolean talkHint = true;
    private static int initiative = EventQueue.DEFAULT_LEVEL;
    /** 最近用过的斜杠命令名,最新在前。排序归命令层,这里只负责存。 */
    private static final java.util.List<String> recentCommands = new java.util.ArrayList<>();

    private ClientPrefs() {}

    /** 客户端启动读一次;文件缺失或损坏都退回默认值,不阻断启动。 */
    public static void init(Path numenConfigDir) {
        file = numenConfigDir.resolve("ui.json");
        try {
            if (!Files.exists(file)) {
                return;
            }
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (o.has("theme")) theme = o.get("theme").getAsString();
            if (o.has("talkHint")) talkHint = o.get("talkHint").getAsBoolean();
            if (o.has("initiative")) initiative = EventQueue.clampLevel(o.get("initiative").getAsInt());
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

    /**
     * 主动性档位 1~10:她多久把攒下的世界变化说一次。
     * 小 = 知道得及时、token 烧得快;大 = 知道得晚、省。见 {@link EventQueue}。
     */
    public static int initiativeLevel() {
        return initiative;
    }

    public static void setInitiativeLevel(int level) {
        initiative = EventQueue.clampLevel(level);
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
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("theme", theme);
            o.addProperty("talkHint", talkHint);
            o.addProperty("initiative", initiative);
            com.google.gson.JsonArray recent = new com.google.gson.JsonArray();
            for (String name : recentCommands) recent.add(name);
            o.add("recentCommands", recent);
            Files.writeString(file, o.toString());
        } catch (Exception e) {
            Constants.LOG.warn("[numen-prefs] ui.json 写不了,偏好没保存", e);
        }
    }
}
