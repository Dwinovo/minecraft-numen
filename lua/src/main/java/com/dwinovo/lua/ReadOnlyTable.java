package com.dwinovo.lua;

import com.dwinovo.lua.vm.LuaError;
import com.dwinovo.lua.vm.LuaTable;
import com.dwinovo.lua.vm.LuaValue;

/**
 * 一张造好之后谁都改不了的表。字符串的元表在虚拟机里是全 JVM 共用的静态对象,一个沙箱改了它(或改了它指向的 string 库表),别的
 * 沙箱里 {@code ("x"):rep(2)} 跟着变;所以沙箱把 string 库表与字符串元表各造一份、锁住,每个沙箱拿到的都是这一份
 * ({@link LuaSandbox})。
 */
final class ReadOnlyTable extends LuaTable {

    private final String what;
    private boolean locked;

    /** @param what 改它时报错里怎么称呼它:{@code the string library} */
    public ReadOnlyTable(String what) {
        this.what = what;
    }

    /** 造好了:从此只读。 */
    public ReadOnlyTable lock() {
        locked = true;
        return this;
    }

    private void refuse() {
        if (locked) {
            throw new LuaError(what + " is read-only");
        }
    }

    @Override
    public LuaValue setmetatable(LuaValue metatable) {
        refuse();
        return super.setmetatable(metatable);
    }

    @Override
    public void rawset(int key, LuaValue value) {
        refuse();
        super.rawset(key, value);
    }

    @Override
    public void rawset(LuaValue key, LuaValue value) {
        refuse();
        super.rawset(key, value);
    }

    @Override
    public void hashset(LuaValue key, LuaValue value) {
        refuse();
        super.hashset(key, value);
    }

    @Override
    public LuaValue remove(int pos) {
        refuse();
        return super.remove(pos);
    }

    @Override
    public void insert(int pos, LuaValue value) {
        refuse();
        super.insert(pos, value);
    }

    @Override
    public void sort(LuaValue comparator) {
        refuse();
        super.sort(comparator);
    }

    @Override
    public void presize(int narray, int nhash) {
        refuse();
        super.presize(narray, nhash);
    }
}
