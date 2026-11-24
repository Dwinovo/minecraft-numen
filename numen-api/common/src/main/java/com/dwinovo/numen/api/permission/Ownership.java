package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.data.ModLanguageData;

import net.minecraft.network.chat.Component;

/**
 * 内置的回答者"归属":动作的对象是方块或实体时,问"这是谁的"({@link Whose},去问 {@link Owners} 那一圈)——别人的不许,
 * 主人这一边的要问,她自己的与没主人的不表态。别的动作(扔东西、用手里的东西、执行命令)没有这样的对象,不表态。
 * 经和插件同一个门登记({@link com.dwinovo.numen.api.Questions#PERMIT},名字 {@link #NAME})。
 */
public final class Ownership implements Answerer {

    /** 登记用的名字。 */
    public static final String NAME = "ownership";

    private static final Answer OTHERS = Answer.deny("belongs to someone else",
            Component.translatable(ModLanguageData.Keys.PERMISSION_OTHERS));
    private static final Answer OWNERS = Answer.ask("belongs to the owner",
            Component.translatable(ModLanguageData.Keys.PERMISSION_OWNERS));

    @Override
    public Answer answer(Action action, Facts facts) {
        Whose whose = action.entity() != null ? Whose.ofEntity(action.entity(), facts)
                : action.pos() != null ? Whose.ofBlock(action.pos(), facts) : Whose.NATURE;
        return switch (whose) {
            case OTHER -> OTHERS;
            case OWNER -> OWNERS;
            case SELF, NATURE -> Answer.ABSTAIN;
        };
    }
}
