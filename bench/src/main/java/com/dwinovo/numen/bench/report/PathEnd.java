package com.dwinovo.numen.bench.report;

/**
 * 她最后一趟路是怎么收的场,从轨迹里 {@code path} 那几条的最后一条读出来的。
 *
 * <p>寻路的失败只有这里说得清:工具结果那一侧看到的是一段给人读的话,{@link Meter} 把它归成"一次 API 调用做了但没做成",
 * 到底是搜不出路、料不够还是被许可拒了,分不出来。
 *
 * @param outcome {@code com.dwinovo.numen.pathing.api.Outcome} 那一类的名字({@code NoRoute}、{@code Arrived}……)
 * @param reason  {@code com.dwinovo.numen.pathing.plan.Reason} 的 {@code name()};执行期的事没有前提上的原因,为 {@code null}
 */
public record PathEnd(String outcome, String reason) {}
