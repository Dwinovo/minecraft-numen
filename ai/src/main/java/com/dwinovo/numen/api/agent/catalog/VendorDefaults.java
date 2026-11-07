package com.dwinovo.numen.api.agent.catalog;

import java.util.List;
import java.util.Map;

/**
 * models.dev 没有、但我们需要的几条厂家数据:按厂家 id 补的缺省地址、额外报头,
 * 以及界面上先列出的常用厂家。全是数据,没有逻辑;解析目录时({@link ModelsDev})一次性并进厂家记录,
 * 之后谁也不再按厂家 id 查这几张表。
 */
final class VendorDefaults {

    private VendorDefaults() {}

    /** models.dev 里这几家没写 {@code api}(由各自 SDK 内置),这里补上。Google 走它的 OpenAI 兼容端点。 */
    static final Map<String, String> BASE_URLS = Map.of(
            "openai", "https://api.openai.com/v1",
            "anthropic", "https://api.anthropic.com/v1",
            "xai", "https://api.x.ai/v1",
            "google", "https://generativelanguage.googleapis.com/v1beta/openai");

    /** OpenRouter 的应用署名报头(可选,用于它的榜单展示)。 */
    static final Map<String, Map<String, String>> HEADERS = Map.of(
            "openrouter", Map.of(
                    "HTTP-Referer", "https://github.com/Dwinovo/Numen",
                    "X-Title", "Numen (Minecraft)"));

    /** 界面先列出的常用厂家(国内外端点分开),顺序即列表顺序;其余厂家靠搜索。 */
    static final List<String> POPULAR = List.of(
            "openai", "anthropic", "deepseek", "openrouter",
            "moonshotai", "moonshotai-cn", "zhipuai", "zai",
            "alibaba", "alibaba-cn", "volcengine", "siliconflow", "siliconflow-cn",
            "minimax", "minimax-cn", "google", "xai");
}
