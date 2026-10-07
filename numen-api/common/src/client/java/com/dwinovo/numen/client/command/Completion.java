package com.dwinovo.numen.client.command;

/**
 * 补全列表里的一行。纯数据——输入行只管照着画,不认识命令是什么东西。
 *
 * @param insert         选中它之后输入框该变成什么。吃参数的命令末尾带一个空格,
 *                       选完接着打参数,不用自己补
 * @param label          左侧显示,如 {@code /build [要求]}
 * @param note           右侧显示的说明;不可用时这里换成<b>不可用的理由</b>
 * @param enabled        {@code false} = 整行灰掉,↑↓ 跳过、回车选不中
 * @param touchesContext 行首记号:这条会不会动她。见 {@link ChatCommand#touchesContext}
 */
public record Completion(String insert, String label, String note,
                         boolean enabled, boolean touchesContext) {

    public Completion {
        insert = insert == null ? "" : insert;
        label = label == null ? "" : label;
        note = note == null ? "" : note;
    }

    /** 可用的一行。 */
    public static Completion of(String insert, String label, String note) {
        return new Completion(insert, label, note, true, false);
    }
}
