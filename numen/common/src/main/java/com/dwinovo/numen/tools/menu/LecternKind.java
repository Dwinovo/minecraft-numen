package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.tools.Books;
import net.minecraft.world.inventory.LecternMenu;

import java.util.Optional;

/**
 * 讲台:书的标题作者、当前第几页、共几页和这一页的字;翻页是按钮 ≥ 100 跳页({@code gui.page(n)});取书是按钮 3,归{@code gui.take}
 * 拿容器里的东西时问到这里({@link MenuKind.Takes}),不在{@code take}里特判。取书原版要求能建造,不行就什么也不做。翻页动的是这座讲台,
 * 交权限层裁决"用这一格"。
 */
final class LecternKind implements MenuKind.Pages<LecternMenu>, MenuKind.Takes<LecternMenu> {

    @Override
    public Class<LecternMenu> menu() {
        return LecternMenu.class;
    }

    @Override
    public String name() {
        return "a lectern";
    }

    @Override
    public Screen read(LecternMenu menu, NumenPlayer her) {
        Optional<Books.Book> book = Books.read(menu.getBook());
        int pages = book.map(b -> b.pages().size()).orElse(0);
        int page = menu.getPage();
        return new Screen.Lectern(book.flatMap(Books.Book::title), book.flatMap(Books.Book::author), page + 1, pages,
                book.filter(b -> page >= 0 && page < pages).map(b -> b.pages().get(page)));
    }

    @Override
    public Action gate(LecternMenu menu, NumenPlayer her) {
        return MenuKinds.useOpenedBlock(her);
    }

    @Override
    public String page(LecternMenu menu, NumenPlayer her, int page) {
        int pages = Books.read(menu.getBook()).map(b -> b.pages().size()).orElse(0);
        if (page < 1 || page > pages) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "the book has " + pages + " pages; page " + page + " does not exist",
                    Call.of("numen.gui.page", 1));
        }
        her.packets().menuButton(LecternMenu.BUTTON_PAGE_JUMP_RANGE_START + page - 1);
        if (menu.getPage() != page - 1) {
            throw new ApiError(ErrorKind.FAILED, "the lectern stayed on page " + (menu.getPage() + 1), Call.of("numen.gui.view"));
        }
        return "turned the lectern to page " + page + " of " + pages;
    }

    @Override
    public boolean takeOut(LecternMenu menu, NumenPlayer her, int slot) {
        if (slot != 0) {
            return false;
        }
        her.packets().menuButton(LecternMenu.BUTTON_TAKE_BOOK);
        return true;
    }
}
