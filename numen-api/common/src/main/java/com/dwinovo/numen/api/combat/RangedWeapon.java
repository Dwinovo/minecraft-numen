package com.dwinovo.numen.api.combat;

import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * 一种远程武器的提供者:攻击任务打远处的东西时,向登记表({@link RangedWeapons})里的提供者问"背包里这件你认不认得、此刻能不能射、
 * 弹道什么样、怎么起一发"。弓和弩是 Numen 自己的两个提供者,模组的枪、魔杖是别的提供者,经插件的门登记({@code NumenApi#rangedWeapon})。
 *
 * <p>提供者只管怎么射,<b>不判断能不能打</b>:该不该打这只由权限层按 {@code attack} 裁决,任务提出动作,放行了才会起 {@link Shot}。
 * 线程:服务端主线程。
 */
public interface RangedWeapon {

    /** 此刻能不能射。 */
    enum Readiness {
        /** 现成的:已上弦的弩、上了膛的枪。 */
        READY,
        /** 要先准备(拉弓、上弦、换弹)才射得出:有弹药。 */
        LOADABLE,
        /** 射不了:没有弹药。 */
        EMPTY
    }

    /** 名字,写进日志。 */
    String name();

    /** 认不认得这件物品。 */
    boolean handles(ItemStack stack);

    /** 这件物品在这具身体手里此刻能不能射。 */
    Readiness readiness(NumenPlayer body, ItemStack stack);

    /** 开始瞄之前的弹道(下一发射出去的样子;弓是拉到合适力度的初速)。 */
    Flight flight(NumenPlayer body, ItemStack stack);

    /** 打它的站位。 */
    Handling handling(NumenPlayer body, ItemStack stack);

    /** 起一发:手上已经是这件武器。 */
    Shot begin(NumenPlayer body, ItemStack stack);

    /** 射不出去(连着几次起手都没射出)时回执里的那句话。 */
    String misfireText();

    /** 一发的生命周期,按刻推进:准备、对准、射、确认射出去了。 */
    interface Shot {

        /** 这一发正在用的弹道(弓拉了几刻,初速就是几刻的)。 */
        Flight flight();

        /**
         * 这一刻该不该转向瞄准。拉弓的过程中不用瞄(原版的箭朝哪飞只看松手那一刻),每刻转向还会把脚带偏;提供者只在快出手时说要。
         */
        boolean aboutToRelease();

        /**
         * 推进一刻。
         *
         * @return 这一发结束了(射出去了,或确认哑火了)
         */
        boolean tick(Aim aim, Entity target);

        /** 结束时:射出去了没有。 */
        boolean fired();

        /** 不射了(目标没了、手上换了东西、任务收场):收回半截的动作。 */
        void abort();
    }
}
