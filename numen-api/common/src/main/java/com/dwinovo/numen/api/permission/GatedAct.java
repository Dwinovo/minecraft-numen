package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.permission.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 受权的动作:一件按刻推进的活动世界之前,"提出这几个动作 → 权限层判 允许/拒绝/要问 → 要问就发问并等主人答复 → 把结论与主人的准许交回"
 * 这一整套,做成一块可组合的积木。活<b>持有</b>它而不是继承它:Numen 的任务、Java 插件的任务、脚本插件都用这一块,谁也不必自己再抄一遍
 * 这个流程。任务自己不判能不能,只提出动作;许不许只由权限层({@link Gate})在这一刻裁决。
 *
 * <p>用法:每刻对要做的事调 {@link #permit}(或一批 {@link #permitAll});{@link PermitState#WAITING} 时让身体站住、下一刻再调,
 * {@link PermitState#REFUSED} 带着 {@link Permit#refusal} 收场,{@link PermitState#ALLOWED} 就动手。主人答应的清单由这里记下,
 * 活收场时把 {@link #allowanceNote} 接进回执——身体做了什么、主人点过什么头,都如实报给模型。
 *
 * <p>线程:服务端主线程。
 */
public final class GatedAct {

    /** 一个动作过权限层的结论。 */
    public enum PermitState { ALLOWED, WAITING, REFUSED }

    /**
     * @param state   放行 / 在等主人 / 不许
     * @param refusal 不许时回执的理由(规则、模式、外部强制的自述,或主人的原话);其余为空串
     */
    public record Permit(PermitState state, String refusal) {
        static final Permit ALLOWED = new Permit(PermitState.ALLOWED, "");
        static final Permit WAITING = new Permit(PermitState.WAITING, "");

        static Permit refused(String why) {
            return new Permit(PermitState.REFUSED, why);
        }
    }

    private final NumenPlayer player;
    private final Supplier<Object> scope;
    /** 在等主人答复的那张号;没在问是 null。 */
    private ConsentDesk.Ticket consent;
    /** 主人点头的那几次,回执末尾交代。 */
    private final List<String> allowances = new ArrayList<>();

    /**
     * @param player 提出动作的身体
     * @param scope  征询记在谁名下(一般是这件活的记录;子活问的记在父活名下,所以每次问时才取)
     */
    public GatedAct(NumenPlayer player, Supplier<Object> scope) {
        this.player = player;
        this.scope = scope;
    }

    /** 一个动作:放行就做;拒绝就带着理由收场;要问就发起征询,等待期间返回 WAITING,主人答应后返回 ALLOWED。 */
    public Permit permit(Action action) {
        return permitAll(List.of(action)).get(0);
    }

    /**
     * 同 {@link #permit},一批动作一起:各自裁决,要问的合成一次征询,主人的答复对这一批里要问的全部生效。结果与 {@code actions} 一一对应。
     */
    public List<Permit> permitAll(List<Action> actions) {
        var gate = Permission.gateFor(player);
        List<Permit> out = new ArrayList<>(actions.size());
        List<ConsentItem> asks = new ArrayList<>();
        List<Integer> askedAt = new ArrayList<>();
        for (Action action : actions) {
            Verdict verdict = gate.judgeLive(action, player.serverLevel());
            switch (verdict.kind()) {
                case ALLOW -> out.add(Permit.ALLOWED);
                case DENY -> out.add(Permit.refused(verdict.reason()));
                case ASK -> {
                    askedAt.add(out.size());
                    asks.add(gate.consentItemLive(action, verdict, player.serverLevel()));
                    out.add(Permit.WAITING);
                }
            }
        }
        if (asks.isEmpty()) {
            settle();
            return out;
        }
        ConsentAnswer answer = consult(asks);
        if (answer != null) {
            Permit settled = answer.allowed() ? Permit.ALLOWED : Permit.refused(answer.refusal(asks));
            for (int i : askedAt) {
                out.set(i, settled);
            }
        }
        return out;
    }

    /**
     * 问主人一批事:第一次调用发起征询,之后每刻读结论;清单变了就重发(新的顶掉旧的)。主人答应的清单由登记处记成这件活的授权,
     * {@link #allowanceNote} 交代。
     *
     * @return 结论;还在等是 null
     */
    public ConsentAnswer consult(List<ConsentItem> items) {
        if (consent != null && !consent.request().items().equals(items)) {
            consent = null;
        }
        if (consent == null) {
            consent = ConsentDesk.of(player).ask(scope.get(), items);
        }
        ConsentAnswer answer = consent.poll();
        if (answer == null) {
            return null;
        }
        consent = null;
        if (answer.allowed()) {
            allowances.add(answer.allowance(items));
        }
        return answer;
    }

    /** 此刻有没有一张在等主人答复的征询:身体这一刻在等,不在干活。 */
    public boolean waiting() {
        return consent != null;
    }

    /**
     * 要做的事此刻不用问了:主人刚答应(授权已经让裁决变成放行)就把这次允许记进回执;还没答复就撤回挂着的征询。
     */
    private void settle() {
        if (consent == null) {
            return;
        }
        ConsentAnswer answer = consent.poll();
        if (answer == null) {
            ConsentDesk.of(player).withdraw(consent);
        } else if (answer.allowed()) {
            allowances.add(answer.allowance(consent.request().items()));
        }
        consent = null;
    }

    /** 回执末尾交代主人点过的头:没问过主人是空串,否则是以空格起头、以句号收尾的一句。 */
    public String allowanceNote() {
        return allowances.isEmpty() ? "" : " " + String.join("; ", allowances) + ".";
    }

    /** 子活收场:它问过主人、主人点过的头并进这件活,由这件活收场时说一次。 */
    public void absorb(GatedAct child) {
        allowances.addAll(child.allowances);
    }
}
