package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.loop.HaltReason;
import com.dwinovo.numen.api.agent.request.BodySnapshot;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.network.payload.RequestStatePayload;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.program.LocalPrograms;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * 一只同伴的服务端大脑怎么从服务器的各处接起来——这个接线只在这里,产品和 GameTest 走同一个 {@link #create}:
 * 家在世界存档里({@link Homes})、模型用服主的档案({@link LlmMind})、人设从主人的库里现取、主动性是主人的偏好
 * ({@link OwnerPrefs})、程序在本进程里跑({@link LocalPrograms})、身体状态直接读身体、谁在场读主人的会话
 * ({@link Conversations})、驾驶席归外接大脑宿主。
 *
 * <p>{@link #ensure} 是"她存在,她就有大脑":名册上有、大脑表里没有就建。
 *
 * <p>线程:服务端主线程。
 */
public final class CompanionBrains {

    private CompanionBrains() {}

    /**
     * 建一只同伴的大脑并登记。
     *
     * @param home        她的家(产品是世界里的 {@code companions/<uuid>/})
     * @param mind        怎么跟模型说话
     * @param link        主人那一头:事件推给谁、没有客户端时她的话怎么告诉他
     * @param ownerOnline 主人在不在场({@link OwnerPresence})
     * @param driving     驾驶席是不是在外接大脑手里
     */
    public static CompanionLoop create(MinecraftServer server, UUID companion, UUID owner, Home home, Mind mind,
                                       CompanionLoops.Link link, BooleanSupplier ownerOnline,
                                       BooleanSupplier driving) {
        PersonaLibrary personas = PersonaLibraries.of(server, owner);
        return create(server, companion, owner, home, mind, link, ownerOnline, driving,
                OwnerPrefs.of(server, owner)::initiative, id -> {
                    PersonaLibrary.Persona persona = personas.get(id);
                    return persona == null ? null : persona.text();
                });
    }

    /**
     * 同上,主动性与人设正文不从主人的偏好和人设库取,由调用方给:评测用它把这两样定死,模型之外的一切仍是产品的零件。
     *
     * @param initiative 主动性档位 1~10
     * @param persona    人设 id 对应的正文;没有这个人设返回 {@code null}
     */
    public static CompanionLoop create(MinecraftServer server, UUID companion, UUID owner, Home home, Mind mind,
                                       CompanionLoops.Link link, BooleanSupplier ownerOnline,
                                       BooleanSupplier driving, IntSupplier initiative,
                                       Function<String, String> persona) {
        CompanionRegistry registry = CompanionRegistry.get(server);
        Conversations rooms = Conversations.of(server, owner);
        CompanionLoop loop = new CompanionLoop(new CompanionLoop.Parts(companion, home, server, mind,
                new LocalPrograms(server),
                new OwnerTools(owner, LibraryHub.of(server).library(McpClientConfig.KIND, owner)),
                () -> NumenPlayer.findByUuid(server, companion),
                () -> {
                    NumenPlayer body = NumenPlayer.findByUuid(server, companion);
                    return body == null ? null
                            : BodySnapshot.of(RequestStatePayload.snapshot(body), System.currentTimeMillis());
                },
                () -> {
                    CompanionRegistry.Entry entry = registry.find(companion);
                    return entry == null ? null : entry.diedAt() > 0L ? HaltReason.DEATH
                            : entry.removed() ? HaltReason.REMOVED : null;
                },
                ownerOnline, driving, initiative, persona,
                () -> server.overworld().getDayTime(), rooms));
        CompanionLoops.install(owner, loop, link, rooms);
        return loop;
    }

    /**
     * 她该有大脑:没有就建。名册上没有她(遣散了)是 {@code null}。
     */
    public static CompanionLoop ensure(MinecraftServer server, UUID companion) {
        var have = CompanionLoops.get(companion);
        if (have.isPresent()) {
            return have.get();
        }
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
        if (entry == null) {
            return null;
        }
        UUID owner = entry.owner();
        Homes homes = Homes.of(server);
        McpClients.of(server).owner(owner);   // 他自己接进来的 MCP 服务器,从这一刻起连着
        CompanionLoop loop = create(server, companion, owner, homes.companion(companion),
                new LlmMind(ModelServices.of(server).providers(owner), owner, ModelServices.of(server).quota()),
                Replies.link(server, owner),
                () -> OwnerPresence.present(server, owner),
                () -> com.dwinovo.numen.api.mcp.server.McpMode.instance().driving(owner, companion));
        return loop;
    }

    /** 把这位主人名下各只的大脑放掉重建,并把此刻的样子补给他的客户端。 */
    public static void reset(MinecraftServer server, UUID owner) {
        for (var entry : CompanionRegistry.get(server).ownedBy(owner)) {
            CompanionLoops.release(entry.getKey());
            ensure(server, entry.getKey());
        }
        var player = server.getPlayerList().getPlayer(owner);
        if (player != null && Presence.hasClient(owner)) {
            CompanionLoops.sendSnapshots(owner, Replies.link(server, owner));
        }
    }
}
