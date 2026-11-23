package com.dwinovo.numen.api.describe;

import com.dwinovo.numen.api.Focus;
import com.dwinovo.numen.api.entity.NumenPlayer;

import java.util.List;

/**
 * "这是什么"的一个回答者:只说它认得的那一样。她看一样东西时被叫一次,认得就交回几行,不认得交回空表。
 * 登记:{@code numen.answer(Questions.DESCRIBE, 回答者)}。
 *
 * <pre>{@code
 * numen.answer(Questions.DESCRIBE, (her, target) -> {
 *     if (target.block() == null || !(her.level().getBlockEntity(target.block()) instanceof MySmelter smelter)) {
 *         return List.of();                             // 不是我的东西
 *     }
 *     return List.of("MyMod smelter: " + smelter.progressPercent() + "% done",
 *             "mymod.smelter.side(pos, face, mode) sets which side takes input");
 * });
 * }</pre>
 *
 * <p>规矩:只说事实和她接下来能用的函数,一句一条,英文(读的是模型);不判能不能做(那是权限层的事);不改世界、不动她的身体。
 * 别的回答者说了什么你看不到也改不了,回答之间不讲先后。抛了异常只丢你这一条、记进日志,别的回答照常。在服务端主线程上被叫,
 * 她每看一次就问一次,别在这里做慢事。
 */
@FunctionalInterface
public interface Describer {

    /**
     * @param target 看的是谁
     * @return 关于它你知道的事,一句一条;不认得是空表
     */
    List<String> describe(NumenPlayer her, Focus target);
}
