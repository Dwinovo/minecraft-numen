package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.tool.Questions.Question;
import com.dwinovo.numen.api.agent.tool.Questions.Reply;

import java.util.List;
import java.util.function.Consumer;

/**
 * 她向主人提的问题挂在哪:一只同伴同一时刻只有一个调用在飞,所以最多挂着一次提问({@link Asking})。面板读 {@link #pending},
 * 主人答完它自己交回答复;调用被放弃(停止、急件、她离开世界)时 {@link #withdraw} 把它摘掉。挂着的时候她的过程那一行读作等待回答。
 *
 * <p>线程:客户端主线程(大脑的线程)。
 */
public final class QuestionDesk {

    private Asking pending;

    /** 挂上一次提问;答复交回之前一直挂着。 */
    public Asking ask(List<Question> questions, Consumer<Reply> replied) {
        if (pending() != null) {
            throw new IllegalStateException("a question to your owner is already waiting for an answer");
        }
        Asking asking = new Asking(questions, replied);
        pending = asking;
        return asking;
    }

    /** 此刻挂着的提问;答完、拒绝、被放弃的都不再挂着,没有是 null。 */
    public Asking pending() {
        return pending != null && pending.open() ? pending : null;
    }

    /** 这次提问不用答了(调用被放弃):它不再交答复。 */
    public void withdraw(Asking asking) {
        asking.withdraw();
    }
}
