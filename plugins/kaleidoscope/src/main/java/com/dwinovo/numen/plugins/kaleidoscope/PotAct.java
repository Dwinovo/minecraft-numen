package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.entity.NumenPlayer;

/**
 * {@code kaleidoscope.pot} 在锅上的一步。一步一个函数,先做哪一步由 Lua 模块 {@code kaleidoscope.pot.cook} 排;每一步动手之前都过
 * 权限层({@link PotActTask})。
 */
enum PotAct {
    /** 倒油(炒锅)。 */
    OIL("oil", false),
    /** 放汤底(汤锅)。 */
    BASE("base", true),
    /** 按这个存档的投料量把料下齐。 */
    FILL("fill", true),
    /** 盖上或揭开盖子(汤锅)。 */
    LID("lid", false),
    /** 起锅、一直翻炒到炒好(炒锅);炒的那段时间是锅自己的,手跟着它走。 */
    STIR("stir", false),
    /** 出锅装盘。 */
    PLATE("plate", true);

    /** 脚本里的函数名。 */
    final String word;
    /** 这一步要知道是哪道菜。 */
    final boolean needsDish;

    PotAct(String word, boolean needsDish) {
        this.word = word;
        this.needsDish = needsDish;
    }

    /** 在这口锅上做这一步的这一刻。 */
    Cooker.Step on(Cooker cooker, NumenPlayer cook, Dish dish, int[] portions) {
        return switch (this) {
            case OIL -> cooker.oil(cook);
            case BASE -> cooker.base(cook, dish);
            case FILL -> cooker.fill(cook, dish, portions);
            case LID -> cooker.lid(cook);
            case STIR -> cooker.stir(cook);
            case PLATE -> cooker.plate(cook, dish);
        };
    }
}
