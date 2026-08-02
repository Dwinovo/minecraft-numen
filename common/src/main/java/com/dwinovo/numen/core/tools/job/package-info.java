/**
 * ③长活车道:占身体、时长无界(取决于世界:路多远/矿够不够/怪多强)的任务。
 * 走 TaskDispatch.dispatchAsync:受理即回执 task_id,回合继续,身体后台执行,
 * 收尾经 task_finished 事件唤醒大脑。占用闸门保证一次一件,占用期拒绝新活
 * 并引导 task_stop。
 *
 * <p>审查红灯:本包之外不得出现 dispatchAsync,本包之内不得出现 enqueue。
 * 没有"干完"语义、只有"被停止"的常驻行为(follow/站岗)不属于本车道——
 * 那是 TaskChain 的事。四连问见 {@link com.dwinovo.numen.core.tools}。
 */
package com.dwinovo.numen.core.tools.job;
