/**
 * 派长活:命令组 {@code move}(go、follow、dismount)、{@code route}(plan)、{@code work}(dig、fish)、{@code fight}(attack)、
 * {@code build}(blueprint、place、diff)。占身体、时长取决于世界的活
 * 都经 {@code TaskDispatch.setTask} 交任务槽:受理即回执 task_id,收尾经 task_finished 事件唤醒大脑;一次一件。
 * 只搜不走的 {@code numen.route.plan}、只读的 {@code numen.build.blueprint} 与 {@code numen.build.diff},
 * 和 {@code numen.move.dismount} 当场回。每件活在镜像的 task/&lt;领域&gt; 包里配一对 TaskRecord + CompanionTask。四连问见
 * {@link com.dwinovo.numen.core.tools} 包说明。
 */
package com.dwinovo.numen.core.tools.work;
