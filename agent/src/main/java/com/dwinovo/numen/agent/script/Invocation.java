package com.dwinovo.numen.agent.script;

import com.google.gson.JsonObject;

/**
 * 脚本里的一次 API 调用,读成了一个函数和它的参数:参数按参数名放进 JSON,值是读好的参数再写回的 Lua 值(规范的写法)。由登记处从
 * 脚本的调用换来({@link ScriptCall.Host#invocation}),交给派发的一方执行;执行的那一侧按同一张参数表、同一种值转换再读一遍。
 *
 * @param group    组的全名,{@code numen.work}
 * @param name     函数名,{@code dig}
 * @param function 脚本里的函数全名,{@code numen.work.dig}:回执与任务名都这样写它
 * @param args     参数名 → Lua 值的 JSON
 */
public record Invocation(String group, String name, String function, JsonObject args) {}
