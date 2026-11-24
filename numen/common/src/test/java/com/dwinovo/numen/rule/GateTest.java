package com.dwinovo.numen.rule;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.permission.Actor;
import com.dwinovo.numen.api.permission.Answer;
import com.dwinovo.numen.api.permission.Answerer;
import com.dwinovo.numen.api.permission.ConsentItem;
import com.dwinovo.numen.api.permission.Gate;
import com.dwinovo.numen.api.permission.Owners;
import com.dwinovo.numen.api.permission.Verdict;
import com.dwinovo.numen.permission.Edit;
import com.dwinovo.numen.permission.Merge;
import com.dwinovo.numen.permission.Preset;
import com.dwinovo.numen.permission.Settings;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.RootCommandNode;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游戏适配层的裁决:一件事问一圈回答者,照固定顺序合出结果,没人说话看主人给这种动作定的档;内置的"归属"——别人的不许,
 * 主人的要问,自己的与没主人的不表态;名单里记什么名字由动作定;任务期授权只覆盖问。合并顺序本身在纯逻辑核心的单测里
 * ({@code MergeTest}),这里钉的是它接上游戏之后的样子。需要 MC 注册表。
 *
 * <p>方块是谁的由测试直接给:一张"格子 → 那个人"的表,和方块归属的回答者交回的查表是同一种东西。没写进表的格没人说,就是没主人的。
 */
@Tag("mc")
class GateTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID HER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID SISTER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID STRANGER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    /** 要动手的是她:名字 Aria,主人 Steve,主人名下还有一只 Bea。 */
    private static final Actor HER = new Actor(HER_ID, "Aria", OWNER_ID, Set.of(SISTER_ID));

    private static final BlockPos POS = new BlockPos(3, 64, 3);

    @BeforeAll
    static void boot() {
        NumenApiFixture.install();
    }

    /** Map 后备的方块视图:没摆的格是空气,读不到方块实体(搜索线程那一种)。 */
    private static final class FakeWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        FakeWorld set(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
            return this;
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    /** 一份归属:{@code cells} 里写着哪一格是谁的,此后往里加的也读得到。 */
    private static Owners.Blocks owned(Map<BlockPos, UUID> cells) {
        return Owners.Blocks.of(List.of(cells::get));
    }

    /** 只有内置的归属回答者。 */
    private static Gate gate(Settings settings, Map<BlockPos, UUID> cells) {
        return new Gate(HER, settings, owned(cells), List.of());
    }

    /** 内置的归属回答者之外,再加几个别的回答者。 */
    private static Gate gateWith(Settings settings, Map<String, Answerer> extra) {
        Map<String, Answerer> all = new LinkedHashMap<>(com.dwinovo.numen.api.Questions.PERMIT.answerers());
        all.putAll(extra);
        return new Gate(HER, settings, Owners.Blocks.NONE, List.of(), all);
    }

    private static final Answerer SAYS_NO = (action, facts) -> Answer.deny("a claim", Component.literal("a claim"));
    private static final Answerer SAYS_ASK = (action, facts) -> Answer.ask("a hunch", Component.literal("a hunch"));
    private static final Answerer SAYS_YES = (action, facts) -> Answer.ALLOW;

    private static Action dig(FakeWorld world, BlockPos pos) {
        return Action.breakBlock(pos, world.getBlockState(pos));
    }

    // ==================== 归属 ====================

    @Test
    void ownershipAsksForTheOwnersAndLeavesTheRestToTheTier() {
        FakeWorld world = new FakeWorld().set(POS, Blocks.STONE.defaultBlockState());
        Map<BlockPos, UUID> cells = new HashMap<>();
        Gate gate = gate(Settings.FACTORY, cells);

        assertTrue(gate.judge(dig(world, POS), world).allowed(), "没人说是谁的,break 的档是许");

        cells.put(POS, STRANGER_ID);
        assertTrue(gate.judge(dig(world, POS), world).allowed(), "别人的,归属不表态,看档");

        cells.put(POS, OWNER_ID);
        Verdict mine = gate.judge(dig(world, POS), world);
        assertEquals(Tier.ASK, mine.tier());
        assertEquals("belongs to the owner", mine.cause());
        assertEquals("belongs to the owner: needs the owner's consent", mine.reason());

        cells.put(POS, SISTER_ID);
        assertEquals(Tier.ASK, gate.judge(dig(world, POS), world).tier(), "主人名下别的同伴的算主人这一边");

        cells.put(POS, HER_ID);
        assertTrue(gate.judge(dig(world, POS), world).allowed(), "她自己的不表态");
    }

    @Test
    void ownershipLooksAtTheBlockPlacedIntoToo() {
        FakeWorld world = new FakeWorld();
        Gate gate = gate(Settings.FACTORY, Map.of(POS, OWNER_ID));
        Verdict verdict = gate.judge(Action.place(POS, world.getBlockState(POS), Items.DIRT), world);
        assertEquals(Tier.ASK, verdict.tier());
    }

    // ==================== 合并 ====================

    @Test
    void whoeverSaysNoWinsEvenOverTheOwnersList() {
        FakeWorld world = new FakeWorld().set(POS, Blocks.STONE.defaultBlockState());
        Settings listed = Settings.FACTORY.withName(Verb.BREAK, "minecraft:stone");
        assertEquals(Tier.DENY, gateWith(listed, Map.of("claims", SAYS_NO, "hunch", SAYS_YES))
                .judge(dig(world, POS), world).tier());
    }

    @Test
    void theOwnersListBeatsAnAskAndAnAskBeatsAnAllow() {
        FakeWorld world = new FakeWorld().set(POS, Blocks.STONE.defaultBlockState());
        Settings listed = Settings.FACTORY.withName(Verb.BREAK, "minecraft:stone");
        Verdict byList = gateWith(listed, Map.of("hunch", SAYS_ASK)).judge(dig(world, POS), world);
        assertTrue(byList.allowed());
        assertEquals(Merge.Step.LISTED, byList.step());

        Verdict asked = gateWith(Settings.FACTORY, Map.of("hunch", SAYS_ASK, "yes", SAYS_YES)).judge(dig(world, POS), world);
        assertEquals(Tier.ASK, asked.tier());
        assertEquals("a hunch", asked.cause());
    }

    @Test
    void anAllowBeatsAnAskTierButNotADenyTierAndWithNobodyTalkingTheTierDecides() {
        FakeWorld world = new FakeWorld().set(POS, Blocks.STONE.defaultBlockState());
        assertTrue(gateWith(Settings.FACTORY, Map.of("yes", SAYS_YES)).judge(Action.drop(Items.DIAMOND), world).allowed(),
                "档位是问时,回答者的许算数");

        Settings observe = Settings.FACTORY.with(Preset.OBSERVE);
        Verdict denied = gateWith(observe, Map.of("yes", SAYS_YES)).judge(dig(world, POS), world);
        assertEquals(Tier.DENY, denied.tier(), "主人定的不许,回答者的许盖不过");
        assertEquals(Merge.Step.TIER, denied.step());
        assertEquals("the owner's setting for break is deny", denied.cause());

        Verdict dropAsks = gateWith(Settings.FACTORY, Map.<String, Answerer>of())
                .judge(Action.drop(Items.DIAMOND), world);
        assertEquals(Tier.ASK, dropAsks.tier(), "扔东西出厂是问");
        Settings dropAllowed = new Edit.SetTier(Verb.DROP, Tier.ALLOW).applyTo(Settings.FACTORY);
        assertTrue(gateWith(dropAllowed, Map.<String, Answerer>of()).judge(Action.drop(Items.DIAMOND), world).allowed());
    }

    @Test
    void anAnswererThatThrowsIsLeftOutAndTheRestAreStillAsked() {
        FakeWorld world = new FakeWorld().set(POS, Blocks.STONE.defaultBlockState());
        Answerer broken = (action, facts) -> {
            throw new IllegalStateException("boom");
        };
        Verdict verdict = gateWith(Settings.FACTORY, Map.of("broken", broken, "hunch", SAYS_ASK)).judge(dig(world, POS), world);
        assertEquals(Tier.ASK, verdict.tier());
    }

    // ==================== 名单里记什么 ====================

    @Test
    void theNameOnTheListIsSetByTheVerb() {
        FakeWorld world = new FakeWorld().set(POS, Blocks.OAK_LOG.defaultBlockState());
        assertEquals("minecraft:oak_log", Action.breakBlock(POS, world.getBlockState(POS)).key());
        assertEquals("minecraft:oak_log", Action.useBlock(POS, world.getBlockState(POS)).key());
        assertEquals("minecraft:oak_planks", Action.place(POS, Blocks.AIR.defaultBlockState(), Items.OAK_PLANKS).key(),
                "放记的是放的那样东西");
        assertEquals("minecraft:diamond", Action.drop(Items.DIAMOND).key());
        assertEquals("minecraft:ender_pearl", Action.useItem(Items.ENDER_PEARL).key());
        assertEquals("", Action.place(POS, Blocks.AIR.defaultBlockState(), null).key(), "规划期还不知道耗材");
    }

    @Test
    void aCommandIsRememberedByItsRootAndAnyAliasMatches() {
        RootCommandNode<Object> tree = new RootCommandNode<>();
        var teleport = LiteralArgumentBuilder.<Object>literal("teleport").build();
        tree.addChild(teleport);
        tree.addChild(LiteralArgumentBuilder.<Object>literal("tp").redirect(teleport).build());

        Action typed = Action.command("tp @s 0 64 0", tree);

        assertEquals("tp", typed.key());
        assertEquals(Set.of("tp", "teleport"), typed.names());
        Settings listed = Settings.FACTORY.withName(Verb.COMMAND, "teleport");
        FakeWorld world = new FakeWorld();
        assertTrue(gateWith(listed, Map.<String, Answerer>of()).judge(typed, world).allowed(), "名单里写的是全名,换个别名绕不过去");
        assertEquals(Tier.ASK, gateWith(Settings.FACTORY, Map.<String, Answerer>of()).judge(typed, world).tier());
    }

    // ==================== 征询与授权 ====================

    @Test
    void theConsentItemRemembersTheNameTheVerbWouldList() {
        FakeWorld world = new FakeWorld().set(POS, Blocks.STONE.defaultBlockState());
        Gate gate = gate(Settings.FACTORY, Map.of(POS, OWNER_ID));
        Action action = dig(world, POS);
        ConsentItem item = gate.consentItem(action, gate.judge(action, world));

        assertEquals(new Settings.Entry(Verb.BREAK, "minecraft:stone"), item.remembering());
        assertEquals("break 1 stone (3,64,3): belongs to the owner", ConsentItem.listingText(List.of(item)));
        assertEquals(List.of("break minecraft:stone"), ConsentItem.rememberedRows(List.of(item)));
    }

    @Test
    void aGrantCoversTheSameVerbAndNameButNeverAnAnswerersNo() {
        FakeWorld world = new FakeWorld().set(POS, Blocks.STONE.defaultBlockState())
                .set(POS.east(), Blocks.STONE.defaultBlockState()).set(POS.west(), Blocks.DIRT.defaultBlockState());
        Map<BlockPos, UUID> cells = new HashMap<>(Map.of(POS, OWNER_ID, POS.east(), OWNER_ID, POS.west(), OWNER_ID));
        Gate asking = gate(Settings.FACTORY, cells);
        ConsentItem granted = asking.consentItem(dig(world, POS), asking.judge(dig(world, POS), world));
        Gate withGrant = new Gate(HER, Settings.FACTORY, owned(cells), List.of(granted));

        assertTrue(withGrant.judge(dig(world, POS.east()), world).allowed(), "同一个动作、同一个名字不再问");
        assertEquals(Tier.ASK, withGrant.judge(dig(world, POS.west()), world).tier(), "换一种方块另问");

        Map<String, Answerer> withNo = new LinkedHashMap<>(com.dwinovo.numen.api.Questions.PERMIT.answerers());
        withNo.put("no", SAYS_NO);
        Gate refused = new Gate(HER, Settings.FACTORY, owned(cells), List.of(granted), withNo);
        assertEquals(Tier.DENY, refused.judge(dig(world, POS.east()), world).tier(), "授权解不开不许");
    }

    @Test
    void theVerdictWithoutAnAnswerDoesNotReadTheLiveWorld() {
        FakeWorld world = new FakeWorld().set(POS, Blocks.STONE.defaultBlockState());
        Answerer sawLive = (action, facts) -> facts.live() == null ? Answer.ABSTAIN
                : Answer.deny("read the live world", Component.empty());
        assertTrue(gateWith(Settings.FACTORY, Map.of("probe", sawLive)).judge(dig(world, POS), world).allowed(),
                "judge 是搜索线程也能调的那一种:回答者看到的 live 是 null");
    }
}
