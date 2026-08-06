package com.dwinovo.numen.entity;

import com.dwinovo.numen.network.payload.NumenInventoryPayload;
import com.dwinovo.numen.network.payload.RequestInventoryPayload;
import com.dwinovo.numen.platform.Services;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
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
 * <h2>就地判断,不算全量</h2>
 * {@code ItemStack.matches} 连耐久和附魔都比,她每挥一镐都会响。但原版顺手就告诉了我们
 * <b>是哪一格、变成了什么</b>,所以判断就在那一格上做:只比"物品身份 + 数量",相同就当没
 * 发生过。组件级的变化到不了模型面前——它读的是"我有什么、几个",要精确到槽位和附魔时
 * 才调 {@code inspect_gui}。
 *
 * <p>顺带,{@link Slot#container} 一比就挡住了别人的容器:她开着箱子时箱子那些格子照样在
 * 广播,但那不是她带着的东西。
 */
public final class CompanionInventoryWatch implements ContainerListener {

    /** 最小推送间隔(tick)。她连续捡东西时压成一波,模型下一轮才读,晚半秒无感。 */
    private static final int MIN_INTERVAL_TICKS = 10;
    /** mirror 里"这一格还没见过"的占位;真实的 pack 值不会是负数。 */
    private static final long UNSEEN = -1L;
    private static final long[] EMPTY_MIRROR = new long[0];

    private static final Map<UUID, CompanionInventoryWatch> WATCHES = new HashMap<>();

    static {
        // 谁持有谁自己报到:这张表攥着上一局的菜单引用,不作废会跟着进下一个存档。
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(CompanionInventoryWatch::dropAll);
    }

    private AbstractContainerMenu watched;
    /** 这具身体的背包;{@link #slotChanged} 靠它认出"这一格是不是她自己的"。 */
    private Container inventory;
    /** 每格上次见到的"物品身份 + 数量";只有它变了才算数。 */
    private long[] mirror = EMPTY_MIRROR;
    private boolean dirty = true;
    /** 只在 {@link #everSent} 为真时有意义——哨兵值参与减法会溢出,别给它哨兵。 */
    private long lastSentTick;
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
        if (inventory == null) {
            inventory = companion.getInventory();
            mirror = new long[inventory.getContainerSize()];
            java.util.Arrays.fill(mirror, UNSEEN);
        }
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
        dirty = false;
        lastSentTick = serverTick;
        boolean first = !everSent;
        everSent = true;
        NumenInventoryPayload payload = RequestInventoryPayload.snapshot(companion);
        Services.NETWORK.sendToPlayer(owner, payload);
        // 一次推送一行。链路是"服务端推 → 客户端缓存 → 渲染进请求",出问题时得能一眼看出
        // 断在哪一节;只记开始不记结果的日志上一轮已经害过我们一次。
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-inv] {} → {} 种物品 / {} 格, 主手 {}, 副手 {}{}",
                companion.getName().getString(), kinds(payload), usedSlots(payload),
                describe(payload.selectedSlot(), payload.items()), name(payload.offhand()),
                first ? " (首次)" : "");
    }

    private static int kinds(NumenInventoryPayload p) {
        return (int) p.items().stream().filter(s -> !s.isEmpty())
                .map(ItemStack::getItem).distinct().count();
    }

    private static int usedSlots(NumenInventoryPayload p) {
        return (int) p.items().stream().filter(s -> !s.isEmpty()).count();
    }

    private static String describe(int selected, java.util.List<ItemStack> items) {
        return selected < 0 || selected >= items.size() ? "空手" : name(items.get(selected));
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "空手"
                : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).getPath();
    }

    /** 一格的"意义":什么物品、几个。耐久、附魔、其它组件一概不在内。 */
    private static long pack(ItemStack stack) {
        return stack.isEmpty() ? 0L
                : ((long) System.identityHashCode(stack.getItem()) << 16) | (stack.getCount() & 0xFFFF);
    }

    @Override
    public void slotChanged(AbstractContainerMenu menu, int slotId, ItemStack stack) {
        Slot slot = menu.getSlot(slotId);
        if (slot.container != inventory) {
            return;   // 她开着的箱子在广播它自己的格子,那不是她带着的东西
        }
        int index = slot.getContainerSlot();
        if (index < 0 || index >= mirror.length) {
            return;
        }
        long packed = pack(stack);
        if (mirror[index] == packed) {
            return;   // 只是耐久掉了 / 附魔变了
        }
        mirror[index] = packed;
        dirty = true;
    }

    @Override
    public void dataChanged(AbstractContainerMenu menu, int slot, int value) {
        // 熔炉进度之类的数据格,与她带着什么无关。
    }
}
