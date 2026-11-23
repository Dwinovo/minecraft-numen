package com.dwinovo.numen.api.witness;

import com.dwinovo.numen.api.Focus;
import com.dwinovo.numen.api.entity.NumenPlayer;

import java.util.List;

/**
 * "这次动作前后变了什么"的一个回答者:只看一样东西。动作之前被叫一次({@link #open}),记下它关心的东西、交回一份记录;动作之后那份
 * 记录被问一次({@link Before#changes}),说出变了什么。登记:{@code numen.answer(Questions.WITNESS, 回答者)}。
 *
 * <pre>{@code
 * numen.answer(Questions.WITNESS, (her, focus) -> {
 *     ItemStack held = her.getMainHandItem();
 *     if (!MyMod.isWand(held)) return null;            // 不是我的东西,这次不关我的事
 *     int charge = MyMod.charge(held);                 // 动作之前:记下
 *     return now -> {                                  // 动作之后:比
 *         int left = MyMod.charge(now.getMainHandItem());
 *         return left == charge ? List.of() : List.of("wand charge: " + charge + " -> " + left);
 *     };
 * });
 * }</pre>
 *
 * <p>规矩:只说事实,一句一条,英文(读的是模型),没变就不说;不判成败、不判能不能做(那是读回执的人和权限层的事);不改世界、不动她的
 * 身体。别的回答者说了什么你看不到也改不了,回答之间不讲先后。抛了异常只丢你这一条、记进日志,动作与别的回答照常。在服务端主线程上被叫。
 */
@FunctionalInterface
public interface Witness {

    /**
     * 动作之前:记下你关心的东西。
     *
     * @param focus 这次动作对着谁
     * @return 之后拿来比的那份记录;这次动作不归你管就交回 null,之后不再问你
     */
    Before open(NumenPlayer her, Focus focus);

    /** 动作之前记下的那一份。之前看到的东西就装在它里面,由账本替你拿到结账。 */
    @FunctionalInterface
    interface Before {

        /** 动作之后(做成了、失败了、被叫停了都问):和记下的比,变了什么;没变是空表。 */
        List<String> changes(NumenPlayer her);
    }
}
