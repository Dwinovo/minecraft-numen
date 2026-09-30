package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 她的女仆身上发生的、她该知道的两件事:驯服成了、女仆死了。
 *
 * <p>驯服是她自己那一下右键的结果,{@code use entity} 的回执只说手里少了一块蛋糕,这只女仆从此归她是这里说的。死亡是急件:
 * 她不知道就会接着把那只女仆当成还在干活,东西也就一直留在墓碑里。
 */
final class MaidEvents {

    static final String TAMED = "maid_tamed";
    static final String DIED = "maid_died";

    private static NumenApi numen;

    private MaidEvents() {}

    /** 登记处那一刻把 API 交过来;两侧都登记(服务端的发出口靠它挡,主人客户端的队列靠它投递)。 */
    static void bind(NumenApi api) {
        numen = api;
        api.registerEventType(TAMED, false);
        api.registerEventType(DIED, true);
    }

    static void tamed(NumenPlayer her, Entity maid) {
        Map<String, String> attrs = attrs(maid);
        numen.emit(her, TAMED, attrs, "you tamed " + Maids.label(maid) + " at " + Maids.where(maid.blockPosition())
                + "; she is yours now, and TLM counts " + Maids.counted(her) + " maid(s) as yours. `"
                + MaidCommands.line(MaidCommands.MAID) + " " + maid.getId() + "` shows her.", false);
    }

    static void died(NumenPlayer her, LivingEntity maid, Entity tombstone) {
        Map<String, String> attrs = attrs(maid);
        attrs.put("tombstone", String.valueOf(tombstone.getId()));
        numen.emit(her, DIED, attrs, Maids.label(maid) + " died at " + Maids.where(maid.blockPosition()) + " in "
                + maid.level().dimension().location() + ": " + maid.getCombatTracker().getDeathMessage().getString()
                + ". Her things and her film are in tombstone " + tombstone.getId() + " at "
                + Maids.where(tombstone.blockPosition()) + "; right-click it (`use entity right " + tombstone.getId()
                + "`) to take them.", true);
    }

    private static Map<String, String> attrs(Entity maid) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("maid", String.valueOf(maid.getId()));
        return attrs;
    }
}
