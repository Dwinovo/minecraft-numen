package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenPlugins;
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

    /** 死后躺多久再在主人身边复活。5 秒:够让主人看清"她死了"这件事,
     *  又短到不会把她整场战斗排除在外。 */
    private static final long RESPAWN_DELAY_TICKS = 5 * 20;

    private Companions() {}

    /**
     * Summon the owner's companion called {@code name}. IDEMPOTENT per (owner, name): if one already
     * exists it is reused — already live → returned as-is; dormant → brought back. Only a name with no
     * existing companion mints a fresh one. (The old "fresh random UUID every summon" minted same-name
     * duplicates that all respawned on login — that's the duplicate-companion bug.)
     *
     * <p>她是什么样子(档案、人设、声线、皮肤)不在这里定:召唤之后再绑({@code CompanionBinding})。玩家的召唤走
     * {@link #summonFor},这里是它和 GameTest 共用的那一步。
     */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String name,
                                      ServerLevel level, Vec3 pos) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        UUID existing = findByOwnerName(server, ownerUuid, name);
        if (existing != null) {
            // 被外面请出去的她只有主人再召唤才回来:召唤就是主人的决定,清掉"被请出去"的状态,身体回来后解开停牌
            boolean returning = reg.find(existing).removed();
            reg.markReturned(existing);
            NumenPlayer body = respawn(server, existing);
            if (body != null) {
                com.dwinovo.numen.api.brain.CompanionBrains.ensure(server, existing);
                if (returning) {
                    com.dwinovo.numen.api.brain.CompanionLoops.get(existing)
                            .ifPresent(com.dwinovo.numen.api.brain.CompanionLoop::onReturned);
                }
                return body;
            }
            reg.remove(existing);   // stale entry (no .dat) — replace it
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
        com.dwinovo.numen.api.brain.CompanionBrains.ensure(server, companionUuid);
        return body;
    }

    /** 召唤的结果:成了是她的身体;没成是要说给玩家听的话。 */
    public record Summoned(NumenPlayer body, net.minecraft.network.chat.Component problem) {}

    /**
     * <b>玩家召唤的唯一入口</b>:召唤卡与 {@code /numen player summon} 都走这里,谁都能召唤,只要一个名字,不设任何前提。
     * 名字要合 Minecraft 的命名规则、不能撞上在线的别人;每人最多养几只是服主在 {@code server.json} 里设的(缺省不限)——
     * 再召唤自己已有的同名同伴是叫醒她,不占名额。召唤成了把新名册推给他。
     *
     * @param creative 玩家在召唤卡上选的模式;{@code null} = 不动她的模式(命令没有这一项)
     */
    public static Summoned summonFor(ServerPlayer owner, String name, Boolean creative) {
        MinecraftServer server = owner.server;
        if (!MojangSkins.validName(name)) {
            return new Summoned(null, net.minecraft.network.chat.Component.translatable(
                    com.dwinovo.numen.api.data.ModLanguageData.Keys.NOTICE_NAME_INVALID));
        }
        // 重名闸:同名玩家已在线(真人/别的主人的同伴)一律拒绝——例外是自己的同名同伴(那是叫醒她)。
        var online = server.getPlayerList().getPlayerByName(name);
        boolean ownSameName = online instanceof NumenPlayer np && np.isOwnedByPlayer(owner.getUUID());
        if (online != null && !ownSameName) {
            return new Summoned(null, net.minecraft.network.chat.Component.translatable(
                    com.dwinovo.numen.api.data.ModLanguageData.Keys.NOTICE_NAME_TAKEN, name));
        }
        int limit = com.dwinovo.numen.api.brain.ModelServices.of(server).settings().maxCompanions();
        if (limit > 0 && findByOwnerName(server, owner.getUUID(), name) == null
                && CompanionRegistry.get(server).ownedBy(owner.getUUID()).size() >= limit) {
            return new Summoned(null, net.minecraft.network.chat.Component.translatable(
                    com.dwinovo.numen.api.data.ModLanguageData.Keys.NOTICE_SUMMON_LIMIT, limit));
        }
        ServerLevel level = (ServerLevel) owner.level();
        NumenPlayer body = summon(server, owner.getUUID(), name, level, owner.position());
        if (creative != null) {
            applyGameMode(owner, body, creative);
        }
        syncRosterToOwner(server, owner);
        return new Summoned(body, null);
    }

    /**
     * 给一只同伴换上这副皮肤(Mojang 签名的 textures);{@code null} = 回到默认皮肤。皮肤只在构造身体时注入 GameProfile,所以
     * 活体要原地重建(存盘离场再按名册重建,背包位置在做的活全走既有持久化通道回来);休眠和死着的只改名册,下次重建自然生效。
     * 穿的本来就是这一副就什么也不做。
     */
    public static void applySkin(MinecraftServer server, UUID companion, MojangSkins.Skin skin) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = reg.find(companion);
        String value = skin == null ? "" : skin.value();
        if (entry == null || entry.skinValue().equals(value)) {
            return;
        }
        reg.put(companion, entry.withSkin(value, skin == null ? "" : skin.signature()));
        NumenPlayer body = NumenPlayer.findByUuid(server, companion);
        if (body != null) {
            dormant(server, body);
            respawn(server, companion);
        }
    }

    /** 主人名下叫这个名字的同伴,没有则 null。判断规则在 {@link CompanionRoster#findByName}
     *  (纯 JVM,可单测);这里只负责把注册表摊平成它认得的形状。 */
    private static UUID findByOwnerName(MinecraftServer server, UUID ownerUuid, String name) {
        return CompanionRoster.findByName(rowsOf(server, ownerUuid), name);
    }

    /** 注册表 → 决策层认得的纯数据行。 */
    private static List<CompanionRoster.Row> rowsOf(MinecraftServer server, UUID ownerUuid) {
        List<CompanionRoster.Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            rows.add(new CompanionRoster.Row(e.getKey(), e.getValue().name(), e.getValue().diedAt()));
        }
        return rows;
    }

    /**
     * Bring a dormant companion back from its catalog entry + {@code .dat}
     * (position/inventory restored from disk). Returns the already-live body if
     * it is spawned, or {@code null} if it is unknown to the registry.
     */
    public static NumenPlayer respawn(MinecraftServer server, UUID companionUuid) {
        NumenPlayer live = NumenPlayer.findByUuid(server, companionUuid);
        if (live != null) return live;
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companionUuid);
        if (entry == null) return null;
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) level = server.overworld();
        // pos=null: keep the position restored from the .dat.
        return CompanionFactory.spawn(server, companionUuid, entry.name(), entry.owner(), level, null);
    }

    /**
     * 主人登录了,记一笔:他的同伴该回世界了。<b>真正的恢复不在这里做。</b>
     *
     * <h2>为什么必须推迟</h2>
     * 登录事件是在原版 {@code PlayerList.placeNewPlayer()} <b>内部</b>触发的——那句还没返回。
     * 在这里恢复同伴,等于把恢复期间的任何异常接到主人的入场流程上:一只同伴的 {@code .dat}
     * 有毛病、或者别的模组在同伴的入场事件里抛了,冒泡上去打断的是<b>主人的登录</b>,客户端
     * 看到的是"无效的玩家数据"——他会以为自己的存档毁了。
     *
     * <p>第二类堵不完:同伴入场会触发它自己的登录事件,装在这个世界里的任何模组都收得到,
     * 而它们没想过"玩家"可能是假的。我们挡不住别人抛异常,只能不让那异常落在主人头上。
     *
     * <p>推迟到下一个服务端 tick({@link #restorePending})就彻底解耦了:那时 placeNewPlayer
     * 早已返回,主人已经在世界里,同伴出什么事都只是同伴的事。
     */
    public static void scheduleRestoreFor(UUID ownerUuid) {
        PENDING_RESTORE.add(ownerUuid);
    }

    /** 排队等恢复的主人。世界作废时一并清掉——排的是上一个存档的账。 */
    private static final java.util.Set<UUID> PENDING_RESTORE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        com.dwinovo.numen.api.platform.ServerLifecycle.onStopped(PENDING_RESTORE::clear);
    }

    /**
     * 每个服务端 tick 调一次:把记下的主人挨个恢复。
     *
     * <p>一个主人恢复失败不牵连下一个——他们之间毫无关系,没有理由一起倒。
     */
    public static void restorePending(MinecraftServer server) {
        drainPending(ownerUuid -> respawnAllOwnedBy(server, ownerUuid));
    }

    /**
     * 排空队列,逐个交给 {@code restore}。
     *
     * <p>两条不变式:<b>每个主人只恢复一次</b>(先摘牌再执行,否则失败的那个会每 tick 重试到
     * 天荒地老),以及<b>一个失败不牵连下一个</b>(主人之间毫无关系,没有理由一起倒)。
     *
     * <p>纯逻辑,不碰 Minecraft——留这个缝是为了这两条能被单测钉住。
     */
    static void drainPending(java.util.function.Consumer<UUID> restore) {
        if (PENDING_RESTORE.isEmpty()) {
            return;
        }
        List<UUID> due = new ArrayList<>(PENDING_RESTORE);
        PENDING_RESTORE.removeAll(due);
        for (UUID ownerUuid : due) {
            try {
                restore.accept(ownerUuid);
            } catch (RuntimeException ex) {
                com.dwinovo.numen.api.Constants.LOG.error("[numen] 恢复 {} 的同伴时出错", ownerUuid, ex);
            }
        }
    }

    /** When an owner logs in, bring back every companion of theirs. A companion that DIED while the owner
     *  was away (death state persisted in the registry — survives the logout) is respawned-at-owner now
     *  AND told why it died; a live one is just restored from its {@code .dat}.
     *
     *  <p>逐只隔离:一只回不来是少一只同伴,不该把同一个主人的其余同伴也一起拖掉。 */
    public static void respawnAllOwnedBy(MinecraftServer server, UUID ownerUuid) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            try {
                com.dwinovo.numen.api.brain.CompanionBrains.ensure(server, e.getKey());
                if (e.getValue().diedAt() > 0L) {
                    if (owner != null) respawnDead(server, e.getKey(), e.getValue(), owner);
                } else if (e.getValue().removed()) {
                    // 她是被外面请出去的,不替主人重建;他上线了就把这件事告诉他
                    if (owner != null) tellRemoved(owner, e.getValue().name(), e.getValue().removedFor());
                } else {
                    respawn(server, e.getKey());
                }
            } catch (RuntimeException ex) {
                com.dwinovo.numen.api.Constants.LOG.error("[numen] 同伴 {} 没能回到世界里,跳过", e.getKey(), ex);
            }
        }
    }

    /**
     * <b>她的身体被外面的人或插件移除了的唯一入口</b>:服主的 {@code /kick}、别的插件踢人、登录插件超时踢人。
     * Numen 自己造成的移除(遣散、休眠、主人下线、关服)各有各的路,不要调这里。
     *
     * <p>平台挂点契约:模组上由 {@code MixinServerCommonPacketListener} 在断开落到她的假连接上那一步调(关服时不调);
     * Paper 上由 {@code PlayerKickEvent}/{@code PlayerQuitEvent} 调(踢人会连着触发两个,第二次被忽略)。调用时她的身体还在世界里;
     * <b>身体本身由平台移除</b>,这里只管之后的一切:
     * <ul>
     *   <li>大脑停牌、收件箱留下一条事实({@link com.dwinovo.numen.api.brain.CompanionLoop#onRemoved}),她手上的活和程序经
     *       {@link com.dwinovo.numen.api.CompanionEvent#REMOVE} 收掉——和主人下线、遣散走同一条收拾的路;</li>
     *   <li>名册记下她被请出去了和理由,在主人再召唤({@link #summon})之前不重建;</li>
     *   <li>主人在线就告诉他,不在线就等他下次上线({@link #respawnAllOwnedBy})。</li>
     * </ul>
     *
     * @param reason 踢人时说的理由
     */
    public static void removedFromOutside(NumenPlayer body, net.minecraft.network.chat.Component reason) {
        MinecraftServer server = body.level().getServer();
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = reg.find(body.getUUID());
        if (entry != null && entry.removed()) {
            return;
        }
        String why = reason.getString();
        com.dwinovo.numen.api.brain.CompanionLoops.get(body.getUUID()).ifPresent(brain -> brain.onRemoved(why));
        if (entry != null) {
            reg.markRemoved(body.getUUID(), why);
        }
        CompanionEvents.fire(CompanionEvent.REMOVE, body);
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner != null) {
            tellRemoved(owner, body.getName().getString(), why);
            syncRosterToOwner(server, owner);
        }
    }

    private static void tellRemoved(ServerPlayer owner, String name, String why) {
        owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                com.dwinovo.numen.api.data.ModLanguageData.Keys.NOTICE_COMPANION_REMOVED, name, why));
    }

    /**
     * A companion just DIED (detected in {@link NumenPlayer#tick}). The death itself is left fully
     * vanilla — drops / a grave mod / keepInventory all run because it's a real ServerPlayer death.
     * We only: stop the brain (its loop suspends, recording the
     * cut-off turn with the death cause), heal the body so its saved {@code .dat} is whole, and
     * queue a timed respawn at the owner. The corpse is removed AFTER this tick (a fake player isn't
     * auto-removed on death — it would sit at 0 HP forever waiting for a respawn packet that never comes).
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
        if (cause == null || cause.isBlank()) cause = "未知原因";
        // 先让大脑停牌,再触发生命周期钩子——<b>顺序要紧</b>:大脑一停牌 DEAD,
        // 此后钩子里产生的收尾事件(异步任务的 task_finished status="interrupted")
        // 照样进队列,但不开 run,安静躺到复活。
        //
        // 反过来的话,那条 urgent 收尾事件会在停牌之前到达、当场开一轮,而紧接着的
        // 停牌又把那一轮整个作废——白烧一次请求,还多一条没人看的对话。
        ServerPlayer owner = body.resolveOwnerPlayer();
        String diedOf = cause;
        com.dwinovo.numen.api.brain.CompanionLoops.get(uuid).ifPresent(brain -> brain.onDied(diedOf));
        CompanionEvents.fire(CompanionEvent.DEATH, body);   // 不发工具结果:那条 tool_call 已由死因结算
        // Persist the death (cause + game-time) in the world-saved registry so it survives a logout during
        // the respawn window — without this, a relog lost the pending state and the body silently respawned
        // "alive" with an empty inventory and no idea it had died.
        CompanionRegistry.get(server).markDead(uuid, cause, server.overworld().getGameTime());
        // 死亡状态进了注册表,名册才说得出"她还在,只是躺着"——面板的倒计时读这个。
        // 少了这一推,主人在死亡窗口里重登就会看见她凭空消失。
        if (owner != null) {
            syncRosterToOwner(server, owner);
        }
        body.setHealth(body.getMaxHealth());             // saved .dat is a healthy body for the respawn
        server.execute(() -> CompanionFactory.despawn(server, body));   // remove the corpse safely after the tick
    }

    /**
     * Bring back any companion whose post-death timer has elapsed, AT ITS OWNER (covers dimension
     * follow too — it returns in whatever dimension the owner is now in). Owner offline → keep waiting
     * (their client-side brain can't run anyway); it respawns the moment they're back. Called each tick.
     */
    public static void tickRespawns(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).pendingDead()) {
            CompanionRegistry.Entry entry = e.getValue();
            if (now - entry.diedAt() < RESPAWN_DELAY_TICKS) continue;
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner());
            if (owner == null) continue;                            // owner offline — wait for login
            // No safe spot right now → quietly retry next tick until the owner reaches open
            // space (the maid/vanilla-pet convention: never nag about a transient squeeze).
            respawnDead(server, e.getKey(), entry, owner);
        }
    }

    /** Respawn a dead companion at its owner, clear the death state, and tell the brain it died + why
     *  (the cause rides the respawn payload, so it works even after a logout cleared the client's memory).
     *  Returns false when no safe landing spot exists near the owner right now (tight tunnel, crawling,
     *  deep water) — the death state stays pending and the ticker retries until the owner reaches open
     *  space. Spawning anyway wedged the body into blocks: suffocate → die → respawn into the same spot,
     *  a death loop until the owner happened to move. */
    private static boolean respawnDead(MinecraftServer server, UUID uuid, CompanionRegistry.Entry entry,
                                       ServerPlayer owner) {
        ServerLevel level = (ServerLevel) owner.level();
        Vec3 pos = SafeSpawn.findNear(level, owner.position());
        if (pos == null && owner.onGround() && SafeSpawn.hasStandingRoom(level, owner.position())) {
            pos = owner.position();   // non-full-block floor (slab/carpet): the owner's own spot fits
        }
        if (pos == null) return false;
        NumenPlayer body = CompanionFactory.spawn(server, uuid, entry.name(), entry.owner(), level, pos);
        body.setHealth(body.getMaxHealth());
        body.clearFire();
        CompanionRegistry.get(server).markAlive(uuid);
        syncRosterToOwner(server, owner);
        com.dwinovo.numen.api.brain.CompanionLoops.get(uuid).ifPresent(brain -> brain.onRespawned(entry.deathCause()));
        return true;
    }

    /**
     * 把主人名下<b>存在的</b>同伴推给他的客户端。
     *
     * <p>取自持久的 {@link CompanionRegistry},不是玩家列表——死了、正等复活、休眠的
     * 同伴都还存在,只是此刻不在世界里。客户端拿这份名册对账并<b>删除已遣散同伴的
     * 本地数据</b>,所以名册的语义必须是"存在";用"此刻活着"做过一版,结果是同伴一死
     * 就被当成遣散,数据整个删掉。判断规则在 {@link CompanionRoster}(纯 JVM,可单测)。
     *
     * <p>任何"存在或存活状态"的变化之后都要调:登录、召唤、遣散、死亡、复活。
     */
    public static void syncRosterToOwner(MinecraftServer server, ServerPlayer owner) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        List<CompanionRoster.Row> rows = rowsOf(server, owner.getUUID());
        List<CompanionListPayload.Entry> list = new ArrayList<>();
        for (CompanionRoster.Line l : CompanionRoster.build(
                rows, server.overworld().getGameTime(), RESPAWN_DELAY_TICKS)) {
            NumenPlayer body = NumenPlayer.findByUuid(server, l.uuid());
            list.add(new CompanionListPayload.Entry(l.uuid(), l.name(), l.respawnInMs(),
                    body != null && body.isCreative(), reg.find(l.uuid()).removedFor()));
        }
        NumenNetwork.sendToPlayer(owner, new CompanionListPayload(reg.worldId(), list));
    }

    /**
     * 游戏模式落地,召唤表单和编辑卡同一道门。创造档过权限门:主人有 /gamemode
     * 权限(等级 2)<b>或本人就在创造</b>(无权限时客户端继承主人档)都放行;
     * 都不满足(伪造/竞态)按生存并说明——同伴的模式上限 = 主人的上限。
     */
    public static void applyGameMode(ServerPlayer owner, NumenPlayer body, boolean creative) {
        if (body == null) return;
        if (creative && !com.dwinovo.numen.api.platform.Services.permissions().isGameMaster(owner.createCommandSourceStack()) && !owner.isCreative()) {
            owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    com.dwinovo.numen.api.data.ModLanguageData.Keys.NOTICE_CREATIVE_NEEDS_OP));
            creative = false;
        }
        body.setGameMode(creative ? net.minecraft.world.level.GameType.CREATIVE
                : net.minecraft.world.level.GameType.SURVIVAL);
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
                "你进入了 " + dim + "。留意这个维度的环境和危险。", false);
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
            attacker = source == null ? "什么东西"
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
            return "你被" + attacker + "打醒了,已经不在床上。先看清楚周围再决定是打是躲"
                    + (day ? ",天已经亮了。" : ",天还没亮。");
        }
        return day
                ? "天亮了,你自己从床上醒来,夜过去了。接着做你原本要做的事。"
                : "你从床上醒来了,可是天还没亮——是外力把你弄醒的(床没了、被传送都算)。先看看周围。";
    }

    /** Save the companion to its {@code .dat} and remove it from the world (dormancy). */
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

    /** 遣散这一只活体,见 {@link #dismiss(MinecraftServer, UUID, Collection)}。 */
    public static void dismiss(MinecraftServer server, NumenPlayer body) {
        dismiss(server, body.getOwnerUuid(), List.of(body.getUUID()));
    }

    /**
     * <b>遣散的唯一入口</b>:面板 ✕ 与 {@code /numen player despawn} 都走这里,调用方只管验归属、挑出是哪几只。
     * 活着的身体像死亡一样把身上的一切掉在脚下再离场;休眠的没有身体可掉,{@code .dat} 留着东西但再没人重建它。
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
     * 永久除名:删注册表条目 = 这只同伴不再存在,再把新名册推给主人——客户端据此把她的家目录一并删掉。
     * 除名和通知是同一件事的两半,放在一处才不会出现"删了条目却忘了通知"或者"通知了却没删干净"的半截状态。
     * 主人不在线就只删条目——他下次登录收到的名册照样是对的,对账是<b>状态同步</b>不是事件通知,漏不掉。
     */
    private static void forget(MinecraftServer server, UUID ownerUuid, Collection<UUID> uuids) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        for (UUID uuid : uuids) {
            CompanionRegistry.Entry gone = reg.find(uuid);
            reg.remove(uuid);
            if (gone != null) {
                // 和她同过会话的告诉一声她走了;名册上已经没有她,成员表按名册过滤
                com.dwinovo.numen.api.brain.Conversations.of(server, gone.owner()).left(uuid, gone.name());
                com.dwinovo.numen.api.brain.ChatRoutes.refresh(server, gone.owner());   // 他聊天栏切进的会话少了她
            }
            CompanionStateWatch.forget(uuid);   // 背包镜像同理:这个 UUID 不会再回来了
            com.dwinovo.numen.api.brain.CompanionLoops.dismissed(server, uuid);   // 大脑收掉,她的家删掉
        }
        ServerPlayer owner = ownerUuid == null ? null : server.getPlayerList().getPlayer(ownerUuid);
        if (owner != null) {
            syncRosterToOwner(server, owner);
        }
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
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            if (e.getValue().name().equals(name)) ids.add(e.getKey());
        }
        dismiss(server, ownerUuid, ids);
        return ids.size();
    }
}
