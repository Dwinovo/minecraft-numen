package com.dwinovo.numen.api.library;

/**
 * 玩家对库里各条的开关,记在他自己的偏好里;默认全开。按库的目录名与条目名记,内置与我的同名的共用一个开关。
 */
public interface Switches {

    /** 记在内存里、不落盘:评测与单测用。 */
    static Switches inMemory() {
        java.util.Set<String> off = java.util.concurrent.ConcurrentHashMap.newKeySet();
        return new Switches() {
            @Override
            public boolean enabled(String library, EntryId id) {
                return !off.contains(library + "/" + id);
            }

            @Override
            public void set(String library, EntryId id, boolean enabled) {
                if (enabled) {
                    off.remove(library + "/" + id);
                } else {
                    off.add(library + "/" + id);
                }
            }
        };
    }

    boolean enabled(String library, EntryId id);

    void set(String library, EntryId id, boolean enabled);
}
