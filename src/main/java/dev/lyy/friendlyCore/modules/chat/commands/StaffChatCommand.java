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

/**
 * Handles sending messages to staff chat or toggling staff chat mode (/staffchat, /sc).
 */
public class StaffChatCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final ChatModule chatModule;

    public StaffChatCommand(FriendlyCore plugin, ChatModule chatModule) {
        this.plugin = plugin;
        this.chatModule = chatModule;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!chatModule.isCommandStaffChatEnabled()) return true;

        if (!sender.hasPermission("friendlycore.chat.command.staffchat")) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.no-permission", "<red>You do not have permission to execute this command.</red>"));
            return true;
        }

        if (args.length > 0) {
            String message = String.join(" ", args).trim();
            if (!message.isEmpty()) {
                chatModule.sendStaffChat(sender, message);
            }
            return true;
        }

        if (sender instanceof Player player) {
            boolean current = chatModule.isStaffChatToggled(player.getUniqueId());
            boolean newState = !current;
            chatModule.setStaffChatToggled(player.getUniqueId(), newState);

            if (newState) {
                plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                        "staff-chat.toggled-on", "<green>Staff chat toggled ON. All your messages will be sent to staff.</green>"));
            } else {
                plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                        "staff-chat.toggled-off", "<yellow>Staff chat toggled OFF.</yellow>"));
            }
        } else {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "staff-chat.no-message", "<red>Please provide a message for staff chat.</red>"));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
