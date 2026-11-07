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
 * Handles ignoring players (/ignore <player>).
 */
public class IgnoreCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final ChatModule chatModule;

    public IgnoreCommand(FriendlyCore plugin, ChatModule chatModule) {
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
        if (player.getName().equalsIgnoreCase(targetName)) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "ignore.cannot-ignore-self", "<red>You cannot ignore yourself!</red>"));
            return true;
        }

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

        Player targetOnline = Bukkit.getPlayer(targetUuid);
        if (targetOnline != null && !player.canSee(targetOnline) && !player.hasPermission("friendlycore.vanish.see")) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.player-not-found", "<red>Player <yellow>%player%</yellow> is not online.</red>"), Map.of("%player%", targetName));
            return true;
        }
        if (targetOnline != null && targetOnline.hasPermission("friendlycore.chat.bypass.ignore")) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "ignore.cannot-ignore-staff", "<red>You cannot ignore staff members!</red>"));
            return true;
        }

        ChatPlayerData data = chatModule.getPlayerData(player.getUniqueId());
        if (data != null && data.isIgnoring(targetUuid)) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "ignore.already-ignored", "<red>You are already ignoring <yellow>%player%</yellow>!</red>"), Map.of("%player%", targetName));
            return true;
        }

        chatModule.addIgnored(player.getUniqueId(), targetUuid);
        plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                "ignore.ignored-success", "<green>You are now ignoring <yellow>%player%</yellow>.</green>"), Map.of("%player%", targetName));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String token = args[0].toLowerCase();
            List<String> list = new ArrayList<>();
            for (String name : chatModule.getOnlinePlayerNames()) {
                if (sender instanceof Player p && p.getName().equalsIgnoreCase(name)) continue;
                if (name.toLowerCase().startsWith(token)) {
                    list.add(name);
                }
            }
            return list;
        }
        return Collections.emptyList();
    }
}
