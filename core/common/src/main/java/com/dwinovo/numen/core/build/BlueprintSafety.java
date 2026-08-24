package com.dwinovo.numen.core.build;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 图纸的<b>安全白名单</b>:方块实体数据哪些可以照搬、实体哪些算建筑的
 * 一部分、摆设身上的物品按什么口径计价与净化。图纸是文件,可以任意编辑
 * 、可以从网上下载——这一层挡的是"一张塞满钻石的图纸建出来就是白送"
 * "一块写着点我领奖的牌子是个可执行口子"这类事。全部用白名单不用黑
 * 名单:黑名单是开放集合,每来一个新方块/新组件就得被咬一次;白名单
 * 一次定完,此后新东西自动落在安全的一侧。
 */
public final class BlueprintSafety {

    private BlueprintSafety() {}

    /**
     * 图纸带来的方块实体数据,哪些可以照搬——<b>白名单</b>。
     *
     * <p>只有<b>装饰性</b>的才搬:告示牌的字、旗帜的花纹、陶罐的纹样。容器里的东西
     * 一律不搬,而这不是保守,是必须:图纸是文件,可以任意编辑、可以从网上下载。
     * 照搬容器内容意味着一张塞满钻石的图纸建出来就是白送——那不是"还原了作者的
     * 设计",那是凭空造物品。同理刷怪笼的刷怪数据、战利品表、命令方块的命令,
     * 都不是"这栋房子长什么样"的一部分。
     *
     * <p>用白名单而不是黑名单,和对账那张"作者属性"名单同一个道理:黑名单是开放
     * 集合,每来一个新方块就可能带一个新的危险字段,只能被咬一次补一条;白名单
     * 是封闭集合,一次定完,此后任何新方块都自动落在安全的一侧。
     *
     * @return 可以照搬的那部分;没有可搬的返回 null
     */
    public static net.minecraft.nbt.CompoundTag safeBlockEntityData(
            BlockState state, net.minecraft.nbt.CompoundTag data) {
        if (state == null || data == null || data.isEmpty()) {
            return null;
        }
        // 判据是<b>数据包标签</b>,不是代码里的一串 if。标签本身就是那句授权:
        // 在里面 = 这种方块的数据可以随图纸走。默认只有牌子和旗帜。
        //
        // 用标签而不是写死,是为了让整合包能声明自己那些装饰性方块实体也安全——写死的
        // 话他们只能来改我们的代码。代价是这条授权真的有效力:往标签里加一个容器,图纸
        // 就能印出里面的东西。那是数据包作者的决定,得是知情的决定。
        //
        // 陶罐没进这个标签。纹样碎片是刷沙刷砾石考古刷出来的稀有掉落,一只四片碎片的
        // 罐子收一件普通陶罐的料就是白送四件稀有物;而按组件精确收又要求玩家先有一只
        // 一模一样的罐子——那还不如让他自己拼。摆一只素罐,纹样留给人。
        if (!state.is(com.dwinovo.numen.core.init.InitTag.SAFE_BLOCK_ENTITY_DATA)) {
            return null;
        }
        // 硬底线用<b>原版自己的判据</b>:{@code BlockEntity#onlyOpCanSetNbt()}。命令方块、
        // 结构方块、拼图方块都在这一档——原版正是拿它决定"这份 NBT 能不能由非管理员设置",
        // 而我们的处境一模一样:图纸是文件,谁都能编辑。
        //
        // 此前这里是我列的一张十一个键的黑名单(Items/LootTable/Command……)。黑名单是
        // 开放集合,每来一个新方块实体就得被咬一次;而这个问题原版早就回答过了。
        net.minecraft.nbt.CompoundTag out = data.copy();
        // 坐标由落位方按落位点重写,存的那份是导出世界的
        for (String positional : new String[]{"x", "y", "z"}) {
            out.remove(positional);
        }
        // 牌子这一支要在底线<b>之前</b>:牌子自己就是"只有管理员能设 NBT"的那一档
        // (原版怕的正是有人往牌子上挂命令),所以底线会把它一并毙掉。牌子的字是我们
        // 唯一真想搬的东西,所以单独过一道自己的检查后放行。
        //
        // 检查的是<b>点击事件</b>:牌子的文本能挂 clickEvent,而 clickEvent 能跑命令。
        // 图纸里一块写着"点我领奖"的牌子就是一个可执行的口子。带事件的整份丢掉——不是
        // 只丢那一行,因为我们无从判断哪一行是作者的本意。带物品的牌子(某些模组的)同理。
        if (state.is(net.minecraft.tags.BlockTags.ALL_SIGNS)) {
            if (out.contains("front_item") || out.contains("back_item")) {
                return null;
            }
            for (String side : new String[]{"front_text", "back_text"}) {
                net.minecraft.nbt.CompoundTag text = out.getCompound(side);
                if (!text.contains("messages", net.minecraft.nbt.Tag.TAG_LIST)) {
                    continue;
                }
                for (net.minecraft.nbt.Tag line
                        : text.getList("messages", net.minecraft.nbt.Tag.TAG_STRING)) {
                    if (hasClickEvent(line.getAsString())) {
                        return null;
                    }
                }
            }
            return out.isEmpty() || (out.size() == 1 && out.contains("id")) ? null : out;
        }
        if (opOnlyNbt(state)) {
            return null;
        }
        return out.isEmpty() || (out.size() == 1 && out.contains("id")) ? null : out;
    }

    /** 这种方块实体的 NBT 只有管理员能设置吗——命令方块、结构方块、拼图方块那一档。 */
    private static boolean opOnlyNbt(BlockState state) {
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock holder)) {
            return false;
        }
        try {
            // 1.21.2+ 把 op 门挪到了 BlockEntityType 上,经实例的 getType() 问
            var be = holder.newBlockEntity(net.minecraft.core.BlockPos.ZERO, state);
            return be != null && be.getType().onlyOpCanSetNbt();
        } catch (RuntimeException e) {
            return true;   // 造不出来就当它不安全
        }
    }

    /** 这段文本组件里有点击事件吗(递归看子组件)——有就是个能跑命令的口子。 */
    private static boolean hasClickEvent(String json) {
        try {
            var component = net.minecraft.network.chat.Component.Serializer.fromJson(
                    json.isEmpty() ? "\"\"" : json, net.minecraft.core.RegistryAccess.EMPTY);
            return component != null && hasClickEvent(component);
        } catch (RuntimeException e) {
            return true;   // 读不懂的文本按有事件处理
        }
    }

    private static boolean hasClickEvent(net.minecraft.network.chat.Component component) {
        if (component.getStyle() != null && component.getStyle().getClickEvent() != null) {
            return true;
        }
        for (var sibling : component.getSiblings()) {
            if (hasClickEvent(sibling)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 图纸里的实体,哪些算建筑的一部分——同样是白名单。
     *
     * <p>只收<b>摆设</b>:展示框、盔甲架、画。它们钉在墙上、立在院里,拆了这栋房子
     * 就不完整。活物不收——图纸里存着的牛马村民不是设计,照搬等于凭空造生物;矿车、
     * 船同理,那是玩家自己的东西。
     *
     * <p>身上带的东西<b>照搬,但要照原样付钱</b>:展示框里那把剑、盔甲架身上那套甲,
     * 按<b>组件完全一致</b>的口径计价(见 {@link #payloadStacks})。这条口径是关键——
     * 若只按物品类型收料,玩家交一把白剑就能换来文件里那把"锋利 255 的剑",漏就漏在
     * 这里,而不在于要不要收料。组件全等之后账是平的:交什么得什么。
     *
     * <p>而且<b>收什么就放什么</b>:计价用的那一叠和最终放进框里的必须是同一叠。只从
     * 计价那边剥掉一部分、落位照原样放,等于自己开一个口子。
     *
     * @return 可以生成的那部分;不收返回 null
     */
    public static net.minecraft.nbt.CompoundTag safeEntityData(
            net.minecraft.nbt.CompoundTag data,
            net.minecraft.core.HolderLookup.Provider registries) {
        if (data == null || !data.contains("id")) {
            return null;
        }
        String id = data.getString("id");
        if (!id.equals("minecraft:item_frame") && !id.equals("minecraft:glow_item_frame")
                && !id.equals("minecraft:armor_stand") && !id.equals("minecraft:painting")) {
            return null;
        }
        net.minecraft.nbt.CompoundTag out = data.copy();
        // 身上带的东西:组件按白名单剥一遍,<b>就地改掉这份 NBT</b>。
        //
        // 落在数据本身上而不是只落在计价上——计价与落位读同一份,账才是平的。别处是在
        // 图纸加载时把展示框里的物品和盔甲架每个装备槽原地换成剥过的版本,同一个做法,
        // 只是我们的落点在这份 NBT 上而不是在实体对象上。
        sanitizePayload(out, registries);
        // 挂件(展示框、画)的<b>锚点</b>是一个绝对方块坐标(TileX/TileY/TileZ),存在
        // 自己的 NBT 里,而不是由位置推出来的。不改它的话:实体读档时把锚点设成导出
        // 世界那个坐标,挂件每 100 刻自查一次"我挂的那面墙还在吗"——查的是源世界的
        // 坐标。那儿是空的就五秒后掉落(玩家付了一个展示框,墙上什么也没有,地上多个
        // 掉落物);那儿恰好有方块就留着,但下次区块重载会把它<b>瞬移回源世界坐标</b>。
        //
        // <p>{@code Pos} 一并删掉,由落位方连锚点一起写:读档时锚点要过一道
        // <b>16 格闸门</b>(锚点离 Pos 太远就判成坏档,丢掉锚点),两个值必须同源。
        // 位置留在这儿只会是导出世界的坐标,而锚点是落位坐标,差的正是搬迁距离。
        for (String anchor : new String[]{"Pos", "TileX", "TileY", "TileZ"}) {
            out.remove(anchor);
        }
        return out;
    }

    /** 摆设身上可能带东西的那几个键。展示框一格,盔甲架四甲两手。 */
    private static final List<String> PAYLOAD_KEYS =
            List.of("Item", "ArmorItems", "HandItems", "equipment");

    /**
     * 这只摆设身上带的东西,要按哪几叠物品计价——每一叠都<b>组件全等</b>地收。
     *
     * <p>不剥、不归一、不打折:收进来的这一叠就是最终放进框里的那一叠。想省事的做法是
     * "只按物品类型收料",那就等于把文件里的附魔白送出去;另一种想省事的做法是"计价
     * 时剥掉一部分组件、落位照原样放",那是自己开的口子。两条都不走。
     *
     * <p>代价说清:玩家得<b>正好有</b>那件东西才装得上。文件里是一把锋利五的剑,他手里
     * 那把白剑不算。装不上就空着框、如实报一笔——不是静默少一件。
     *
     * @return 要收的那些叠(空的槽位不计);没有返回空表
     */
    public static List<net.minecraft.world.item.ItemStack> payloadStacks(
            net.minecraft.nbt.CompoundTag data,
            net.minecraft.core.HolderLookup.Provider registries) {
        if (data == null) {
            return List.of();
        }
        List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
        for (String key : PAYLOAD_KEYS) {
            net.minecraft.nbt.Tag tag = data.get(key);
            if (tag instanceof net.minecraft.nbt.CompoundTag one) {
                addStack(out, one, registries);
            } else if (tag instanceof net.minecraft.nbt.ListTag many) {
                for (net.minecraft.nbt.Tag slot : many) {
                    if (slot instanceof net.minecraft.nbt.CompoundTag c) {
                        addStack(out, c, registries);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 组件白名单:一件物品身上<b>只有这四样</b>可以随图纸走——附魔、药水成分、耐久、
     * 自定义名。其余一概剥掉。
     *
     * <p>为什么是白名单:组件是开放集合(容器内容、捆绑包内容、方块实体数据、上膛的弹药、
     * 自定义数据……还有模组自己加的)。列"哪些危险"每来一个新组件就漏一次;列"哪些安全"
     * 一次定完,此后任何新组件自动落在安全的一侧。这四样的共性是<b>它们不装东西</b>。
     */
    public static boolean unsafeItemComponent(
            net.minecraft.core.component.DataComponentType<?> type) {
        return !(type.equals(net.minecraft.core.component.DataComponents.ENCHANTMENTS)
                || type.equals(net.minecraft.core.component.DataComponents.POTION_CONTENTS)
                || type.equals(net.minecraft.core.component.DataComponents.DAMAGE)
                || type.equals(net.minecraft.core.component.DataComponents.CUSTOM_NAME));
    }

    /** 剥掉不安全的组件,只留白名单那四样。 */
    public static net.minecraft.world.item.ItemStack withUnsafeComponentsDiscarded(
            net.minecraft.world.item.ItemStack stack) {
        if (stack.getComponentsPatch().isEmpty()) {
            return stack;
        }
        net.minecraft.world.item.ItemStack copy = stack.copy();
        stack.getComponents().stream()
                .filter(c -> unsafeItemComponent(c.type()))
                .map(net.minecraft.core.component.TypedDataComponent::type)
                .forEach(copy::remove);
        return copy;
    }

    private static void addStack(List<net.minecraft.world.item.ItemStack> out,
                                 net.minecraft.nbt.CompoundTag tag,
                                 net.minecraft.core.HolderLookup.Provider registries) {
        if (tag.isEmpty()) {
            return;   // 空槽位
        }
        net.minecraft.world.item.ItemStack.parse(registries, tag)
                .map(BlueprintSafety::withUnsafeComponentsDiscarded)
                .filter(s -> !s.isEmpty())
                .ifPresent(out::add);
    }

    /** 把载荷里每一叠的组件按白名单剥一遍,就地写回——计价和落位读的因此是同一份。 */
    private static void sanitizePayload(net.minecraft.nbt.CompoundTag data,
                                        net.minecraft.core.HolderLookup.Provider registries) {
        for (String key : PAYLOAD_KEYS) {
            net.minecraft.nbt.Tag tag = data.get(key);
            if (tag instanceof net.minecraft.nbt.CompoundTag one) {
                net.minecraft.nbt.Tag fixed = sanitizeItemTag(one, registries);
                if (fixed == null) {
                    data.remove(key);
                } else {
                    data.put(key, fixed);
                }
            } else if (tag instanceof net.minecraft.nbt.ListTag many) {
                // 槽位顺序有意义(四甲两手),空槽位留成空复合标签占位
                net.minecraft.nbt.ListTag rebuilt = new net.minecraft.nbt.ListTag();
                for (net.minecraft.nbt.Tag slot : many) {
                    net.minecraft.nbt.Tag fixed = slot instanceof net.minecraft.nbt.CompoundTag c
                            ? sanitizeItemTag(c, registries) : null;
                    rebuilt.add(fixed == null ? new net.minecraft.nbt.CompoundTag() : fixed);
                }
                data.put(key, rebuilt);
            }
        }
    }

    private static net.minecraft.nbt.Tag sanitizeItemTag(
            net.minecraft.nbt.CompoundTag tag,
            net.minecraft.core.HolderLookup.Provider registries) {
        if (tag.isEmpty()) {
            return null;
        }
        var cleaned = net.minecraft.world.item.ItemStack.parse(registries, tag)
                .map(BlueprintSafety::withUnsafeComponentsDiscarded)
                .filter(s -> !s.isEmpty());
        return cleaned.isEmpty() ? null : cleaned.get().save(registries);
    }

    /** 把摆设身上带的东西整个拿掉——付不起的时候用,框空着比框里凭空多件东西好。 */
    public static void stripPayload(net.minecraft.nbt.CompoundTag data) {
        if (data == null) {
            return;
        }
        for (String key : PAYLOAD_KEYS) {
            data.remove(key);
        }
    }

}
