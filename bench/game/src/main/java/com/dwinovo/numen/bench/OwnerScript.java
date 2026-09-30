package com.dwinovo.numen.bench;

import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.permission.ConsentAnswer;

/**
 * 模拟主人的剧本:她征询时按哪个键,她说完话要不要回一句。答复走真实入口({@code ConsentDesk.reply}),和主人在答复框上
 * 按键一样。
 */
public interface OwnerScript {

    /** 允许一次、从不回话。 */
    OwnerScript ALLOW_ONCE = request -> new Answer(ConsentAnswer.Decision.ALLOW_ONCE, null);

    /** 她挂出这条征询时主人怎么答。 */
    Answer consent(ConsentRequestPayload request);

    /**
     * 她一轮说完、停下来之后,主人要不要回一句。
     *
     * @param herWords  她最后说的那句
     * @param repliedSoFar 这一次运行里主人已经回过几句
     * @return 主人说的话;不回是 null
     */
    default String reply(String herWords, int repliedSoFar) {
        return null;
    }

    /**
     * 一次答复。
     *
     * @param note 附言,只随拒绝;没有是 null
     */
    record Answer(ConsentAnswer.Decision decision, String note) {}
}
