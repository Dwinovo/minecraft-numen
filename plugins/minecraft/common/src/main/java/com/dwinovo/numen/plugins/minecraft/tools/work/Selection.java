package com.dwinovo.numen.plugins.minecraft.tools.work;

import com.dwinovo.numen.api.sdk.BadValue;
import com.dwinovo.numen.api.sdk.Codec;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.api.sdk.Target;
import com.dwinovo.numen.plugins.minecraft.Constants;
import com.dwinovo.numen.plugins.minecraft.tools.Clusters;
import com.dwinovo.numen.plugins.minecraft.tools.ScanOps;
import com.dwinovo.numen.script.ScriptType;

import java.util.ArrayList;
import java.util.List;

/**
 * 点名的格:一个 Block、一个 Pos,或扫描交回的一团(它的每一格各是一项)。团是本插件的值,所以由本插件把它读成引擎认得的 Target。
 *
 * @param targets 读出来的格,至少一格
 */
public record Selection(List<Target> targets) {

    public Selection {
        targets = List.copyOf(targets);
    }

    /** 一个 Block、一个 Pos 或一团读成它;写回去照原样。 */
    @SuppressWarnings("unchecked")
    static final Codec<Selection> CODEC = new Codec<>() {
        private final Codec<Target> target = (Codec<Target>) LuaCodecs.of(Target.class, Constants.PLUGIN);

        @Override
        public ScriptType type() {
            return ScriptType.union(target.type(), LuaCodecs.of(ScanOps.Cluster.class, Constants.PLUGIN).type());
        }

        @Override
        public Selection decode(Object value) {
            List<Object> items = Clusters.items(value);
            List<Target> out = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                try {
                    out.add(target.decode(items.get(i)));
                } catch (BadValue bad) {
                    throw items.size() == 1 ? bad : bad.in("item " + (i + 1));
                }
            }
            if (out.isEmpty()) {
                throw new BadValue("expected a Block, a Pos or a Cluster with blocks in it; got " + BadValue.given(value));
            }
            return new Selection(out);
        }

        @Override
        public Object encode(Selection value) {
            return value.targets().size() == 1 ? target.encode(value.targets().get(0))
                    : value.targets().stream().map(target::encode).toList();
        }
    };
}
