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
 * @param shard     并行跑时这个服务器是第几份({@code bench.shard} 写成 {@code 第几份/共几份},从 0 数);一个服务器跑全部是 0
 * @param shards    并行跑时共几份;一个服务器跑全部是 1
 * @param results   结果写到哪个目录({@code bench.results},并行跑时由构建脚本给每份一个);没给是 null,写到
 *                  {@code <游戏目录>/results/<时间戳>/}
 */
record Settings(List<String> scenarios, int repeats, String provider, String model, String baseUrl,
                String reasoning, Pricing pricing, String commit, int shard, int shards, Path results) {

    /** API key 所在的环境变量。 */
    static final String KEY_ENV = "NUMEN_BENCH_API_KEY";

    static Settings fromSystem() {
        String pricing = prop("bench.pricing", "");
        String[] shard = prop("bench.shard", "0/1").split("/");
        String results = prop("bench.results", "");
        return new Settings(
                Arrays.stream(prop("bench.scenarios", "").split(",")).map(String::strip).filter(s -> !s.isEmpty())
                        .toList(),
                Integer.parseInt(prop("bench.repeats", "3")),
                prop("bench.provider", "deepseek"),
                prop("bench.model", "deepseek-v4-flash"),
                prop("bench.baseUrl", "https://api.deepseek.com/beta"),
                prop("bench.reasoning", ""),
                pricing.isEmpty() ? Pricing.NONE : Pricing.load(Path.of(pricing)),
                prop("bench.commit", "unknown"),
                Integer.parseInt(shard[0]), Integer.parseInt(shard[1]),
                results.isEmpty() ? null : Path.of(results));
    }

    /**
     * 这个服务器跑一组里点到的哪几个:并行跑时按登记顺序轮流分给各份(第 i 个归第 {@code i % 共几份} 份),一个服务器跑全部时就是
     * 全部。每个场景(连同它的两种基线与全部真实模型的次数)只在一份里跑。
     */
    List<String> mine(List<String> picked) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            if (i % shards == shard) {
                out.add(picked.get(i));
            }
        }
        return out;
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
