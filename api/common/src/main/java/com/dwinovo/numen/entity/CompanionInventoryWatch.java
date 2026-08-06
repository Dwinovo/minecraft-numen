package com.dwinovo.numen.entity;

import com.dwinovo.numen.network.payload.NumenInventoryPayload;
import com.dwinovo.numen.network.payload.RequestInventoryPayload;
import com.dwinovo.numen.platform.Services;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 同伴背包一变就推一份给主人的客户端,让 agent 循环不必花一整轮 {@code get_self_status}
 * 去重新发现自己带着什么。
 *
 * <h2>为什么非推不可</h2>
 * 渲染那块提示词的代码在<b>客户端</b>,而 companion 的 36 格从不同步给客户端——原版只同步
 * 装备槽,背包是每个玩家的私有数据。客户端手上根本没有背包可遍历,而构造请求是同步的,
 * 不能现场等一个网络往返。所以只能由服务端把它推过去,让客户端的缓存始终是新鲜的。
 *
 * <h2>为什么要检测变化</h2>
 * 省的不是提示词 token(那块每次请求都得带,它从不进历史),是<b>包和序列化</b>。不检测就得
 * 每 tick 推一个,20 包/秒/同伴,每包 36 个 {@code ItemStack};而客户端只在发请求那一刻读一次,
 * 她十秒不说话,那 200 个包里 199 个白发。服务器上同伴一多就更明显。
 *
 * <h2>为什么是自己遍历,不挂原版监听器</h2>
 * 原版的 {@code ContainerListener} 确实白送一份算好的 diff,但它要跟着菜单换(广播的只有
 * 当前菜单,开箱子就换了)、要按 {@code Slot.container} 挡掉箱子自己的格子、还要信
 * {@code getContainerSlot()} 的下标口径。一趟 41 格的遍历省掉这一整串前提,代价是每 tick
 * 多读四十来次——在一个 50ms 的 tick 预算里看不见。
 *
 * <h2>只认"什么物品、几个"</h2>
 * 耐久、附魔、其它组件一概不进比较。否则她每挥一镐都算变了。模型读的是"我有什么、几个",
 * 要精确到槽位和附魔时才调 {@code inspect_gui}。
 */
public final class CompanionInventoryWatch {

    /** 最小推送间隔(tick)。她连续捡东西时压成一波,模型下一轮才读,晚半秒无感。 */
    private static final int MIN_INTERVAL_TICKS = 10;
    private static final Item[] EMPTY_ITEMS = new Item[0];
    private static final int[] EMPTY_COUNTS = new int[0];

    private static final Map<UUID, CompanionInventoryWatch> WATCHES = new HashMap<>();

    static {
        // 谁持有谁自己报到:这张表是世界作用域的,不作废会跟着进下一个存档。
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(CompanionInventoryWatch::dropAll);
    }

    /** 每格上次见到的物品与数量。{@link Item} 是注册表单例,比引用就够——不必算哈希,
     *  也就没有"把数量塞进低位、模组把堆叠上限调过 65535 就溢出"这种雷。 */
    private Item[] lastItem = EMPTY_ITEMS;
    private int[] lastCount = EMPTY_COUNTS;
    private int lastSelected = -1;
    private long lastSentTick;
    private boolean everSent;

    private CompanionInventoryWatch() {}

    /** 每服务端 tick、每个同伴调一次(见 {@code CompanionTickDispatcher})。 */
    public static void tick(NumenPlayer companion, long serverTick) {
        WATCHES.computeIfAbsent(companion.getUUID(), ignored -> new CompanionInventoryWatch())
                .tickOne(companion, serverTick);
    }

    /** 同伴离场(休眠/死亡/关服):丢掉它的镜像。 */
    public static void forget(UUID companionUuid) {
        WATCHES.remove(companionUuid);
    }

    /** 关服 / 换存档:世界作用域的进程内状态一律作废。 */
    public static void dropAll() {
        WATCHES.clear();
    }

    private void tickOne(NumenPlayer companion, long serverTick) {
        // 主人不在线就直接走,而且<b>不碰镜像</b>:走一趟把变化吃掉的话,他回来时镜像已经
        // 是最新的,再也检测不出那段时间发生过什么,她的背包就一直是他离线前那份。
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null) {
            return;
        }
        // 节流先行:没到间隔连遍历都省了。只对"已经推过一次"成立——第一次必须放行,
        // 她一登场就该有一份快照。
        if (everSent && serverTick - lastSentTick < MIN_INTERVAL_TICKS) {
            return;
        }
        if (!changedSince(companion.getInventory())) {
            return;
        }
        lastSentTick = serverTick;
        boolean first = !everSent;
        everSent = true;
        NumenInventoryPayload payload = RequestInventoryPayload.snapshot(companion);
        Services.NETWORK.sendToPlayer(owner, payload);
        // 一次推送一行。链路是"服务端推 → 客户端缓存 → 渲染进请求",出问题时得能一眼看出
        // 断在哪一节;只记开始不记结果的日志已经害过我们一次。
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-inv] {} → {} 种物品 / {} 格, 主手 {}, 副手 {}{}",
                companion.getName().getString(), kinds(payload), usedSlots(payload),
                slotName(payload.selectedSlot(), payload.items()), name(payload.offhand()),
                first ? " (首次)" : "");
    }

    /** 一趟遍历:顺便把镜像更新到最新。返回"有没有模型在乎的变化"。 */
    private boolean changedSince(Inventory inv) {
        int size = inv.getContainerSize();
        if (lastItem.length != size) {
            lastItem = new Item[size];   // 全 null,首格全部算变化
            lastCount = new int[size];
        }
        boolean changed = false;
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getItem(i);
            // 空栈的 getItem() 是 AIR、getCount() 是 0,和"这一格没东西"天然一致。
            if (lastItem[i] != stack.getItem() || lastCount[i] != stack.getCount()) {
                lastItem[i] = stack.getItem();
                lastCount[i] = stack.getCount();
                changed = true;   // 不 break:整趟走完,镜像才是完整的
            }
        }
        // 换手不动任何格子的内容,但块里的 holding 会变——所以选中槽自成一处状态。
        if (inv.selected != lastSelected) {
            lastSelected = inv.selected;
            changed = true;
        }
        return changed;
    }


    private static int kinds(NumenInventoryPayload p) {
        return (int) p.items().stream().filter(s -> !s.isEmpty())
                .map(ItemStack::getItem).distinct().count();
    }

    private static int usedSlots(NumenInventoryPayload p) {
        return (int) p.items().stream().filter(s -> !s.isEmpty()).count();
    }

    private static String slotName(int selected, java.util.List<ItemStack> items) {
        return selected < 0 || selected >= items.size() ? "空手" : name(items.get(selected));
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "空手"
                : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).getPath();
    }
}
