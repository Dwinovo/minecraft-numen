package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;

import java.util.function.Supplier;

/**
 * 一行只读的「标签 + 值」。
 *
 * <p>用在展示型分区上:端点地址、令牌、连接状态这类主人只看不改的东西。它<b>是个控件</b>,
 * 因此和表单行共用同一个 {@code ry} 游标——只读文本一旦改成在 render 里按固定 Y 手绘,
 * 中间插一行就得把后面所有常量重排一遍。
 *
 * <p>值走 {@link Supplier} 惰性取:这些东西随时在变(服务起停、客户端接入),build 时捕获
 * 会把过期值钉死在屏幕上。
 *
 * <p>高 {@value #HEIGHT}px——比表单行矮,因为没有控件要放。
 */
public final class ValueRow extends Widget {

    /** 行高。标签一行、值一行,紧凑排布。 */
    public static final int HEIGHT = 16;

    private final String label;
    private final Supplier<String> value;
    private Supplier<Boolean> dimmed;

    public ValueRow(String label, Supplier<String> value) {
        this.label = label == null ? "" : label;
        this.value = value;
    }

    /**
     * 值什么时候画成灰的。给「未设置」「暂无」这类占位文字用——它们是提示不是数据,
     * 跟真值同色会让人以为那就是内容。主题色在渲染时才拿得到,所以这里只表达"要不要灰"。
     */
    public ValueRow dimWhen(Supplier<Boolean> dimmed) {
        this.dimmed = dimmed;
        return this;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (!visible) {
            return;
        }
        s.drawText(TextClip.ellipsize(s, label, Math.max(0, Math.min(LABEL_W - 4, w))),
                x, y, c.textMuted(), false);
        if (w <= LABEL_W) {
            return;
        }
        String text = value == null ? "" : value.get();
        boolean grey = dimmed != null && Boolean.TRUE.equals(dimmed.get());
        s.drawText(TextClip.ellipsize(s, text, Math.max(0, w - LABEL_W)), x + LABEL_W, y,
                grey ? c.textMuted() : c.textPrimary(), false);
    }

    /** 标签列宽——值列从这儿起,几行的值才对得齐。 */
    public static final int LABEL_W = 46;
}
