package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.brain.Visits;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.permission.ConsentVenues;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 渠道令牌的信箱:她的回话和要问主人的事({@link Visits.Channel})攒在这里,渠道用 {@code get_replies} 取走。
 * 一张令牌一个信箱,满了丢最老的——渠道桥长时间没来取,攒下的不该无限涨。条目是通用形式的 JSON,
 * 在 QQ、Discord 上怎么显示是渠道插件自己的事。
 */
final class McpChannels {

    private static final int CAPACITY = 256;

    /** 一张令牌的信箱,和投进来过几条(等着取的那一方凭它认"等的到了",取走之后信箱又空也不会认错)。 */
    private record Box(LinkedBlockingDeque<JsonObject> items, AtomicLong posts) {}

    private final Map<String, Box> boxes = new ConcurrentHashMap<>();

    private Box box(String token) {
        return boxes.computeIfAbsent(token, k -> new Box(new LinkedBlockingDeque<>(CAPACITY), new AtomicLong()));
    }

    private void post(String token, JsonObject item) {
        Box box = box(token);
        // 先记一笔再投:等着的那一方一看到这一笔就不再算在等,哪怕它醒来取得比这边投得还快
        box.posts().incrementAndGet();
        while (!box.items().offerLast(item)) {
            box.items().pollFirst();
        }
    }

    /** 这张令牌的渠道那一头:来访期间她的话和征询都投到它的信箱。 */
    Visits.Channel channel(String token) {
        return new Visits.Channel() {
            @Override
            public void said(UUID companion, String text) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "said");
                item.addProperty("companion", companion.toString());
                item.addProperty("text", text);
                post(token, item);
            }

            @Override
            public void ask(ConsentVenues.Offer offer) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "consent");
                item.addProperty("id", offer.id());
                item.addProperty("companion", offer.companion().toString());
                item.addProperty("name", offer.companionName());
                item.addProperty("doing", offer.doing());
                JsonArray remembers = new JsonArray();
                offer.remembers().forEach(remembers::add);
                item.add("remember", remembers);
                JsonArray choices = new JsonArray();
                choices.add("allow");
                choices.add("remember");
                choices.add("deny");
                item.add("choices", choices);
                post(token, item);
            }

            @Override
            public void clear(long id, ConsentDesk.Withdrawal why) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "consent_withdrawn");
                item.addProperty("id", id);
                item.addProperty("why", why == null ? "answered" : why.name().toLowerCase(java.util.Locale.ROOT));
                post(token, item);
            }
        };
    }

    /** 取走这张令牌信箱里攒的;空的就最多等 {@code waitMillis},有一条到就把当时攒的都交出。 */
    List<JsonObject> take(String token, long waitMillis) throws Exception {
        Box box = box(token);
        LinkedBlockingDeque<JsonObject> items = box.items();
        long seen = box.posts().get();
        // 空着等的是她的话或征询:游戏那一头投进来才到
        JsonObject first = waitMillis > 0 && items.isEmpty()
                ? GameWaits.await(() -> box.posts().get() != seen, () -> items.pollFirst(waitMillis, TimeUnit.MILLISECONDS))
                : items.pollFirst();
        List<JsonObject> out = new ArrayList<>();
        if (first != null) {
            out.add(first);
            items.drainTo(out);
        }
        return out;
    }
}
