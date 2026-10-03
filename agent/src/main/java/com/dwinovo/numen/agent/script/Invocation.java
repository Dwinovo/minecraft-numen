package com.dwinovo.numen.agent.script;

import com.google.gson.JsonObject;

/**
 * 脚本里的一次 API 调用,读成了一个动作和它的参数:参数按参数名放进 JSON,值是它们在参数类型里的写法。由命令层从脚本的调用
 * 换来({@link ScriptCall.Host#invocation}),交给派发的一方执行;执行的那一侧按同一张参数表、同一种参数类型把它读成值。
 *
 * @param group    组名,{@code work}
 * @param verb     动作名,{@code dig}
 * @param function 脚本里的函数名,{@code work.dig}:回执与任务名都这样写它
 * @param args     参数名 → 值
 */
public record Invocation(String group, String verb, String function, JsonObject args) {}
