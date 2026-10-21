package com.dwinovo.numen.api.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 她的客户端会发给服务端的那些包,由这里代发(同伴的是 {@link NumenPlayer#packets})。<b>这是仓库里替她把客户端的包喂给服务端的唯一出口</b>:
 * 别处不直接调她连接上的 {@code handleXxx},每种包在这里一个方法。真玩家在界面里按一个钮、写一块牌子、选一笔交易、
 * 按 E,客户端发的是一个包,校验和落地全是原版服务端收包时做的;她没有客户端,这里把同一个包交给她连接上原版的
 * {@code ServerGamePacketListenerImpl.handleXxx},和 {@link Mouse}、{@link Hotbar} 送挖掘、切格是同一个入口。校验一条也不另写:
 * 附魔的等级与青金石、交易的材料与次数、告示牌要是 {@code playerWhoMayEdit} 且没涂蜡、书的页数、界面要 {@code stillValid},
 * 原版不认就静默不做。
 *
 * <p><b>发完要读回来。</b>原版拒绝时不报错也不回话(客户端靠下行的同步包才看到结果),所以调用方发完要去看世界、物品、界面真的变了没有,
 * 如实说成了或没成。同步处理的包(按钮、改名、选交易、信标、槽开关、玩家指令、交互)在方法返回时就落地了,读回来就是;写告示牌和写书
 * 原版先把文字交给过滤({@code filterTextPacket}),再排进服务端任务队列执行,方法返回时还没落地:读回来的活交给
 * {@link #afterHandled},排在它们后面。(过滤服务是异步的服务器——Mojang 的文字过滤配置——上,排进去的时候还没过滤完,读回来会看见没变;
 * 没有开过滤的服务器上过滤是当场完成的。)
 *
 * <p>只在服务端线程上用。
 */
public final class ClientPackets {

    private final ServerPlayer body;

    public ClientPackets(ServerPlayer body) {
        this.body = body;
    }

    /** 点"重生"(她死了以后):原版的 {@code PlayerList.respawn} 把她放回世界。 */
    public void respawn() {
        body.connection.handleClientCommand(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    /** 答传送编号:服务端每次传送她都等客户端报这个数。 */
    public void acceptTeleport(int id) {
        body.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
    }

    /** 玩家动作:挖掘的开始、中止、完成,松开右键,丢东西,主副手交换。{@code sequence} 是挖掘方块动作的序号,别的动作为 0。 */
    public void playerAction(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face, int sequence) {
        body.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, pos, face, sequence));
    }

    /** 中键把背包里第 {@code slot} 格的东西换进快捷栏。 */
    public void pickItem(int slot) {
        body.connection.handlePickItem(new ServerboundPickItemPacket(slot));
    }

    /** 数字键、滚轮:选中快捷栏第 {@code slot} 格。 */
    public void setCarriedItem(int slot) {
        body.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
    }

    /** 写告示牌:{@code lines} 至多四行,不足的补空行(原版一次换掉整面的四行)。 */
    public void signUpdate(BlockPos pos, boolean front, List<String> lines) {
        List<String> four = new ArrayList<>(lines);
        while (four.size() < 4) {
            four.add("");
        }
        body.connection.handleSignUpdate(new ServerboundSignUpdatePacket(pos, front, four.get(0), four.get(1),
                four.get(2), four.get(3)));
    }

    /**
     * 书与笔:{@code slot} 是快捷栏的格(0 到 8)或副手(40),{@code title} 有值就署名成成书。原版只认这两种格。
     */
    public void editBook(int slot, List<String> pages, Optional<String> title) {
        body.connection.handleEditBook(new ServerboundEditBookPacket(slot, pages, title));
    }

    /** 铁砧改名:开着的界面是铁砧时,换掉要起的名字。 */
    public void renameItem(String name) {
        body.connection.handleRenameItem(new ServerboundRenameItemPacket(name));
    }

    /** 交易界面里选第 {@code index} 笔(从 0 起):原版自己把付款从背包填进付款格。 */
    public void selectTrade(int index) {
        body.connection.handleSelectTrade(new ServerboundSelectTradePacket(index));
    }

    /** 信标界面里定主、副效果。 */
    public void setBeacon(Optional<Holder<MobEffect>> primary, Optional<Holder<MobEffect>> secondary) {
        body.connection.handleSetBeaconPacket(new ServerboundSetBeaconPacket(primary, secondary));
    }

    /** 开着的界面里按第 {@code button} 个钮(附魔台、切石机、织布机、讲台的钮号随界面而定)。 */
    public void menuButton(int button) {
        body.connection.handleContainerButtonClick(
                new ServerboundContainerButtonClickPacket(body.containerMenu.containerId, button));
    }

    /** 合成器界面里开关第 {@code slot} 格:{@code enabled} 为真是开着、能放东西。 */
    public void slotState(int slot, boolean enabled) {
        body.connection.handleContainerSlotStateChanged(
                new ServerboundContainerSlotStateChangedPacket(slot, body.containerMenu.containerId, enabled));
    }

    /** 玩家指令:起床、鞘翅起飞、骑着时开坐骑的物品栏……。 */
    public void command(ServerboundPlayerCommandPacket.Action action) {
        body.connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(body, action));
    }

    /**
     * 右键实体上的某一点({@code INTERACT_AT},盔甲架摆装备取装备走这条):{@code at} 是相对实体位置的点,和真客户端发的一样。
     * 真客户端接着看自己预测的结果,没被吃掉才再发一个 {@code INTERACT};这里没有预测,用"手里的东西、对方身上的装备有没有变"代它回答。
     *
     * @return 这一下起了作用(手里的东西或对方身上的装备变了):调用方不必再按 {@code INTERACT}
     */
    public boolean interactAt(Entity target, InteractionHand hand, Vec3 at) {
        ItemStack held = body.getItemInHand(hand).copy();
        List<ItemStack> worn = equipment(target);
        body.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(target,
                body.isShiftKeyDown(), hand, at));
        return !ItemStack.matches(held, body.getItemInHand(hand)) || !same(worn, equipment(target));
    }

    /**
     * 原版把写字的包排在服务端任务队列里;{@code readBack} 排在它后面,到它跑的时候文字已经落地(或被原版拒了),可以读回来。
     * 任务队列是先进先出,所以只要在发包之后排,就一定在它后面。
     */
    public void afterHandled(Runnable readBack) {
        body.getServer().execute(readBack);
    }

    private static List<ItemStack> equipment(Entity target) {
        List<ItemStack> out = new ArrayList<>();
        if (target instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                out.add(living.getItemBySlot(slot).copy());
            }
        }
        return out;
    }

    private static boolean same(List<ItemStack> before, List<ItemStack> after) {
        for (int i = 0; i < before.size(); i++) {
            if (!ItemStack.matches(before.get(i), after.get(i))) {
                return false;
            }
        }
        return true;
    }
}
