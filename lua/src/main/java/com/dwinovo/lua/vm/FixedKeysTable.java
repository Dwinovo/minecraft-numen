package com.dwinovo.lua.vm;

import java.util.HashSet;
import java.util.Set;

/**
 * Numen:一张有几个键定死了的表。定死的键照常读;给它们赋值(普通赋值、{@code rawset}、{@code function t.k() end})一律拒,拒的那句话
 * 由宿主给({@link Refusal})。宿主登记的函数与装它们的表就这样钉住:脚本加别的键随便,换不掉宿主的那几个,也遮不住它们。
 */
public class FixedKeysTable extends LuaTable {

    /** 有人要改一个定死的键:抛给脚本的错误。 */
    @FunctionalInterface
    public interface Refusal {
        LuaError refuse(LuaValue key);
    }

    private final Set<LuaValue> fixed = new HashSet<>();
    private Refusal refusal;

    /** 从此 {@code key} 定死:先 {@code rawset} 好它的值再调。 */
    public void fix(LuaValue key, Refusal refusal) {
        fixed.add(key);
        this.refusal = refusal;
    }

    private void check(LuaValue key) {
        if (!fixed.isEmpty() && fixed.contains(key)) {
            throw refusal.refuse(key);
        }
    }

    @Override
    public void rawset(LuaValue key, LuaValue value) {
        check(key);
        super.rawset(key, value);
    }

    @Override
    public void hashset(LuaValue key, LuaValue value) {
        check(key);
        super.hashset(key, value);
    }
}
