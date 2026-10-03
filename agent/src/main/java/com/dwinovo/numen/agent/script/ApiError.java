package com.dwinovo.numen.agent.script;

/**
 * 一次 API 调用在派出去之前就不成立:没有这个函数({@link ErrorKind#NO_FUNCTION})、参数读不成({@link ErrorKind#BAD_ARGUMENT})。
 * 命令层把脚本的调用读成动作时抛出({@link ScriptCall.Host#invocation}),脚本在调用处收到同样字段的错误值。
 */
public final class ApiError extends IllegalArgumentException {

    private final ErrorKind kind;
    private final String hint;

    /**
     * @param message 错在哪(参数错写明哪个参数、收什么形状、给了什么),可以带上用法的几行
     * @param hint    能照抄的下一步;没有是 null
     */
    public ApiError(ErrorKind kind, String message, String hint) {
        super(message);
        this.kind = kind;
        this.hint = hint;
    }

    public ErrorKind kind() {
        return kind;
    }

    public String hint() {
        return hint;
    }
}
