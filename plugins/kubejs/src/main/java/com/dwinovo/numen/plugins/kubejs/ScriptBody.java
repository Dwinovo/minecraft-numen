package com.dwinovo.numen.plugins.kubejs;

/** 脚本函数的执行体:她调这个函数时跑,{@code call} 里是这一次的参数与受权的动作。返回的值按函数声明的 {@code returns} 交回。 */
@FunctionalInterface
public interface ScriptBody {

    Object run(ScriptCall call);
}
