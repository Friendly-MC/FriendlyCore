package dev.lyy.friendlycore.modules.chat.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.chat.ChatModule;
import dev.lyy.friendlycore.modules.chat.model.ChatPlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Handles unignoring players (/unignore <player>).
 */
public class UnignoreCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final ChatModule chatModule;

    public UnignoreCommand(FriendlyCore plugin, ChatModule chatModule) {
        this.plugin = plugin;
        this.chatModule = chatModule;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!chatModule.isCommandIgnoreEnabled()) return true;

        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.player-only", "<red>This command can only be used by players.</red>"));
            return true;
        }

        if (args.length < 1) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.usage", "<red>Usage: <gray>%usage%</gray></red>"), Map.of("%usage%", "/" + label + " <player>"));
            return true;
        }

        String targetName = args[0];
        UUID targetUuid = chatModule.getUuidByName(targetName);
        if (targetUuid == null) {
            Player targetOnline = Bukkit.getPlayerExact(targetName);
            if (targetOnline != null) targetUuid = targetOnline.getUniqueId();
        }

        if (targetUuid == null) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.player-not-found", "<red>Player <yellow>%player%</yellow> is not online.</red>"), Map.of("%player%", targetName));
            return true;
        }

        ChatPlayerData data = chatModule.getPlayerData(player.getUniqueId());
        if (data == null || !data.isIgnoring(targetUuid)) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "ignore.not-ignored", "<red>You are not ignoring <yellow>%player%</yellow>!</red>"), Map.of("%player%", targetName));
            return true;
        }

        chatModule.removeIgnored(player.getUniqueId(), targetUuid);
        plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                "ignore.unignored-success", "<green>You have unignored <yellow>%player%</yellow>.</green>"), Map.of("%player%", targetName));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            ChatPlayerData data = chatModule.getPlayerData(player.getUniqueId());
            if (data != null && !data.getIgnoredPlayers().isEmpty()) {
                List<String> list = new ArrayList<>();
                String token = args[0].toLowerCase();
                for (UUID u : data.getIgnoredPlayers()) {
                    String name = chatModule.getUsernameByUuid(u);
                    if (name != null && name.toLowerCase().startsWith(token)) {
                        list.add(name);
                    }
                }
                return list;
            }
        }
        return Collections.emptyList();
    }
}
