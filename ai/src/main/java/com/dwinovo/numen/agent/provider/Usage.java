package com.dwinovo.numen.agent.provider;

/**
 * 一次请求的 token 用量,四元拆分。各家方言由 {@link LlmProvider#usage} 归一到这里,
 * 下游(记账、页脚、诊断)只认这一个形状。
 *
 * <p>四个数是<b>互不重叠</b>的:{@code input} 是真正新处理的提示词,
 * {@code cacheRead} 是从缓存直接取回的,{@code cacheWrite} 是这一轮为了以后能命中
 * 而写进缓存的。三者相加才是提示词的完整体量({@link #promptTokens})——这是唯一与
 * 服务商无关的拆法,也是 {@link #cacheHitRate} 的分母。
 *
 * <p>不含金额。价格随服务商、中转站、订阅制千差万别,{@code models.dev} 那类目录
 * 覆盖不到自建端点与中转;报一个查不到就显示 0 的数字,比不报更误导。token 数本身
 * 已经够诊断了——"缓存被打穿了七万八千 token"这句话不需要标价。
 */
public record Usage(long input, long output, long cacheRead, long cacheWrite) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    /** 提示词的完整体量:新处理 + 缓存读 + 缓存写。 */
    public long promptTokens() {
        return input + cacheRead + cacheWrite;
    }

    /** 提示词加输出。 */
    public long total() {
        return promptTokens() + output;
    }

    /**
     * 真正新处理的量:缓存读近乎免费,不计;缓存写是实打实处理过的,计。
     * 缓存正常工作时这个数很小,暴涨说明缓存前缀碎了。
     */
    public long fresh() {
        return input + cacheWrite + output;
    }

    /**
     * 缓存命中率(0–1)。没有提示词就返回 {@code -1}——<b>"没数据"和"命中率 0%"
     * 是两回事</b>,后者会让人以为缓存坏了。
     */
    public double cacheHitRate() {
        long prompt = promptTokens();
        return prompt > 0 ? (double) cacheRead / prompt : -1;
    }

    /** 报没报过缓存活动。没报过的服务商不该显示命中率那一栏。 */
    public boolean reportsCache() {
        return cacheRead > 0 || cacheWrite > 0;
    }

    public Usage plus(Usage o) {
        if (o == null) return this;
        return new Usage(input + o.input, output + o.output,
                cacheRead + o.cacheRead, cacheWrite + o.cacheWrite);
    }
}
