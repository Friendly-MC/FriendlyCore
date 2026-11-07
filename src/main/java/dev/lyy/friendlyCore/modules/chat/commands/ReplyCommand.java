package dev.lyy.friendlycore.modules.chat.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.chat.ChatModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Handles fast reply to the last conversation partner (/r, /reply <message>).
 */
public class ReplyCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final ChatModule chatModule;

    public ReplyCommand(FriendlyCore plugin, ChatModule chatModule) {
        this.plugin = plugin;
        this.chatModule = chatModule;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!chatModule.isCommandReplyEnabled()) {
            return true;
        }

        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.player-only", "<red>This command can only be used by players.</red>"));
            return true;
        }

        if (args.length < 1) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.usage", "<red>Usage: <gray>%usage%</gray></red>"), Map.of("%usage%", "/" + label + " <message>"));
            return true;
        }

        String targetName = chatModule.getLastReplier(player.getUniqueId());
        if (targetName == null || targetName.isEmpty()) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "private-messages.no-reply-target", "<red>You have no one to reply to.</red>"));
            return true;
        }

        String message = String.join(" ", args).trim();
        if (message.isEmpty()) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.usage", "<red>Usage: <gray>%usage%</gray></red>"), Map.of("%usage%", "/" + label + " <message>"));
            return true;
        }

        chatModule.sendPrivateMessage(player, targetName, message);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
