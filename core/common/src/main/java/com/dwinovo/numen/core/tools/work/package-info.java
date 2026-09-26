/**
 * 派长活:命令组 {@code move}(goto、follow、route)、{@code work}(mine、collect、fish)、{@code fight}(attack)、
 * {@code build}(原语、设计、按设计或蓝图文件施工),以及她自己的一项设置 {@code throwaway}(赶路时愿意消耗的方块)。占身体、时长取决于世界的活
 * 都经 {@code TaskDispatch.setTask} 交任务槽:受理即回执 task_id,收尾经 task_finished 事件唤醒大脑;一次一件。
 * 只读的 route、show、designs、built,改设计库的 new/step/insert/drop/delete 与带 {@code --into} 的原语,和改登记的
 * throwaway 组当场回。每件活在镜像的 task/&lt;领域&gt; 包里配一对 TaskRecord + CompanionTask。四连问见
 * {@link com.dwinovo.numen.core.tools} 包说明。
 */
package com.dwinovo.numen.core.tools.work;
