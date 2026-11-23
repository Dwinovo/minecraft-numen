package com.dwinovo.numen.rule;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.permission.ActView;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.permission.RuleWord;
import com.dwinovo.numen.permission.Verb;

import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

import java.util.EnumSet;
import java.util.Set;

/**
 * 原版里规则能写的词,一个只认一类东西:{@code contents}(里面装着东西)、{@code villager}(村民)、{@code hostile}(敌对生物)、
 * {@code hazard_item}(放下去会烧、会炸、会淹的物品)。经插件的门登记({@link #install}),和模组的词同一个写法;多认一类就是在这里
 * 多写一个、列进 {@link #install}。出厂规则与主人的规则写的就是这几个词。
 *
 * <p>只陈述事实,不裁决。线程照 {@link RuleWord.Test} 的约定:裁决在哪条线程就在哪条线程上被问;只有 {@code contents} 要读活世界,
 * 读不到时怎么答写在它那里。
 */
public final class VanillaWords {

    private VanillaWords() {}

    /** 放下去会烧、会炸、会淹的东西。 */
    private static final Set<Item> HAZARD_ITEMS = Set.of(
            Items.LAVA_BUCKET, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE, Items.TNT, Items.WATER_BUCKET);

    public static void install(NumenApi numen) {
        // 拆了东西洒一地会消失,撤不回
        numen.answer(Questions.RULE_WORD, "contents", new RuleWord("has contents",
                Component.translatable("numen.rule_word.contents"), EnumSet.allOf(Verb.class), VanillaWords::contents));
        numen.answer(Questions.RULE_WORD, "villager", new RuleWord("is a villager",
                Component.translatable("numen.rule_word.villager"), Set.of(),
                view -> view.action().entity() instanceof AbstractVillager));
        numen.answer(Questions.RULE_WORD, "hostile", new RuleWord("is hostile",
                Component.translatable("numen.rule_word.hostile"), Set.of(),
                view -> view.action().entity() instanceof Enemy));
        numen.answer(Questions.RULE_WORD, "hazard_item", new RuleWord("is a hazard",
                Component.translatable("numen.rule_word.hazard_item"), Set.of(),
                view -> view.action().item() != null && HAZARD_ITEMS.contains(view.action().item())));
    }

    /**
     * 这一格里面装没装着东西。只有主线程读得到方块实体;搜索线程按"有"回答——规划比执行保守,一条要等主人点头的路不会被规划成免费的。
     */
    private static boolean contents(ActView view) {
        Action a = view.action();
        return a.pos() != null && a.state() != null && a.state().hasBlockEntity()
                && (view.facts().live() == null || holds(view.facts().live().getBlockEntity(a.pos())));
    }

    /** 这个方块实体里有能拿走的东西:容器不空;讲台上放着书(讲台不是容器,书靠它界面上的钮取下)。 */
    private static boolean holds(BlockEntity be) {
        return be instanceof Container container ? !container.isEmpty()
                : be instanceof LecternBlockEntity lectern && lectern.hasBook();
    }
}
