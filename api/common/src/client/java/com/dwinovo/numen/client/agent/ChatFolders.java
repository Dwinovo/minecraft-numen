package com.dwinovo.numen.client.agent;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * 会话分组(Telegram 的 Chat Folders):左栏顶上那排标签。内置三个——全部、私聊、群聊——按会话本身
 * 归类。
 *
 * <p><b>私聊/群聊的判据不在这里。</b>"就他俩"只有 {@link Conversations#soloOf} 一个来源,调用方问过它,
 * 把答案传进 {@link #includes};这里只管"哪个分组收哪一类",不另起一条判据。
 *
 * <p>选中哪个分组随会话的其它 extra 一起落在 {@code conversations.json} 里(见 {@link Conversations}),
 * 这个类只负责那几个字段的读写,自己不碰文件。纯 JVM,可单测。
 */
public final class ChatFolders {

    /** 全部会话。 */
    public static final String ALL = "all";
    /** 就他俩的会话。 */
    public static final String SOLO = "solo";
    /** 落过盘的会话(群)。 */
    public static final String GROUP = "group";

    /** 标签条从左到右的顺序:全部永远在最前(Telegram 的 All Chats)。 */
    private static final List<String> BUILT_IN = List.of(ALL, SOLO, GROUP);

    private String active = ALL;

    /** 选中的分组;没选过是 {@link #ALL}。 */
    public String active() {
        return active;
    }

    /** 标签条上的分组,按显示顺序。 */
    public List<String> ids() {
        return BUILT_IN;
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
    public boolean includes(String folderId, boolean solo) {
        return switch (folderId) {
            case ALL -> true;
            case SOLO -> solo;
            case GROUP -> !solo;
            default -> false;
        };
    }

    // ---- 落盘:conversations.json 里的 "folder" ----

    public void read(JsonObject root) {
        active = ALL;
        if (root.has("folder") && root.get("folder").isJsonPrimitive()) {
            select(root.get("folder").getAsString());
        }
    }

    /** 选的是"全部"就不写——没选过和选了全部是同一回事。 */
    public void write(JsonObject root) {
        if (!active.equals(ALL)) root.addProperty("folder", active);
    }

    public void clear() {
        active = ALL;
    }
}
