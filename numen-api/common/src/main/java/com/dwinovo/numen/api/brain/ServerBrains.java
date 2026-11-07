package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenPaths;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.voice.VoiceProfile;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.mcp.server.ServerMcp;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.skin.SkinCache;
import com.dwinovo.numen.api.skin.SkinRef;
import com.dwinovo.numen.api.store.Trees;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * 服务器起来时同伴的大脑在服务端开工要备好的东西:札记与 Lua 模块的落点、技能、型号目录、接进来的 MCP 工具、
 * 外接大脑宿主,然后名册上每只同伴(主人在不在线都一样)一个大脑。服务器停时这些放手。
 * 谁在这里登记,谁就不必在两个加载器里各写一遍。
 */
public final class ServerBrains {

    private ServerBrains() {}

    /** 由 {@code CommonClass.init} 调一次。 */
    public static void register() {
        ServerLifecycle.onStarted(ServerBrains::start);
        ServerLifecycle.onStopped(ServerBrains::stop);
        ServerLifecycle.onReloaded(ServerBrains::reload);
    }

    static void start(MinecraftServer server) {
        Homes homes = Homes.of(server);
        CompanionRegistry registry = CompanionRegistry.get(server);
        // 内置要先同步进服务器目录,库才有东西可读
        LibraryHub hub = LibraryHub.start(server);
        SkinCache skins = new SkinCache(homes.server());
        migrateHost(server, homes, registry, skins);
        // 她的札记在世界里她的家下;模块与技能是主人那一份库(内置加我的)
        NoteBook.init(uuid -> {
            Home home = homes.companion(uuid);
            return new Home(home.dir().resolve("memory"), home.io());
        }, () -> (int) (server.overworld().getDayTime() / 24000L));
        Modules.init(companion -> hub.library(Modules.KIND, ownerOf(registry, companion)));
        SkillRegistry.init(companion -> hub.library(SkillRegistry.KIND, ownerOf(registry, companion)));
        // 目标模组只在客户端的联动,按主人的客户端提供什么显示给她的名字空间:同伴到主人的对应在这里接上
        com.dwinovo.numen.api.plugin.ClientMods.bindOwners(companion -> ownerOrNull(registry, companion));
        ModelServices.start(server);
        migrateSkins(server, homes, registry, skins);   // 要在她们的大脑读绑定之前:绑定里的皮肤从旧条目名换成缓存的引用
        ServerSkins.start(server, skins);
        McpClients.start(server);
        ServerMcp.start(server);
        Visits.start();
        for (UUID companion : registry.all()) {
            try {
                CompanionBrains.ensure(server, companion);
            } catch (RuntimeException ex) {
                Constants.LOG.error("[numen] 同伴 {} 的大脑没能起来,跳过", companion, ex);
            }
        }
        // 旧版为主人离线攒下的事件:一次性倒进她们的收件箱
        Path outbox = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("numen_event_outbox.dat");
        LegacyMigration.outbox(outbox).forEach((companion, entries) ->
                CompanionLoops.get(companion).ifPresent(loop -> loop.push(entries)));
    }

    /**
     * {@code /reload}:库(内置与每位玩家的)、钥匙串、服务器设置、接进来的 MCP 工具都从磁盘重新读——管理员或玩家拿编辑器改过的
     * 文件在这里读到。jar 里的内置不重新同步(jar 在进程里不会变)。
     */
    static void reload(MinecraftServer server) {
        LibraryHub.of(server).reloadAll();
        ModelServices.of(server).reload();
        McpClients.of(server).reconcileAll();
        Constants.LOG.info("[numen] 库与钥匙串已从磁盘重新读");
    }

    /** 这只同伴的主人,以名册为准;名册上没有她是 null。 */
    private static UUID ownerOrNull(CompanionRegistry registry, UUID companion) {
        CompanionRegistry.Entry entry = registry.find(companion);
        return entry == null ? null : entry.owner();
    }

    /** 这只同伴的主人;名册上没有她是编程错误。 */
    private static UUID ownerOf(CompanionRegistry registry, UUID companion) {
        UUID owner = ownerOrNull(registry, companion);
        if (owner == null) {
            throw new IllegalStateException("没有这只同伴: " + companion);
        }
        return owner;
    }

    private static void stop() {
        Visits.stop();
        McpMode.instance().shutdown();
    }

    /**
     * 单人游戏的房主在旧版留下的、归他自己的东西,搬进现在的布局:模型档案与 key、人设、Lua 模块、技能、接进来的 MCP 服务器、
     * 会话成员表、主动性档位、每只同伴的家(以及它们绑定里的档案与人设的新名字),和这台电脑上旧的声线库、皮肤库、
     * 每只同伴的声线皮肤选择({@link LegacyLooks};皮肤进皮肤文件夹与签名缓存)。只做一次,复制不移动
     * ({@link LegacyMigration}、{@link LegacyLibraries});多人服务器上旧客户端的数据没法这样读到,不迁也不上传。
     * 房主的"我的"各库就是服务器目录那一份,所以旧的人设、声线、技能直接进服务器目录;最后把他个人目录里已有的库条目与钥匙并进来
     * ({@link HostMerge})。要在库开张、大脑建起来之前做完,它们读的是搬好的。
     */
    private static void migrateHost(MinecraftServer server, Homes homes, CompanionRegistry registry, SkinCache skins) {
        var profile = server.getSingleplayerProfile();
        if (profile == null) {
            return;
        }
        UUID host = profile.getId();
        Path legacy = NumenPaths.config();
        Path serverDir = homes.server().dir();
        Path hostDir = homes.owner(host).dir();
        LegacyMigration.initiative(legacy, homes.owner(host).file("prefs.json"));
        LegacyMigration.conversations(LegacyMigration.conversationsFile(legacy),
                conv -> conv.members().stream().anyMatch(member -> registry.find(member) != null),
                homes.worldOwner(host).file("conversations.json"));
        LegacyMigration.file(legacy.resolve("mcp_oauth.json"), serverDir.resolve("mcp_oauth.json"));
        LegacyMigration.file(legacy.resolve("mcp_server.json"), serverDir.resolve("mcp_server.json"));

        OwnerPrefs prefs = OwnerPrefs.of(server, host);
        var providerIds = LegacyLibraries.providers(legacy.resolve("providers.json"), serverDir);
        // 人设与模块旧版放在房主的目录里平铺;更早的版本放在根下(模块按账号分)
        Path flatPersona = Files.isDirectory(hostDir.resolve("persona")) ? hostDir.resolve("persona")
                : legacy.resolve("persona");
        var personaIds = LegacyLibraries.personas(flatPersona, serverDir.resolve("persona").resolve(EntryId.MINE), serverDir);
        Path flatModules = Files.isDirectory(hostDir.resolve("lua")) ? hostDir.resolve("lua")
                : legacy.resolve("lua").resolve(host.toString());
        LegacyLibraries.modules(flatModules, hostDir.resolve("modules"), serverDir, prefs);
        LegacyLibraries.skills(legacy.resolve("skills"), legacy.resolve("skills_state.json"),
                serverDir.resolve("skills").resolve(EntryId.MINE), serverDir.resolve("skills"), prefs);
        LegacyLibraries.mcp(legacy.resolve("mcp_clients.json"), serverDir.resolve("mcp"));

        List<Path> bindings = new ArrayList<>();
        Map<UUID, Path> justMoved = new LinkedHashMap<>();
        for (UUID companion : registry.all()) {
            boolean moved = LegacyMigration.companion(legacy.resolve("companions").resolve(companion.toString()),
                    homes.companion(companion).dir());
            bindings.add(homes.companion(companion).file("binding.json"));
            if (moved) {
                justMoved.put(companion, homes.companion(companion).file("binding.json"));
            }
        }
        LegacyLibraries.bindings(bindings, providerIds, personaIds);

        // 声线库、皮肤库和每只同伴的声线皮肤选择在这台电脑的 client/ 里(客户端先把更旧的位置接了过去)
        Path clientDir = legacy.resolve("client");
        var voiceIds = LegacyLooks.voices(clientDir.resolve("voice.json"), serverDir.resolve("voice").resolve(EntryId.MINE),
                clientDir.resolve("keys"));
        var skinIds = LegacyLooks.skins(clientDir.resolve("skin.json"), clientDir.resolve("skins"), NumenPaths.skins(), skins);
        LegacyLooks.bindings(clientDir.resolve("looks.json"), legacy.resolve("companions"), justMoved, voiceIds, skinIds);

        // 他个人目录里已有的库条目与钥匙并进服务器目录;同名的改了名,他名下同伴的绑定跟着改指
        List<HostMerge.Renamed> renamed = HostMerge.libraries(hostDir, serverDir, MERGED_KINDS);
        List<String> replaced = HostMerge.keys(hostDir.resolve("keys"), serverDir.resolve("keys"));
        List<Path> hers = registry.all().stream().filter(c -> host.equals(registry.find(c).owner()))
                .map(c -> homes.companion(c).file("binding.json")).toList();
        HostMerge.rebind(hers, renamed);
        HostNotices.merged(host, renamed, replaced);
    }

    /** 房主的"我的"并进服务器目录的那几种库。 */
    private static final List<LibraryKind> MERGED_KINDS = List.of(PersonaLibrary.KIND, ProviderLibrary.KIND,
            VoiceProfile.KIND, SkillRegistry.KIND, McpClientConfig.KIND);

    /**
     * 旧的皮肤库(服务器的和每位玩家的 {@code skin/<命名空间>/<名字>.json})搬成现在的样子({@link SkinMigration}):原图导出成皮肤文件夹里的
     * png,签好的进签名缓存,同伴绑定里的旧条目名换成缓存的引用。服务器的与房主的原图进这台机器的皮肤文件夹(房主就是这台服务器,
     * 他的"我的"与"服务器提供"是同一个文件夹),搬完这两份旧库删掉;连专用服务器的玩家的原图要交到他本机去,旧库留着,
     * 等他的客户端报到时下发({@link SkinHandover}),发完一张删一张。可重来:搬过的不会再多出东西。
     */
    private static void migrateSkins(MinecraftServer server, Homes homes, CompanionRegistry registry, SkinCache skins) {
        Path folder = NumenPaths.skins();
        Path serverLibrary = homes.server().file("skin");
        Map<String, SkinRef> serverIds = SkinMigration.library(serverLibrary, folder, skins, true);
        Trees.delete(serverLibrary);
        Map<UUID, Map<String, SkinRef>> ownerIds = new LinkedHashMap<>();
        for (UUID owner : homes.owners()) {
            Path library = homes.owner(owner).file("skin");
            boolean host = homes.isHost(owner);
            ownerIds.put(owner, SkinMigration.library(library, folder, skins, host));
            if (host) {
                Trees.delete(library);
            }
        }
        Map<String, SkinRef> ids = new LinkedHashMap<>();
        for (UUID companion : registry.all()) {
            ids.clear();
            ids.putAll(serverIds);
            ids.putAll(ownerIds.getOrDefault(registry.find(companion).owner(), Map.of()));   // 同名时他的优先
            SkinMigration.rebind(List.of(homes.companion(companion).file("binding.json")), ids);
        }
    }
}
