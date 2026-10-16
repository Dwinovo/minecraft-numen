package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.NumenActuator;

import java.util.List;
import com.dwinovo.numen.api.agent.inbox.ExternalWait;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 同伴的大脑还在主人客户端时({@code BrainSite.CLIENT})外脑的手:全部经 {@link NumenActuator} 落到客户端的旧循环上。
 * 动作行喂 {@link McpTranscript}——旧循环没有日志可写,面板聊天区画的是那份现场缓冲。
 */
final class ClientHands implements ExternalHands {

    @Override
    public CompletableFuture<List<Companion>> companions() {
        return NumenActuator.companions();
    }

    @Override
    public CompletableFuture<Boolean> create(String name) {
        return NumenActuator.create(name);
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID companion) {
        return NumenActuator.delete(companion);
    }

    @Override
    public CompletableFuture<String> takeEvents(UUID companion) {
        return NumenActuator.takeEvents(companion);
    }

    @Override
    public ExternalWait.Parked awaitEvents(UUID companion) {
        return NumenActuator.awaitEvents(companion);
    }

    @Override
    public CompletableFuture<Boolean> say(UUID companion, String text) {
        return NumenActuator.say(companion, text);
    }

    @Override
    public CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson) {
        return NumenActuator.invoke(companion, toolName, argsJson);
    }

    @Override
    public void activity(UUID companion, String line, boolean error) {
        McpTranscript.tool(companion, line, error);
    }
}
