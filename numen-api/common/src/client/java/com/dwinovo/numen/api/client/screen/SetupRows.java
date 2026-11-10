package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.client.agent.RemoteCompanions;
import com.dwinovo.numen.api.client.agent.ServerLibraries;
import com.dwinovo.numen.api.client.screen.SetupDraft.Item;
import com.dwinovo.numen.api.client.ui.widget.Dropdown;
import com.dwinovo.numen.api.client.ui.widget.Reveal;
import com.dwinovo.numen.api.client.ui.widget.SlideWrap;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.google.gson.JsonObject;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 召唤卡与编辑卡共用的配置行:人设、模型配置两行摆出来,声线、模式收进"更多选项"(每次开卡收着)。每一项有哪些选项、各叫什么、
 * 默认是什么、改没改过、怎么恢复,都只在这里定;值记在 {@link SetupDraft} 里。照 Telegram 新建群的那张卡:不常用的项建的时候
 * 就能改,摆着当前值,默认取不指定时的那个。
 *
 * <ul>
 *   <li>库绑定的"不绑"那一项叫"默认(X)",X 是不绑时她实际的样子(见 {@link SetupDraft#summonDefaults});</li>
 *   <li>没改过的行值是次要色;改过的行值是主色,行尾一个 ×,点它回到基线——召唤卡是"恢复默认",编辑卡是"撤销更改";</li>
 *   <li>模式那一行在主人不能让她进创造时置灰,判据与服务端同一道({@code Companions.mayChooseCreative});</li>
 *   <li>模型配置没有"不绑"可选:基线没绑(召唤卡、没绑过的老档)才摆着"默认(未配置)"这一项。</li>
 * </ul>
 *
 * <p>皮肤的入口是卡头部那张脸,在卡里(召唤卡选定的皮肤进草稿,编辑卡选定就是当场换)。
 */
final class SetupRows {

    /** 召唤卡还是编辑卡:基线不同,模式的"默认"与 × 的说法不同。 */
    enum Context { SUMMON, EDIT }

    /** 收在"更多选项"里的两行。 */
    private static final int HIDDEN_ROWS = 2;

    private final Context context;
    private final BooleanSupplier canChooseMode;
    private final Reveal more = Reveal.slide();
    private SetupDraft draft = SetupDraft.summonDefaults();

    /** 这一版排出来的行与各行的选项(选项的值,按显示的次序)。 */
    private final Map<Item, Dropdown> picks = new EnumMap<>(Item.class);
    private final Map<Item, List<Object>> options = new EnumMap<>(Item.class);
    private SlideWrap wrap;
    private boolean modeLocked;

    SetupRows(Context context, BooleanSupplier canChooseMode) {
        this.context = context;
        this.canChooseMode = canChooseMode;
    }

    /** 开卡:草稿换成这一份,"更多选项"收起;人设目录可能刚被增删过,让服务端重扫一次。 */
    void reset(SetupDraft draft) {
        this.draft = draft;
        more.snap(false);
        RemoteCompanions.library().request(Libraries.PERSONA, Libraries.RELOAD, new JsonObject());
    }

    SetupDraft draft() {
        return draft;
    }

    /** 推进开合的过渡;返回还在走。 */
    boolean advance(long nowMs) {
        return more.step(nowMs);
    }

    /** 此刻占多高:摆出的两行、"更多选项"一行,加上收着的那两行此刻露出的一截(与 {@link SlideWrap#shownH} 同一算法)。 */
    int height() {
        return 3 * ModalCard.ROW_H + Math.round(HIDDEN_ROWS * ModalCard.ROW_H * more.value());
    }

    /** 从 {@code ry} 起往下排进 {@code card}。 */
    void layout(ModalCard card, int ry) {
        picks.clear();
        options.clear();
        row(card, null, Item.PERSONA, ry);
        ry += ModalCard.ROW_H;
        row(card, null, Item.PROVIDER, ry);
        ry += ModalCard.ROW_H;
        card.expandRow(ry, t(Keys.SETUP_MORE), this::summary, more);
        ry += ModalCard.ROW_H;
        wrap = card.slideWrap(ry, HIDDEN_ROWS * ModalCard.ROW_H, more);
        row(card, wrap, Item.VOICE, ry);
        ry += ModalCard.ROW_H;
        row(card, wrap, Item.MODE, ry);
    }

    private void row(ModalCard card, SlideWrap in, Item item, int ry) {
        Map<Object, String> named = choices(item);
        List<Object> values = new ArrayList<>(named.keySet());
        List<String> names = new ArrayList<>(named.values());
        options.put(item, values);
        int selected = Math.max(0, values.indexOf(draft.value(item)));
        java.util.function.IntConsumer onSelect = i -> draft.set(item, values.get(i));
        Dropdown pick = in == null ? card.select(ry, label(item), names, selected, onSelect)
                : card.select(in, ry, label(item), names, selected, onSelect);
        pick.revertible(() -> draft.changed(item), () -> {
            draft.reset(item);
            pick.select(Math.max(0, values.indexOf(draft.value(item))));
        });
        if (item == Item.MODE) {
            modeLocked = !canChooseMode.getAsBoolean();
            pick.setEnabled(!modeLocked);
        }
        picks.put(item, pick);
    }

    /** 这一项能选的值与它们的名字,按显示的次序。基线与此刻的值即使不在列表里(条目刚被删、被关)也照实摆着。 */
    private Map<Object, String> choices(Item item) {
        Map<Object, String> out = new LinkedHashMap<>();
        switch (item) {
            case PERSONA -> {
                out.put(null, defaultName(item));
                ServerLibraries.personasInUse().forEach(p -> out.put(p.id(), p.name()));
            }
            case PROVIDER -> {
                if (draft.base(item) == null) {
                    out.put(null, defaultName(item));
                }
                ServerLibraries.providersInUse().forEach(p -> out.put(p.id(), p.name()));
            }
            case VOICE -> {
                out.put(null, defaultName(item));
                ServerLibraries.voicesInUse().forEach(v -> out.put(v.id(), v.name()));
            }
            case MODE -> {
                out.put(Boolean.FALSE, context == Context.SUMMON ? defaultName(item) : t(Keys.SUMMON_MODE_SURVIVAL));
                out.put(Boolean.TRUE, t(Keys.SUMMON_MODE_CREATIVE));
            }
            case SKIN -> throw new IllegalArgumentException("the skin is chosen from the header photo");
        }
        for (Object kept : new Object[]{draft.base(item), draft.value(item)}) {
            if (!out.containsKey(kept)) {
                out.put(kept, String.valueOf(kept));
            }
        }
        return out;
    }

    /** "默认(X)":X 是不指定这一项时她实际的样子。 */
    private static String defaultName(Item item) {
        return I18n.get(Keys.SETUP_DEFAULT, defaultValue(item));
    }

    /** 不指定这一项时她实际的样子(见 {@link SetupDraft#summonDefaults})。 */
    private static String defaultValue(Item item) {
        return switch (item) {
            case PERSONA -> t(Keys.SETUP_PERSONA_DEFAULT);
            case PROVIDER -> t(Keys.SETUP_PROVIDER_DEFAULT);
            case VOICE -> t(Keys.SETUP_VOICE_DEFAULT);
            case MODE -> t(Keys.SUMMON_MODE_SURVIVAL);
            case SKIN -> t(Keys.SUMMON_SKIN_DEFAULT);
        };
    }

    private static String label(Item item) {
        return item.slot() != null ? t(item.slot().label()) : t("numen.summon.mode");
    }

    /** "更多选项"收着时写在行上的:里面各项此刻的值。 */
    private String summary() {
        StringBuilder out = new StringBuilder();
        for (Item item : new Item[]{Item.VOICE, Item.MODE}) {
            if (!out.isEmpty()) {
                out.append(" · ");
            }
            out.append(I18n.get(Keys.SETUP_SUMMARY_ITEM, label(item), shortValue(item)));
        }
        return out.toString();
    }

    /** 摘要里的值:"默认(X)"只写 X。 */
    private String shortValue(Item item) {
        Object value = draft.value(item);
        if (item == Item.MODE) {
            return t(Boolean.TRUE.equals(value) ? Keys.SUMMON_MODE_CREATIVE : Keys.SUMMON_MODE_SURVIVAL);
        }
        if (value == null) {
            return defaultValue(item);
        }
        String name = choices(item).get(value);
        return name == null ? String.valueOf(value) : name;
    }

    /** 悬停提示:行尾的 × 说它做什么;置灰的模式行说为什么。 */
    String tooltipAt(double mx, double my) {
        for (Map.Entry<Item, Dropdown> e : picks.entrySet()) {
            Dropdown pick = e.getValue();
            boolean here = wrap.children().contains(pick) ? wrap.childAt(mx, my) == pick : pick.contains(mx, my);
            if (!here) {
                continue;
            }
            if (pick.overRevert(mx, my)) {
                return t(context == Context.SUMMON ? Keys.SETUP_RESET : Keys.SETUP_REVERT);
            }
            if (e.getKey() == Item.MODE && modeLocked) {
                return t(Keys.SETUP_MODE_LOCKED);
            }
        }
        return null;
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
