package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.plugins.ftbquests.FtbqApi;
import com.dwinovo.numen.plugins.ftbquests.QuestBookProbe;
import com.google.gson.JsonElement;
import dev.ftb.mods.ftblibrary.config.Tristate;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.QuestObjectType;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * FTB Quests 在场时,她在任务书里认出的名字:任务、章节、任务项、奖励各写了明文标题、写了翻译键标题、没写标题三种。
 * 都从入口调({@code ftbquests.quest.submit} 与任务书的样子),任务书用代码搭在专用服务器的那本 {@link ServerQuestFile} 里。
 *
 * <p>只在挂着 FTB Quests 的那一次跑批里跑({@code :plugins:ftbquests:runGameTestServer},见插件的 build.gradle),套件 {@code numen_ftbquests}
 * (见 {@link FtbQuestsGameTestSuite})。那一次是专用服务器:FTB 只在客户端有的方法在这里不存在,联动调到就是 NoSuchMethodError,
 * 一次完成事件就能把服务端 tick 带崩。
 */
public class FtbQuestsGameTests {

    private static final String BATCH = "numen_ftbquests";

    @BeforeBatch(batch = BATCH)
    public static void prepareFtbquestsBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 她交齐一个物品任务:服务端不崩,任务书里这一章只有这一个任务,所以任务与章节各发一条完成事件,
     * FTB 自动发的奖励也报给她,每条里的名字都是书里的名字;交任务的回执里任务项的名字也对。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void an_item_quest_she_hands_in_is_named_in_what_she_is_told(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_ftbq_submitter");
        Fixture book = new Fixture("Mining");
        Quest quest = book.quest("Diamond Hunt");
        book.itemTask(quest, null, new ItemStack(Items.DIAMOND), 2);
        book.itemReward(quest, null, new ItemStack(Items.EMERALD), 3, true);
        her.getInventory().add(new ItemStack(Items.DIAMOND, 2));
        ToolRun submit = lua(her, "ftbquests.quest.submit(\"" + QuestObjectBase.getCodeString(quest) + "\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(submit.succeeded(), "the hand-in failed: " + submit.outcome());
            var handed = dataIn(submit.reply()).getAsJsonArray("tasks").get(0).getAsJsonObject();
            helper.assertTrue("2x Diamond".equals(handed.get("title").getAsString()),
                    "the hand-in does not name the task as the book does: " + submit.reply());
            helper.assertTrue(outboxHas(her, "quest_completed", "the quest &quot;Diamond Hunt&quot;"),
                    "no quest_completed with the quest's title: " + outboxEntries(her));
            helper.assertTrue(outboxHas(her, "quest_completed", "the chapter &quot;Mining&quot;"),
                    "no quest_completed with the chapter's title: " + outboxEntries(her));
            helper.assertTrue(outboxHas(her, "quest_reward_auto", "3x Emerald (quest &quot;Diamond Hunt&quot;)"),
                    "no quest_reward_auto naming the reward and its quest: " + outboxEntries(her));
            book.remove();
            leave(helper, her);
        });
    }

    /**
     * 任务书把一个任务摊开时,任务、章节、任务项、奖励的名字:写了明文标题的是那段文字,写了翻译键的是翻出来的文字,
     * 没写的是 FTB 的默认名(任务取第一个任务项的名字,物品任务项与物品奖励是物品名带数量)。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = BATCH)
    public static void the_book_names_every_object_whether_titled_by_text_by_key_or_not_at_all(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_ftbq_reader");
        Fixture book = new Fixture("Mining");
        Quest plain = book.quest("Diamond Hunt");
        book.itemTask(plain, null, new ItemStack(Items.IRON_INGOT), 2);
        book.itemReward(plain, null, new ItemStack(Items.EMERALD), 3, false);
        Quest keyed = book.quest("{item.minecraft.emerald}");
        book.itemTask(keyed, "{item.minecraft.gold_ingot}", new ItemStack(Items.GOLD_INGOT), 4);
        book.itemReward(keyed, "Shiny", new ItemStack(Items.GOLD_NUGGET), 1, false);
        Quest untitled = book.quest(null);
        book.itemTask(untitled, null, new ItemStack(Items.APPLE), 1);

        succeedWhen(helper, () -> {
            FtbqApi.Quest shown = QuestBookProbe.show(her, QuestObjectBase.getCodeString(plain));
            helper.assertTrue("Diamond Hunt".equals(shown.title()) && "Mining".equals(shown.chapter()),
                    "a quest titled by text is named " + shown.title() + " in chapter " + shown.chapter());
            helper.assertTrue("2x Iron Ingot".equals(shown.tasks().orElseThrow().get(0).title()),
                    "an untitled item task is named " + shown.tasks());
            helper.assertTrue("3x Emerald".equals(shown.rewards().orElseThrow().get(0).title()),
                    "an untitled item reward is named " + shown.rewards());

            shown = QuestBookProbe.show(her, QuestObjectBase.getCodeString(keyed));
            helper.assertTrue("Emerald".equals(shown.title()),
                    "a quest titled by a translation key is named " + shown.title());
            helper.assertTrue("Gold Ingot".equals(shown.tasks().orElseThrow().get(0).title()),
                    "a task titled by a translation key is named " + shown.tasks());
            helper.assertTrue("Shiny".equals(shown.rewards().orElseThrow().get(0).title()),
                    "a reward titled by text is named " + shown.rewards());

            shown = QuestBookProbe.show(her, QuestObjectBase.getCodeString(untitled));
            helper.assertTrue("Apple".equals(shown.title()),
                    "an untitled quest is not named after its first task: " + shown.title());
            helper.assertTrue("Emerald".equals(QuestBookProbe.show(her, "Emerald").title()),
                    "the book cannot find a quest by the title it shows");
            book.remove();
            leave(helper, her);
        });
    }

    /** 交任务时她拿不出东西:回执里点的是任务书里的名字,三种标题都一样。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = BATCH)
    public static void a_hand_in_with_nothing_to_hand_in_names_the_quest_as_the_book_does(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_ftbq_empty_handed");
        Fixture book = new Fixture("Mining");
        Quest plain = book.quest("Diamond Hunt");
        book.itemTask(plain, null, new ItemStack(Items.COAL), 2);
        Quest keyed = book.quest("{item.minecraft.lapis_lazuli}");
        book.itemTask(keyed, null, new ItemStack(Items.LAPIS_LAZULI), 2);
        Quest untitled = book.quest(null);
        book.itemTask(untitled, null, new ItemStack(Items.STICK), 1);
        ToolRun asksPlain = lua(her, "ftbquests.quest.submit(\"" + QuestObjectBase.getCodeString(plain) + "\")");
        ToolRun asksKeyed = lua(her, "ftbquests.quest.submit(\"" + QuestObjectBase.getCodeString(keyed) + "\")");
        ToolRun asksUntitled = lua(her, "ftbquests.quest.submit(\"" + QuestObjectBase.getCodeString(untitled) + "\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(asksPlain.outcome() != null && asksPlain.outcome().contains("\"Diamond Hunt\" ("),
                    "the refusal does not name a quest titled by text: " + asksPlain.outcome());
            helper.assertTrue(asksKeyed.outcome() != null && asksKeyed.outcome().contains("\"Lapis Lazuli\" ("),
                    "the refusal does not name a quest titled by a key: " + asksKeyed.outcome());
            helper.assertTrue(asksUntitled.outcome() != null && asksUntitled.outcome().contains("\"Stick\" ("),
                    "the refusal does not name an untitled quest: " + asksUntitled.outcome());
            book.remove();
            leave(helper, her);
        });
    }

    private static NumenPlayer keeper(GameTestHelper helper, String name) {
        return spawnAt(helper, name, new BlockPos(3, 2, 3), false);
    }

    private static Object outboxEntries(NumenPlayer her) {
        return events(her);
    }

    /** 她的收件箱里有这一种事件,而且话里有 {@code words}(事件文本是 XML 写法,引号是 {@code &quot;})。 */
    private static boolean outboxHas(NumenPlayer her, String type, String words) {
        return events(her).stream().anyMatch(e -> e.type().equals(type) && e.text().contains(words));
    }

    /** 收场:她离开世界。 */
    private static void leave(GameTestHelper helper, NumenPlayer her) {
        CompanionFactory.despawn(helper.getLevel().getServer(), her);
    }

    /**
     * 用例用的一章任务书,代码搭在服务端那本 {@link ServerQuestFile} 里,和 FTB 的编辑器建对象走同一个入口
     * ({@code create} 与 {@code onCreated});标题 null 是不写标题。
     */
    private static final class Fixture {

        private final ServerQuestFile file = ServerQuestFile.INSTANCE;
        private final Chapter chapter;

        Fixture(String title) {
            chapter = (Chapter) create(QuestObjectType.CHAPTER, 0L, new CompoundTag(), title);
        }

        Quest quest(String title) {
            return (Quest) create(QuestObjectType.QUEST, chapter.id, new CompoundTag(), title);
        }

        void itemTask(Quest quest, String title, ItemStack stack, int count) {
            CompoundTag type = new CompoundTag();
            type.putString("type", "ftbquests:item");
            ItemTask task = (ItemTask) create(QuestObjectType.TASK, quest.id, type, title);
            task.setStackAndCount(stack, count);
            // 交上去就扣:这样它是按提交按钮交的任务项,不是只数背包的
            task.setConsumeItems(Tristate.TRUE);
        }

        void itemReward(Quest quest, String title, ItemStack stack, int count, boolean autoClaim) {
            ItemReward reward = new ItemReward(file.newID(), quest, stack, count);
            CompoundTag nbt = new CompoundTag();
            reward.writeData(nbt, file.holderLookup());
            if (autoClaim) {
                nbt.putString("auto", "enabled");
            }
            reward.readData(nbt, file.holderLookup());
            if (title != null) {
                reward.setRawTitle(title);
            }
            reward.onCreated();
            file.clearCachedData();
        }

        private QuestObjectBase create(QuestObjectType type, long parent, CompoundTag extra, String title) {
            QuestObjectBase object = file.create(file.newID(), type, parent, extra);
            object.readData(new CompoundTag(), file.holderLookup());
            if (title != null) {
                object.setRawTitle(title);
            }
            object.onCreated();
            file.clearCachedData();
            return object;
        }

        void remove() {
            file.deleteObjects(java.util.List.of(chapter.id));
        }
    }
}
