package com.dwinovo.numen.api;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.UUID;

/**
 * 替同伴回答一个问题:<b>她现在长什么样</b>。
 *
 * <p>引擎在名册、轮盘、聊天气泡等处画同伴头像,默认画的是原版皮肤上剪下来的那张脸。
 * 装了会改外观的插件之后那张脸就不对了——皮肤没变,身上那套模型变了。实现这个接口
 * 把真实的样子画出来,引擎就不画自己那张。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li><b>画不了就返回 {@code false}</b>,并且什么都别画。引擎会往下问其他实现,
 *       都不认领就回退到皮肤脸——那条路一直是通的,你不必兜底。</li>
 *   <li>认领了就<b>把那块方形区域填满</b>:{@code (x, y)} 起、边长 {@code size}。
 *       引擎已经画好了边框之类的装饰,你只管里面。</li>
 *   <li>同一个同伴会在不同尺寸下被问到(8 像素的气泡、24 像素的名册行)。
 *       小尺寸下你的画法糊成一团的话,<b>那个尺寸返回 false 就好</b>,
 *       引擎会用皮肤脸填上——不必勉强。</li>
 * </ul>
 *
 * <h2>这个方法每帧都会被调用</h2>
 * 所以它必须<b>便宜</b>。一次 blit、一次实体渲染都可以;读盘、解码图片、
 * 起线程不行。要预处理就自己缓存好,这里只负责画。
 *
 * <h2>多个实现</h2>
 * 按注册顺序问,<b>第一个认领的胜出</b>。两个插件都想画同一个同伴本身就是冲突,
 * 先来先得至少是确定的。
 */
@FunctionalInterface
public interface CompanionPortrait {

    /**
     * 画一个同伴的头像。
     *
     * @param g         画布
     * @param companion 同伴的 UUID
     * @param x         方形区域左上角
     * @param y         方形区域左上角
     * @param size      边长(像素)
     * @return {@code true} = 我画了;{@code false} = 我不画,交给引擎
     */
    boolean draw(GuiGraphicsExtractor g, UUID companion, int x, int y, int size);
}
