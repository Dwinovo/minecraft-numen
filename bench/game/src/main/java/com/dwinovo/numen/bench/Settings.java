package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.bench.report.Pricing;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 这一次评测的设置,全部来自运行配置传进来的系统属性(见 {@code docs/bench.md}),API key 例外:只从环境变量
 * {@value #KEY_ENV} 读,不经任何属性、文件或日志。
 *
 * @param scenarios 要跑的场景({@code bench.scenarios},逗号隔开:{@code all}、组名、场景名);空 = 什么都不跑
 * @param repeats   真实模型每个场景跑几次({@code bench.repeats});0 = 只跑两种基线
 * @param commit    跑的是哪个提交({@code bench.commit},由构建脚本算好)
 */
record Settings(List<String> scenarios, int repeats, String provider, String model, String baseUrl,
                String reasoning, Pricing pricing, String commit) {

    /** API key 所在的环境变量。 */
    static final String KEY_ENV = "NUMEN_BENCH_API_KEY";

    static Settings fromSystem() {
        String pricing = prop("bench.pricing", "");
        return new Settings(
                Arrays.stream(prop("bench.scenarios", "").split(",")).map(String::strip).filter(s -> !s.isEmpty())
                        .toList(),
                Integer.parseInt(prop("bench.repeats", "3")),
                prop("bench.provider", "deepseek"),
                prop("bench.model", "deepseek-v4-flash"),
                prop("bench.baseUrl", "https://api.deepseek.com/beta"),
                prop("bench.reasoning", ""),
                pricing.isEmpty() ? Pricing.NONE : Pricing.load(Path.of(pricing)),
                prop("bench.commit", "unknown"));
    }

    /** 运行配置没给、或者给了空串(构建脚本对没设的参数传空串),都按默认值。 */
    private static String prop(String name, String fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** 记录里的模型名:{@code 服务商/模型}。 */
    String modelLabel() {
        return provider + "/" + model;
    }

    /** 有没有 API key。只答有没有,key 本身不出这个类。 */
    boolean hasKey() {
        String key = System.getenv(KEY_ENV);
        return key != null && !key.isBlank();
    }

    /** 真实模型的端点。代理不走:评测直连。 */
    LlmEndpoint endpoint() {
        return new LlmEndpoint(provider, model, System.getenv(KEY_ENV), baseUrl, "", reasoning);
    }
}
