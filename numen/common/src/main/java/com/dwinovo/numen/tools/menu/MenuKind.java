package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.Action;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 一种界面:怎么按它的种类读它({@link #read}),和它能做的几件事——选一项、改名、翻页、开关一格、把东西拿出来。能做的事是下面几个小接口,
 * 一种界面实现它有的那几个;{@code numen.gui} 的函数在 {@link MenuKinds} 里按开着的界面找到它的种类、问它有没有这件事,没有就当场说这个界面能做什么。
 *
 * <p>做一件事的样子是一样的:先把原版客户端会发的那个包交给原版({@code ClientPackets}),再读回来界面真的变了没有。包的校验全是原版做的
 * (附魔的等级与青金石、交易的材料与次数、信标要付款物品、界面要 {@code stillValid}),被拒了原版什么也不说,所以读回来不对就抛失败,
 * 说读到的事实(要多少、有多少),不另判能不能。参数本身不对(编号超出范围、写错类型)是{@code bad_argument},在发包之前就抛。
 *
 * @param <M> 这种界面的菜单类
 */
public interface MenuKind<M extends AbstractContainerMenu> {

    /** 这种界面的菜单类;开着的界面是它的实例就是这种界面。 */
    Class<M> menu();

    /** 这种界面叫什么,说给模型听:{@code an enchanting table}。 */
    String name();

    /** 按它的种类读它,选项带编号(从 1 起)。 */
    Screen read(M menu, NumenPlayer her);

    /**
     * 在这种界面里做事会动到的那一件世界里的东西,交权限层裁决;只动她自己的东西(附魔台里放的物品、铁砧里的名字)的界面是 null。
     * 动手的那一刻才问,所以这里只说是什么。
     */
    default Action gate(M menu, NumenPlayer her) {
        return null;
    }

    /**
     * 在这种界面里做事会改动右键打开它的那一格方块(讲台翻页、信标定效果、合成器开关)。为真时 {@link #gate} 给不出那一格(界面来历
     * 不明)就问主人,不放行。
     */
    default boolean changesBlock() {
        return false;
    }

    /** 选一项:附魔台、切石机、织布机、交易、信标。 */
    interface Chooses<M extends AbstractContainerMenu> extends MenuKind<M> {

        /**
         * @param first  第一项;信标是主效果
         * @param second 第二项;信标的副效果,别的界面没有
         * @return 做了什么,一句话
         * @throws com.dwinovo.numen.script.ApiError 参数不对,或原版没让做
         */
        String choose(M menu, NumenPlayer her, Pick first, Pick second);
    }

    /** 改名:铁砧。 */
    interface Renames<M extends AbstractContainerMenu> extends MenuKind<M> {

        String rename(M menu, NumenPlayer her, String name);
    }

    /** 翻页:讲台。 */
    interface Pages<M extends AbstractContainerMenu> extends MenuKind<M> {

        /** @param page 第几页,从 1 起 */
        String page(M menu, NumenPlayer her, int page);
    }

    /** 开关一格:合成器。 */
    interface Toggles<M extends AbstractContainerMenu> extends MenuKind<M> {

        /** @param slot 界面里的格号,和 {@code numen.gui.view} 列的一样 */
        String toggle(M menu, NumenPlayer her, int slot);
    }

    /**
     * 把格子里的东西拿出来不是 Shift 点它:讲台里的书靠一个钮取下来。{@code numen.gui.take} 与 {@code numen.gui.quick} 拿容器里的东西
     * 前先问界面自己的拿法。
     */
    interface Takes<M extends AbstractContainerMenu> extends MenuKind<M> {

        /** @return 这一格的东西归界面自己拿,已经交给原版了;不归它管是 false,照常 Shift 点 */
        boolean takeOut(M menu, NumenPlayer her, int slot);
    }
}
