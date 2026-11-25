package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.entity.Attendance.Outside;
import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.api.network.payload.CompanionListPayload;
import com.dwinovo.numen.api.network.NumenNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates companion lifecycle on top of {@link CompanionFactory} (body
 * spawn/despawn) and {@link CompanionRegistry} (the persistent index). The body
 * persists as a player {@code .dat}; the registry remembers it exists so it can
 * be recreated when its owner returns or a tool call arrives.
 */
@com.dwinovo.numen.api.Internal
public final class Companions {

    private Companions() {}

    /**
     * Summon the companion called {@code name} that this person can use (his own, or one shared with him).
     * IDEMPOTENT per (person, name): if one already exists it is reused — already live → returned as-is;
     * dormant → brought back. Only a name with no existing companion mints a fresh one, owned by him. (The old "fresh random UUID every summon" minted same-name
     * duplicates that all respawned on login — that's the duplicate-companion bug.)
     *
     * <p>她是什么样子(档案、人设、声线、皮肤、模式)不在这里定:玩家的召唤走 {@link #summonFor},召唤卡上选了的那几项在她到达的那一刻
     * 经编辑时同一条绑定代码落下({@link SummonSetup#apply});这里是它和 GameTest 共用的那一步。
     */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String name, ServerLevel level, Vec3 pos) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        UUID existing = findByOwnerName(server, ownerUuid, name);
        if (existing != null) {
            // 留在外面的她(主人让她下线的、被外面请出去的)只有主人再召唤才回来:召唤就是主人的决定,清掉"留在外面"的状态
            Outside was = reg.find(existing).outside();
            reg.markReturned(existing);
            NumenPlayer back = respawn(server, existing);
            if (back != null) {
                welcomeBack(server, back, was);
                return back;
            }
            reg.remove(existing);   // stale entry — replace it
        }
        UUID companionUuid = UUID.randomUUID();
        Vec3 safe = SafeSpawn.findNear(level, pos);
        if (safe != null) pos = safe;   // no safe spot around → keep the summoner's own position
        // 先入册再造体:CompanionFactory.spawn 从注册表读皮肤,所有出生路径共用一个注入点。
        CompanionRegistry.Entry fresh = new CompanionRegistry.Entry(
                name, ownerUuid, level.dimension(), net.minecraft.core.BlockPos.containing(pos));
        reg.put(companionUuid, fresh);
        NumenPlayer body = CompanionFactory.spawn(server, companionUuid, name, ownerUuid, level, pos);
        reg.put(companionUuid, fresh.movedTo(level.dimension(), body.blockPosition()));
        return body;
    }

    /** 召唤的结果:成了是她的身体;没成是要说给玩家听的话。 */
    public record Summoned(NumenPlayer body, net.minecraft.network.chat.Component problem) {}

    /**
     * 名额闸:服主定了每人至多几只({@code limit},0 是不限),主人已经有这么多只时再召一只新的不行;叫醒他已有的那只
     * ({@code hers})不占新名额。
     */
    static boolean overLimit(int limit, boolean hers, int owned) {
        return limit > 0 && !hers && owned >= limit;
    }

    /**
     * <b>玩家召唤的唯一入口</b>:召唤卡与 {@code /numen player summon} 都走这里,谁都能召唤,只要一个名字,不设任何前提。
     * 名字要合 Minecraft 的命名规则、不能撞上在线的别人;每人最多养几只是服主在 {@code server.json} 里设的(缺省不限,
     * 只数他创建的)——再召唤他用得了的同名同伴(自己的、分享给他的)是叫醒她,不占名额。召唤成了把新名册推给她的各位主人。
     *
     * <p>召唤卡可以一并带上几项配置({@link SummonSetup}:三项库绑定、模式、皮肤),都不是必须的;命令与外接大脑只带名字
     * ({@link SummonSetup#NONE})。带了的每一项照编辑时的规矩当场检查,有一项不行就整次不召唤、说明是哪一项;都行的在她到达时落下。
     *
     * <p>能当场判断的当场拒绝:不合规矩的(名字、重名、名额、配置)返回带着原因的 {@link Summoned};都行就召唤,返回她的身体。
     *
     * @param setup 召唤卡上改过的那几项;没改的不在里面,服务端照不指定时的规矩办
     */
    public static Summoned summonFor(ServerPlayer owner, String name, SummonSetup setup) {
        MinecraftServer server = owner.server;
        if (!MojangSkins.validName(name)) {
            return new Summoned(null, net.minecraft.network.chat.Component.translatable(
                    com.dwinovo.numen.api.data.ModLanguageData.Keys.NOTICE_NAME_INVALID));
        }
        // 重名闸:同名玩家已在线(真人/别的主人的同伴)一律拒绝——例外是他用得了的同名同伴(那是叫醒她)。
        var online = server.getPlayerList().getPlayerByName(name);
        boolean ownSameName = online instanceof NumenPlayer np && np.usableBy(owner.getUUID());
        if (online != null && !ownSameName) {
            return new Summoned(null, net.minecraft.network.chat.Component.translatable(
                    com.dwinovo.numen.api.data.ModLanguageData.Keys.NOTICE_NAME_TAKEN, name));
        }
        int limit = new com.dwinovo.numen.api.config.ServerSettings(com.dwinovo.numen.api.home.Homes.of(server).server()).maxCompanions();
        if (overLimit(limit, findByOwnerName(server, owner.getUUID(), name) != null,
                CompanionRegistry.get(server).ownedBy(owner.getUUID()).size())) {
            return new Summoned(null, net.minecraft.network.chat.Component.translatable(
                    com.dwinovo.numen.api.data.ModLanguageData.Keys.NOTICE_SUMMON_LIMIT, limit));
        }
        SummonSetup.Prepared prepared = setup.prepare(SummonSetup.checks(owner), name);
        if (prepared.refusal() != null) {
            return new Summoned(null, prepared.refusal());
        }
        ServerLevel level = (ServerLevel) owner.level();
        NumenPlayer body = summon(server, owner.getUUID(), name, level, owner.position());
        prepared.apply(owner, body);
        syncRosterToKeepers(server, body.keepers());
        return new Summoned(body, null);
    }

    /**
     * 给一只同伴换上这副皮肤(Mojang 签名的 textures);{@code null} = 回到默认皮肤。名册记下新皮肤(下次建身体时用);活体<b>原地换</b>,
     * 不重建身体、不发入服离服的消息、她手上的活与背包都不动——照 SkinsRestorer、FabricTailor 的做法:改她 GameProfile 的 textures,
     * 给所有在线玩家发玩家信息的移除与重新加入,再让追踪她的客户端重新生成她的实体,别人看到的皮肤就刷新了。
     * 穿的本来就是这一副就什么也不做。
     */
    public static void applySkin(MinecraftServer server, UUID companion, MojangSkins.Skin skin, String ref) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = reg.find(companion);
        String value = skin == null ? "" : skin.value();
        if (entry == null) {
            return;
        }
        String signature = skin == null ? "" : skin.signature();
        boolean sameLook = entry.skinValue().equals(value);
        reg.put(companion, entry.withSkin(value, signature, ref));
        if (sameLook) {
            syncRosterToKeepers(server, entry.keepers());   // 穿的是哪一张变了,面板据此标出
            return;
        }
        NumenPlayer body = NumenPlayer.findByUuid(server, companion);
        if (body != null) {
            refreshSkin(server, body, value, signature);
        }
    }

    /** 改活体档案里的 textures,并让所有人的客户端重新认她:玩家信息先移除再加入,追踪她的客户端重新生成她的实体。 */
    private static void refreshSkin(MinecraftServer server, NumenPlayer body, String value, String signature) {
        var properties = body.getGameProfile().getProperties();
        properties.removeAll("textures");
        if (!value.isEmpty()) {
            properties.put("textures", new com.mojang.authlib.properties.Property("textures", value,
                    signature.isEmpty() ? null : signature));
        }
        var players = server.getPlayerList();
        players.broadcastAll(new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(
                java.util.List.of(body.getUUID())));
        players.broadcastAll(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
                .createPlayerInitializing(java.util.List.of(body)));
        var chunks = body.serverLevel().getChunkSource();
        chunks.removeEntity(body);
        chunks.addEntity(body);
    }

    /** 这位主人用得了的(他创建的、分享给他的)叫这个名字的同伴,没有则 null。判断规则在 {@link CompanionRoster#findByName}
     *  (纯 JVM,可单测);这里只负责把注册表摊平成它认得的形状。 */
    private static UUID findByOwnerName(MinecraftServer server, UUID ownerUuid, String name) {
        return CompanionRoster.findByName(rowsOf(server, ownerUuid), name);
    }

    /** 注册表 → 决策层认得的纯数据行。 */
    private static List<CompanionRoster.Row> rowsOf(MinecraftServer server, UUID ownerUuid) {
        List<CompanionRoster.Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).usableBy(ownerUuid)) {
            rows.add(new CompanionRoster.Row(e.getKey(), e.getValue().name()));
        }
        return rows;
    }

    /**
     * Bring a dormant companion back from its catalog entry + {@code .dat}
     * (position/inventory restored from disk). Already live → she is returned as-is. Returns {@code null}
     * when she is unknown to the registry.
     */
    public static NumenPlayer respawn(MinecraftServer server, UUID companionUuid) {
        NumenPlayer live = NumenPlayer.findByUuid(server, companionUuid);
        if (live != null) {
            return live;
        }
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companionUuid);
        if (entry == null) return null;
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) level = server.overworld();
        // pos=null: keep the position restored from the .dat.
        return CompanionFactory.spawn(server, companionUuid, entry.name(), entry.owner(), level, null);
    }

    /** 把登出的她叫回世界,回来的事告诉她({@link #welcomeBack})。注册表不认得她就什么也不做。 */
    private static void bringBack(MinecraftServer server, UUID companionUuid) {
        NumenPlayer body = respawn(server, companionUuid);
        if (body != null) {
            welcomeBack(server, body, Outside.NONE);
        }
    }

    /**
     * 她回到了世界里。从登出回来的(名册记着她是什么时候离开的)或从"留在外面"回来的:解开停牌,留下一条回来的事实——
     * 因为什么离开、过了多久、此刻在哪;被服务器请出去的另有离开时那条事实,不再重复。本来就在世界里的什么也不做。
     */
    private static void welcomeBack(MinecraftServer server, NumenPlayer body, Outside was) {
        UUID uuid = body.getUUID();
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = reg.find(uuid);
        long leftAt = entry == null ? -1L : entry.leftAt();
        if (!was.isOutside() && leftAt < 0) {
            return;
        }
        if (leftAt >= 0) {
            reg.markLeftAt(uuid, -1L);
            if (!was.server()) {
                com.dwinovo.numen.api.event.NumenEvents.emit(body, com.dwinovo.numen.api.agent.inbox.EventTypes.RETURNED,
                        java.util.Map.of("cause", was.why() == Attendance.Why.OWNER ? "owner_ordered" : "owner_absent"),
                        returnedText(body, was, server.overworld().getGameTime() - leftAt), false);
            }
        }
    }

    /** 回来那条事件的话:为什么离开、过了多久、落在哪;背包与位置都是离开时的样子。 */
    static String returnedText(NumenPlayer body, Outside was, long awayTicks) {
        String why = was.why() == Attendance.Why.OWNER ? "your owner sent you offline" : "your owner was not in the game, so you were put away";
        return "You are back in the world: " + why + "; about " + awayWords(awayTicks) + " passed in the game. You are in "
                + body.level().dimension().location() + " "
                + com.dwinovo.numen.api.sdk.Positions.literal(body.blockPosition())
                + "; your inventory and position are as you left them, nobody moved you.";
    }

    /** 游戏刻数说成人话:不到一分钟、几分钟、几小时(都是游戏里的时间)。 */
    static String awayWords(long ticks) {
        long minutes = Math.max(0L, ticks) / 1200L;
        return minutes < 1 ? "less than a minute" : minutes < 120 ? minutes + " minutes" : minutes / 60 + " hours";
    }

    /**
     * 记一笔:这位主人用得了的同伴该对一次账。<b>真正的对账({@link #reconcile})不在这里做。</b>用于主人登入、登出这两个时机。
     *
     * <h2>为什么必须推迟</h2>
     * 登录事件是在原版 {@code PlayerList.placeNewPlayer()} <b>内部</b>触发的——那句还没返回。
     * 在这里把同伴带回来,等于把带回来期间的任何异常接到主人的入场流程上:一只同伴的 {@code .dat}
     * 有毛病、或者别的模组在同伴的入场事件里抛了,冒泡上去打断的是<b>主人的登录</b>,客户端
     * 看到的是"无效的玩家数据"——他会以为自己的存档毁了。
     *
     * <p>第二类堵不完:同伴入场会触发它自己的登录事件,装在这个世界里的任何模组都收得到,
     * 而它们没想过"玩家"可能是假的。我们挡不住别人抛异常,只能不让那异常落在主人头上。
     *
     * <p>登出事件同理跑在 {@code PlayerList.remove} 内部,那一刻他还在玩家表里,"主人在不在场"还算不准。
     *
     * <p>推迟到下一个服务端 tick({@link #reconcilePending})就彻底解耦了:那时 placeNewPlayer / remove
     * 早已返回,同伴出什么事都只是同伴的事。
     */
    public static void scheduleReconcile(UUID ownerUuid) {
        PENDING_RECONCILE.add(ownerUuid);
    }

    /** 排队等对账的主人。世界作废时一并清掉——排的是上一个存档的账。 */
    private static final java.util.Set<UUID> PENDING_RECONCILE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        com.dwinovo.numen.api.platform.ServerLifecycle.onStopped(PENDING_RECONCILE::clear);
    }

    /**
     * 每个服务端 tick 调一次:把记下的主人挨个对账;他在线就先把"被服务器请出去"的同伴告诉他。
     *
     * <p>一个主人对账失败不牵连下一个——他们之间毫无关系,没有理由一起倒。
     */
    public static void reconcilePending(MinecraftServer server) {
        drainPending(ownerUuid -> {
            tellRemoved(server, ownerUuid);
            reconcile(server, ownerUuid);
        });
    }

    /**
     * 排空队列,逐个交给 {@code reconcile}。
     *
     * <p>两条不变式:<b>每个主人只对一次</b>(先摘牌再执行,否则失败的那个会每 tick 重试到
     * 天荒地老),以及<b>一个失败不牵连下一个</b>(主人之间毫无关系,没有理由一起倒)。
     *
     * <p>纯逻辑,不碰 Minecraft——留这个缝是为了这两条能被单测钉住。
     */
    static void drainPending(java.util.function.Consumer<UUID> reconcile) {
        if (PENDING_RECONCILE.isEmpty()) {
            return;
        }
        List<UUID> due = new ArrayList<>(PENDING_RECONCILE);
        PENDING_RECONCILE.removeAll(due);
        for (UUID ownerUuid : due) {
            try {
                reconcile.accept(ownerUuid);
            } catch (RuntimeException ex) {
                com.dwinovo.numen.api.Constants.LOG.error("[numen] reconciling the companions of {} failed", ownerUuid, ex);
            }
        }
    }

    /** 主人上线了:他用得了的同伴里被服务器请出去的,把理由告诉他(主人让她下线的不用说)。 */
    private static void tellRemoved(MinecraftServer server, UUID ownerUuid) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner == null) {
            return;
        }
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).usableBy(ownerUuid)) {
            if (e.getValue().outside().server()) {
                tellRemoved(owner, e.getValue().name(), e.getValue().removedFor());
            }
        }
    }

    /**
     * 把一位主人用得了的每一只同伴(他创建的,加上分享给他的)对一次账,见 {@link #reconcileCompanion}。他登入、登出、
     * 他的外部大脑连上或断开、他的来访开始或结束,都调这里。
     *
     * <p>逐只隔离:一只出了问题是少一只同伴,不该把同一个主人的其余同伴也一起拖掉。
     */
    public static void reconcile(MinecraftServer server, UUID ownerUuid) {
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).usableBy(ownerUuid)) {
            try {
                reconcileCompanion(server, e.getKey());
            } catch (RuntimeException ex) {
                com.dwinovo.numen.api.Constants.LOG.error("[numen] companion {} could not be brought to its proper state, skipped", e.getKey(), ex);
            }
        }
    }

    /**
     * <b>把一只同伴修到该有的样子的唯一入口</b>:该在线的(判据见 {@link Attendance#move},"主人在场"是她的主人表上任一位在场,
     * 见 {@code OwnerPresence})不在世界里就带回来,不该在线的还在世界里就登出。登入、登出、外部大脑连上或断开、
     * 来访开始或结束、下线命令、召唤、主人表变了,都只是"对一次账"的时机,她的去留不由它们各自决定。登出是真玩家登出
     * ({@link #dormant}):存 {@code .dat}、不动背包,回来落在 {@code .dat} 里的位置。名册上没有她就什么也不做。
     */
    public static void reconcileCompanion(MinecraftServer server, UUID companionUuid) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companionUuid);
        if (entry == null) {
            return;
        }
        NumenPlayer body = NumenPlayer.findByUuid(server, companionUuid);
        boolean ownerPresent = com.dwinovo.numen.api.brain.OwnerPresence.present(companionUuid);
        switch (Attendance.move(entry.outside(), ownerPresent, body != null)) {
            case BRING -> bringBack(server, companionUuid);
            case LOG_OUT -> logOut(server, body);
            case STAY -> { }
        }
    }

    /**
     * 她登出世界:名册记下离开的时刻,身体存档离场。主人不在场时大脑本来就待命({@code Hold.AWAY}),不用另外停牌。
     */
    private static void logOut(MinecraftServer server, NumenPlayer body) {
        CompanionRegistry.get(server).markLeftAt(body.getUUID(), server.overworld().getGameTime());
        dormant(server, body);
    }

    /**
     * <b>她的身体被外面的人或插件移除了的唯一入口</b>:服主的 {@code /kick}、别的插件踢人。
     * Numen 自己造成的移除(删除、登出、关服)各有各的路,不要调这里。
     *
     * <p>平台挂点契约:由 {@code MixinServerCommonPacketListener} 在断开落到她的假连接上那一步调(关服时不调)。调用时她的身体还在世界里;
     * <b>身体本身由平台移除</b>,这里只管之后的一切:
     * <ul>
     *   <li>她的大脑收到一条"被请出去了"的事实({@link com.dwinovo.numen.api.event.NumenEvents#removed}),她手上的活和程序经
     *       {@link com.dwinovo.numen.api.CompanionEvent#REMOVE} 收掉——和她被登出、被删除走同一条收拾的路;</li>
     *   <li>名册记下她被请出去了和理由,在主人再召唤({@link #summon})之前不重建;</li>
     *   <li>她的主人里在线的就告诉他,不在线的等他下次上线({@link #reconcilePending})。</li>
     * </ul>
     *
     * @param reason 踢人时说的理由
     */
    public static void removedFromOutside(NumenPlayer body, net.minecraft.network.chat.Component reason) {
        MinecraftServer server = body.level().getServer();
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = reg.find(body.getUUID());
        if (entry != null && entry.outside().isOutside()) {
            return;
        }
        String why = reason.getString();
        com.dwinovo.numen.api.event.NumenEvents.removed(body, why);
        if (entry != null) {
            reg.markOutside(body.getUUID(), Outside.byServer(why));
        }
        CompanionEvents.fire(CompanionEvent.REMOVE, body);
        for (ServerPlayer keeper : body.keepersInGame()) {
            tellRemoved(keeper, body.getName().getString(), why);
        }
        syncRosterToKeepers(server, body.keepers());
    }

    private static void tellRemoved(ServerPlayer owner, String name, String why) {
        owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                com.dwinovo.numen.api.data.ModLanguageData.Keys.NOTICE_COMPANION_REMOVED, name, why));
    }

    /** 死了、等着代她点"重生"的身体(主线程)。世界作废时一并清掉——排的是上一个存档的账。 */
    private static final java.util.Set<NumenPlayer> AWAITING_RESPAWN = new java.util.LinkedHashSet<>();

    static {
        com.dwinovo.numen.api.platform.ServerLifecycle.onStopped(AWAITING_RESPAWN::clear);
    }

    /**
     * 一只同伴刚死了({@link NumenPlayer#die} 里,原版的死亡流程已经走完:掉落、经验、广播、{@code keepInventory} 都是原版的)。
     * 这里只做三件事:大脑停牌并把死亡告诉她,生命周期钩子发 {@code DEATH},把这具尸体排进待重生。重生在下一个服务端刻开头
     * ({@link #respawnDead}),不在实体刻里当场做——和真玩家一样,重生是网络阶段处理一个点击,不是死亡那一刻的事。
     */
    public static void onDeath(NumenPlayer body) {
        MinecraftServer server = body.level().getServer();
        if (server == null) return;
        UUID uuid = body.getUUID();
        // 死因取 die() 里抄下的那一句:此刻再问战斗记录已经被 vanilla 清空了,
        // 只会拿到"她死了"这种没有凶手的兜底文案(见 NumenPlayer#deathMessage)。
        String cause = body.deathMessage();
        if (cause == null || cause.isBlank()) {
            cause = body.getCombatTracker().getDeathMessage().getString();
        }
        if (cause == null || cause.isBlank()) cause = "unknown cause";
        // 先让大脑停牌,再触发生命周期钩子——<b>顺序要紧</b>:大脑一停牌 DEAD,
        // 此后钩子里产生的收尾事件(异步任务的 task_finished status="interrupted")
        // 照样进队列,但不开 run,安静躺到重生。
        //
        // 反过来的话,那条 urgent 收尾事件会在停牌之前到达、当场开一轮,而紧接着的
        // 停牌又把那一轮整个作废——白烧一次请求,还多一条没人看的对话。
        String where = "You died in " + body.level().dimension().location() + " at "
                + com.dwinovo.numen.api.sdk.Positions.literal(body.blockPosition()) + ". "
                + (body.level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY)
                ? "This world has keepInventory on, so everything in your inventory is still on you."
                : "What was in your inventory dropped where you died, by the vanilla rules.");
        String diedOf = cause;
        com.dwinovo.numen.api.event.NumenEvents.died(body, diedOf, where);
        CompanionEvents.fire(CompanionEvent.DEATH, body);   // 不发工具结果:那条 tool_call 已由死因结算
        AWAITING_RESPAWN.add(body);
    }

    /**
     * 每个服务端刻开头调一次:替排着的死者点"重生"({@link FakeClient#respawn}),由原版的 {@code PlayerList.respawn} 把她放回世界。
     * 一只重生出错不牵连下一只。
     */
    public static void respawnDead(MinecraftServer server) {
        if (AWAITING_RESPAWN.isEmpty()) {
            return;
        }
        List<NumenPlayer> dead = new ArrayList<>(AWAITING_RESPAWN);
        AWAITING_RESPAWN.clear();
        for (NumenPlayer body : dead) {
            try {
                respawnBody(server, body);
            } catch (RuntimeException ex) {
                com.dwinovo.numen.api.Constants.LOG.error("[numen] respawning companion {} failed", body.getUUID(), ex);
            }
        }
    }

    /**
     * 原版重生一具死着的身体,再把换上来的新身体接回 Numen 这一侧。原版决定一切:落在她自己的重生点(睡过的床、充能的重生锚),
     * 没有或失效就是世界出生点,不经 {@code placeNewPlayer}(没有"加入了游戏"的广播),也不传送到主人身边。
     *
     * <p>模组上原版换一个新的身体对象:手上的活、任务槽、各种看护都绑着旧身体,所以按"一具身体离场、一具身体入场"走,
     * 和休眠再唤醒同一套生命周期钩子({@code REMOVE}、{@code SPAWN});大脑按 UUID 认身体,不用换。
     */
    private static void respawnBody(MinecraftServer server, NumenPlayer dead) {
        if (server.getPlayerList().getPlayer(dead.getUUID()) != dead) {
            return;   // 排队期间她被移出了世界(遣散、被请出去):没有身体可重生
        }
        boolean hadRespawnPoint = dead.getRespawnPosition() != null;
        CompanionEvents.fire(CompanionEvent.REMOVE, dead);   // 这一条命的身体到头了:手上的活收尾,键盘松开
        NumenPlayer body = dead.fakeClient().respawn();
        UUID uuid = body.getUUID();
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = reg.find(uuid);
        if (entry != null) {
            reg.put(uuid, entry.movedTo(body.serverLevel().dimension(), body.blockPosition()));
        }
        CompanionEvents.fire(CompanionEvent.SPAWN, body);
        // 身体动了就得让她知道;事件先于解冻进收件箱,解冻后死亡与重生两条一起交给她
        com.dwinovo.numen.api.event.NumenEvents.emit(body, com.dwinovo.numen.api.agent.inbox.EventTypes.RESPAWNED,
                java.util.Map.of("spawn", body.getRespawnPosition() != null ? "respawn_point" : "world_spawn"),
                respawnedText(body, hadRespawnPoint), true);
    }

    /** 重生那条事件的话:落在哪、凭什么落在这儿、离主人多远。 */
    private static String respawnedText(NumenPlayer body, boolean hadRespawnPoint) {
        String how = body.getRespawnPosition() != null ? "you landed beside your respawn point (the bed you slept in or a charged respawn anchor)"
                : hadRespawnPoint ? "your respawn point (bed or respawn anchor) is gone or blocked, so you landed at the world spawn"
                : "you have no respawn point, so you landed at the world spawn";
        ServerPlayer owner = body.keeperAtHand();
        String ownerPart = owner == null ? "Your owner is not online. "
                : owner.level() != body.level() ? "Your owner is in " + owner.level().dimension().location() + ", not the dimension you are in. "
                : "Your owner is to your " + com.dwinovo.numen.api.sdk.CompassUtil.compass(body, owner) + ", about "
                        + Math.round(body.distanceTo(owner)) + " blocks away. ";
        return "You respawned: " + how + ", in " + body.level().dimension().location() + " at "
                + com.dwinovo.numen.api.sdk.Positions.literal(body.blockPosition()) + ". " + ownerPart
                + "Nobody moved you; whether and how to go back to your owner is up to you.";
    }

    /**
     * 把这位主人用得了的、<b>存在的</b>同伴(他创建的,加上分享给他的)推给他的客户端;每行带着她的主人表(创建者在前)。
     *
     * <p>取自持久的 {@link CompanionRegistry},不是玩家列表——休眠的同伴还存在,只是此刻不在世界里。客户端拿这份名册对账并<b>删除已遣散同伴的
     * 本地数据</b>,所以名册的语义必须是"存在";用"此刻活着"做过一版,结果是同伴一休眠
     * 就被当成遣散,数据整个删掉。判断规则在 {@link CompanionRoster}(纯 JVM,可单测)。
     *
     * <p>任何"存在状态"的变化之后都要调:登录、召唤、遣散、被请出服务器、主人表变了。
     */
    public static void syncRosterToOwner(MinecraftServer server, ServerPlayer owner) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        List<CompanionListPayload.Entry> list = new ArrayList<>();
        for (CompanionRoster.Row row : CompanionRoster.build(rowsOf(server, owner.getUUID()))) {
            NumenPlayer body = NumenPlayer.findByUuid(server, row.uuid());
            CompanionRegistry.Entry entry = reg.find(row.uuid());
            list.add(new CompanionListPayload.Entry(row.uuid(), row.name(),
                    body != null && body.isCreative(), entry.outside(), entry.skinRef(),
                    entry.keepers().stream().map(k -> new Keeper(k, NumenPlayer.playerName(server, k))).toList()));
        }
        NumenNetwork.sendToPlayer(owner, new CompanionListPayload(reg.worldId(), list));
    }

    /** 一只同伴的"存在状态"变了:把新名册推给这几位(她的主人们)里在线的每一位。 */
    public static void syncRosterToKeepers(MinecraftServer server, Collection<UUID> keepers) {
        for (UUID keeper : keepers) {
            ServerPlayer player = server.getPlayerList().getPlayer(keeper);
            if (player != null && !(player instanceof NumenPlayer)) {
                syncRosterToOwner(server, player);
            }
        }
    }

    /**
     * 游戏模式落地,召唤和编辑卡同一道门({@link #mayChooseCreative})。召唤前已经当场拒绝过不过门的创造;到这里还不过门的
     * (编辑卡上的伪造或竞态)按生存并说明——同伴的模式上限 = 主人的上限。
     */
    public static void applyGameMode(ServerPlayer owner, NumenPlayer body, boolean creative) {
        if (body == null) return;
        if (creative && !mayChooseCreative(owner)) {
            owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    com.dwinovo.numen.api.data.ModLanguageData.Keys.NOTICE_CREATIVE_NEEDS_OP));
            creative = false;
        }
        body.setGameMode(creative ? net.minecraft.world.level.GameType.CREATIVE
                : net.minecraft.world.level.GameType.SURVIVAL);
    }

    /** 主人能不能让同伴进创造:有 /gamemode 权限(等级 2),或本人就在创造。客户端置灰模式那一行用的是同一判据。 */
    public static boolean mayChooseCreative(ServerPlayer owner) {
        return com.dwinovo.numen.api.platform.Services.permissions().isGameMaster(owner.createCommandSourceStack())
                || owner.isCreative();
    }

    /**
     * The companion crossed into a new dimension — on its OWN (it travels where it likes, not tied to
     * the owner). Tell its brain, ambient: it rides along on the next owner-driven turn rather than
     * spending a fresh LLM call just to note the move. Called from each loader's dimension-change hook.
     */
    public static void onDimensionChanged(NumenPlayer body) {
        String dim = body.level().dimension().location().toString();
        com.dwinovo.numen.api.event.NumenEvents.emit(body,
                com.dwinovo.numen.api.agent.inbox.EventTypes.DIMENSION_CHANGE,
                java.util.Map.of("to", dim),
                "You entered " + dim + ". Mind this dimension's surroundings and dangers.", false);
    }

    /**
     * 她从床上醒了。<b>急件</b>——{@code sleep} 到躺下那一刻就返回了,不发这条的话她会一直
     * 以为自己还睡着,躺在原地等一个不会来的结果。
     *
     * <p>成因取自原版自己的叫醒路径:{@code Player.tick} 里唯一的条件判定就是天亮
     * ({@code ServerLevel} 跳过夜也归到这条,它先把时间拨过去再叫醒);此外
     * {@code LivingEntity.hurt} 一挨打立刻醒。剩下的(床没了、被传送)没有共同标记,统称外力。
     */
    public static void onWoke(NumenPlayer body) {
        // 睡着时一挨打就醒,所以"上一刻还在睡"配上"这一刻还在受伤硬直"只可能是这一下打的——
        // 不是就近猜一个凶手:更早的伤根本不可能与"她还睡着"并存。
        String attacker = null;
        if (body.hurtTime > 0) {
            var source = body.getLastDamageSource();
            attacker = source == null ? "something"
                    : source.getEntity() != null ? source.getEntity().getName().getString()
                    : source.type().msgId();
        }
        boolean day = body.level().isDay();
        com.dwinovo.numen.api.event.NumenEvents.emit(body,
                com.dwinovo.numen.api.agent.inbox.EventTypes.WOKE,
                java.util.Map.of("cause", attacker != null ? "hurt" : day ? "daybreak" : "other"),
                wokeText(day, attacker), true);
    }

    /**
     * 醒来那句话。挨打排在天亮前面:两件都真时,挨她的那一下才是她该先处理的。
     *
     * @param attacker 打醒她的那个东西的名字;没挨打则 null
     */
    static String wokeText(boolean day, String attacker) {
        if (attacker != null) {
            return "You were hit awake by " + attacker + " and are out of bed. Look around before you decide to fight or get away"
                    + (day ? "; it is already day." : "; it is still night.");
        }
        return day
                ? "It is day; you woke up in bed on your own and the night is over. Carry on with what you were doing."
                : "You woke up in bed but it is still night — something woke you (the bed being gone or a teleport would do it). Look around first.";
    }

    /** Save the companion to its {@code .dat} and remove it from the world (the real-player logout). */
    public static void dormant(MinecraftServer server, NumenPlayer body) {
        // Refresh the respawn hint before the body leaves.
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry prev = reg.find(body.getUUID());
        if (prev != null) {
            reg.put(body.getUUID(),
                    prev.movedTo(((ServerLevel) body.level()).dimension(), body.blockPosition()));
        }
        CompanionFactory.despawn(server, body);
    }

    /**
     * <b>下线的唯一入口</b>:面板的下线与 {@code /numen player despawn} 都走这里,调用方只管验她是不是他用得了的
     * ({@link #usableBy},任何一位主人都能下令)、挑出是哪几只。
     * 名册记下"主人让她留在外面"、大脑停牌(主人在场,不停牌它会照样开回合),再对一次账({@link #reconcileCompanion}):她像真玩家一样登出,
     * 东西随 {@code .dat} 留着,她的家(对话、札记)原样;她的任何一位主人再召唤她才回来。已经被服务器请出去的不改。
     */
    public static void offline(MinecraftServer server, Collection<UUID> uuids) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        for (UUID uuid : uuids) {
            CompanionRegistry.Entry entry = reg.find(uuid);
            if (entry == null) {
                continue;
            }
            if (!entry.outside().isOutside()) {
                reg.markOutside(uuid, Outside.byOwner());
                NumenPlayer body = NumenPlayer.findByUuid(server, uuid);
                if (body != null) {
                    com.dwinovo.numen.api.event.NumenEvents.orderedOffline(body);
                }
            }
            reconcileCompanion(server, uuid);
            syncRosterToKeepers(server, entry.keepers());   // 面板据此把她显示成下线了的样子
        }
    }

    /** 这位主人用得了的、叫这个名字的同伴(大小写不计,同名的都算)让她们下线,见 {@link #offline};返回有几只。 */
    public static int offlineByName(MinecraftServer server, UUID ownerUuid, String name) {
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).usableBy(ownerUuid)) {
            if (e.getValue().name().equalsIgnoreCase(name)) ids.add(e.getKey());
        }
        offline(server, ids);
        return ids.size();
    }

    /** 删除这一只活体,见 {@link #dismiss(MinecraftServer, UUID, Collection)}。 */
    public static void dismiss(MinecraftServer server, NumenPlayer body) {
        dismiss(server, body.getOwnerUuid(), List.of(body.getUUID()));
    }

    /**
     * <b>删除(销号)的唯一入口</b>:面板的删除与 {@code /numen player delete} 都走这里,调用方只管验归属、挑出是哪几只。
     * 活着的身体像死亡一样把身上的一切掉在脚下再离场;登出着的没有身体可掉,{@code .dat} 留着东西但再没人重建它。
     * 最后一次除名、一次推送。
     */
    public static void dismiss(MinecraftServer server, UUID ownerUuid, Collection<UUID> uuids) {
        for (UUID uuid : uuids) {
            NumenPlayer live = NumenPlayer.findByUuid(server, uuid);
            if (live == null) continue;
            dropEverything(live);
            CompanionFactory.despawn(server, live);
        }
        forget(server, ownerUuid, uuids);
    }

    /** 穿戴的经各穿戴来源摘下再掉:模组的饰品栏不在原版物品栏里,只 dropAll 的话它们会跟着身体一起消失。 */
    private static void dropEverything(NumenPlayer body) {
        for (GearSlot slot : NumenPlugins.gearSlots(body)) {
            ItemStack worn = slot.swap(ItemStack.EMPTY);
            if (!worn.isEmpty()) body.drop(worn, true, false);
        }
        body.getInventory().dropAll();
    }

    /**
     * 永久除名:删注册表条目 = 这只同伴不再存在,再把新名册推给她的每一位主人——客户端据此把她的家目录一并删掉。
     * 除名和通知是同一件事的两半,放在一处才不会出现"删了条目却忘了通知"或者"通知了却没删干净"的半截状态。
     * 主人不在线就只删条目——他下次登录收到的名册照样是对的,对账是<b>状态同步</b>不是事件通知,漏不掉。
     */
    private static void forget(MinecraftServer server, UUID ownerUuid, Collection<UUID> uuids) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        java.util.Set<UUID> tell = new java.util.LinkedHashSet<>();
        if (ownerUuid != null) {
            tell.add(ownerUuid);
        }
        for (UUID uuid : uuids) {
            CompanionRegistry.Entry gone = reg.find(uuid);
            reg.remove(uuid);
            if (gone != null) {
                for (UUID keeper : gone.keepers()) {
                    // 和她同过会话的告诉一声她走了;名册上已经没有她,成员表按名册过滤
                    com.dwinovo.numen.api.brain.Conversations.of(server, keeper).left(uuid, gone.name());
                }
                tell.addAll(gone.keepers());
            }
            CompanionStateWatch.forget(uuid);   // 背包镜像同理:这个 UUID 不会再回来了
            com.dwinovo.numen.api.seat.ServerSeats.dismissed(uuid);   // 座位收掉,发件箱不再留
        }
        syncRosterToKeepers(server, tell);
    }

    /**
     * Permanently dismiss every registry entry of {@code ownerUuid} named {@code name} — gone for good, it
     * will NOT come back on login — through {@link #dismiss(MinecraftServer, UUID, Collection)}, which
     * also pushes the roster. Returns how many it dismissed. The registry is the whole answer: every body
     * is spawned from an entry and its entry is removed only after the body leaves ({@link #forget}), so a
     * live body always has one. (The {@code .dat} files orphan harmlessly — with no registry entry nothing
     * respawns them.)
     */
    public static int dismissByName(MinecraftServer server, UUID ownerUuid, String name) {
        List<UUID> ids = idsByName(server, ownerUuid, name);
        dismiss(server, ownerUuid, ids);
        return ids.size();
    }

    /** 主人名下叫这个名字的同伴(大小写不计,同名的都算)。 */
    private static List<UUID> idsByName(MinecraftServer server, UUID ownerUuid, String name) {
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            if (e.getValue().name().equalsIgnoreCase(name)) ids.add(e.getKey());
        }
        return ids;
    }

    /**
     * 这个人用不用得了这只同伴(他在她的主人表上):活体问身体;登出着的、没加载的看名册。面板的下线请求先过这一问。
     */
    public static boolean usableBy(MinecraftServer server, UUID person, UUID companion) {
        NumenPlayer body = NumenPlayer.findByUuid(server, companion);
        if (body != null) {
            return body.usableBy(person);
        }
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
        return entry != null && entry.usableBy(person);
    }

    /**
     * 这只同伴是不是这位主人创建的(归属):活体看身体上的主人;登出着的、没加载的看名册。只有创建者能做的事
     * (面板的删除请求、改主人表)先过这一问。
     */
    public static boolean owns(MinecraftServer server, UUID ownerUuid, UUID companion) {
        NumenPlayer body = NumenPlayer.findByUuid(server, companion);
        if (body != null) {
            return body.isOwnedByPlayer(ownerUuid);
        }
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
        return entry != null && entry.owner().equals(ownerUuid);
    }
}
