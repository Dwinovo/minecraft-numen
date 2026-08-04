package com.dwinovo.numen.client.data;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.event.InboxPolicy;
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
 * <p>从前这些挂在 {@code UiTheme} 上,于是那个类既是调色板又是配置文件读写器,
 * 还存着跟主题毫无关系的"快捷对话提醒"。一个类两个职责,加第三个偏好时就会
 * 继续往里塞——所以拆出来:{@code UiTheme} 只管颜色,这里只管主人选了什么。
 *
 * <p>客户端主线程专用。
 */
public final class ClientPrefs {

    private static Path file;

    private static String theme = "light";
    private static boolean talkHint = true;
    private static int initiative = InboxPolicy.DEFAULT_LEVEL;

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
            if (o.has("initiative")) initiative = InboxPolicy.clampLevel(o.get("initiative").getAsInt());
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
     * 小 = 知道得及时、token 烧得快;大 = 知道得晚、省。见 {@link InboxPolicy}。
     */
    public static int initiativeLevel() {
        return initiative;
    }

    public static void setInitiativeLevel(int level) {
        initiative = InboxPolicy.clampLevel(level);
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
            Files.writeString(file, o.toString());
        } catch (Exception e) {
            Constants.LOG.warn("[numen-prefs] ui.json 写不了,偏好没保存", e);
        }
    }
}
