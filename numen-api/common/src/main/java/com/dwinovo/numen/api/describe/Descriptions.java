package com.dwinovo.numen.api.describe;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.Focus;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.entity.NumenPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 问一圈"这是什么":把登记着的回答者({@link Describer})挨个问一遍,把它们说的按登记先后接成一张单子。Numen 的"看"和插件自己写的
 * "看"问的是同一处。没人认得就是空表。
 *
 * <p>一个回答者抛了异常只丢它那一条、记进日志,别的回答照常。只在服务端主线程上用。
 */
public final class Descriptions {

    private Descriptions() {}

    /** @param target 看的是谁 */
    public static List<String> of(NumenPlayer her, Focus target) {
        List<String> out = new ArrayList<>();
        for (Describer describer : Questions.DESCRIBE.answerers()) {
            try {
                List<String> said = describer.describe(her, target);
                if (said != null) {
                    out.addAll(said);
                }
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] describer {} failed; what it knows is left out this time",
                        describer.getClass().getName(), e);
            }
        }
        return List.copyOf(out);
    }
}
