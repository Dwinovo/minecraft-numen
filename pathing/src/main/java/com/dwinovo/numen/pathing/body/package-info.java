/**
 * 身体:一具服务端假玩家怎样按键移动、怎样挖、怎样放、怎样把东西拿到手上。假玩家没有客户端,原版客户端那一半
 * (把按键变成输入、挖掘循环、右键、选物品)与网络层替玩家做的那一趟(物理步进、摔伤、区块跟随)都在这里补上,
 * 原版机制只此一份;执行层与宿主都用它,宿主只在外面组合自己的规矩(许可、事件、报告)。
 *
 * <ul>
 *   <li>{@link com.dwinovo.numen.pathing.body.Controls} —— 键盘;</li>
 *   <li>{@link com.dwinovo.numen.pathing.body.Physics} —— 每刻的物理步进;</li>
 *   <li>{@link com.dwinovo.numen.pathing.body.Crosshair} —— 准星落在哪;</li>
 *   <li>{@link com.dwinovo.numen.pathing.body.PlayerHands} —— 左键挖、右键用,端口 {@link com.dwinovo.numen.pathing.body.Effector}
 *       的原版实现;</li>
 *   <li>{@link com.dwinovo.numen.pathing.body.Hotbar} —— 把工具或料拿到手上,每一次都交回一个
 *       {@link com.dwinovo.numen.pathing.body.BodyAction};</li>
 *   <li>{@link com.dwinovo.numen.pathing.body.Snapshots} —— 从身体上抄下规划要的快照;</li>
 *   <li>端口 {@link com.dwinovo.numen.pathing.body.Body} 与 {@link com.dwinovo.numen.pathing.body.Effector}。</li>
 * </ul>
 *
 * <p>只在世界所在的线程上用。
 */
package com.dwinovo.numen.pathing.body;
