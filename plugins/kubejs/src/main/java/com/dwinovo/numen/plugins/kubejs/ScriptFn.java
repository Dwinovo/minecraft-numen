package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.api.sdk.ApiFunction;
import com.dwinovo.numen.api.sdk.FunctionBuilder;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.script.ScriptCatalog;
import dev.latvian.mods.rhino.util.HideFromJS;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个脚本函数的描述,用的就是 Java 插件那一份函数描述({@link FunctionBuilder}):名字、说明、参数(类型、说明、可不可以不写)、返回、
 * 例子,最后是执行体。{@code summary} 与每个参数的说明必填——模型读的就是它们,脚本写的函数也一样。
 *
 * <p>每个方法返回自己,所以可以连着写。
 */
public final class ScriptFn {

    private final String namespace;
    private final String function;
    private final FunctionBuilder builder;
    private final List<String> undocumented = new ArrayList<>();
    private String summary = "";
    private ValueType returns;
    private boolean acts;
    private ScriptBody body;

    ScriptFn(String namespace, String group, String name) {
        this.namespace = namespace;
        this.function = namespace + "." + group + "." + name;
        this.builder = ApiFunction.builder(name);
        this.returns = ValueType.nothing(namespace);
    }

    /** 一句话:这个函数做什么。进她的 API 索引与帮助。 */
    public ScriptFn summary(String text) {
        this.summary = text == null ? "" : text.strip();
        builder.summary(summary);
        return this;
    }

    /** 一个必须写的位置参数。 */
    public ScriptFn param(String name, String type, String doc) {
        builder.required(name, ValueType.parse(type, namespace).codec(), documented(name, doc));
        return this;
    }

    /** 最后一个位置参数,收下余下的全部;{@code type} 是一项的类型。 */
    public ScriptFn rest(String name, String type, String doc) {
        builder.rest(name, ValueType.parse(type, namespace).codec(), documented(name, doc));
        return this;
    }

    /** 选项表里的一项,可以不写;{@code omitted} 说不写时会怎样("say it once")。 */
    public ScriptFn option(String name, String type, String doc, String omitted) {
        builder.option(name, ValueType.parse(type, namespace).codec(), documented(name, doc), omitted);
        return this;
    }

    public ScriptFn option(String name, String type, String doc) {
        return option(name, type, doc, null);
    }

    /** 返回值的类型;不写就是不返回值。 */
    public ScriptFn returns(String type) {
        this.returns = ValueType.parse(type, namespace);
        builder.returns(returns.codec());
        return this;
    }

    /**
     * 这个函数要动世界(经 {@code call.useEntity} 一类受权的动作):它交回的是一件等主人点头的事,所以她等它做完再往下走。
     * 不写就是当场交回值。
     */
    public ScriptFn pending() {
        this.acts = true;
        builder.kind(ScriptCatalog.Kind.PENDING);
        return this;
    }

    /** 一个例子:一行她能照抄的调用。 */
    public ScriptFn example(String line) {
        builder.example(line);
        return this;
    }

    /** 一句注意:她用这个函数时该知道的限制。 */
    public ScriptFn note(String text) {
        builder.note(text);
        return this;
    }

    /** 执行体:她调这个函数时跑。 */
    public ScriptFn run(ScriptBody run) {
        this.body = run;
        return this;
    }

    private String documented(String param, String doc) {
        if (doc == null || doc.isBlank()) {
            undocumented.add(param);
        }
        return doc;
    }

    @HideFromJS
    ApiFunction build() {
        List<String> missing = new ArrayList<>();
        if (summary.isBlank()) {
            missing.add("a summary (fn.summary(\"…\"))");
        }
        undocumented.forEach(param -> missing.add("a doc for the argument '" + param + "'"));
        if (body == null) {
            missing.add("a body (fn.run(call => …))");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(function + " needs " + String.join(", ", missing)
                    + ": the model reads these to know what the function is for");
        }
        ValueType type = returns;
        return builder.server((call, args) -> {
            Object result;
            try {
                result = body.run(new ScriptCall(function, call, args));
            } catch (RuntimeException error) {
                throw ScriptErrors.asFailure(function, error);
            }
            if (!acts) {
                return type.coerce().apply(result);
            }
            @SuppressWarnings("unchecked")
            Pending<Object> pending = result instanceof Pending<?> already ? (Pending<Object>) already
                    : Pending.of(result);
            return pending.then(value -> type.coerce().apply(value));
        });
    }
}
