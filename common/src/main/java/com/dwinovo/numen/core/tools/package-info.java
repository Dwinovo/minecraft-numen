/**
 * 身体工具总目录:按<b>执行车道</b>分包,包结构本身就是分类判据的执法者。
 * 本包根部只住注册器与跨车道共享助手(ToolParse/MenuOps/各 *Ops 实现类)。
 *
 * <h2>新增工具的四连问(按顺序问,答案即归属)</h2>
 * <ol>
 *   <li><b>需要摸服务端的世界吗?(读或写都算)</b><br>
 *       不需要 → 问的是世界(读客户端副本/查状态)归 {@link com.dwinovo.numen.core.tools.sense};
 *       管的是大脑自己(计划/技能)归 {@link com.dwinovo.numen.core.tools.meta}。
 *       都在 invoke 里干完,当场 complete。</li>
 *   <li><b>只是"看",还是要"动"?</b><br>
 *       只看但必须进服务端线程安全读世界 → 机制上走短任务通道(见 act 包的定位例外);
 *       要动 → 继续问。</li>
 *   <li><b>最坏情况下,几秒内保证干完吗?</b><br>
 *       自测:能给它写出一个不冤枉它的固定 deadline 吗?能("装备 5 秒超时"合理)→
 *       {@link com.dwinovo.numen.core.tools.act};不能("挖 32 个铁 5 秒超时"荒谬,
 *       时长取决于世界)→ {@link com.dwinovo.numen.core.tools.job}。</li>
 *   <li><b>它有"干完了"的那一刻吗?</b><br>
 *       没有(follow/站岗,只有"被停止")→ 它根本不是工具调用,是常驻链:注册
 *       TaskChain 参与竞价,工具只做开关(开关本身是 sense 级,当场返回)。</li>
 * </ol>
 *
 * <h2>选错的代价(为什么值得问)</h2>
 * <ul>
 *   <li>该 job 却走 act:串行派发器等一个几分钟的活——回合冻结,主人以为她死机;</li>
 *   <li>该 act 却走 job:白付一轮"受理→休眠→事件唤醒"的 LLM 调用费与延迟;</li>
 *   <li>该常驻链却做成 job:永不完成的任务占死车道,占用闸门把后续全拒;</li>
 *   <li>该 sense 却进队:身体被长活占着时,一句"附近有什么"都会被拒。</li>
 * </ul>
 */
package com.dwinovo.numen.core.tools;
