package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.RuleSet;

import java.util.List;

/**
 * 随模组发的规则:硬规则,和出厂层。规则原文住在这里,主人看得见;出厂层主人改得了(写自己那一层,压过它),硬规则只有服主能关
 * ({@link PermissionConfig#hardRules})。
 *
 * <h2>硬规则</h2>
 * 主人答应不了的事:别人的东西。命中即拒,模式、主人层、任务期授权都解不开。
 *
 * <h2>出厂层</h2>
 * 读法:没人的随便挖、随便打;她自己的随便拆(装着东西的除外);主人的、说不清的、村子里的要问;别人的碰不得(硬规则)。
 * <ul>
 *   <li>allow 行写得比 ask 行窄,它们才轮得到 ask 表(同层 allow 先于 ask):带方块实体的(地牢的刷怪笼与战利品箱、蜂巢、
 *       可疑的沙子)不进任何拆方块的 allow 行,落到 {@code break(block_entity)} 去问;</li>
 *   <li>床、门、活板门、栅栏门不再单列:主人放的已经是 {@code owner},村子里的已经是 {@code structure(#minecraft:village)},
 *       自然生成的门只在结构里;</li>
 *   <li>从主人的容器拿东西放行:她的设计就是用主人的工作台熔炉箱子;主人想管就加 {@code ask take(owner)}。废弃结构里的战利品箱随便拿,
 *       村民的箱子要问;</li>
 *   <li>右键方块全放行:开门、开容器、按按钮不改世界的归属;拿东西另由 {@code take} 管;</li>
 *   <li>执行只读或只说话的指令:帮助、在线名单、动作消息、私信、队伍消息、种子、随机数。别名随根名认
 *       ({@code tell}、{@code w} 归 {@code msg}),见 {@link Action#command}。其余指令没有一行说到,照旧问。</li>
 * </ul>
 */
public final class FactoryRules {

    private FactoryRules() {}

    /** 硬规则原文:都是拒绝。 */
    public static final List<String> HARD = List.of(
            "break(other)",
            "place(other)",
            "take(other)",
            "attack(other)",
            "use_entity(other)",
            "place(hazard_item & near(other))");

    /** 出厂 allow 表原文。 */
    public static final List<String> ALLOW = List.of(
            "break(nature & !block_entity)",
            "break(self & !contents)",
            "break(structure & !structure(#minecraft:village) & !block_entity)",
            "place(!hazard_item)",
            "place(hazard_item & !near(owner) & !near(other))",
            "attack(hostile)",
            "attack(nature & !named & !villager)",
            "use_block(*)",
            "use_entity(nature)",
            "use_entity(self)",
            "take(owner)",
            "take(self)",
            "take(structure & !structure(#minecraft:village))",
            "command(help)",
            "command(list)",
            "command(me)",
            "command(msg)",
            "command(teammsg)",
            "command(seed)",
            "command(random)");

    /** 出厂 ask 表原文。同一个动作命中几行时第一行作数。 */
    public static final List<String> ASK = List.of(
            "break(owner)",
            "break(unknown)",
            "break(structure(#minecraft:village))",
            "break(block_entity)",
            "break(self & contents)",
            "attack(owner)",
            "attack(self)",
            "attack(named)",
            "attack(villager)",
            "use_entity(owner)",
            "drop(*)",
            "place(hazard_item & near(owner))");

    private static final List<Rule> HARD_RULES = HARD.stream().map(Groups::parse).toList();

    private static final RuleSet LAYER = new RuleSet(List.of(),
            ASK.stream().map(Groups::parse).toList(),
            ALLOW.stream().map(Groups::parse).toList());

    /** 硬规则;服主关掉({@link PermissionConfig#hardRules})时调用方传空表。 */
    public static List<Rule> hard() {
        return HARD_RULES;
    }

    /** 出厂层:deny 空;allow 见 {@link #ALLOW};ask 见 {@link #ASK}。 */
    public static RuleSet layer() {
        return LAYER;
    }
}
