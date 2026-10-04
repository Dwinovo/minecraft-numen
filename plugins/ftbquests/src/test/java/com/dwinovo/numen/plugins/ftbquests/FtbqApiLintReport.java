package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.core.ApiLintReport;
import com.dwinovo.numen.core.CoreApiFixture;
import com.dwinovo.numen.sdk.ApiTester;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * FTB Quests 联动的 lint 报告,不是守卫:{@code ftbquests} 名字空间下的函数的写法,与随它发的技能文档里写着的调用,对着 core 的组与本组读一遍。
 * 本组经 {@link NumenPlugins} 那扇门、用联动自己登记它的那一段装上。报告写进 {@code build/reports/api-lint.txt},不让构建失败。
 */
class FtbqApiLintReport {

    @Test
    void writeTheReport() throws IOException, URISyntaxException {
        CoreApiFixture.install();
        NumenPlugins.register(FtbqApi.NAMESPACE, FtbqApi::install);
        List<ApiTester.Lint> lint = new ArrayList<>(ApiTester.lint().stream()
                .filter(l -> l.where().startsWith("ftbquests.")).toList());
        lint.addAll(ApiTester.lint(ApiLintReport.documents("plugins/ftbquests/skills")));
        ApiLintReport.write(lint);
    }
}
