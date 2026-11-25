package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.agent.request.TaskReport;
import com.dwinovo.numen.api.brain.Conversations;
import com.dwinovo.numen.api.brain.Hearing;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.brain.Spoken;
import com.dwinovo.numen.api.brain.Voice;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.program.ClientTransport;
import com.dwinovo.numen.api.program.LocalPrograms;
import com.dwinovo.numen.api.program.ProgramRunner;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.Executor;

/** 产品里的 {@link SeatWorld}:正在跑的那个 Minecraft 服务端。服务器起来时接上,停了放掉。 */
final class ServerSeatWorld implements SeatWorld {

    private volatile MinecraftServer server;
    private volatile LocalPrograms programs;
    private volatile BodyReports reports;

    void start(MinecraftServer server, ClientTransport transport) {
        this.programs = new LocalPrograms(server, transport);
        this.reports = new BodyReports(server);
        this.server = server;
    }

    void stop() {
        this.server = null;
        this.programs = null;
        this.reports = null;
    }

    private MinecraftServer running() {
        MinecraftServer running = server;
        if (running == null) {
            throw new IllegalStateException("no server is running");
        }
        return running;
    }

    @Override
    public Executor main() {
        return task -> running().execute(task);
    }

    @Override
    public boolean mayOpen(UUID player, UUID companion) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(running()).find(companion);
        return entry != null && entry.owner().equals(player) && Presence.hasClient(player);
    }

    @Override
    public SeatState state(UUID companion) {
        return reports.state(companion);
    }

    /** 任务槽说她在做什么。 */
    void task(UUID companion, TaskReport report) {
        BodyReports mine = reports;
        if (mine != null) {
            mine.task(companion, report);
        }
    }

    /** 她被遣散了。 */
    void forget(UUID companion) {
        BodyReports mine = reports;
        if (mine != null) {
            mine.forget(companion);
        }
    }

    @Override
    public void occupancyChanged(UUID companion) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(running()).find(companion);
        if (entry != null) {
            Companions.reconcile(running(), entry.owner());
        }
    }

    @Override
    public long gameTime() {
        return running().overworld().getGameTime();
    }

    @Override
    public long dayTime() {
        return running().overworld().getDayTime();
    }

    @Override
    public ProgramRunner programs() {
        return programs;
    }

    @Override
    public boolean keeps(UUID companion, UUID person) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(running()).find(companion);
        return entry != null && entry.usableBy(person);
    }

    /**
     * 她的话说出口:{@code spoken} 是她在答谁、他在哪儿问的,话回到那里——面板里问的是他自己的聊天行,私聊回私信,群里回带会话名的聊天行、
     * 同会话的其他成员旁听到一份。服务端唯一说话的一处,客户端从不碰聊天。
     */
    @Override
    public void say(UUID companion, String text, Spoken spoken) {
        MinecraftServer server = running();
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
        if (entry == null) {
            return;
        }
        ServerPlayer to = server.getPlayerList().getPlayer(spoken.who());
        if (to != null) {
            Conversations rooms = Conversations.of(server, spoken.who());
            String title = spoken.conversation() == null ? null : rooms.titleFor(companion, spoken.conversation());
            to.sendSystemMessage(Voice.line(spoken.venue(), entry.name(), title, text));
        }
        if (spoken.conversation() != null) {
            Conversation conv = Conversations.of(server, spoken.who()).get(spoken.conversation());
            if (conv != null) {
                Hearing.heard(server, spoken.who(), conv, companion, text);
            }
        }
    }

    @Override
    public JsonObject catalog(UUID companion) {
        return SeatCatalog.of(companion);
    }
}
