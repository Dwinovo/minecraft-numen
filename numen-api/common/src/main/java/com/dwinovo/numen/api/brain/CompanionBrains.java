package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenPaths;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.request.BodySnapshot;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.EventOutbox;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.network.payload.RequestStatePayload;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.program.LocalPrograms;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 一只同伴的服务端大脑怎么从服务器的各处接起来——这个接线只在这里,产品和 GameTest 走同一个 {@link #create}:
 * 家在世界存档里({@link Homes})、模型用服主的档案({@link LlmMind})、人设从主人的库里现取、主动性是主人的偏好
 * ({@link OwnerPrefs})、程序在本进程里跑({@link LocalPrograms})、身体状态直接读身体、谁在场读主人的会话
 * ({@link Conversations})、驾驶席归外接大脑宿主。
 *
 * <p>{@link #ensure} 是"她存在,她就有大脑":名册上有、大脑表里没有就建,建之前先把旧版客户端留下的数据搬进来
 * ({@link LegacyMigration},复制不移动、只做一次),再把离线出箱里攒着的事件放进她的收件箱。
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
     * @param ownerOnline 主人在不在线
     * @param driving     驾驶席是不是在外接大脑手里
     */
    public static CompanionLoop create(MinecraftServer server, UUID companion, UUID owner, Home home, Mind mind,
                                       CompanionLoops.Link link, BooleanSupplier ownerOnline,
                                       BooleanSupplier driving) {
        CompanionRegistry registry = CompanionRegistry.get(server);
        Conversations rooms = Conversations.of(server, owner);
        PersonaLibrary personas = PersonaLibraries.of(server, owner);
        OwnerPrefs prefs = OwnerPrefs.of(server, owner);
        CompanionLoop loop = new CompanionLoop(new CompanionLoop.Parts(companion, home, server, mind,
                new LocalPrograms(server),
                () -> NumenPlayer.findByUuid(server, companion),
                () -> {
                    NumenPlayer body = NumenPlayer.findByUuid(server, companion);
                    return body == null ? null
                            : BodySnapshot.of(RequestStatePayload.snapshot(body), System.currentTimeMillis());
                },
                () -> {
                    CompanionRegistry.Entry entry = registry.find(companion);
                    return entry != null && entry.diedAt() > 0L;
                },
                ownerOnline, driving, prefs::initiative,
                id -> {
                    PersonaLibrary.Persona persona = personas.get(id);
                    return persona == null ? null : persona.text();
                },
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
        migrateLegacy(server, companion, owner, homes);
        CompanionLoop loop = create(server, companion, owner, homes.companion(companion),
                new LlmMind(ProviderLibrary.instance()), Replies.link(server, owner),
                () -> server.getPlayerList().getPlayer(owner) != null,
                com.dwinovo.numen.api.mcp.server.McpMode.instance()::driving);
        // 离线出箱里攒着的:从此她的收件箱就是离线时攒东西的地方,出箱里的一次性倒进来
        List<EventQueue.Entry> pending = EventOutbox.get(server).take(companion, System.currentTimeMillis());
        if (!pending.isEmpty()) {
            loop.push(pending);
            Constants.LOG.info("[numen-outbox] {} 的 {} 条离线输入进了收件箱", companion, pending.size());
        }
        return loop;
    }

    /** 旧版客户端留在 {@code config/numen/companions/<uuid>/} 里的她的家:单人游戏的房主,搬进世界里她的家。 */
    private static void migrateLegacy(MinecraftServer server, UUID companion, UUID owner, Homes homes) {
        if (!isHost(server, owner)) {
            return;
        }
        Path legacy = NumenPaths.config().resolve("companions").resolve(companion.toString());
        LegacyMigration.companion(legacy, homes.companion(companion).dir());
    }

    /** 这位主人是不是单人游戏的房主:旧版留下的数据只可能是他的。 */
    static boolean isHost(MinecraftServer server, UUID owner) {
        var profile = server.getSingleplayerProfile();
        return profile != null && owner.equals(profile.getId());
    }
}
