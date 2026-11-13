package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.library.EntryId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 她的绑定文件({@code binding.json},在她的家里)的内容:三项库条目({@link Slot})各绑着什么、是谁绑的({@link Bound}),
 * 和她穿的皮肤。这个文件长什么样只有这里知道:她的大脑({@link CompanionLoop})读写它,启动时改指绑定的迁移({@link HostMerge})
 * 也经这里。
 *
 * <pre>{@code
 * {"provider": {"by": "<绑的人>", "id": "my:档案"}, "persona": {...}, "voice": {...}, "skin": "<哈希>-<粗细>"}
 * }</pre>
 * 一项的值只是一个 id 字符串({@code "persona": "my:小焰"})的,是记下"谁绑的"之前写的:绑的人算她的创建者({@link #read}),
 * 下一次落盘就是上面的样子。皮肤不带出处——穿上的是签名缓存里的一份副本,记的是对缓存的引用。
 *
 * <p>不可变:换一项得到新的一份。
 *
 * @param slots 绑着的那几项;没绑的不在表里
 * @param skin  穿着的皮肤({@code SkinRef} 的文字写法);默认皮肤是 {@code null}
 */
public record Bindings(Map<Slot, Bound> slots, String skin) {

    /** 文件名。 */
    public static final String FILE = "binding.json";
    /** 什么都没绑、穿着默认皮肤。 */
    public static final Bindings NONE = new Bindings(Map.of(), null);

    private static final String SKIN = "skin";

    public Bindings {
        EnumMap<Slot, Bound> copy = new EnumMap<>(Slot.class);
        copy.putAll(slots);
        slots = Collections.unmodifiableMap(copy);
        skin = skin == null || skin.isBlank() ? null : skin;
    }

    /** 这一项绑着什么;没绑是 {@code null}。 */
    public Bound bound(Slot slot) {
        return slots.get(slot);
    }

    /** 换一项({@code null} = 不绑)。 */
    public Bindings with(Slot slot, Bound bound) {
        EnumMap<Slot, Bound> next = new EnumMap<>(Slot.class);
        next.putAll(slots);
        if (bound == null) {
            next.remove(slot);
        } else {
            next.put(slot, bound);
        }
        return new Bindings(next, skin);
    }

    /** 换一张皮肤({@code null} = 默认皮肤)。 */
    public Bindings wearing(String ref) {
        return new Bindings(slots, ref);
    }

    /** 这个人绑的是哪几项。 */
    public List<Slot> boundBy(UUID person) {
        List<Slot> out = new ArrayList<>();
        slots.forEach((slot, bound) -> {
            if (bound.by().equals(person)) {
                out.add(slot);
            }
        });
        return out;
    }

    /** 什么都没记:这样的绑定不留文件。 */
    public boolean isEmpty() {
        return slots.isEmpty() && skin == null;
    }

    /**
     * 读文件的内容。读不懂的一项(条目名写坏了、绑的人不是 UUID)当作没绑——它指不到任何东西。
     *
     * @param creator 她的创建者:只有 id 的值算他绑的
     */
    public static Bindings read(JsonObject o, UUID creator) {
        EnumMap<Slot, Bound> slots = new EnumMap<>(Slot.class);
        for (Slot slot : Slot.values()) {
            Bound bound = bound(o.get(slot.wire()), creator);
            if (bound != null) {
                slots.put(slot, bound);
            }
        }
        return new Bindings(slots, o.get(SKIN) instanceof JsonPrimitive p ? p.getAsString() : null);
    }

    private static Bound bound(JsonElement value, UUID creator) {
        if (value instanceof JsonObject o) {
            return Bound.fromJson(o);
        }
        EntryId id = value instanceof JsonPrimitive p ? EntryId.parse(p.getAsString()) : null;
        return id == null ? null : new Bound(creator, id);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        slots.forEach((slot, bound) -> o.add(slot.wire(), bound.toJson()));
        if (skin != null) {
            o.addProperty(SKIN, skin);
        }
        return o;
    }
}
