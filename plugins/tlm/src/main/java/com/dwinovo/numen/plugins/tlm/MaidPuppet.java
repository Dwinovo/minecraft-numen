package com.dwinovo.numen.plugins.tlm;

import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 同伴的<b>傀儡</b>:一只永不入世界、只在渲染那一帧存在的 Mob,替同伴去做
 * 车万女仆的动画。每只同伴一只,状态每帧从本尊抄过来。
 *
 * <h2>为什么非得有它</h2>
 * 车万女仆的动画入口写死在 {@code BedrockModel.setupAnim} 里:
 * <pre>if (entityIn instanceof Mob mob) { IMaid.convert(mob); ...跑动画... }</pre>
 * 同伴是 {@code ServerPlayer} 的分身,渲染时是 {@code AbstractClientPlayer}——
 * 不是 Mob,落到这句就直接跳过,模型会以静止姿势僵住。给它配一只 Mob,
 * 动画就照常跑,车万女仆一行都不用改。
 *
 * <h2>为什么自己实现 IMaid,而不是挂 ConvertMaidEvent</h2>
 * {@code IMaid.convert} 的第一句是 {@code if (mob instanceof IMaid) return (IMaid) mob;},
 * 走到发事件那步之前就返回了。自己实现少一次事件广播,更重要的是不跟别的
 * 模组的 handler 抢同一只 Mob 的认领权。
 *
 * <h2>为什么借香草的实体类型,而不是自己注册一个</h2>
 * {@code LivingEntity} 构造时要 {@code DefaultAttributes.getSupplier(type)},
 * 拿不到属性表<b>当场崩</b>。所以类型必须是已注册且带属性表的——借
 * {@code EntityType.ZOMBIE}(人形、尺寸接近)。这只傀儡从不加进世界、
 * 从不存盘、从不同步,借谁的类型对外都不可见。
 * <b>别把它"清理"成自定义类型,会崩在构造函数里。</b>
 */
public final class MaidPuppet extends Mob implements IMaid {

    private String modelId;
    private AbstractClientPlayer body;

    public MaidPuppet(Level level) {
        super(EntityType.ZOMBIE, level);
        this.noPhysics = true;
        // 千万别 setInvisible(true) 当"保险"。LivingEntityRenderer 拿 isInvisible()
        // 决定 getRenderType,隐身且不发光时它返回 null——整只傀儡一笔都不画,
        // 表现就是同伴凭空消失。傀儡从不加进世界,本来也不需要这道保险。
    }

    /**
     * 把本尊此刻的状态抄过来。渲染前每帧调一次——傀儡不 tick,不抄就是一具静止的壳。
     *
     * <p>只抄动画真正读得到的那些({@code EntityMaidWrapper} 的口):姿态、朝向、
     * 移动量、生命、装备、是否在水里/地上。抄多了没用,抄少了动画会读到默认值。
     */
    public void mirror(AbstractClientPlayer player, String modelId) {
        boolean first = this.body == null;
        this.body = player;
        this.modelId = modelId;

        setPos(player.getX(), player.getY(), player.getZ());
        xo = player.xo; yo = player.yo; zo = player.zo;

        setYRot(player.getYRot());          yRotO = player.yRotO;
        setXRot(player.getXRot());          xRotO = player.xRotO;
        yBodyRot = player.yBodyRot;         yBodyRotO = player.yBodyRotO;
        yHeadRot = player.yHeadRot;         yHeadRotO = player.yHeadRotO;

        tickCount = player.tickCount;
        setDeltaMovement(player.getDeltaMovement());
        setOnGround(player.onGround());
        setPose(player.getPose());
        setSwimming(player.isSwimming());
        setSprinting(player.isSprinting());
        setShiftKeyDown(player.isShiftKeyDown());

        setHealth(player.getHealth());
        hurtTime = player.hurtTime;

        // 名牌:原生玩家渲染被取消了,名字得由傀儡来顶。Mob 默认只在被瞄准时显示
        // 自定义名,所以要显式打开——同伴的名字是主人认人的主要凭据,不能没有。
        setCustomName(player.getName());
        setCustomNameVisible(true);

        // 体型跟着本尊。名牌画在 getBbHeight() 上方,而 getBbHeight() 是 final、
        // 只能经 getDimensions 换——傀儡借的是僵尸类型(1.95),不换的话名牌会比
        // 主人身上时高出一截,更要紧的是会跟气泡对不齐:气泡是引擎画在<b>本尊</b>
        // 身上的,两者取不同的高度就会一上一下。
        // setPose 变化时香草自己会 refresh,这里只补第一次。
        if (first) refreshDimensions();

        swinging = player.swinging;
        swingTime = player.swingTime;
        swingingArm = player.swingingArm;
        attackAnim = player.attackAnim;
        oAttackAnim = player.oAttackAnim;
    }

    /**
     * 推进走路动画。<b>每客户端 tick 一次</b>,不能放进 {@link #mirror}。
     *
     * <p>{@code WalkAnimationState} 的 position 是个累加器,只能 {@code update} 推,
     * 没有 setter——抄不过来,只能自己按同样的节奏走。放到每帧去推的话,
     * 迈步快慢会跟着帧率变。
     */
    public void advanceWalk() {
        if (body == null) return;
        walkAnimation.update(body.walkAnimation.speed(), 1.0F);
    }

    // ── IMaid：只有这两个是抽象的，其余 default 全部读本尊 ──────────────

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public Mob asEntity() {
        return this;
    }

    // 其余 30 个 default 一个都不覆写。它们读的是 asEntity(),也就是这只傀儡,
    // 而傀儡的状态每帧从本尊抄过来——覆写等于给同一个事实开第二个源。
    //
    // 整份接口里只有 asStrictMaid/convertToMaid 需要真的 EntityMaid,而动画一个
    // 都不碰。上一版自作主张把 getTask() 覆写成返回 null,动画里 getTask().getUid()
    // 当场 NPE,setupAnim 每帧抛出、渲染中断,同伴整个消失——它的 default 本来
    // 就是 TaskManager.getIdleTask()。

    /** {@code getDimensions} 是 final(它 = getDefaultDimensions × getScale),换这一个就够。 */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return body == null ? super.getDefaultDimensions(pose) : body.getDimensions(pose);
    }

    // ── LivingEntity 的四个抽象方法：装备直接透传本尊 ────────────────

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return body == null ? List.of() : body.getArmorSlots();
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return body == null ? ItemStack.EMPTY : body.getItemBySlot(slot);
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        // 傀儡是只读镜像,写回去没有意义
    }

    @Override
    public HumanoidArm getMainArm() {
        return body == null ? HumanoidArm.RIGHT : body.getMainArm();
    }
}
