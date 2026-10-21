package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import net.minecraft.core.Holder;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.level.block.entity.BannerPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 织布机:旗帜、染料(和图案物品)放进去之后能织的图案;选一个就是按钮 = 图案序号(编号减一),结果格随即出旗帜。
 */
final class LoomKind implements MenuKind.Chooses<LoomMenu> {

    @Override
    public Class<LoomMenu> menu() {
        return LoomMenu.class;
    }

    @Override
    public String name() {
        return "a loom";
    }

    @Override
    public Screen read(LoomMenu menu, NumenPlayer her) {
        List<Screen.WeavePattern> patterns = new ArrayList<>();
        List<Holder<BannerPattern>> selectable = menu.getSelectablePatterns();
        for (int i = 0; i < selectable.size(); i++) {
            patterns.add(new Screen.WeavePattern(i + 1,
                    selectable.get(i).unwrapKey().map(key -> key.location().toString()).orElse("?")));
        }
        int selected = menu.getSelectedBannerPatternIndex();
        return new Screen.Weaving(patterns, selected < 0 ? Optional.empty() : Optional.of(selected + 1));
    }

    @Override
    public String choose(LoomMenu menu, NumenPlayer her, Pick first, Pick second) {
        if (second != null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a loom takes one pattern number", Call.of("numen.gui.view"));
        }
        List<Holder<BannerPattern>> selectable = menu.getSelectablePatterns();
        if (selectable.isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "the loom offers no pattern: it needs a banner and a dye in (and a "
                    + "pattern item for most patterns) — numen.gui.put them first", Call.of("numen.gui.view"));
        }
        int n = first.numberIn("pattern", selectable.size());
        her.packets().menuButton(n - 1);
        if (menu.getSelectedBannerPatternIndex() != n - 1) {
            throw new ApiError(ErrorKind.FAILED, "pattern " + n + " was not selected", Call.of("numen.gui.view"));
        }
        return "selected loom pattern " + n;
    }
}
