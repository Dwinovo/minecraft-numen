package com.dwinovo.numen.bench.report;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 单价表:{@code 服务商/模型} → 每百万 token 的价钱,分缓存未命中的输入、命中的输入、输出三档。价钱随服务商调整,
 * 所以放在配置文件里({@code bench/pricing.json}),不写在代码里;表里没有的模型不折成本,报告里那一栏空着。
 *
 * <pre>
 * { "deepseek/deepseek-v4-flash": { "currency": "CNY", "miss": 1.0, "hit": 0.1, "output": 2.0 } }
 * </pre>
 */
public final class Pricing {

    /** 一个模型的单价,每百万 token。 */
    public record Price(String currency, double miss, double hit, double output) {

        public double cost(long tokensMiss, long tokensHit, long tokensOut) {
            return (tokensMiss * miss + tokensHit * hit + tokensOut * output) / 1_000_000.0;
        }
    }

    /** 一个价钱都没有的表。 */
    public static final Pricing NONE = new Pricing(Map.of());

    private final Map<String, Price> prices;

    private Pricing(Map<String, Price> prices) {
        this.prices = prices;
    }

    /** 这个模型的单价;表里没有是 null。 */
    public Price of(String model) {
        return prices.get(model);
    }

    public static Pricing parse(String json) {
        Map<String, Price> prices = new HashMap<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;   // 顶层的说明文字这类
            }
            JsonObject p = e.getValue().getAsJsonObject();
            prices.put(e.getKey(), new Price(p.get("currency").getAsString(), p.get("miss").getAsDouble(),
                    p.get("hit").getAsDouble(), p.get("output").getAsDouble()));
        }
        return new Pricing(prices);
    }

    public static Pricing load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
