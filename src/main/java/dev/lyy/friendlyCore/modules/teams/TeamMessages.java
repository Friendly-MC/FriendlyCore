package dev.lyy.friendlycore.modules.teams;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/** Shorthand for {@link Messages} bound to modules/teams/messages.yml. */
public final class TeamMessages {

    private TeamMessages() {
    }

    public static void send(FriendlyCore plugin, CommandSender to, String key, String... placeholders) {
        Messages.send(plugin, to, "teams", key, placeholders);
    }

    public static String raw(FriendlyCore plugin, String key) {
        return Messages.raw(plugin, "teams", key);
    }

    public static Component component(FriendlyCore plugin, String key, String... placeholders) {
        return Messages.component(plugin, "teams", key, placeholders);
    }
}
