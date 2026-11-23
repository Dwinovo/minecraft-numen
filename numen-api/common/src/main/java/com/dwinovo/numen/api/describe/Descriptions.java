package com.dwinovo.numen.api.describe;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.Question;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.LuaCodecs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 问一圈"这是什么":把一个问题登记着的回答者({@link Describer})挨个问一遍,各人说的归在各人的名字下,按登记先后。Numen 的"看"和插件
 * 自己写的"看"问的是同一处。没说话的回答者不占名字,没人认得就是空表。
 *
 * <p>一个回答者抛了异常、或交回了写不成脚本的值,只丢它那一条、记进日志(点它的名字),别的回答照常。只在服务端主线程上用。
 */
public final class Descriptions {

    private Descriptions() {}

    /**
     * @param question 问的是哪一种东西({@link com.dwinovo.numen.api.Questions#BLOCK}、{@link com.dwinovo.numen.api.Questions#ENTITY})
     * @param target   看的是谁
     * @return 回答者的名字到它说的字段
     */
    @SuppressWarnings("unchecked")
    public static <T> Map<String, Map<String, Object>> of(Question<Describer<T>> question, NumenPlayer her, T target) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        question.answerers().forEach((name, describer) -> {
            try {
                Map<String, Object> said = describer.describe(her, target);
                if (said != null && !said.isEmpty()) {
                    // 当场写成脚本的值:字段里有写不成的(回答者的毛病)和它抛异常一样,只丢它这一条
                    out.put(name, (Map<String, Object>) LuaCodecs.encode(said));
                }
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] answerer '{}' of question {} failed; what it knows is left out this time",
                        name, question.id(), e);
            }
        });
        return Collections.unmodifiableMap(out);
    }
}
