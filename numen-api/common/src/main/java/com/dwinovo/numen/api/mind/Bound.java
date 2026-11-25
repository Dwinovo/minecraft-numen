package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.library.EntryId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Objects;
import java.util.UUID;

/**
 * 她的一项绑定({@link Slot})的值:谁绑的,绑的是哪一条。条目在<b>绑的那个人</b>看到的库里解析(服务器提供的加他的"我的"),
 * 模型档案用他的钥匙串、服主 key 的限额记在他名下——她的主人不止一位时,这三件事跟着绑的人走,不跟着创建者
 * (见 {@code docs/adr/runtime/0006-keepers-ownership-use-provenance.md})。绑定文件、循环、{@link Mind}、人设解析、推给客户端的状态
 * 传的都是这一个值。
 *
 * @param by 绑的人
 * @param id 库条目的名字({@code 命名空间:名字})
 */
public record Bound(UUID by, EntryId id) {

    private static final String BY = "by";
    private static final String ID = "id";

    public Bound {
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(id, "id");
    }

    /** 同一个人绑的另一条:库条目改了名,绑定跟着改指。 */
    public Bound to(EntryId next) {
        return new Bound(by, next);
    }

    /** 落盘和上线的样子:{@code {"by": 绑的人的 UUID, "id": "命名空间:名字"}}。 */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty(BY, by.toString());
        o.addProperty(ID, id.toString());
        return o;
    }

    /** 读 {@link #toJson} 写的;缺项或写坏了是 {@code null}。 */
    public static Bound fromJson(JsonObject o) {
        UUID by = uuid(o.get(BY));
        EntryId id = o.get(ID) instanceof JsonPrimitive p ? EntryId.parse(p.getAsString()) : null;
        return by == null || id == null ? null : new Bound(by, id);
    }

    private static UUID uuid(JsonElement e) {
        if (!(e instanceof JsonPrimitive p)) {
            return null;
        }
        try {
            return UUID.fromString(p.getAsString());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
