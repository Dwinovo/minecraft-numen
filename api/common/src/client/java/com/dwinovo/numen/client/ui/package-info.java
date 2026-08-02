/**
 * NumenUI——设置界面重构的自有组件层。
 *
 * <h2>分层纪律(易迁移是第一优先)</h2>
 * 本包内除 {@code mc} 子包外<b>零 Minecraft import</b>:布局、状态、主题、
 * 动画、toast 队列全是纯 JVM,面向 {@link com.dwinovo.numen.client.ui.IDrawSurface}
 * 编程,单元测试直接跑。每个版本分支只重写 {@code mc.McDrawSurface} 一个类。
 *
 * <h2>性能纪律</h2>
 * 逐帧重绘的立即模式下:排版结果缓存到脏标记失效、文本测量不进渲染帧、
 * 列表只画可视区(scissor)。渲染帧内禁止字符串拼接与集合分配。
 *
 * <p>控件基类待第一批具体控件(提供商分区)成形时再定形——先见血再定骨,
 * 不做投机抽象。
 */
package com.dwinovo.numen.client.ui;
