package dev.lyy.friendlycore.modules.teams.model;

import dev.lyy.friendlycore.common.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Cosmetic name formatting only: no click actions, hover content, or inserted components. */
public final class TeamNameFormat {
    public static final int MAX_INPUT_LENGTH = 512;
    private static final MiniMessage FORMAT = MiniMessage.builder().tags(TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(),
            StandardTags.rainbow(), StandardTags.reset())).build();

    private TeamNameFormat() {}

    public static Component parse(String name) {
        return FORMAT.deserialize(TextService.convertLegacyToMiniMessage(name));
    }

    public static String plain(String name) {
        return PlainTextComponentSerializer.plainText().serialize(parse(name));
    }
}
