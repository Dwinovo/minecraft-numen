package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.task.WorkProfile;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 创造画像专属:凭空取物入背包——原版创造玩家有创造物品栏 GUI,假玩家
 * 没有,这个工具就是那个 GUI 的假体,补齐的是原版创造本来就有的能力
 * (同伴能进创造已过主人的权限门)。生存画像调用吃诚实拒绝,顺带把
 * 模型往采集/合成/交易的正道上引。瞬时动作,不占任务队列。
 */
public final class TakeItemsTool implements NumenTool {

    private static final Gson GSON = new Gson();
    /** 一次最多一背包量级(36 格 × 64)。 */
    private static final int MAX_COUNT = 2304;

    private record Args(String item_id, Integer count) {}

    @Override
    public String name() {
        return "take_items";
    }

    @Override
    public String description() {
        return "CREATIVE MODE ONLY: conjure items directly into your inventory, like a creative player "
                + "pulling from the creative menu. Fails in survival mode — there you must mine, craft, "
                + "loot or trade for items instead. Overflow beyond inventory space is dropped at your feet.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item, e.g. minecraft:diamond.")
                .integer("count", "How many to take (1-" + MAX_COUNT + ").", 1, MAX_COUNT)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        JsonObject out = new JsonObject();
        if (!WorkProfile.of(companion).freeMaterials()) {
            out.addProperty("success", false);
            out.addProperty("message", "survival mode can't conjure items — mine, craft, loot or trade"
                    + " for " + a.item_id() + " instead (take_items works only in creative mode)");
            reply.accept(GSON.toJson(out));
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(a.item_id() == null ? "" : a.item_id());
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR || (id != null && !BuiltInRegistries.ITEM.containsKey(id))) {
            out.addProperty("success", false);
            out.addProperty("message", "unknown item id: " + a.item_id());
            reply.accept(GSON.toJson(out));
            return;
        }
        int want = Math.clamp(a.count() == null ? 1 : a.count(), 1, MAX_COUNT);
        // 按满栈分批塞;背包塞不下的原版 add 会留在栈里,掉在脚下(与描述一致)
        int remaining = want;
        while (remaining > 0) {
            int n = Math.min(remaining, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, n);
            if (!companion.getInventory().add(stack) && !stack.isEmpty()) {
                companion.drop(stack, false);
            }
            remaining -= n;
        }
        out.addProperty("success", true);
        out.addProperty("message", "took " + want + " × " + a.item_id()
                + " (now carrying " + companion.getInventory().countItem(item) + ")");
        reply.accept(GSON.toJson(out));
    }
}
