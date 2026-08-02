/**
 * 身体工具总目录:按<b>领域</b>分包(perception 看/inventory 物品/interact 碰/
 * locate 找/work 长活/agent 管自己),包名回答"这是干嘛的";执行机制不进包名,
 * 由每个工具在执行体里选通道。根部只住注册器与共享助手(*Ops 实现类/
 * ToolParse/MenuOps)。注册集中在 NumenCore 且顺序即提示词缓存键,新工具
 * 一律追加在注册表末尾。
 *
 * <h2>新增工具的四连问(按顺序问,答案即执行通道)</h2>
 * <ol>
 *   <li><b>需要摸服务端的世界吗?(读或写都算)</b><br>
 *       不需要 → invoke 现场 complete,不进任务系统。</li>
 *   <li><b>只是"看",还是要"动"?</b><br>
 *       只看但必须进服务端线程安全读世界 → 机制上走 enqueue 短任务
 *       (如 locate 双工具,理由写在类注释);要动 → 继续问。</li>
 *   <li><b>最坏情况下,几秒内保证干完吗?</b><br>
 *       自测:能给它写出一个不冤枉它的固定 deadline 吗?能("装备 5 秒超时"
 *       合理)→ TaskDispatch.enqueue,回合等结果;不能("挖 32 个铁 5 秒超时"
 *       荒谬,时长取决于世界)→ TaskDispatch.dispatchAsync,受理回执 +
 *       task_finished 事件收尾。</li>
 *   <li><b>它有"干完了"的那一刻吗?</b><br>
 *       没有(follow/站岗,只有"被停止")→ 它根本不是工具调用,是常驻链:
 *       注册 TaskChain 参与竞价,工具只做开关(开关本身现场返回)。</li>
 * </ol>
 *
 * <h2>选错的代价(为什么值得问)</h2>
 * <ul>
 *   <li>该 dispatchAsync 却 enqueue:串行派发器等一个几分钟的活——回合冻结,
 *       主人以为她死机;</li>
 *   <li>该 enqueue 却 dispatchAsync:白付一轮"受理→休眠→事件唤醒"的 LLM
 *       调用费与延迟;</li>
 *   <li>该常驻链却做成任务:永不完成的任务占死车道,占用闸门把后续全拒;</li>
 *   <li>纯查询却进队:身体被长活占着时,一句"附近有什么"都会被拒。</li>
 * </ul>
 */
package com.dwinovo.numen.core.tools;
