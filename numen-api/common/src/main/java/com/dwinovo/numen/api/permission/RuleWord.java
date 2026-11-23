package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.permission.Verb;

import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.Set;

/**
 * 规则里能写的一个词,由认得那类东西的一方登记:{@code numen.answer(Questions.RULE_WORD, "hostile", 词)},名字就是规则文本里写的
 * 那个词({@code allow attack(hostile)})。Numen API 自带的词({@link Groups})不认识任何具体的东西;说得出"这是哪一类东西"的词
 * (村民、敌对生物、容器里有没有东西、你的模组的机器)都从这里登记,原版的那几个由 Numen 登记。
 *
 * <pre>{@code
 * numen.answer(Questions.RULE_WORD, "villager", new RuleWord("is a villager",
 *         Component.translatable("mymod.rule_word.villager"), Set.of(),
 *         view -> view.action().entity() instanceof AbstractVillager));
 * }</pre>
 *
 * <p>登记的词不带括号参数。名字照回答者名字的规矩(小写字母开头,小写字母、数字、下划线);和别的登记者重名、用了自带的名字,
 * 登记时就是错。登记撤回之后,已经解析好的规则里这个词在任何对象上都不成立,再解析写着它的规则是解析错误。
 *
 * @param description    命中时的英文自述,给模型与日志:回执里拒绝与要问的理由({@code has contents}),不带主语
 * @param shown          同一句自述给主人看的那一版,征询卡上显示:用你的语言文件里的翻译键,不写死文字
 * @param irreversibleOn 在这些动词下这个词成立就意味着撤不回(拆装着东西的容器):征询卡把那一项标出来,"允许并记住"
 *                       记下的规则也盖不到撤不回的情形;没有就是空集
 * @param test           这个词在一个动作的对象上成不成立
 */
public record RuleWord(String description, Component shown, Set<Verb> irreversibleOn, Test test) {

    /**
     * 这个词在一个动作的对象上成不成立。只陈述事实,不裁决:许不许由写着这个词的那一行规则与判定顺序定。
     *
     * <p>线程:哪条线程在裁决就在哪条线程上被叫,两种都有。真要动手前的那一问在服务端主线程,{@code view.facts().live()} 是活世界,
     * 可以读方块实体;寻路给每一格定价时在搜索线程,{@code view.facts().live()} 是 null,只许读 {@code view.action()} 与
     * {@code view.facts().view()}(冻结的方块快照,任何线程可读),碰不得活世界与实体——要活读才答得出的词这时按保守的一头回答
     * (规划比执行保守:宁可把一条路估成要问,不把它估成免费),并在登记处写明。每定价一格就可能被问一次,别在这里做慢事;
     * 不改世界,不留状态。
     */
    @FunctionalInterface
    public interface Test {

        /**
         * @param view 这个动作与世界此刻的样子:{@link ActView#action} 说做什么、对哪一格哪只实体哪件物品,{@link ActView#facts}
         *             是读世界的口
         */
        boolean holds(ActView view);
    }

    public RuleWord {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(shown, "shown");
        Objects.requireNonNull(test, "test");
        irreversibleOn = Set.copyOf(irreversibleOn);
    }
}
