package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.brain.Slot;
import com.dwinovo.numen.api.client.skin.SkinChoices;
import com.dwinovo.numen.api.entity.SummonSetup;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一只同伴的配置草稿:人设、模型配置、声线、模式、皮肤五项,每项一个基线、一个此刻的值。召唤卡与编辑卡的配置行
 * ({@link SetupRows})改的都是它。改过 = 此刻的值与基线不同;恢复 = 回到基线;只有改过的项发给服务端。
 *
 * <p>值的样子:三项库绑定是条目 id({@code 命名空间:名字},{@code null} 是不绑),模式是 {@code Boolean}(创造为真),
 * 皮肤是 {@link SkinChoices.Pick}({@code null} 是默认皮肤)。
 */
public final class SetupDraft {

    /** 五项;三项库绑定对着它们的 {@link Slot}。 */
    public enum Item {
        PERSONA(Slot.PERSONA), PROVIDER(Slot.PROVIDER), VOICE(Slot.VOICE), MODE(null), SKIN(null);

        private final Slot slot;

        Item(Slot slot) {
            this.slot = slot;
        }

        /** 库绑定的那一种;模式、皮肤是 {@code null}。 */
        public Slot slot() {
            return slot;
        }
    }

    private final Map<Item, Object> base;
    private final Map<Item, Object> now;

    private SetupDraft(Map<Item, Object> base) {
        this.base = base;
        this.now = new EnumMap<>(base);
    }

    /**
     * 召唤卡的基线 = 服务端在召唤请求里不写这一项时实际会做的:三项库条目都不绑({@code CompanionBinding} 不被调用;没绑档案的她
     * 想不了事,没绑人设用服务器的设定,没绑声线不出声),第一次出生是生存({@code CompanionFactory} 加入时定的),皮肤是原版按她的
     * UUID 挑的默认皮肤。
     */
    public static SetupDraft summonDefaults() {
        Map<Item, Object> base = new EnumMap<>(Item.class);
        base.put(Item.PERSONA, null);
        base.put(Item.PROVIDER, null);
        base.put(Item.VOICE, null);
        base.put(Item.MODE, Boolean.FALSE);
        base.put(Item.SKIN, null);
        return new SetupDraft(base);
    }

    /** 编辑卡的基线 = 她此刻的样子。皮肤不在草稿里:编辑时选皮肤卡里选定就是当场换。 */
    public static SetupDraft current(String persona, String provider, String voice, boolean creative) {
        Map<Item, Object> base = new EnumMap<>(Item.class);
        base.put(Item.PERSONA, persona);
        base.put(Item.PROVIDER, provider);
        base.put(Item.VOICE, voice);
        base.put(Item.MODE, creative);
        base.put(Item.SKIN, null);
        return new SetupDraft(base);
    }

    /** 这一项此刻的值。 */
    public Object value(Item item) {
        return now.get(item);
    }

    /** 这一项的基线。 */
    public Object base(Item item) {
        return base.get(item);
    }

    public void set(Item item, Object value) {
        now.put(item, value);
    }

    public boolean changed(Item item) {
        return !Objects.equals(base.get(item), now.get(item));
    }

    /** 回到基线。 */
    public void reset(Item item) {
        now.put(item, base.get(item));
    }

    /** 改过的项,按 {@link Item} 的次序。 */
    public List<Item> changes() {
        List<Item> out = new ArrayList<>();
        for (Item item : Item.values()) {
            if (changed(item)) {
                out.add(item);
            }
        }
        return out;
    }

    public SkinChoices.Pick skin() {
        return (SkinChoices.Pick) now.get(Item.SKIN);
    }

    /** 随召唤请求发出的那几项:只有改过的;没改的服务端照不指定时的规矩办,和基线是同一回事。 */
    public SummonSetup summonSetup() {
        Map<Slot, String> entries = new EnumMap<>(Slot.class);
        Boolean creative = null;
        SummonSetup.Skin skin = null;
        for (Item item : changes()) {
            Object value = now.get(item);
            if (item.slot() != null) {
                if (value != null) {
                    entries.put(item.slot(), (String) value);
                }
            } else if (item == Item.MODE) {
                creative = (Boolean) value;
            } else if (value instanceof SkinChoices.Pick pick) {
                boolean server = pick.choice().where() == SkinChoices.Where.SERVER;
                skin = new SummonSetup.Skin(pick.choice().name(), server, server ? null : pick.choice().png(),
                        pick.variant());
            }
        }
        return new SummonSetup(entries, creative, skin);
    }
}
