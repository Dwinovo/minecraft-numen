package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 她的女仆身上发生的、她该知道的三件事:驯服成了、女仆死了、女仆喂了她。
 *
 * <p>驯服是她自己那一下右键的结果,{@code numen.use.entity} 的回执只说手里少了一块蛋糕,这只女仆从此归她是这里说的。死亡是急件:
 * 她不知道就会接着把那只女仆当成还在干活,东西也就一直留在墓碑里。喂食是别人对她身体做的事,和挨打一样要告诉她。
 *
 * <p>类与 {@link #fed} 是 public 的:喂食那一刻由 {@code mixin/TaskFeedOwnerMixin} 报,它的代码并进车万女仆的类里执行。
 */
public final class MaidEvents {

    static final String TAMED = "maid_tamed";
    static final String DIED = "maid_died";
    static final String FED = "maid_fed_you";

    private static NumenApi numen;

    private MaidEvents() {}

    /** 登记处那一刻把 API 交过来;两侧都登记(服务端的发出口靠它挡,主人客户端的队列靠它投递)。 */
    static void bind(NumenApi api) {
        numen = api;
        api.registerEventType(TAMED, false);
        api.registerEventType(DIED, true);
        api.registerEventType(FED, false);
    }

    static void tamed(NumenPlayer her, Entity maid) {
        Map<String, String> attrs = attrs(maid);
        numen.emit(her, TAMED, attrs, "you tamed " + Maids.label(maid) + " at " + Maids.where(maid.blockPosition())
                + "; she is yours now, and TLM counts " + Maids.counted(her) + " maid(s) as yours. `"
                + "tlm.maid.info(" + maid.getId() + ")` shows her.", false);
    }

    static void died(NumenPlayer her, LivingEntity maid, Entity tombstone) {
        Map<String, String> attrs = attrs(maid);
        attrs.put("tombstone", String.valueOf(tombstone.getId()));
        numen.emit(her, DIED, attrs, Maids.label(maid) + " died at " + Maids.where(maid.blockPosition()) + " in "
                + maid.level().dimension().location() + ": " + maid.getCombatTracker().getDeathMessage().getString()
                + ". Her things and her film are in tombstone " + tombstone.getId() + " at "
                + Maids.where(tombstone.blockPosition()) + "; right-click it (`numen.use.entity(" + tombstone.getId()
                + ")`) to take them.", true);
    }

    /**
     * 一只女仆喂了主人一口({@code eaten} 是喂的那一样),吃下去之后调:连同她此刻的饱食度与血量一起报。只报她——女仆的主人
     * 是真玩家时什么都不做。
     */
    public static void fed(Player owner, Item eaten) {
        if (!(owner instanceof NumenPlayer her)) {
            return;
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("item", BuiltInRegistries.ITEM.getKey(eaten).toString());
        numen.emit(her, FED, attrs, "a maid of yours fed you " + BuiltInRegistries.ITEM.getKey(eaten)
                + "; your food is " + her.getFoodData().getFoodLevel() + "/20, health "
                + String.format("%.1f", her.getHealth()) + "/" + String.format("%.1f", her.getMaxHealth()) + ".",
                false);
    }

    private static Map<String, String> attrs(Entity maid) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("maid", String.valueOf(maid.getId()));
        return attrs;
    }
}
