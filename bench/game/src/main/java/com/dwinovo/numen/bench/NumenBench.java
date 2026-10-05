package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;

/**
 * 评测这个模组:只在评测的运行配置里加载,平时的游戏、GameTest 与发行 jar 里都没有它。
 *
 * <p>主人客户端起来时把技能接进技能表({@code NumenPlugins.bindClient}),提示词里的技能清单由此而来。评测的进程是
 * 服务端,没有客户端来接,所以在这里接同一扇门——只接技能,插件的客户端块照旧不跑——再扫一遍技能表。
 * 玩家自己的技能目录不扫:评测比的是自带的那一份。
 */
@Mod(Bench.NAMESPACE)
public final class NumenBench {

    public NumenBench() {
        NumenPlugins.bindSkills(root -> SkillRegistry.instance().declareBundled(root));
        SkillRegistry.instance().scan(null);
    }
}
