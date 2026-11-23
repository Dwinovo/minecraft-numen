package com.dwinovo.numen.api.witness;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.entity.NumenPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次动作的账本:动作之前开({@link #open}),把登记着的回答者({@link Witness})挨个问一遍"你要记什么";动作收场时结
 * ({@link #close}),把它们各自说的变化接成一张单子。"身体做的一切都要如实报告给模型"在点一下这类动作上的出处只有这一个,
 * Numen 的任务、插件自己写的任务用的是同一本。
 *
 * <p>谁驱动这次动作谁开谁关:按键按下去的那一刻不是结账的时候(吃东西、拉弓的结果在松手之后)。做成了、失败了、被叫停了都结,
 * 那时身体可能已经变了。挖、捡、合成这些自己有账的活不开这一本,免得一件事报两遍。
 *
 * <p>一个回答者抛了异常只丢它那一条、记进日志,别的回答照常。只在服务端主线程上用。
 */
public final class Ledger {

    private record Entry(Witness witness, Witness.Before before) {}

    private final NumenPlayer her;
    private final List<Entry> entries = new ArrayList<>();
    /** 结账的结果;没结为 null。 */
    private List<String> facts;

    private Ledger(NumenPlayer her) {
        this.her = her;
    }

    /**
     * 动作之前开账。
     *
     * @param focus 这次动作对着谁
     */
    public static Ledger open(NumenPlayer her, Focus focus) {
        Ledger ledger = new Ledger(her);
        for (Witness witness : Questions.WITNESS.answerers()) {
            try {
                Witness.Before before = witness.open(her, focus);
                if (before != null) {
                    ledger.entries.add(new Entry(witness, before));
                }
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] witness {} failed before the action; it reports nothing this time",
                        witness.getClass().getName(), e);
            }
        }
        return ledger;
    }

    /**
     * 结账:从开账到现在,每一条是一件真发生的事;都没变是空表。结过再问给的是同一张单子(回执的几个出口都可以来要)。
     */
    public List<String> close() {
        if (facts == null) {
            List<String> out = new ArrayList<>();
            for (Entry entry : entries) {
                try {
                    List<String> said = entry.before().changes(her);
                    if (said != null) {
                        out.addAll(said);
                    }
                } catch (RuntimeException e) {
                    Constants.LOG.error("[numen] witness {} failed after the action; its report is dropped",
                            entry.witness().getClass().getName(), e);
                }
            }
            facts = List.copyOf(out);
        }
        return facts;
    }
}
