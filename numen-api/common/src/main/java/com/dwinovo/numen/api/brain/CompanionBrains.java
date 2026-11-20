package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.loop.HaltReason;
import com.dwinovo.numen.api.agent.request.BodySnapshot;
import com.dwinovo.numen.api.agent.request.Nearby;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.network.payload.RequestStatePayload;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.program.LocalPrograms;
import com.dwinovo.numen.api.text.ServerLang;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * 一只同伴的服务端大脑怎么从服务器的各处接起来——这个接线只在这里,产品和 GameTest 走同一个 {@link #create}:
 * 家在世界存档里({@link Homes})、模型用她绑的档案({@link LlmMind})、人设从绑它的那个人的库里现取({@link Bound})、主动性是主人的偏好
 * ({@link OwnerPrefs})、程序在本进程里跑({@link LocalPrograms})、身体状态直接读身体、谁在场读说话那位主人的会话
 * ({@link Conversations})、驾驶席归外接大脑宿主。她的主人表(创建者加名册上分享给的人)现问名册。
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
     * @param owner       她的创建者
     * @param link        主人那一头:事件推给谁、没有客户端时她的话怎么告诉他
     * @param ownerOnline 主人在不在场({@link OwnerPresence})
     * @param driving     驾驶席是不是在外接大脑手里
     */
    public static CompanionLoop create(MinecraftServer server, UUID companion, UUID owner, Home home, Mind mind,
                                       CompanionLoops.Link link, BooleanSupplier ownerOnline,
                                       BooleanSupplier driving) {
        return create(server, companion, owner, home, mind, link, ownerOnline, driving,
                OwnerPrefs.of(server, owner)::initiative, bound -> {
                    PersonaLibrary.Persona persona = PersonaLibraries.of(server, bound.by()).get(bound.id().toString());
                    return persona == null ? null : persona.text();
                });
    }

    /**
     * 同上,主动性与人设正文不从主人的偏好和人设库取,由调用方给:评测用它把这两样定死,模型之外的一切仍是产品的零件。
     *
     * @param initiative 主动性档位 1~10
     * @param persona    她绑的人设的正文;没有这个人设返回 {@code null}
     */
    public static CompanionLoop create(MinecraftServer server, UUID companion, UUID owner, Home home, Mind mind,
                                       CompanionLoops.Link link, BooleanSupplier ownerOnline,
                                       BooleanSupplier driving, IntSupplier initiative,
                                       Function<Bound, String> persona) {
        CompanionRegistry registry = CompanionRegistry.get(server);
        Function<UUID, Conversations> roomsOf = who -> Conversations.of(server, who);
        CompanionLoop loop = new CompanionLoop(new CompanionLoop.Parts(companion, owner, home, server, mind,
                new LocalPrograms(server),
                toolsOf(server, owner),
                () -> NumenPlayer.findByUuid(server, companion),
                () -> {
                    NumenPlayer body = NumenPlayer.findByUuid(server, companion);
                    return body == null ? null
                            : BodySnapshot.of(RequestStatePayload.snapshot(body), System.currentTimeMillis())
                                    .withNearby(Nearby.of(body));
                },
                () -> {
                    CompanionRegistry.Entry entry = registry.find(companion);
                    return entry == null ? null : switch (entry.outside().why()) {
                        case SERVER -> HaltReason.REMOVED;
                        case OWNER -> HaltReason.OFFLINE;
                        case NONE -> null;
                    };
                },
                ownerOnline, driving, initiative, persona, player -> NumenPlayer.playerName(server, player),
                () -> {
                    NumenPlayer body = NumenPlayer.findByUuid(server, companion);
                    ServerPlayer keeper = body == null ? null : body.keeperAtHand();
                    return keeper == null ? null : ServerLang.languageName(ServerLang.localeOf(keeper));
                },
                () -> server.overworld().getDayTime(), () -> server.overworld().getGameTime(),
                (speaker, her, conversation) -> roomsOf.apply(speaker).audienceLine(her, conversation),
                () -> keepers(registry, owner, companion)));
        CompanionLoops.install(owner, loop, link, roomsOf);
        return loop;
    }

    /** 她的主人表,创建者在前:创建者是建大脑时给的那一位,分享给了谁现问名册。 */
    private static List<UUID> keepers(CompanionRegistry registry, UUID owner, UUID companion) {
        List<UUID> out = new ArrayList<>();
        out.add(owner);
        out.addAll(registry.sharedWith(companion));
        return out;
    }

    /** 这位主人的同伴看得到的工具:内置大脑与外接大脑(MCP 端点)问的是同一份。 */
    public static ToolBook toolsOf(MinecraftServer server, UUID owner) {
        return new OwnerTools(owner, LibraryHub.of(server).library(McpClientConfig.KIND, owner));
    }

    /**
     * 她该有大脑:没有就建。名册上没有她(遣散了)是 {@code null}。
     */
    public static CompanionLoop ensure(MinecraftServer server, UUID companion) {
        var have = CompanionLoops.get(companion);
        if (have.isPresent()) {
            return have.get();
        }
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = registry.find(companion);
        if (entry == null) {
            return null;
        }
        UUID owner = entry.owner();
        Homes homes = Homes.of(server);
        McpClients.of(server).owner(owner);   // 他自己接进来的 MCP 服务器,从这一刻起连着
        ModelServices models = ModelServices.of(server);
        CompanionLoop loop = create(server, companion, owner, homes.companion(companion),
                new LlmMind(models::providers, models.catalog(), models.quota()),
                new KeeperLinks(() -> keepers(registry, owner, companion), who -> Replies.link(server, who),
                        () -> CompanionLoops.get(companion).map(CompanionLoop::speaker).orElse(null)),
                () -> OwnerPresence.present(server, keepers(registry, owner, companion)),
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
            Replies.snapshots(server, owner);
        }
    }
}
