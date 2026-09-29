/**
 * <strong>Public API.</strong> 区域:一个维度里有名字、存盘的一堆格子,每格可附带当时看到的方块。"一块地方"在全仓只有这一种
 * 写法——权限规则的 {@code area:} 项、以后路线的终点与禁区、挖矿与捡东西的范围都消费它,谁都可以产出它。
 *
 * <p>{@link com.dwinovo.numen.area.Cells} 是格子本身(按 16³ 小节存位图,集合运算逐节位运算);
 * {@link com.dwinovo.numen.area.Area} 给它一个维度并分成编号的部分;{@link com.dwinovo.numen.area.AreaRef} 是点名一块区域
 * 或其中一部分的写法;{@link com.dwinovo.numen.area.AreaStore} 按主人存。都是不可变值或写时复制,任何线程可读。
 * 设计稿见 {@code docs/look-plan-act.md} §三。
 */
package com.dwinovo.numen.area;
