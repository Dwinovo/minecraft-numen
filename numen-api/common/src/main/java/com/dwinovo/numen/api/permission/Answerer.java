package com.dwinovo.numen.api.permission;

/**
 * "这个动作、这个对象,许不许"的一个回答者:谁懂谁登记,{@code numen.answer(Questions.PERMIT, "my_claims", answerer)}。
 * 权限层自己不认方块、不认实体,也不知道"这是谁的";它把每件事问一圈,照固定的顺序合出结果({@link Gate})。回答者之间互不知道,
 * 只说自己懂的,不懂就不表态({@link Answer#ABSTAIN})。
 *
 * <p>线程:任何线程都可能叫你。真要动手前的那一问在服务端主线程,{@code facts.live()} 是活世界;寻路给每一格定价时在搜索线程,
 * {@code facts.live()} 是 null,只许读 {@code action} 与 {@code facts} 里冻结的那几份(方块快照、归属查表),碰不得活世界——要活读才
 * 答得出的事这时按保守的一头回答(宁可估成要问,不估成免费)。每定价一格就可能被问一次,别在这里做慢事;只陈述所知,不改世界,不留状态。
 * 抛了异常只丢你这一条,记进日志。
 */
@FunctionalInterface
public interface Answerer {

    /**
     * @param action 要做的事:哪种动作、对哪一格哪只实体哪件物品哪条指令
     * @param facts  世界此刻的样子与要动手的同伴
     */
    Answer answer(Action action, Facts facts);
}
