package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.ApiLintReport;
import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.sdk.ApiTester;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;

/**
 * JEI 联动的 lint 报告,不是守卫:{@code jei} 名字空间下的来源函数的写法,对着 Numen 的组与本联动读一遍。登记经 {@link NumenPlugins} 那扇门、
 * 用联动自己登记的那一段装上(加载器答的那件事在这里用不上,来源函数不会被执行)。报告写进 {@code build/reports/api-lint.txt},不让构建失败。
 */
public class ApiLint {

    public static void main(String[] args) throws IOException, URISyntaxException {
        NumenApiFixture.install();
        NumenJei.install(fluid -> 0);
        List<ApiTester.Lint> lint = ApiTester.lint().stream().filter(l -> l.where().startsWith("jei.")).toList();
        ApiLintReport.write(lint);
    }
}
