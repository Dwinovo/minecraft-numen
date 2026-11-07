package com.dwinovo.numen.e2e;

import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.chat.ChatCompletionAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCustomChatCompletionsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandSignedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundCommandSuggestionPacket;

import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 无头的原版客户端:用 MCProtocolLib 按原版协议连进服务端,不装任何模组,做玩家在原版客户端里能做的事——敲指令、打字、
 * 点聊天里的按钮(就是把那个按钮的命令敲出去)、请求补全。收到的聊天、动作栏、补全词都记下来,场景据此断言。
 *
 * <p>线程:网络线程写、场景线程读,记录都在同一把锁里。
 */
final class Bot implements AutoCloseable {

    /** 一条服务端发来的聊天类消息。 */
    record Message(Kind kind, Component content, boolean overlay) {
        enum Kind {
            /** 系统消息与动作栏({@code ClientboundSystemChatPacket}):指令的回话、她的话、征询、状态。 */
            SYSTEM,
            /** 玩家聊天({@code ClientboundPlayerChatPacket})。 */
            PLAYER,
            /** 无来源的聊天({@code ClientboundDisguisedChatPacket})。 */
            DISGUISED
        }

        String text() {
            return Text.plain(content);
        }
    }

    /** 服务端对一次补全请求的回答。 */
    record Suggestions(int start, int length, List<String> matches) {}

    /** 指令里带 {@code message} 参数、原版客户端要签名发送的那几条。其余发不签名的包。 */
    private static final Set<String> SIGNED_COMMANDS = Set.of("msg", "tell", "w", "say", "me", "teammsg", "tm");

    /** 环境变量 E2E_TRACE 非空时把收发的包名打出来——协议对不上时的排查口。 */
    private static final boolean TRACE = System.getenv("E2E_TRACE") != null;

    private final String name;
    private final Session session;
    private final Object lock = new Object();
    private final List<Message> messages = new ArrayList<>();
    private final List<List<String>> completionHistory = new ArrayList<>();
    private final Map<Integer, CompletableFuture<Suggestions>> suggestions = new ConcurrentHashMap<>();
    private final AtomicInteger transaction = new AtomicInteger();
    private final CompletableFuture<Void> joined = new CompletableFuture<>();
    private volatile String disconnected;
    private boolean informed;
    private final List<String> packetErrors = new CopyOnWriteArrayList<>();
    private final List<String> payloadChannels = new CopyOnWriteArrayList<>();
    private final List<ClientboundCustomPayloadPacket> payloads = new CopyOnWriteArrayList<>();

    private Bot(String name, String locale, Spec server) {
        this.name = name;
        this.session = new TcpClientSession(server.host(), server.port(), new MinecraftProtocol(name));
        session.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session from, Packet packet) {
                if (TRACE) {
                    System.out.println("[bot " + name + "] <- " + packet.getClass().getSimpleName());
                }
                received(packet, locale);
            }

            @Override
            public void packetSent(Session from, Packet packet) {
                if (TRACE) {
                    System.out.println("[bot " + name + "] -> " + packet.getClass().getSimpleName());
                }
            }

            @Override
            public void packetError(org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent event) {
                // 收到的包读不通,是协议对不上(或服务端发了原版客户端读不了的东西):场景收场时报出来
                packetErrors.add(event.getCause().toString());
                event.setSuppress(true);
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                String reason = Text.plain(event.getReason());
                disconnected = reason.isEmpty() ? "(no reason)" : reason;
                joined.completeExceptionally(new IllegalStateException(name + " disconnected: " + disconnected));
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
    }

    /** 以这个名字、这个客户端语言进服;等到进了游戏才返回,被拒就抛出服务端给的原因。 */
    static Bot join(Spec server, String name, String locale) throws Exception {
        Bot bot = new Bot(name, locale, server);
        bot.session.connect();
        try {
            bot.joined.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            bot.session.disconnect("join failed");
            throw e;
        }
        return bot;
    }

    private void received(Packet packet, String locale) {
        if (packet instanceof ClientboundCustomPayloadPacket payload) {
            payloadChannels.add(payload.getChannel().asString());
            payloads.add(payload);
        }
        if (packet instanceof ClientboundCustomPayloadPacket && !informed) {
            // 配置阶段的第一个包(服务端品牌)到了:原版客户端在这个阶段把自己的语言报上去
            informed = true;
            session.send(new ServerboundClientInformationPacket(locale, 8, ChatVisibility.FULL, true,
                    List.of(SkinPart.values()), HandPreference.RIGHT_HAND, false, true));
        } else if (packet instanceof ClientboundPingPacket ping) {
            // 配置阶段服务端用 ping 探客户端处理到哪了;原版客户端立刻原样回 pong
            session.send(new ServerboundPongPacket(ping.getId()));
        } else if (packet instanceof ClientboundLoginPacket) {
            joined.complete(null);
        } else if (packet instanceof ClientboundSystemChatPacket chat) {
            record(new Message(Message.Kind.SYSTEM, chat.getContent(), chat.isOverlay()));
        } else if (packet instanceof ClientboundPlayerChatPacket chat) {
            Component unsigned = chat.getUnsignedContent();
            record(new Message(Message.Kind.PLAYER, unsigned != null ? unsigned : Component.text(chat.getContent()),
                    false));
        } else if (packet instanceof ClientboundDisguisedChatPacket chat) {
            record(new Message(Message.Kind.DISGUISED, chat.getMessage(), false));
        } else if (packet instanceof ClientboundCustomChatCompletionsPacket completions) {
            synchronized (lock) {
                completionWords(completions.getAction(), completions.getEntries());
                lock.notifyAll();
            }
        } else if (packet instanceof ClientboundCommandSuggestionsPacket reply) {
            CompletableFuture<Suggestions> waiting = suggestions.remove(reply.getTransactionId());
            if (waiting != null) {
                waiting.complete(new Suggestions(reply.getStart(), reply.getLength(), List.of(reply.getMatches())));
            }
        } else if (packet instanceof ClientboundDisconnectPacket kick) {
            disconnected = Text.plain(kick.getReason());
        }
    }

    private final List<String> words = new ArrayList<>();

    /** 聊天框的自定义补全词:原版客户端的规矩,ADD 加、REMOVE 去、SET 整个换。 */
    private void completionWords(ChatCompletionAction action, String[] entries) {
        switch (action) {
            case ADD -> words.addAll(List.of(entries));
            case REMOVE -> words.removeAll(List.of(entries));
            case SET -> {
                words.clear();
                words.addAll(List.of(entries));
            }
        }
        completionHistory.add(List.copyOf(words));
    }

    private void record(Message message) {
        synchronized (lock) {
            messages.add(message);
            lock.notifyAll();
        }
    }

    // ---- 玩家做的事 ----

    /** 敲一条指令(不带斜杠,和聊天栏里输入 {@code /…} 发出去的一样)。 */
    void command(String command) {
        String verb = command.split(" ", 2)[0];
        if (SIGNED_COMMANDS.contains(verb)) {
            session.send(new ServerboundChatCommandSignedPacket(command, System.currentTimeMillis(), 0L, List.of(),
                    0, new BitSet(20)));
        } else {
            session.send(new ServerboundChatCommandPacket(command));
        }
    }

    /** 在聊天栏打一句话发出去。 */
    void say(String message) {
        session.send(new ServerboundChatPacket(message, System.currentTimeMillis(), 0L, null, 0, new BitSet(20)));
    }

    /** 点聊天里的按钮:原版客户端对 {@code run_command} 的点击,就是把那条命令当成自己敲的发出去。 */
    void click(String runCommand) {
        command(runCommand.startsWith("/") ? runCommand.substring(1) : runCommand);
    }

    /** 在聊天栏输入到这里按下 Tab,问服务端有什么补全;{@code text} 不带斜杠开头也行,带了也行。 */
    Suggestions suggest(String text, Duration timeout) throws Exception {
        int id = transaction.incrementAndGet();
        CompletableFuture<Suggestions> answer = new CompletableFuture<>();
        suggestions.put(id, answer);
        session.send(new ServerboundCommandSuggestionPacket(id, text));
        try {
            return answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(name + ": 补全 \"" + text + "\" 没有回答");
        }
    }

    // ---- 看到的东西 ----

    /** 此刻收到的消息数;之后的 {@link #await} 只看这个位置以后的。 */
    int mark() {
        synchronized (lock) {
            return messages.size();
        }
    }

    /** 此刻自定义补全词的完整历史(每次变动一份)。 */
    List<List<String>> completionHistory() {
        synchronized (lock) {
            return List.copyOf(completionHistory);
        }
    }

    /** 此刻的自定义补全词。 */
    List<String> completionWords() {
        synchronized (lock) {
            return List.copyOf(words);
        }
    }

    /** 等 {@code since} 之后出现满足条件的消息;到点没有就失败,连同这段时间收到的全部消息一起报出来。 */
    Message await(int since, Predicate<Message> wanted, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (true) {
                for (Message message : messages.subList(since, messages.size())) {
                    if (wanted.test(message)) {
                        return message;
                    }
                }
                long left = deadline - System.nanoTime();
                if (left <= 0 || disconnected != null) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            StringBuilder seen = new StringBuilder();
            for (Message message : messages.subList(since, messages.size())) {
                seen.append("\n  ").append(message.overlay() ? "[bar] " : "").append(message.text());
            }
            throw new AssertionError(name + ": 没等到 " + what + (disconnected != null ? "(已断线: " + disconnected + ")" : "")
                    + ";这段时间收到:" + (seen.isEmpty() ? " 无" : seen));
        }
    }

    /** 等 {@code since} 之后任意一条满足条件的补全词变动(整个补全词表满足条件)。 */
    List<String> awaitCompletionWords(Predicate<List<String>> wanted, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (true) {
                if (wanted.test(words)) {
                    return List.copyOf(words);
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError(name + ": 没等到 " + what + ";补全词历史: " + completionHistory);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(name + ": 等待被打断");
                }
            }
        }
    }

    /** 收到但读不通的包。 */
    List<String> packetErrors() {
        return List.copyOf(packetErrors);
    }

    /** 服务端发来的自定义载荷频道——原版客户端自己用不上的东西(我们的包)不该出现在这里。 */
    List<String> payloadChannels() {
        return List.copyOf(payloadChannels);
    }

    /** 模拟可选模组登记频道与发包;场景自己检查对应版本的载荷。 */
    void payload(String channel, byte[] data) {
        session.send(new ServerboundCustomPayloadPacket(net.kyori.adventure.key.Key.key(channel), data));
    }

    List<byte[]> payloads(String channel) {
        return payloads.stream().filter(p -> p.getChannel().asString().equals(channel))
                .map(ClientboundCustomPayloadPacket::getData).toList();
    }

    boolean connected() {
        return session.isConnected() && disconnected == null;
    }

    String name() {
        return name;
    }

    @Override
    public void close() {
        if (session.isConnected()) {
            session.disconnect("e2e done");
        }
    }
}
