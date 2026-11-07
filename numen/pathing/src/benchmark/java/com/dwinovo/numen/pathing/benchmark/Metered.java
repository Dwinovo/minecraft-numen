package com.dwinovo.numen.pathing.benchmark;

import com.dwinovo.numen.pathing.search.SearchClock;

/**
 * 搜索的表,外加读一次搜索想了多久:预演({@link Rehearsal})把这个读数当作这次搜索的结论晚到了多少刻。
 * 一件搜索在一个线程上跑完,{@link #nanos} 读到的就是最近那次搜索的。
 */
interface Metered extends SearchClock {

    /** 最近一次搜索想了多少纳秒(表的读数)。 */
    long nanos();
}
