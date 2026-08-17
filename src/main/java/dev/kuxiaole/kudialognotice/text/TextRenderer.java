package dev.kuxiaole.kudialognotice.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;

public final class TextRenderer {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public Component render(String input) {
        return miniMessage.deserialize(input);
    }

    public Component render(String input, Map<String, Component> placeholders) {
        TagResolver.Builder resolver = TagResolver.builder();
        placeholders.forEach((key, value) -> resolver.resolver(Placeholder.component(key, value)));
        return miniMessage.deserialize(input, resolver.build());
    }
}

