package com.dwinovo.numen.client.ui.widget;

/**
 * 贴着输入框上方弹出来的那一层。
 *
 * <h2>为什么是个基类而不是就用 {@link SelectPanel}</h2>
 * 弹层要装什么,取决于命令想说什么:{@code /skills} 要一份能上下选、回车开关的名单;
 * {@code /usage} 要一张读数卡——有构成条、有右对齐的数字、没有一行可以按。把两者塞进
 * 同一个列表控件,不是让列表长出画条的能力(那是给共享控件加只有一处用的字段),就是
 * 让读数假装成一串行。<b>列表只是弹层的一种。</b>
 *
 * <p>宿主({@code ChatInputBar})只需要两件事:多高、怎么画。键盘与鼠标沿用
 * {@link Widget} 的那套,不需要的子类什么都不覆写即可。
 */
public abstract class Popup extends Widget {

    /**
     * 装得下全部内容时的高度。宿主据此决定顶边在哪——弹层底边对齐输入框,<b>往上</b>长。
     */
    public abstract int preferredHeight();

    /** 这一层还想不想留着;{@code false} = 宿主收起它(例如按了 Esc)。 */
    public boolean alive() {
        return true;
    }
}
