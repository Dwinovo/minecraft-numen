package com.dwinovo.numen.api.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.permission.ConsentItem;
import com.dwinovo.numen.api.permission.Gate;
import com.dwinovo.numen.api.permission.Permission;
import com.dwinovo.numen.api.permission.Verdict;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 一具服务端假玩家的鼠标,原版机制只此一份(同伴的是 {@link NumenPlayer#mouse})。和真人一样,鼠标只对准星落着的东西起作用:
 * 先用 {@link Look} 转过去,再按;要点哪一格、哪只实体由准星({@link #pick})说了算,不收调用方给的命中结果。
 *
 * <h2>准星</h2>
 * 照原版客户端每刻算准星的那一次拾取({@code GameRenderer.pick}):沿视线先找方块轮廓(不看流体),再找挡在它前面、能被点中的
 * 实体,各按自己的交互距离截断。准星被实体挡住时,挖掘停下、放置不按,与真玩家一样。
 *
 * <h2>左键:挖</h2>
 * 照原版客户端的挖掘循环({@code MultiPlayerGameMode} 的 {@code startDestroyBlock}、{@code continueDestroyBlock}、
 * {@code stopDestroyBlock})。送到服务端的,是真客户端的包到了服务端之后走的同一个入口——挖掘经 {@code handlePlayerAction}。
 * 第一下送 START:秒破的方块与创造模式当场碎;其余每刻累加原版的 {@code getDestroyProgress},到 1 送 STOP,服务端按它自己的钟
 * 确认后挖掉。碎掉之后手要缓几刻才挖下一格,照原版客户端的 {@code destroyDelay}({@link DigTime#cooldown}:累着进度挖碎的与创造
 * 模式缓 5 刻,秒破的不缓)。手上的东西换了就从头挖;原地变了的(修补附魔吸经验改了耐久)还是同一件,不重开——原版客户端记的是
 * 手上那一叠本身。换了一格挖,或松开({@link #release})进度清零。
 *
 * <p>服务端没挖掉就是没让挖:送 STOP 之后方块没变,再等服务端自己的钟走一阵(它记的进度偶尔比客户端慢,会挂成延迟破坏,过几刻
 * 自己挖掉);还不变就以 {@link Refusal#SERVER} 收场,不空挥到超时。
 *
 * <h2>右键:用</h2>
 * 照原版客户端的 {@code Minecraft.startUseItem}:两次右键至少隔 4 刻;正在挖时不按;每只手(主手先)先试准星落着的东西——方块
 * ({@code handleUseItemOn} 的同一套检查之后调 {@code useItemOn})或实体({@code interact}、{@code interactOn})——没吃掉再用手里
 * 的东西({@code useItem});准星什么也没落着就只剩手里的东西。点格子、点空气、点实体是同一个右键、同一种结果({@link Use.Pressed}):
 * 点中的那一格与它面前那一格(各连同上下一格,门的另一半在那里)哪几格变了,以及这一下有没有打开一个界面。吃喝、拉弓、举盾是同一个
 * 键按住:按下去({@link #use}、{@link #useItem})开始用,松开是 {@link #stopUse}。
 *
 * <h2>权限</h2>
 * 身体是同伴({@link NumenPlayer})时,每一下动手之前先过权限层,这是关卡长在身体上、谁驱动她都绕不开的地方:左键换到新的一格时问
 * "能不能拆这一格",右键手上的东西会往世界里放东西({@link #placing})时问"能不能放",否则问"能不能用这一格"。不许就不动手,交回
 * 裁决本身当理由;要问主人时鼠标停下,交回要问的那一条({@link Refusal.Asks}),由驱动者决定怎么问。不是同伴的普通假玩家照原版,
 * 不过权限层。服务端自己退回的挖掘如实报成被拒({@link Refusal#SERVER}),那是身体汇报,不是权限裁决。
 *
 * <p>缓手按游戏刻计(世界的游戏时间),不随有没有按键、有没有被调用而冻住;调高刻速率也一样。只在世界所在的线程上调用。
 * 类不是 final:测试可以派生一个包在身体的鼠标外面看它做了什么。
 */
public class Mouse {

    /** 服务端没让这一下落地(挖了没碎)。 */
    public sealed interface Refusal {

        /** 动手之前权限层说不许。 */
        record Denied(Verdict verdict) implements Refusal {}

        /** 动手之前权限层说要问主人:裁决连同要问的那一条。 */
        record Asks(Verdict verdict, ConsentItem item) implements Refusal {}

        /** 服务端把这一下退回来了:挖了没碎(别的模组取消了破坏、出生点保护、冒险模式)。 */
        record Server() implements Refusal {}

        /** 服务端退回的那一种。 */
        Refusal SERVER = new Server();

        /** 权限层的裁决;服务端退回的不是权限层的话,为 null。 */
        default Verdict verdict() {
            return switch (this) {
                case Denied denied -> denied.verdict();
                case Asks asks -> asks.verdict();
                case Server server -> null;
            };
        }
    }

    /** 左键按住这一刻的结果。 */
    public sealed interface Strike {

        /**
         * 还在挖,或上一格刚碎、手还没缓过来。{@code pressed}:这一刻真按下去了(送出了这一下);手还没缓过来、这一下没按时为
         * false——只点一下的人要等到按下去的那一刻。
         */
        record Swinging(boolean pressed) implements Strike {}

        /** 碎了:{@code pos} 这一格原来是 {@code before}。 */
        record Broke(BlockPos pos, BlockState before) implements Strike {}

        /** 没让挖。 */
        record Refused(BlockPos pos, Refusal reason) implements Strike {}

        /** 准星没落在任何一格方块上,没有可挖的;在挖的那一格已经放下。 */
        record Missed() implements Strike {}

        Strike SWINGING = new Swinging(true);
        Strike RECOVERING = new Swinging(false);
        Strike MISSED = new Missed();
    }

    /** 右键这一下的结果。 */
    public sealed interface Use {

        /** 手还没缓过来(原版两次右键至少隔 4 刻),或正在挖:这一下没按。 */
        record Waiting() implements Use {}

        /**
         * 按了。
         *
         * @param hit     这一下按在准星落着的什么上(方块、实体,或什么也没有)
         * @param changes 点中的那一格与它面前那一格(各连同上下一格)里,变了的格
         * @param opened  这一下打开了一个界面(箱子、熔炉、交易、模组机器)
         */
        record Pressed(HitResult hit, List<Change> changes, boolean opened) implements Use {

            public Pressed {
                changes = List.copyOf(changes);
            }
        }

        /** 没让按。 */
        record Refused(BlockPos pos, Refusal reason) implements Use {}

        Use WAITING = new Waiting();
    }

    /** 一格从 {@code before} 变成了 {@code after}。 */
    public record Change(BlockPos pos, BlockState before, BlockState after) {}

    /** 原版客户端两次右键之间的刻数。 */
    private static final int RIGHT_CLICK_DELAY = 4;
    /** 送了 STOP 之后,最多等服务端这么多刻把延迟破坏落地。 */
    private static final int STOP_GRACE = 20;

    private final ServerPlayer body;
    private int sequence;
    private long destroyReadyAt;
    private long useReadyAt;
    /** 正在挖的那一格;没在挖为 null。 */
    private BlockPos destroying;
    /** 开挖时手上那一叠本身(不是副本)。 */
    private ItemStack destroyingItem = ItemStack.EMPTY;
    private float progress;
    /** 送过 STOP、等服务端落地的那一格与原来的方块;没在等为 null。 */
    private BlockPos stopped;
    private BlockState stoppedState;
    private long stopDeadline;

    public Mouse(ServerPlayer body) {
        this.body = body;
    }

    private long now() {
        return body.level().getGameTime();
    }

    // ==================== 准星 ====================

    /** 准星落着的东西:方块、实体,或什么也没有({@link HitResult.Type#MISS})。 */
    public HitResult pick() {
        double blockRange = body.blockInteractionRange();
        double entityRange = body.entityInteractionRange();
        double range = Math.max(blockRange, entityRange);
        double rangeSqr = Mth.square(range);
        Vec3 eye = body.getEyePosition();
        HitResult block = body.pick(range, 1.0F, false);
        double blockSqr = block.getLocation().distanceToSqr(eye);
        if (block.getType() != HitResult.Type.MISS) {
            rangeSqr = blockSqr;
            range = Math.sqrt(blockSqr);
        }
        Vec3 view = body.getViewVector(1.0F);
        Vec3 end = eye.add(view.x * range, view.y * range, view.z * range);
        AABB swept = body.getBoundingBox().expandTowards(view.scale(range)).inflate(1.0, 1.0, 1.0);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(body, eye, end, swept,
                e -> !e.isSpectator() && e.isPickable(), rangeSqr);
        return entity != null && entity.getLocation().distanceToSqr(eye) < blockSqr
                ? within(entity, eye, entityRange)
                : within(block, eye, blockRange);
    }

    /** 准星正落在 {@code pos} 这一格上时交出那一下;否则为 null。 */
    public BlockHitResult on(BlockPos pos) {
        return pick() instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos) ? hit : null;
    }

    /**
     * 手里的东西在空中右键时自己沿视线打的那一条,照原版 {@code Item.getPlayerPOVHitResult}(桶倒水、舀水就用它):从眼睛沿视线
     * 打方块交互距离,按方块轮廓,液体按 {@code fluid} 的规矩算不算;不看实体。和准星那一次拾取不是同一条:桶瞄的是它自己这条。
     */
    public BlockHitResult itemRay(ClipContext.Fluid fluid) {
        Vec3 eye = body.getEyePosition();
        Vec3 end = eye.add(body.calculateViewVector(body.getXRot(), body.getYRot()).scale(body.blockInteractionRange()));
        return body.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, body));
    }

    private static HitResult within(HitResult hit, Vec3 eye, double range) {
        Vec3 at = hit.getLocation();
        if (at.closerThan(eye, range)) {
            return hit;
        }
        Direction direction = Direction.getNearest(at.x - eye.x, at.y - eye.y, at.z - eye.z);
        return BlockHitResult.miss(at, direction, BlockPos.containing(at));
    }

    // ==================== 左键 ====================

    /** 左键正按在某一格上(包括送了 STOP 在等服务端落地)。 */
    public boolean digging() {
        return pressing() != null;
    }

    /** 左键正按在哪一格上(包括送了 STOP 在等服务端落地的那一格);没按为 null。 */
    public BlockPos pressing() {
        return stopped != null ? stopped : destroying;
    }

    /**
     * 按住左键,这一刻挖准星落着的那一格。换了一格就从头挖;挖到那一格碎掉为止要按住好几刻,中间松开({@link #release})进度清零,
     * 与真玩家一样。准星没落在方块上就是没有可挖的({@link Strike.Missed})。
     */
    public Strike dig() {
        ServerLevel level = body.serverLevel();
        if (stopped != null) {
            return awaitStop(level);
        }
        if (!(pick() instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            release();
            return Strike.MISSED;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        if (!pos.equals(pressing())) {
            Refusal refusal = refusal(Action.breakBlock(pos, level.getBlockState(pos)));
            if (refusal != null) {
                release();
                return new Strike.Refused(pos, refusal);
            }
        }
        if (now() < destroyReadyAt) {
            return Strike.RECOVERING;
        }
        Direction face = hit.getDirection();
        BlockState state = level.getBlockState(pos);
        if (body.gameMode.isCreative()) {
            cooldown(true, true);
            body.swing(InteractionHand.MAIN_HAND);
            send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face);
            return landed(level, pos, state);
        }
        if (destroying == null || !destroying.equals(pos)
                || !ItemStack.isSameItemSameComponents(body.getMainHandItem(), destroyingItem)) {
            return start(level, pos, face, state);
        }
        if (state.isAir()) {
            destroying = null;
            return Strike.SWINGING;
        }
        progress += state.getDestroyProgress(body, level, pos);
        body.swing(InteractionHand.MAIN_HAND);
        if (progress < 1.0F) {
            return Strike.SWINGING;
        }
        destroying = null;
        progress = 0;
        cooldown(false, false);
        send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face);
        if (level.getBlockState(pos) != state) {
            return new Strike.Broke(pos, state);
        }
        stopped = pos;
        stoppedState = state;
        stopDeadline = now() + STOP_GRACE;
        return Strike.SWINGING;
    }

    /** 碎掉一格之后缓手:缓的那几刻里不挖,下一刻才挖下一格。 */
    private void cooldown(boolean creative, boolean instant) {
        destroyReadyAt = now() + DigTime.cooldown(creative, instant) + 1;
    }

    /** 第一下:原版客户端换了目标就先放下旧的,再送 START;秒破的当场碎,否则开始累加进度。 */
    private Strike start(ServerLevel level, BlockPos pos, Direction face, BlockState state) {
        release();
        body.swing(InteractionHand.MAIN_HAND);
        boolean instant = !state.isAir() && state.getDestroyProgress(body, level, pos) >= 1.0F;
        send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face);
        if (instant) {
            return landed(level, pos, state);
        }
        destroying = pos;
        destroyingItem = body.getMainHandItem();
        progress = 0;
        return Strike.SWINGING;
    }

    /** 当场该碎的那一下落没落地。 */
    private static Strike landed(ServerLevel level, BlockPos pos, BlockState before) {
        return level.getBlockState(pos) != before
                ? new Strike.Broke(pos, before)
                : new Strike.Refused(pos, Refusal.SERVER);
    }

    /** 送过 STOP 还没碎:服务端挂了延迟破坏会在它自己的刻里挖掉,等一阵;一直不变就是没让挖。 */
    private Strike awaitStop(ServerLevel level) {
        BlockPos pos = stopped;
        if (level.getBlockState(pos) != stoppedState) {
            stopped = null;
            return new Strike.Broke(pos, stoppedState);
        }
        if (now() < stopDeadline) {
            return Strike.SWINGING;
        }
        stopped = null;
        return new Strike.Refused(pos, Refusal.SERVER);
    }

    /** 松开左键:正在挖的那一格放下,进度清零。没在挖时什么也不做。 */
    public void release() {
        if (destroying != null) {
            send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, destroying, Direction.DOWN);
            body.resetAttackStrengthTicker();
        }
        destroying = null;
        progress = 0;
    }

    /** 这一个动作经服务端收包的入口送进去。 */
    private void send(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face) {
        body.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, pos, face, ++sequence));
    }

    // ==================== 右键 ====================

    /** 右键一下准星落着的东西,方块与实体没吃掉这一下就用手里的东西:原版客户端的完整右键。 */
    public Use use() {
        return use(true);
    }

    /**
     * 右键一下准星落着的东西。
     *
     * @param itemFallthrough 方块与实体没吃掉这一下时,同一次按键落到手里的东西自用(桶找水、船找水面、掷物出手都住在那条
     *                        路上);驱动者拿它挡身体约束物品(手里是食物、末影珍珠时点了块石头别把她自己喂了)。准星什么也没
     *                        落着时,手里的东西本来就是这一下的全部,不受它管
     */
    public Use use(boolean itemFallthrough) {
        if (now() < useReadyAt || digging()) {
            return Use.WAITING;
        }
        HitResult hit = pick();
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            Use.Refused refused = refusedUse(block);
            if (refused != null) {
                return refused;
            }
        }
        return press(hit, itemFallthrough);
    }

    /**
     * 只用手里的东西,不看准星落着什么(吃、喝、拉弓、掷出去、桶找水):主手先试,不成再用副手,和原版右键点向空气同一个顺序。
     */
    public Use useItem() {
        if (now() < useReadyAt || digging()) {
            return Use.WAITING;
        }
        return press(BlockHitResult.miss(body.getEyePosition(), Direction.UP, body.blockPosition()), true);
    }

    /** 松开右键:还在用的东西(拉着的弓)放开。没在用时什么也不做。 */
    public void stopUse() {
        if (body.isUsingItem()) {
            body.releaseUsingItem();
        }
    }

    private Use press(HitResult hit, boolean itemFallthrough) {
        useReadyAt = now() + RIGHT_CLICK_DELAY;
        ServerLevel level = body.serverLevel();
        AbstractContainerMenu menuBefore = body.containerMenu;
        BlockHitResult block = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b : null;
        Entity entity = hit instanceof EntityHitResult e ? e.getEntity() : null;
        Map<BlockPos, BlockState> before = block == null ? Map.of() : around(level, block);
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = body.getItemInHand(hand);
            if (!stack.isItemEnabled(level.enabledFeatures())) {
                break;
            }
            if (block != null) {
                InteractionResult onBlock = useItemOn(level, stack, hand, block);
                if (onBlock.consumesAction() || onBlock == InteractionResult.FAIL) {
                    break;
                }
            } else if (entity != null && useOnEntity(entity, hand)) {
                break;
            }
            if ((itemFallthrough || (block == null && entity == null)) && !stack.isEmpty() && useItem(level, stack, hand)) {
                break;
            }
        }
        List<Change> changes = new ArrayList<>();
        before.forEach((pos, was) -> {
            BlockState now = level.getBlockState(pos);
            if (now != was) {
                changes.add(new Change(pos, was, now));
            }
        });
        boolean opened = body.containerMenu != menuBefore && body.containerMenu != body.inventoryMenu;
        return new Use.Pressed(hit, changes, opened);
    }

    /**
     * 原版 {@code handleUseItemOn} 在调 {@code useItemOn} 之前的检查:够得着(交互距离加 1 格宽限)、点中的位置落在那一格上、
     * 不在建筑高度之上、世界许这具身体动它。
     */
    private InteractionResult useItemOn(ServerLevel level, ItemStack stack, InteractionHand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        Vec3 offset = hit.getLocation().subtract(Vec3.atCenterOf(pos));
        if (!body.canInteractWithBlock(pos, 1.0) || Math.abs(offset.x) >= 1.0000001
                || Math.abs(offset.y) >= 1.0000001 || Math.abs(offset.z) >= 1.0000001) {
            return InteractionResult.PASS;
        }
        body.resetLastActionTime();
        if (pos.getY() >= level.getMaxBuildHeight() || !level.mayInteract(body, pos)) {
            return InteractionResult.PASS;
        }
        InteractionResult result = body.gameMode.useItemOn(body, level, stack, hand, hit);
        if (result.consumesAction()) {
            CriteriaTriggers.ANY_BLOCK_USE.trigger(body, pos, stack.copy());
        }
        if (result.shouldSwing()) {
            body.swing(hand, true);
        }
        return result;
    }

    /** 右键一只实体(动物、村民、物品展示框、拴绳):它吃掉了这一下为 true。 */
    private boolean useOnEntity(Entity entity, InteractionHand hand) {
        return entity.interact(body, hand).consumesAction()           // 动物、村民
                || body.interactOn(entity, hand).consumesAction();    // 物品展示框、拴绳
    }

    /** 用手里的东西({@code useItem});它吃掉了这一下为 true。 */
    private boolean useItem(ServerLevel level, ItemStack stack, InteractionHand hand) {
        InteractionResult result = body.gameMode.useItem(body, level, stack, hand);
        if (result.shouldSwing()) {
            body.swing(hand);
        }
        return result.consumesAction();
    }

    /** 右键可能改到的几格:点中的那一格、它面前那一格,各连同上下一格。 */
    private static Map<BlockPos, BlockState> around(ServerLevel level, BlockHitResult hit) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (BlockPos center : new BlockPos[] {hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection())}) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos pos = center.offset(0, dy, 0).immutable();
                out.putIfAbsent(pos, level.getBlockState(pos));
            }
        }
        return out;
    }

    // ==================== 权限 ====================

    /**
     * 右键落在 {@code hit} 这一面、手里是 {@code stack} 时,会不会往世界里放东西、放在哪:方块物品贴着命中面放进可替换的格,
     * 桶倒出或舀起液体,打火石与火焰弹点起火——都是一次放置,交权限层裁决。不往世界里放东西时为 null。按下右键的各处都按这一份判。
     */
    public static Action placing(Level level, BlockHitResult hit, ItemStack stack) {
        BlockPos placeAt = hit.getBlockPos().relative(hit.getDirection());
        BlockState before = level.getBlockState(placeAt);
        Item item = stack.getItem();
        boolean places = (before.canBeReplaced() && item instanceof BlockItem)
                || item instanceof BucketItem
                || item instanceof FlintAndSteelItem
                || item instanceof FireChargeItem;
        return places ? Action.place(placeAt, before, item) : null;
    }

    /**
     * 右键方块之前过权限层:两只手里会往世界里放东西的各问各的放下它;都不放,问用这一格(开门、开箱)。放行为 null。
     */
    private Use.Refused refusedUse(BlockHitResult hit) {
        for (InteractionHand hand : InteractionHand.values()) {
            Action action = placing(body.level(), hit, body.getItemInHand(hand));
            if (action == null) {
                continue;
            }
            Refusal refusal = refusal(action);
            if (refusal != null) {
                return new Use.Refused(action.pos(), refusal);
            }
        }
        BlockPos clicked = hit.getBlockPos();
        Refusal refusal = refusal(Action.useBlock(clicked, body.level().getBlockState(clicked)));
        return refusal == null ? null : new Use.Refused(clicked.immutable(), refusal);
    }

    /** 这一下过权限层:放行(或身体不是同伴,不过权限层)为 null;拒绝是裁决;要问是连同要问的那一条。 */
    private Refusal refusal(Action action) {
        if (!(body instanceof NumenPlayer companion)) {
            return null;
        }
        Gate gate = Permission.gateFor(companion);
        Verdict verdict = gate.judgeLive(action, companion.serverLevel());
        return switch (verdict.kind()) {
            case ALLOW -> null;
            case DENY -> new Refusal.Denied(verdict);
            case ASK -> new Refusal.Asks(verdict, gate.consentItemLive(action, verdict, companion.serverLevel()));
        };
    }
}
