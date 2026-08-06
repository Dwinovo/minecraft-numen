package com.dwinovo.numen.entity;

import com.dwinovo.numen.network.payload.NumenInventoryPayload;
import com.dwinovo.numen.network.payload.RequestInventoryPayload;
import com.dwinovo.numen.platform.Services;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 同伴背包一变就推一份给主人的客户端,让 agent 循环不必花一整轮 {@code get_self_status}
 * 去重新发现自己带着什么。
 *
 * <h2>变化是原版告诉我们的,不是我们轮询出来的</h2>
 * {@code AbstractContainerMenu.broadcastChanges()} 每 tick 都在跑,它自己就在
 * {@code triggerSlotListeners} 里逐格比对并通知 {@link ContainerListener}。挂上去等于白
 * 蹭一份已经算好的 diff——我们每 tick 只做<b>一次引用比较</b>(菜单换没换),不读 38 个格子。
 *
 * <h2>菜单会换,监听器得跟着走</h2>
 * 广播的只有<b>当前</b>菜单:开箱子时 {@code containerMenu} 换成箱子的菜单,关掉才换回
 * {@code inventoryMenu}。只挂在 {@code inventoryMenu} 上,一开容器就哑了——而那正是
 * {@code transfer} / {@code take_items} 搬东西的时候。{@code addSlotListener} 幂等,而且挂上
 * 时会立刻广播一次帮我们对齐基线。
 *
 * <h2>脏标记不等于该推</h2>
 * {@code ItemStack.matches} 连耐久和附魔都比,她每挥一镐都会响。所以 {@code slotChanged}
 * 只置脏标记;真正决定推不推的是"物品身份 + 数量"的指纹,而那个指纹<b>只在脏了之后才算</b>。
 * 组件级的变化到不了模型面前——它读的是"我有什么、几个",要精确到槽位和附魔时才调
 * {@code inspect_gui}。
 */
public final class CompanionInventoryWatch implements ContainerListener {

    /** 最小推送间隔(tick)。她连续捡东西时压成一波,模型下一轮才读,晚半秒无感。 */
    private static final int MIN_INTERVAL_TICKS = 10;
    private static final int MAIN_SLOTS = 36;

    private static final Map<UUID, CompanionInventoryWatch> WATCHES = new HashMap<>();

    static {
        // 谁持有谁自己报到:这张表攥着上一局的菜单引用,不作废会跟着进下一个存档。
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(CompanionInventoryWatch::dropAll);
    }

    private AbstractContainerMenu watched;
    private boolean dirty = true;
    /** 只在 {@link #everSent} 为真时有意义——哨兵值参与减法会溢出,别给它哨兵。 */
    private long lastSentTick;
    private int lastSentFingerprint;
    private boolean everSent;

    private CompanionInventoryWatch() {}

    /** 每服务端 tick、每个同伴调一次(见 {@code CompanionTickDispatcher})。 */
    public static void tick(NumenPlayer companion, long serverTick) {
        WATCHES.computeIfAbsent(companion.getUUID(), ignored -> new CompanionInventoryWatch())
                .tickOne(companion, serverTick);
    }

    /** 同伴离场(休眠/死亡/关服):丢掉它的看守,别把菜单引用攥着不放。 */
    public static void forget(UUID companionUuid) {
        WATCHES.remove(companionUuid);
    }

    /** 关服 / 换存档:世界作用域的进程内状态一律作废。 */
    public static void dropAll() {
        WATCHES.clear();
    }

    private void tickOne(NumenPlayer companion, long serverTick) {
        AbstractContainerMenu menu = companion.containerMenu;
        if (menu != watched) {
            watched = menu;
            menu.addSlotListener(this);   // 幂等;挂上即广播一次,基线自动对齐
        }
        if (!dirty) {
            return;
        }
        // 节流只对"已经推过一次"成立。第一次必须放行:她一登场就该有一份快照,
        // 而且用哨兵值去减会溢出成负数,那样条件永远成立、一个包也发不出去。
        if (everSent && serverTick - lastSentTick < MIN_INTERVAL_TICKS) {
            return;
        }
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null) {
            dirty = false;   // 主人不在线,推给谁;等他回来时 everSent 那条会补
            return;
        }
        int fingerprint = fingerprint(companion);
        dirty = false;
        if (everSent && fingerprint == lastSentFingerprint) {
            return;   // 只是耐久掉了 / 附魔变了,模型不关心
        }
        lastSentFingerprint = fingerprint;
        lastSentTick = serverTick;
        boolean first = !everSent;
        everSent = true;
        NumenInventoryPayload payload = RequestInventoryPayload.snapshot(companion);
        Services.NETWORK.sendToPlayer(owner, payload);
        // 一次推送一行。链路是"服务端推 → 客户端缓存 → 渲染进请求",出问题时得能一眼看出
        // 断在哪一节;只记开始不记结果的日志上一轮已经害过我们一次。
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-inv] {} → {} 种物品 / {} 格, 主手 {}{}",
                companion.getName().getString(), kinds(payload), usedSlots(payload),
                describe(payload.selectedSlot(), payload.items()), first ? " (首次)" : "");
    }

    private static int kinds(NumenInventoryPayload p) {
        return (int) p.items().stream().filter(s -> !s.isEmpty())
                .map(ItemStack::getItem).distinct().count();
    }

    private static int usedSlots(NumenInventoryPayload p) {
        return (int) p.items().stream().filter(s -> !s.isEmpty()).count();
    }

    private static String describe(int selected, java.util.List<ItemStack> items) {
        if (selected < 0 || selected >= items.size() || items.get(selected).isEmpty()) {
            return "空手";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(items.get(selected).getItem()).getPath();
    }

    /** 只认"什么物品、几个"——组件不进指纹,否则挖矿时每 tick 都算变了。 */
    private static int fingerprint(NumenPlayer companion) {
        var inv = companion.getInventory();
        int hash = inv.selected;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            hash = hash * 31 + stackHash(inv.getItem(i));
        }
        return hash * 31 + stackHash(companion.getOffhandItem());
    }

    private static int stackHash(ItemStack stack) {
        return stack.isEmpty() ? 0 : System.identityHashCode(stack.getItem()) * 31 + stack.getCount();
    }

    @Override
    public void slotChanged(AbstractContainerMenu menu, int slot, ItemStack stack) {
        dirty = true;
    }

    @Override
    public void dataChanged(AbstractContainerMenu menu, int slot, int value) {
        // 熔炉进度之类的数据格,与她带着什么无关。
    }
}
