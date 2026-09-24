package com.dwinovo.numen.client.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 会话分组(Telegram 的 Chat Folders):左栏顶上那排标签。内置三个——全部、私聊、群聊——按会话本身
 * 归类;主人自建的分组是一个名字加一张会话 id 清单,排在内置的后面。
 *
 * <p><b>私聊/群聊的判据不在这里。</b>"就他俩"只有 {@link Conversations#soloOf} 一个来源,调用方问过它,
 * 把答案传进 {@link #includes};这里只管"哪个分组收哪些会话",不另起一条判据。
 *
 * <p>自建分组和选中哪个分组随会话的其它 extra 一起落在 {@code conversations.json} 里(见
 * {@link Conversations}),这个类只负责那几个字段的读写,自己不碰文件。纯 JVM,可单测。
 */
public final class ChatFolders {

    /** 全部会话。 */
    public static final String ALL = "all";
    /** 就他俩的会话。 */
    public static final String SOLO = "solo";
    /** 落过盘的会话(群)。 */
    public static final String GROUP = "group";

    /** 标签条从左到右先是这三个:全部永远在最前(Telegram 的 All Chats)。 */
    private static final List<String> BUILT_IN = List.of(ALL, SOLO, GROUP);

    /** 主人建的一个分组:名字和收进来的会话 id(按勾选的先后)。 */
    public record Folder(String id, String name, List<String> chats) {}

    private final List<Folder> custom = new ArrayList<>();
    private String active = ALL;

    /** 选中的分组;没选过是 {@link #ALL}。 */
    public String active() {
        return active;
    }

    /** 标签条上的分组,按显示顺序:内置三个,再是自建的(按建的先后)。 */
    public List<String> ids() {
        List<String> out = new ArrayList<>(BUILT_IN);
        for (Folder f : custom) out.add(f.id());
        return out;
    }

    /** 自建的那一个;内置的、不认识的是 null。 */
    public Folder get(String id) {
        for (Folder f : custom) {
            if (f.id().equals(id)) return f;
        }
        return null;
    }

    /** 选中一个分组。不认识的 id 不接;返回有没有变(变了才需要落盘)。 */
    public boolean select(String id) {
        if (!ids().contains(id) || id.equals(active)) return false;
        active = id;
        return true;
    }

    /**
     * 这个会话在不在这个分组里。
     *
     * @param solo 它是不是"就他俩"——调用方从 {@link Conversations#soloOf} 取来
     */
    public boolean includes(String folderId, String conversationId, boolean solo) {
        return switch (folderId) {
            case ALL -> true;
            case SOLO -> solo;
            case GROUP -> !solo;
            default -> {
                Folder f = get(folderId);
                yield f != null && f.chats().contains(conversationId);
            }
        };
    }

    // ---- 改 ----

    /** 建一个分组,排在最后。 */
    public Folder create(String name, List<String> chats) {
        Folder f = new Folder("f_" + UUID.randomUUID(), name.strip(), distinct(chats));
        custom.add(f);
        return f;
    }

    /** 改名字和收哪些会话;位置不动。 */
    public boolean edit(String id, String name, List<String> chats) {
        for (int i = 0; i < custom.size(); i++) {
            if (custom.get(i).id().equals(id)) {
                custom.set(i, new Folder(id, name.strip(), distinct(chats)));
                return true;
            }
        }
        return false;
    }

    /** 删一个分组;里面的会话不受影响。删的正是选中的那个,就回到全部(Telegram 同样)。 */
    public boolean delete(String id) {
        if (!custom.removeIf(f -> f.id().equals(id))) return false;
        if (active.equals(id)) active = ALL;
        return true;
    }

    /** 把会话放进这个分组,已经在里面就拿出来(Telegram 行菜单"加入分组"里点一下是切换)。 */
    public boolean toggle(String folderId, String conversationId) {
        Folder f = get(folderId);
        if (f == null) return false;
        List<String> chats = new ArrayList<>(f.chats());
        if (!chats.remove(conversationId)) chats.add(conversationId);
        return edit(folderId, f.name(), chats);
    }

    /** 这个会话没了(解散):从每个自建分组里拿掉。返回有没有动过。 */
    public boolean forget(String conversationId) {
        boolean changed = false;
        for (Folder f : List.copyOf(custom)) {
            if (f.chats().contains(conversationId)) {
                List<String> chats = new ArrayList<>(f.chats());
                chats.remove(conversationId);
                edit(f.id(), f.name(), chats);
                changed = true;
            }
        }
        return changed;
    }

    private static List<String> distinct(List<String> chats) {
        return List.copyOf(new LinkedHashSet<>(chats));
    }

    // ---- 落盘:conversations.json 里的 "folders" 与 "folder" ----

    public void read(JsonObject root) {
        custom.clear();
        active = ALL;
        if (root.has("folders") && root.get("folders").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("folders")) {
                Folder f = readFolder(el);
                if (f != null) custom.add(f);
            }
        }
        if (root.has("folder") && root.get("folder").isJsonPrimitive()) {
            select(root.get("folder").getAsString());
        }
    }

    /** 读一个自建分组;id 或名字读不出来就不要——没名字的标签画不出来。 */
    private static Folder readFolder(JsonElement el) {
        if (!el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        if (!o.has("id") || !o.get("id").isJsonPrimitive() || !o.has("name") || !o.get("name").isJsonPrimitive()) {
            return null;
        }
        String name = o.get("name").getAsString().strip();
        if (name.isEmpty()) return null;
        List<String> chats = new ArrayList<>();
        if (o.has("chats") && o.get("chats").isJsonArray()) {
            for (JsonElement c : o.getAsJsonArray("chats")) {
                if (c.isJsonPrimitive()) chats.add(c.getAsString());
            }
        }
        return new Folder(o.get("id").getAsString(), name, distinct(chats));
    }

    /** 选的是"全部"就不写"folder"——没选过和选了全部是同一回事;没有自建分组就不写"folders"。 */
    public void write(JsonObject root) {
        if (!custom.isEmpty()) {
            JsonArray a = new JsonArray();
            for (Folder f : custom) {
                JsonObject o = new JsonObject();
                o.addProperty("id", f.id());
                o.addProperty("name", f.name());
                JsonArray chats = new JsonArray();
                f.chats().forEach(chats::add);
                o.add("chats", chats);
                a.add(o);
            }
            root.add("folders", a);
        }
        if (!active.equals(ALL)) root.addProperty("folder", active);
    }

    public void clear() {
        custom.clear();
        active = ALL;
    }
}
