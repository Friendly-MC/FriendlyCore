package dev.lyy.friendlycore.modules.chat.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.chat.ChatModule;
import dev.lyy.friendlycore.modules.chat.model.ChatPlayerData;
import net.kyori.adventure.text.Component;
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
 * Handles private direct messaging between players (/msg, /tell, /w, /whisper)
 * with full Redis cross-server support.
 */
public class MsgCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final ChatModule chatModule;

    public MsgCommand(FriendlyCore plugin, ChatModule chatModule) {
        this.plugin = plugin;
        this.chatModule = chatModule;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!chatModule.isCommandMsgEnabled()) {
            return true;
        }

        if (args.length < 2) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.usage", "<red>Usage: <gray>%usage%</gray></red>"), Map.of("%usage%", "/" + label + " <player> <message>"));
            return true;
        }

        String targetName = args[0];
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();

        if (message.isEmpty()) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.usage", "<red>Usage: <gray>%usage%</gray></red>"), Map.of("%usage%", "/" + label + " <player> <message>"));
            return true;
        }

        if (sender instanceof Player player && player.getName().equalsIgnoreCase(targetName)) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "private-messages.self", "<red>You cannot send private messages to yourself!</red>"));
            return true;
        }

        chatModule.sendPrivateMessage(sender, targetName, message);
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
