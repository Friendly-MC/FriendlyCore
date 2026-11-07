package dev.lyy.friendlycore.modules.chat.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.chat.ChatModule;
import dev.lyy.friendlycore.modules.chat.model.ChatPlayerData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Toggles whether private messages from everyone are blocked (/ignoreall).
 */
public class IgnoreAllCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final ChatModule chatModule;

    public IgnoreAllCommand(FriendlyCore plugin, ChatModule chatModule) {
        this.plugin = plugin;
        this.chatModule = chatModule;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!chatModule.isCommandIgnoreAllEnabled()) return true;

        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.player-only", "<red>This command can only be used by players.</red>"));
            return true;
        }

        ChatPlayerData data = chatModule.getPlayerData(player.getUniqueId());
        boolean currentClosed = data != null && data.hasMessagesClosed();
        boolean newClosed = !currentClosed;

        chatModule.setMessagesClosed(player.getUniqueId(), newClosed);

        if (newClosed) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "ignore.ignore-all-enabled", "<yellow>You are now ignoring private messages from everyone.</yellow>"));
        } else {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "ignore.ignore-all-disabled", "<green>You can now receive private messages again.</green>"));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
