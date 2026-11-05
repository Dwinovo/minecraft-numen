package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.nav.NavClock;
import com.dwinovo.numen.pathing.search.SearchClock;
import com.dwinovo.numen.pathing.search.Searches;
import java.util.List;
import java.util.function.Supplier;

/**
 * 用例里有同伴的套件(Numen 的日常批与慢批、手动的绕路套件、各联动的套件)都从这里继承测试服的表与等待,写在一处,
 * 选了哪个套件、几个套件一起选,测试服都是同一套设置:
 * <ul>
 *   <li>同伴的寻路按做了多少活折算想多久({@link NavClock} 换成 {@link SearchClock#STEADY}),与寻路模块自己的用例、靶场同一块表;</li>
 *   <li>每刻开头等寻路的搜索全收场({@link Searches#busy})。Numen API 自己的后台(程序等)由 {@code GameTestSuites} 登记。</li>
 * </ul>
 * 这几样都只换"多久"的量法,不改结论:正式运行是墙钟,测试服里与机器快慢、负载无关。
 */
public abstract class CompanionGameTestSuite implements GameTestSuite {

    protected CompanionGameTestSuite() {
        ServerLifecycle.afterStarted(server -> NavClock.use(server, SearchClock.STEADY));
    }

    @Override
    public List<Supplier<String>> background() {
        return List.of(Searches::busy);
    }
}
