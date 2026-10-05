package com.dwinovo.numen.agent.script;

/**
 * 一次 API 调用失败:API 函数只经它说失败,脚本在调用处收到同样字段的错误值({@link ScriptRun#failure}),{@code pcall} 接住后按
 * {@code err.kind} 分支。种类少而稳定({@link ErrorKind});查询查不到不是失败,交 nil 或空表。
 *
 * <p>{@code hint} 是能照抄的下一行程序,用写调用的那一处写出(api 的 {@code Call.of}),不手拼。{@code data} 是失败时知道的东西
 * (够不着的最近一格这类),和返回值一样经值转换写成 Lua 的值。
 */
public final class ApiError extends RuntimeException {

    private final ErrorKind kind;
    private final String hint;
    private final transient Object data;

    /**
     * @param message 错在哪(参数错写明哪个参数、收什么形状、给了什么)
     * @param hint    能照抄的下一行程序;没有是 null
     */
    public ApiError(ErrorKind kind, String message, String hint) {
        this(kind, message, hint, null);
    }

    /** @param data 失败时知道的东西;没有是 null */
    public ApiError(ErrorKind kind, String message, String hint, Object data) {
        super(message, null, false, false);
        this.kind = kind;
        this.hint = hint;
        this.data = data;
    }

    public ErrorKind kind() {
        return kind;
    }

    public String hint() {
        return hint;
    }

    public Object data() {
        return data;
    }
}
