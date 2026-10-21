package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.api.entity.Hotbar;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.nav.NavText;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.List;
import java.util.Optional;

/**
 * 书:读书与笔、成书的标题作者各页({@code numen.inv.read}、讲台上那本),和往书与笔里写字、署名({@code numen.inv.write})。
 * 写是替她发 {@code EditBook}(见 {@code ClientPackets}),原版只认快捷栏或副手里的书与笔,校验和落地是原版做的,发完读回来。
 */
public final class Books {

    private Books() {}

    /** 一本书的字。 */
    @Doc("A book and quill or a written book, read.")
    public record Book(@Doc("minecraft:writable_book or minecraft:written_book.") String item,
                       @Doc("Its title; only a written book has one.") Optional<String> title,
                       @Doc("Who signed it; only a written book has an author.") Optional<String> author,
                       @Doc("The text of each page, in order.") List<String> pages,
                       @Doc("Signed: it can no longer be edited.") boolean signed) {}

    /** 一叠东西若是书与笔或成书,读出它的字;不是书是空。 */
    public static Optional<Book> read(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (stack.is(Items.WRITABLE_BOOK)) {
            WritableBookContent content = stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
            return Optional.of(new Book(id, Optional.empty(), Optional.empty(),
                    content.pages().stream().map(Filterable::raw).toList(), false));
        }
        if (stack.is(Items.WRITTEN_BOOK)) {
            WrittenBookContent content = stack.getOrDefault(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent.EMPTY);
            return Optional.of(new Book(id, Optional.of(content.title().raw()), Optional.of(content.author()),
                    content.pages().stream().map(page -> page.raw().getString()).toList(), true));
        }
        return Optional.empty();
    }

    /** 背包(含副手)里这一种书,每一本读一遍,先见先列。 */
    public static List<Book> carried(NumenPlayer her, net.minecraft.world.item.Item item) {
        if (item != Items.WRITABLE_BOOK && item != Items.WRITTEN_BOOK) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "only a book and quill or a written book can be read",
                    Call.of("numen.inv.read", "minecraft:written_book"));
        }
        Inventory inventory = her.getInventory();
        java.util.ArrayList<Book> out = new java.util.ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                read(inventory.getItem(i)).ifPresent(out::add);
            }
        }
        return out;
    }

    /**
     * 往快捷栏或副手里的书与笔写上这些页;{@code title} 有值就署名成成书。书不在那两处就先拿到手上(和 {@code numen.gear.hold} 同一个拿法,
     * 拿了什么如实报告)。页数、每页和标题的长度取原版客户端能发的上限;原版排队写完之后读回来,写进去的和要的不一样就是没成。
     *
     * @param title 署名的书名;不署名为 null
     */
    public static Pending<Book> write(NumenPlayer her, List<String> pages, String title) {
        if (pages.size() > WritableBookContent.MAX_PAGES
                || pages.stream().anyMatch(page -> page.length() > WritableBookContent.PAGE_EDIT_LENGTH)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a book holds at most " + WritableBookContent.MAX_PAGES
                    + " pages of " + WritableBookContent.PAGE_EDIT_LENGTH + " characters", null);
        }
        if (title != null && (title.isBlank() || title.length() > WrittenBookContent.TITLE_MAX_LENGTH)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a title is 1 to " + WrittenBookContent.TITLE_MAX_LENGTH
                    + " characters", null);
        }
        Hotbar.Grip grip = her.hotbar().grip(Items.WRITABLE_BOOK);
        if (!grip.ready()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "you carry no book and quill", Call.of("numen.inv.make",
                    "minecraft:writable_book"));
        }
        int slot = grip.hand() == net.minecraft.world.InteractionHand.MAIN_HAND ? her.getInventory().selected
                : Inventory.SLOT_OFFHAND;
        Pending<Book> out = Pending.create();
        her.packets().editBook(slot, pages, Optional.ofNullable(title));
        her.packets().afterHandled(() -> {
            Optional<Book> now = read(her.getInventory().getItem(slot));
            boolean written = now.isPresent() && now.get().pages().equals(pages)
                    && now.get().signed() == (title != null)
                    && (title == null || now.get().title().equals(Optional.of(title)));
            if (!written) {
                out.fail(new ApiError(ErrorKind.FAILED, "the book was not written as asked: the slot now holds "
                        + (now.isPresent() ? now.get() : her.getInventory().getItem(slot)),
                        Call.of("numen.inv.read", "minecraft:writable_book")));
                return;
            }
            BodyAction moved = grip.action();
            out.report((moved == null ? "" : NavText.actions(List.of(moved)) + "; ") + "wrote " + pages.size()
                    + " pages" + (title == null ? " into the book and quill" : " and signed it \"" + title + "\"")
                    + " in hotbar slot " + slot + ".");
            out.complete(now.get());
        });
        return out;
    }
}
