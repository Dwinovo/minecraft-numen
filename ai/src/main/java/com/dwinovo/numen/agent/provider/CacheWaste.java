package com.dwinovo.numen.agent.provider;

/**
 * 缓存重计费的累计器:数出<b>本该命中却重新处理</b>的 token。
 *
 * <p>判据是逐轮比对——上一轮的提示词理应整段留在缓存里,这一轮的提示词只要不比它短,
 * 就该整段读回来。差额就是白付的部分:
 *
 * <pre>{@code 漏掉的 = min(上轮提示词, 本轮提示词) - 本轮缓存读}</pre>
 *
 * <p>它是<b>诊断</b>不是统计:这个数一涨,说明前缀被谁动了——系统提示改了、工具清单
 * 变了、历史被从中间剪过、或者隔太久缓存自己过期了。平时应当接近零。
 *
 * <p>不报金额。价格随服务商与中转站千差万别,"漏掉七万八千 token"这句话本身已经
 * 足够定位问题,标价反而要背上一份维护不动的价目表。
 *
 * <p>累计式,不存历史:算这个数只需要上一轮的提示词体量,存整部历史是为同一个答案
 * 付更多的存储。
 */
public final class CacheWaste {

    /** 低于这个数的差额算缓存分段粒度的噪声,不计。 */
    static final long NOISE_FLOOR_TOKENS = 1024;

    private long prevPrompt;
    private boolean sawCache;
    private long missedTokens;
    private int missCount;

    /**
     * 记下一轮的用量。
     *
     * <p>三种情况不计:没有上一轮(第一轮无从比对)、这轮没有提示词、以及这轮完全没有
     * 缓存活动<b>且此前也从未有过</b>——最后那条是为了区分"缓存没命中"和"这家服务商
     * 根本不报缓存":后者每一轮都会被误判成全量漏掉。
     */
    public void observe(Usage u) {
        if (u == null) return;
        long prompt = u.promptTokens();
        boolean thisTurnReported = u.reportsCache();
        if (prevPrompt > 0 && prompt > 0 && (thisTurnReported || sawCache)) {
            long missed = Math.min(prevPrompt, prompt) - u.cacheRead();
            if (missed > NOISE_FLOOR_TOKENS) {
                missedTokens += missed;
                missCount++;
            }
        }
        sawCache |= thisTurnReported;
        if (prompt > 0) prevPrompt = prompt;
    }

    /** 历史被剪断(压缩、清空)之后上一轮不再可比——从下一轮重新起算。 */
    public void reset() {
        prevPrompt = 0;
    }

    public long missedTokens() {
        return missedTokens;
    }

    public int missCount() {
        return missCount;
    }
}
