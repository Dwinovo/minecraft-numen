/**
 * 身体:寻路模块在一具服务端玩家身上做事的这一层。键盘({@code Controls})、每刻的物理步进({@code Physics})、
 * 换手({@code Hotbar})是假玩家缺的那半个客户端,属于 Numen API(包 {@code com.dwinovo.numen.api.entity}),这里按它们用。
 *
 * <ul>
 *   <li>{@link com.dwinovo.numen.pathing.body.Aim} —— 把视角转向一个点;</li>
 *   <li>{@link com.dwinovo.numen.pathing.body.Crosshair} —— 准星落在哪;</li>
 *   <li>{@link com.dwinovo.numen.pathing.body.PlayerHands} —— 左键挖、右键用,端口 {@link com.dwinovo.numen.pathing.body.Effector}
 *       的原版实现;</li>
 *   <li>{@link com.dwinovo.numen.pathing.body.Snapshots} —— 从身体上抄下规划要的快照;</li>
 *   <li>端口 {@link com.dwinovo.numen.pathing.body.Body} 与 {@link com.dwinovo.numen.pathing.body.Effector}。</li>
 * </ul>
 *
 * <p>只在世界所在的线程上用。
 */
package com.dwinovo.numen.pathing.body;
