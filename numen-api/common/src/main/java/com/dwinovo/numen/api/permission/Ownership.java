package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.data.ModLanguageData;

import net.minecraft.network.chat.Component;

/**
 * 内置的回答者"归属":动作的对象是方块或实体时,问"这是谁的"({@link Whose},去问 {@link Owners} 那一圈)——主人这一边的
 * 要问,其余(别人的、她自己的、没主人的)不表态:别人的东西归服务器上管保护的那一方去挡。别的动作(扔东西、用手里的东西、执行命令)没有这样的对象,不表态。
 * 经和插件同一个门登记({@link com.dwinovo.numen.api.Questions#PERMIT},名字 {@link #NAME})。
 */
public final class Ownership implements Answerer {

    /** 登记用的名字。 */
    public static final String NAME = "ownership";

    private static final Answer OWNERS = Answer.ask("belongs to the owner",
            Component.translatable(ModLanguageData.Keys.PERMISSION_OWNERS));

    @Override
    public Answer answer(Action action, Facts facts) {
        Whose whose = action.entity() != null ? Whose.ofEntity(action.entity(), facts)
                : action.pos() != null ? Whose.ofBlock(action.pos(), facts) : Whose.NATURE;
        return whose == Whose.OWNER ? OWNERS : Answer.ABSTAIN;
    }
}
