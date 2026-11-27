package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.script.ScriptType;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 点名的她带着的一样东西:物品 id,或背包清单交回的一项(一个 Stack,带 {@code item} 与 {@code kind})。只说了 id 是
 * 这种物品的哪一叠都行(取哪一叠见 {@link Stacks});给了一项就是那一种,那一种的叠挪到哪一格都认得。
 *
 * <p>落到 {@link Stacks.Which} 在收它的函数入口做({@link #in}),等主人点头、走路之前:她已经没有那一种了当场就说,往后只带着
 * Which 走。
 *
 * @param item 物品
 * @param kind 那一项的记号({@link Stacks#code});只说了物品为 null
 */
public record StackRef(Item item, String kind) {

    /** 只说了物品。 */
    public static StackRef of(Item item) {
        return new StackRef(item, null);
    }

    /**
     * 它在这个背包里要的是哪一种:只说了物品是哪一叠都行,给了一项是背包里记号相同的那一种。
     *
     * @throws ApiError {@link ErrorKind#NOT_FOUND}:她已经没有那一种了,这一项过时了
     */
    public Stacks.Which in(Inventory inventory, HolderLookup.Provider registries) {
        if (kind == null) {
            return Stacks.Which.of(item);
        }
        ItemStack there = Stacks.carried(inventory, item, kind, registries).orElseThrow(() -> new ApiError(ErrorKind.NOT_FOUND,
                "you no longer carry the kind of " + BuiltInRegistries.ITEM.getKey(item) + " this entry of "
                        + "the inventory listing names. Read the listing again and pass the entry it returns", null));
        return Stacks.Which.exactly(there);
    }

    /** 物品 id、带 {@code item} 的表(一个 Stack)读成它;写回去只说了物品的是 id,给了一项的是带 {@code item} 与 {@code kind} 的表。 */
    static final Codec<StackRef> CODEC = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.union(ScriptType.STRING, new ScriptType.Named("Stack"));
        }

        @Override
        public StackRef decode(Object value) {
            if (value instanceof String) {
                return of(Builtin.ITEM.decode(value));
            }
            Map<String, Object> entry = LuaCodecs.table(value);
            if (entry == null || !entry.containsKey("item")) {
                throw new BadValue("expected an item you carry: its id (\"minecraft:shulker_box\"), or one entry of "
                        + "the inventory listing to name that exact kind; got " + BadValue.given(value));
            }
            Item item;
            try {
                item = Builtin.ITEM.decode(entry.get("item"));
            } catch (BadValue bad) {
                throw bad.in("field 'item'");
            }
            Object kind = entry.get("kind");
            try {
                return new StackRef(item, kind == null ? null : Builtin.STRING.decode(kind));
            } catch (BadValue bad) {
                throw bad.in("field 'kind'");
            }
        }

        @Override
        public Object encode(StackRef value) {
            Object id = Builtin.ITEM.encode(value.item);
            if (value.kind == null) {
                return id;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("item", id);
            out.put("kind", value.kind);
            return out;
        }
    };
}
