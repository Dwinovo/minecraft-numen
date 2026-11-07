package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.script.ScriptCatalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 用代码写一个 API 函数的描述({@link ApiFunction#builder}):名字、说明、参数(类型、说明、是不是可以不写)、返回、例子,最后一句
 * 说它在哪执行、怎么执行。反射读 {@code @Fn} 方法({@link Binder})是另一个生产者,出来的描述一样,登记、帮助、体检都分不出谁写的;
 * 脚本里没有 Java 类可读,脚本插件的函数就这样写。
 *
 * <pre>{@code
 * ApiFunction.builder("greet")
 *         .summary("Say hello to a player by name.")
 *         .required("who", LuaCodecs.of(String.class, "mypack"), "The player's name.")
 *         .option("loud", LuaCodecs.of(Boolean.class, "mypack"), "Shout it.", "say it normally")
 *         .returns(LuaCodecs.of(String.class, "mypack"))
 *         .example("mypack.talk.greet(\"Steve\")")
 *         .server((call, args) -> "hello " + args.get("who"));
 * }</pre>
 *
 * <p>不拦写法:没写说明、没写例子都能登记,交给 {@code ApiTester.lint}。会破坏系统的(名字写不出来、可以不写的位置参数不在最后、
 * 客户端的函数不当场返回)在最后一句抛 {@link IllegalArgumentException}。
 */
public final class FunctionBuilder {

    /** 服务端函数的执行体:交回 {@code R}、{@link Pending} 或 {@link Job},按 {@link #kind}。 */
    @FunctionalInterface
    public interface ServerBody {
        Object run(ServerCall call, FnArgs args);
    }

    /** 主人客户端函数的执行体:当场交回值。 */
    @FunctionalInterface
    public interface ClientBody {
        Object run(ClientCall call, FnArgs args);
    }

    private final String name;
    private String summary = "";
    private final List<ApiFunction.Param> params = new ArrayList<>();
    private Codec<?> returns = Builtin.NOTHING;
    private ScriptCatalog.Kind kind = ScriptCatalog.Kind.VALUE;
    private final List<String> examples = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> seeAlso = new ArrayList<>();

    FunctionBuilder(String name) {
        this.name = name;
    }

    public FunctionBuilder summary(String summary) {
        this.summary = summary == null ? "" : summary;
        return this;
    }

    /** 一个必须写的位置参数。 */
    public FunctionBuilder required(String name, Codec<?> type, String doc) {
        return param(name, ApiFunction.Role.REQUIRED, type, doc, null);
    }

    /** 最后一个位置参数,可以不写。{@code omitted} 说不写时会怎样。 */
    public FunctionBuilder positional(String name, Codec<?> type, String doc, String omitted) {
        return param(name, ApiFunction.Role.OPTIONAL, type, doc, omitted);
    }

    /** 最后一个位置参数,收下余下的全部(也可以只给一张列表);{@code item} 是一项的类型。 */
    public FunctionBuilder rest(String name, Codec<?> item, String doc) {
        return param(name, ApiFunction.Role.REST, item, doc, null);
    }

    /** 选项表里的一项,可以不写。 */
    public FunctionBuilder option(String name, Codec<?> type, String doc, String omitted) {
        return param(name, ApiFunction.Role.OPTION, type, doc, omitted);
    }

    @SuppressWarnings("unchecked")
    private FunctionBuilder param(String name, ApiFunction.Role role, Codec<?> type, String doc, String omitted) {
        params.add(new ApiFunction.Param(name, role, (Codec<Object>) type, doc, omitted));
        return this;
    }

    /** 返回值的类型;不写是不返回值。 */
    public FunctionBuilder returns(Codec<?> type) {
        this.returns = type;
        return this;
    }

    /** 怎么交回:当场的值({@code VALUE},默认)、等一会儿({@code PENDING})、占身体的活({@code JOB})。 */
    public FunctionBuilder kind(ScriptCatalog.Kind kind) {
        this.kind = kind;
        return this;
    }

    public FunctionBuilder example(String example) {
        examples.add(example);
        return this;
    }

    public FunctionBuilder note(String note) {
        notes.add(note);
        return this;
    }

    public FunctionBuilder seeAlso(String... functions) {
        seeAlso.addAll(Arrays.asList(functions));
        return this;
    }

    /** 在服务端执行(身体与世界)。 */
    public ApiFunction server(ServerBody body) {
        return build(ApiFunction.Side.SERVER, (call, args) -> body.run((ServerCall) call, args));
    }

    /** 在主人客户端执行(只有那里才有的数据)。 */
    public ApiFunction client(ClientBody body) {
        return build(ApiFunction.Side.CLIENT, (call, args) -> body.run((ClientCall) call, args));
    }

    @SuppressWarnings("unchecked")
    private ApiFunction build(ApiFunction.Side side, ApiFunction.Handler handler) {
        return new ApiFunction(null, null, name, summary, side, kind, params, (Codec<Object>) returns, examples,
                notes, seeAlso, handler);
    }
}
