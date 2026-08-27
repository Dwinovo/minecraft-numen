package com.dwinovo.numen.api;

import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * 替同伴回答一个问题:<b>她现在长什么样</b>。
 *
 * <p>引擎在名册、轮盘、聊天气泡、HUD 等处画同伴头像,默认画的是原版皮肤上剪下来的
 * 那张脸。装了会改外观的插件之后那张脸就不对了——皮肤没变,身上那套模型变了。
 * 实现这个接口把真实的样子交回来,引擎就用你的。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li><b>拿不出就返回 {@code null}</b>,不要硬编一张占位图。引擎会往下问其他实现,
 *       都没有就回退到皮肤脸——那条路一直是通的,你不必兜底。</li>
 *   <li>返回的是<b>一张方形纹理</b>,引擎整张按请求的尺寸画出来。它不是皮肤:引擎不会
 *       去上面剪 8×8 的脸。</li>
 *   <li>{@code size} 是<b>提示</b>不是要求。同一个同伴在 8 像素的气泡里和 24 像素的
 *       名册行里都会问一次,你可以据此给不同精度,也可以一律给同一张。</li>
 * </ul>
 *
 * <h2>这个方法每帧都会被调用</h2>
 * 所以它必须<b>便宜</b>:该做的是提前把头像渲染/加载好,这里只把已经准备好的
 * {@link Identifier} 递出来(一次 map 查找)。不要在这里渲染、读盘或解码图片。
 *
 * <p>什么时候重新准备由你自己判断——引擎不知道你的外观什么时候变,也不该知道。
 *
 * <h2>多个实现</h2>
 * 按注册顺序问,<b>第一个给出非 null 的胜出</b>。两个插件都想画同一个同伴本身就是冲突,
 * 先来先得至少是确定的。
 */
@FunctionalInterface
public interface CompanionPortrait {

    /**
     * @param companion 同伴的 UUID
     * @param size      引擎打算画多大(像素),仅作提示
     * @return 方形头像纹理;这个同伴你画不了就返回 {@code null}
     */
    Identifier portraitOf(UUID companion, int size);
}
