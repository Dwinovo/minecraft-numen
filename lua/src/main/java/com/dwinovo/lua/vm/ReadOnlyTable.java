package com.dwinovo.lua.vm;

/**
 * Numen:一张造好之后谁都改不了的表。字符串的元表与 string 库表在上游是全 JVM 共用的静态对象,一个沙箱改了它,别的沙箱里
 * {@code ("x"):rep(2)} 跟着变;所以它们只造一份、锁住,每个沙箱拿到的都是这一份。
 */
public final class ReadOnlyTable extends LuaTable {

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
