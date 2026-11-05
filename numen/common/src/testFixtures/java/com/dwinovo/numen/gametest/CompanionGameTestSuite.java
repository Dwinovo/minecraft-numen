package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.nav.NavClock;
import com.dwinovo.numen.pathing.search.SearchClock;
import com.dwinovo.numen.pathing.search.Searches;
import com.dwinovo.numen.scan.SearchBudget;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 用例里有同伴的套件(Numen 的日常批与慢批、手动的绕路套件、各联动的套件)都从这里继承测试服的表与等待,写在一处,
 * 选了哪个套件、几个套件一起选,测试服都是同一套设置:
 * <ul>
 *   <li>同伴的寻路按做了多少活折算想多久({@link NavClock} 换成 {@link SearchClock#STEADY}),与寻路模块自己的用例、靶场同一块表;</li>
 *   <li>扫描与定位每刻做到哪按活量算({@link SearchBudget#use}):每查一次额度记 {@link #SCAN_NANOS_PER_CHECK} 纳秒;</li>
 *   <li>每刻开头等寻路的搜索全收场({@link Searches#busy})。Numen API 自己的后台(程序等)由 {@code GameTestSuites} 登记。</li>
 * </ul>
 * 这几样都只换"多久"的量法,不改结论:正式运行是墙钟,测试服里与机器快慢、负载无关。
 */
public abstract class CompanionGameTestSuite implements GameTestSuite {

    /**
     * 测试服里扫描每查一次额度({@code SearchBudget.withinTime} 与各项许可)记多少纳秒。每次查额度之后是一步活(看一节、读一节、
     * 取一个样),10 微秒落在实测的一步之间(索引已有的一节几微秒,真读一节约 50 微秒):一刻 4 毫秒的上限约四百步,
     * 每段自己保底的 0.25 毫秒约二十五步。
     */
    static final long SCAN_NANOS_PER_CHECK = 10_000;

    private static final AtomicLong SCAN_WORK = new AtomicLong();

    protected CompanionGameTestSuite() {
        ServerLifecycle.afterStarted(server -> NavClock.use(server, SearchClock.STEADY));
        SearchBudget.use(() -> SCAN_WORK.addAndGet(SCAN_NANOS_PER_CHECK));
    }

    @Override
    public List<Supplier<String>> background() {
        return List.of(Searches::busy);
    }
}
