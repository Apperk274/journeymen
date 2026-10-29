package io.github.apperk274.journeymen.build;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.SignText;

import java.util.List;

public final class SafeText {

    private SafeText() {
    }

    public static Component plain(Component component) {
        ComponentContents contents = component.getContents();
        MutableComponent copy;
        if (contents instanceof TranslatableContents translatable) {
            Object[] args = translatable.getArgs().clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component argument) {
                    args[i] = plain(argument);
                }
            }
            copy = Component.translatableWithFallback(translatable.getKey(), translatable.getFallback(), args);
        } else if (contents instanceof PlainTextContents || contents instanceof KeybindContents) {
            copy = MutableComponent.create(contents);
        } else {
            copy = Component.empty();
        }
        copy.setStyle(component.getStyle().withClickEvent(null).withHoverEvent(null).withInsertion(null));
        for (Component sibling : component.getSiblings()) {
            copy.append(plain(sibling));
        }
        return copy.equals(component) ? component : copy;
    }

    public static SignText sign(SignText text) {
        Component[] messages = text.getMessages(false).clone();
        Component[] filtered = text.getMessages(true).clone();
        for (int i = 0; i < messages.length; i++) {
            messages[i] = plain(messages[i]);
        }
        for (int i = 0; i < filtered.length; i++) {
            filtered[i] = plain(filtered[i]);
        }
        return new SignText(messages, filtered, text.getColor(), text.hasGlowingText());
    }

    public static ItemStack item(ItemStack stack) {
        WrittenBookContent book = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (book == null) {
            return stack;
        }
        List<Filterable<Component>> pages = book.pages().stream()
                .map(page -> new Filterable<>(plain(page.raw()), page.filtered().map(SafeText::plain)))
                .toList();
        if (pages.equals(book.pages())) {
            return stack;
        }
        ItemStack copy = stack.copy();
        copy.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(book.title(), book.author(), book.generation(), pages, book.resolved()));
        return copy;
    }
}
