package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 锅上发生的、她该知道的两件事。
 *
 * <p>出锅那一刻锅里到底出来了什么,只有服务端这一侧看得到;回执说的是"这次 kc_cook 怎么样",
 * 事件说的是"这道菜出来了、成色如何"——两句话不重样。糊了登记成恒为急件:她不知道就会
 * 接着按原计划端菜上桌。
 */
final class KcEvents {

    static final String DONE = "cooking_done";
    static final String RUINED = "cooking_ruined";

    private static NumenApi numen;

    private KcEvents() {}

    /** 登记处那一刻把 API 交过来;发事件要用它。 */
    static void bind(NumenApi api) {
        numen = api;
        api.registerEventType(DONE, false);
        // 糊了 = 她不知道就会做错事,每一条都立刻开一轮
        api.registerEventType(RUINED, true);
    }

    static void done(NumenPlayer cook, Cookware kind, BlockPos pos, ResourceLocation recipe, ItemStack plated) {
        numen.emit(cook, DONE, attrs(kind, pos, recipe),
                Dish.idOf(plated.getItem()) + quality(plated) + " came out of the " + kind.id()
                        + " at " + Cooker.where(pos), false);
    }

    static void ruined(NumenPlayer cook, Cookware kind, BlockPos pos, ResourceLocation recipe,
                       ItemStack plated, String why) {
        numen.emit(cook, RUINED, attrs(kind, pos, recipe),
                "the " + kind.id() + " at " + Cooker.where(pos) + " did not make "
                        + recipe + ": " + why, true);
    }

    private static Map<String, String> attrs(Cookware kind, BlockPos pos, ResourceLocation recipe) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("cookware", kind.id());
        attrs.put("at", Cooker.where(pos));
        attrs.put("recipe", recipe.toString());
        return attrs;
    }

    /** 弹性配方的成品带着品质;固定配方没有这一项,就什么都不说。 */
    private static String quality(ItemStack plated) {
        return QualityUtils.hasQuality(plated)
                ? " (" + QualityUtils.getQuality(plated).getSerializedName() + ")" : "";
    }
}
