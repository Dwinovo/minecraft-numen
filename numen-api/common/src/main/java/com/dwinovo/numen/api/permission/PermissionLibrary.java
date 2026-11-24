package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.permission.Edit;
import com.dwinovo.numen.permission.Preset;
import com.dwinovo.numen.permission.Settings;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.UUID;

/**
 * 库协议里的"权限"一种({@code Libraries.PERMISSION}):主人名下每只同伴的八个档位与每种动作的"不用问"名单。读的是
 * {@link PermissionStore} 里的 {@link Settings},写调的也是它的公开方法(命令改设置调的是同一批);这里不判动作对不对,
 * 档位、动作与预设的词认不出就办不成。
 *
 * <p>谁能读写:调用者只摸得到自己那一份。{@link Ground} 是按发请求的玩家取的,别人的同伴不在它里面;管理员也一样。
 *
 * <h2>整表</h2>
 * JSON 数组,每行一个对象,{@code row} 说是哪种:
 * <ul>
 *   <li>{@code "tier"}:{@code companion}(UUID)、{@code name}(同伴的名字)、{@code verb}、{@code tier}
 *       ({@code allow}/{@code ask}/{@code deny});每只同伴八行,按动作的次序;</li>
 *   <li>{@code "listed"}:{@code companion}、{@code name}、{@code verb}、{@code entry}(名单里的一个名字);
 *       每只同伴每种动作的名单按记下的先后。</li>
 * </ul>
 *
 * <h2>操作</h2>
 * {@link #TIER} 设一个档位({@code companion}、{@code verb}、{@code tier}),{@link #PRESET} 套一个预设
 * ({@code companion}、{@code preset}),{@link #DELETE} 从名单里删一项({@code companion}、{@code verb}、{@code entry})。
 */
public final class PermissionLibrary {

    public static final String TIER = "tier";
    public static final String PRESET = "preset";
    public static final String DELETE = "delete";

    /** 这位主人那一头:他的存档、他名下的同伴。 */
    public interface Ground {

        PermissionStore store();

        /** 主人名下的同伴,UUID 到名字,按名册顺序。 */
        Map<UUID, String> companions();
    }

    /**
     * 一次写的结局。
     *
     * @param problem 办不成的原因;办成为 null
     */
    public record Outcome(String problem) {

        static final Outcome DONE = new Outcome(null);

        static Outcome problem(String problem) {
            return new Outcome(problem);
        }
    }

    private PermissionLibrary() {}

    /** 这位主人此刻的整表。 */
    public static JsonArray view(Ground ground) {
        JsonArray out = new JsonArray();
        ground.companions().forEach((uuid, name) -> {
            Settings settings = ground.store().settingsOf(uuid);
            for (Verb verb : Verb.values()) {
                JsonObject row = row("tier", uuid, name, verb);
                row.addProperty("tier", settings.tier(verb).word());
                out.add(row);
            }
            for (Settings.Entry entry : settings.entries()) {
                JsonObject row = row("listed", uuid, name, entry.verb());
                row.addProperty("entry", entry.name());
                out.add(row);
            }
        });
        return out;
    }

    private static JsonObject row(String kind, UUID companion, String name, Verb verb) {
        JsonObject row = new JsonObject();
        row.addProperty("row", kind);
        row.addProperty("companion", companion.toString());
        row.addProperty("name", name);
        row.addProperty("verb", verb.word());
        return row;
    }

    /** 一次写。整表只读,不经这里。 */
    public static Outcome write(Ground ground, String op, JsonObject body) {
        UUID companion = uuid(text(body, "companion"));
        if (companion == null || !ground.companions().containsKey(companion)) {
            return Outcome.problem("not one of your companions");
        }
        PermissionStore store = ground.store();
        switch (op) {
            case TIER: {
                Verb verb = Verb.byWord(text(body, "verb"));
                Tier tier = Tier.byWord(text(body, "tier"));
                if (verb == null || tier == null) {
                    return Outcome.problem("a tier is allow, ask or deny, for one of the eight verbs");
                }
                store.edit(companion, new Edit.SetTier(verb, tier));
                return Outcome.DONE;
            }
            case PRESET: {
                Preset preset = Preset.byWord(text(body, "preset"));
                if (preset == null) {
                    return Outcome.problem("a preset is observe, bypass or factory");
                }
                store.preset(companion, preset);
                return Outcome.DONE;
            }
            case DELETE: {
                Verb verb = Verb.byWord(text(body, "verb"));
                String entry = text(body, "entry");
                if (verb == null || entry == null) {
                    return Outcome.problem("name a verb and an entry on its list");
                }
                if (!store.settingsOf(companion).listed(verb, entry)) {
                    return Outcome.problem("no such entry on the list of " + verb.word());
                }
                store.edit(companion, new Edit.Remove(verb, entry));
                return Outcome.DONE;
            }
            default:
                return Outcome.problem("unknown operation");
        }
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String text(JsonObject body, String key) {
        JsonElement e = body == null ? null : body.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
