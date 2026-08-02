/**
 * 派长活:goto/挖矿/建造/蓝图/钓鱼/双战斗/捡拾——时长无界(取决于世界)的
 * 任务全员在此,统一走 dispatchAsync:受理即回执 task_id,收尾经 task_finished
 * 事件唤醒大脑;占用闸门一次一件。
 *
 * <p>审查红灯:本包之外不得出现 dispatchAsync,本包之内不得出现 enqueue。
 * 每个工具在镜像的 task/&lt;领域&gt; 包里配一对 TaskRecord + CompanionTask。
 * 四连问见 {@link com.dwinovo.numen.core.tools} 包说明。
 */
package com.dwinovo.numen.core.tools.work;
