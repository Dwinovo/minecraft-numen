/**
 * 派长活:命令组 {@code move}(goto、follow、route)、{@code work}(mine、collect、fish)、{@code fight}(attack)、
 * {@code build}(按图施工、读图纸、垫路料清单),以及一串建造原语的 {@code build} 工具。占身体、时长取决于世界的活
 * 都经 {@code TaskDispatch.setTask} 交任务槽:受理即回执 task_id,收尾经 task_finished 事件唤醒大脑;一次一件。
 * 只读的 route、blueprints、blueprint_read 与改登记的 scaffold 系列当场回。每件活在镜像的 task/&lt;领域&gt; 包里配一对
 * TaskRecord + CompanionTask。四连问见 {@link com.dwinovo.numen.core.tools} 包说明。
 */
package com.dwinovo.numen.core.tools.work;
