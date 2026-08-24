/**
 * 战斗系统:<b>顶层判状态,执行层两条腿</b>。
 *
 * <h2>顶层——{@link com.dwinovo.numen.core.combat.AttackPlan}</h2>
 * 每刻从头跑一遍的纯函数,只回答两件事:
 *
 * <ul>
 *   <li><b>还打不打得过</b> —— 有效血量撑不住,或者手上没有任何武器 → 跑</li>
 *   <li><b>用弓还是走位</b> —— 走不到就用弓,走得到就走位</li>
 * </ul>
 *
 * <p>它<b>没有状态位</b>。逃跑不是一个"进去要出来"的模式:血回来了下一刻自然回到战斗,
 * 不必等任何出口。唯一的记忆是上一刻的决定,只用来做目标承诺(选中一只打完再换,否则
 * 一群会分裂的怪里"最近那只"每刻都在变)。
 *
 * <h2>执行层——两条腿,正交</h2>
 * <table>
 *   <tr><th></th><th>管什么</th><th>不管什么</th></tr>
 *   <tr><td>攻击</td><td>冷却好了、有谁进了攻击距离 → 打</td>
 *       <td>她这一刻在靠近还是在拉开</td></tr>
 *   <tr><td>寻路</td><td>把她带到打得到、又不挨打的地方</td>
 *       <td>打不打</td></tr>
 * </table>
 *
 * <p>它们并排跑,互不影响 —— 攻击最多让她回个头。曾经攻击是一个<b>动作</b>、与移动互斥,
 * 于是靠近那一支不挥刀、拉开那一支也不挥刀:她躲的时候一刀不还,骷髅一边后退一边射她
 * 也追不上。
 *
 * <h2>走位是一个环,不是两个状态</h2>
 * <pre>
 * 内沿 = 它够得着我   (Menace.rawDangerRadius,碰撞箱推)
 * 外沿 = 我够得着它   (Swing.reachTo,原版 ENTITY_INTERACTION_RANGE + 目标半宽)
 * 带内什么都不做,攻击层自己打
 * </pre>
 *
 * 写成一个目标而不是「太近→拉开状态、太远→靠近状态」,是因为两个状态很容易把<b>触发线
 * 和终止线变成同一条</b>,于是在边界上来回横跳。带内有天然余量,不需要迟滞。
 *
 * <h2>那些数从哪来</h2>
 * 危险半径、够到距离、爆炸波及范围、引信时长、攻击间隔<b>全部从原版常数与碰撞箱推</b>——
 * 换个模组怪、换个体型,数自己跟着变。手调的只剩势场强度、边成本倍率、血线这几个偏好量。
 *
 * <h2>各个类</h2>
 * <ul>
 *   <li>{@link com.dwinovo.numen.core.combat.AttackPlan} —— 顶层判据</li>
 *   <li>{@link com.dwinovo.numen.core.combat.Battlefield} —— 喂给判据的那一刻的局面</li>
 *   <li>{@link com.dwinovo.numen.core.combat.Menace} —— 危险半径:离它多近算危险,一处定义</li>
 *   <li>{@link com.dwinovo.numen.core.combat.Swing} —— 够得着多远、这一下能不能挥</li>
 *   <li>{@link com.dwinovo.numen.core.combat.Haven} —— 逃跑往哪儿跑(一个落点,不是一个方向)</li>
 *   <li>{@link com.dwinovo.numen.core.combat.Loadout} —— 手上有什么可用的</li>
 *   <li>{@link com.dwinovo.numen.core.combat.WeaponDamage} —— 哪把更疼</li>
 * </ul>
 *
 * 把这些接起来的是 {@code core.task.combat.AttackCompanionTask}:它每刻扫一遍局面、
 * 问一次判据、跑一次攻击层、驱一次寻路层。
 */
package com.dwinovo.numen.core.combat;
