package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.tool.Questions.Answer;
import com.dwinovo.numen.api.agent.tool.Questions.Question;
import com.dwinovo.numen.api.agent.tool.Questions.Reply;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 一次挂着的提问和主人答到哪了:一次显示一个问题,选项选一个(多选可选几个)或写一句"其他"回答;问到最后一个之后是确认一步,
 * 提交才算答完。只有一个单选问题的,选了就交。面板只读这里的状态、把按键与点击转成这里的动作;答完、拒绝、想先聊聊
 * 各交出一份 {@link Reply},恰好一次。
 *
 * <p>线程:客户端主线程。
 */
public final class Asking {

    private final List<Question> questions;
    private final Consumer<Reply> done;
    /** 每个问题选中的选项下标,按选项顺序。 */
    private final List<TreeSet<Integer>> picks = new ArrayList<>();
    /** 每个问题主人自己写的那句;没写是 null。 */
    private final String[] others;
    private int step;
    private boolean finished;

    public Asking(List<Question> questions, Consumer<Reply> done) {
        this.questions = List.copyOf(questions);
        this.done = done;
        this.others = new String[questions.size()];
        for (int i = 0; i < questions.size(); i++) {
            picks.add(new TreeSet<>());
        }
    }

    public List<Question> questions() {
        return questions;
    }

    /** 此刻在第几个问题;等于问题数时是确认那一步。 */
    public int step() {
        return step;
    }

    /** 只有一个单选问题:选了就交,没有确认那一步。 */
    public boolean direct() {
        return questions.size() == 1 && !questions.get(0).multiSelect();
    }

    /** 在确认那一步。 */
    public boolean reviewing() {
        return !finished && step == questions.size();
    }

    /** 此刻问的那一题;在确认那一步是 null。 */
    public Question current() {
        return step < questions.size() ? questions.get(step) : null;
    }

    public boolean picked(int question, int option) {
        return picks.get(question).contains(option);
    }

    /** 主人给这个问题自己写的那句;没写是 null。 */
    public String other(int question) {
        return others[question];
    }

    /** 这个问题答成什么:选中的各项名字加上他写的那句,用逗号连起来;还没答是 null。 */
    public String answer(int question) {
        List<String> parts = new ArrayList<>();
        for (int i : picks.get(question)) {
            parts.add(questions.get(question).options().get(i).label());
        }
        if (others[question] != null) {
            parts.add(others[question]);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** 选第 {@code option} 项:单选的选定就往下走,多选的是开关。 */
    public void pick(int option) {
        Question q = current();
        if (finished || q == null || option < 0 || option >= q.options().size()) {
            return;
        }
        TreeSet<Integer> chosen = picks.get(step);
        if (q.multiSelect()) {
            if (!chosen.remove(option)) {
                chosen.add(option);
            }
            return;
        }
        chosen.clear();
        chosen.add(option);
        others[step] = null;
        advance();
    }

    /** 主人自己写的一句:单选就是这个问题的答案,多选是附在选中的几项后面;写了就往下走。空串不算。 */
    public void other(String text) {
        Question q = current();
        String said = text == null ? "" : text.strip();
        if (finished || q == null || said.isEmpty()) {
            return;
        }
        if (!q.multiSelect()) {
            picks.get(step).clear();
        }
        others[step] = said;
        advance();
    }

    /** 多选的问题选好了,往下走;一项都没选、也没写的不动。 */
    public void next() {
        Question q = current();
        if (finished || q == null || !q.multiSelect() || answer(step) == null) {
            return;
        }
        advance();
    }

    /** 回到上一个问题改。 */
    public void back() {
        if (!finished && step > 0) {
            step--;
        }
    }

    /** 确认那一步提交:每个问题都答了才交。 */
    public void submit() {
        if (finished || !reviewing()) {
            return;
        }
        finish(new Reply.Answered(answers()));
    }

    /** 现在不想答(Esc)。 */
    public void decline() {
        finish(new Reply.Declined());
    }

    /** 想先聊聊这些问题:连同已答的一起交回。 */
    public void clarify() {
        finish(new Reply.Clarify(answers()));
    }

    /** 还在等主人答:没答完、没被放弃。 */
    public boolean open() {
        return !finished;
    }

    /** 这次提问被放弃(调用被停止、打断):它不再交答复。 */
    void withdraw() {
        finished = true;
    }

    /** 到此为止已答的。 */
    public List<Answer> answers() {
        List<Answer> out = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            String a = answer(i);
            if (a != null) {
                out.add(new Answer(questions.get(i).question(), a));
            }
        }
        return out;
    }

    private void advance() {
        if (direct()) {
            finish(new Reply.Answered(answers()));
        } else {
            step++;
        }
    }

    private void finish(Reply reply) {
        if (finished) {
            return;
        }
        finished = true;
        done.accept(reply);
    }
}
