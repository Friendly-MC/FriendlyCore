package dev.lyy.friendlycore.modules.chat.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.chat.ChatModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
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
 * Main administrative command for FriendlyCore Chat (/chat, /zelchat, /friendlychat).
 */
public class ChatAdminCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final ChatModule chatModule;

    public ChatAdminCommand(FriendlyCore plugin, ChatModule chatModule) {
        this.plugin = plugin;
        this.chatModule = chatModule;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("friendlycore.chat.admin")) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.no-permission", "<red>You do not have permission to execute this command.</red>"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                chatModule.reload();
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                        "general.reload-success", "<green>Chat configuration and filters reloaded successfully!</green>"));
            }
            case "similar" -> {
                if (args.length < 3) {
                    plugin.getTextService().sendMessage(sender, "<red>Usage: /" + label + " similar <word1> <word2></red>");
                    return true;
                }
                String w1 = args[1];
                String w2 = args[2];
                double similarity = chatModule.getFilterManager().evaluateSimilarity(w1, w2);
                double threshold = chatModule.getFilterManager().getSimilarityThreshold();
                String percent = String.format(Locale.US, "%.1f%%", similarity * 100.0);
                String thresholdPercent = String.format(Locale.US, "%.1f%%", threshold * 100.0);
                plugin.getTextService().sendMessage(sender, "<dark_gray>» <aqua>Similarity:</aqua> <yellow>" + percent + "</yellow> <gray>(Threshold: </gray><yellow>" + thresholdPercent + "</yellow><gray>)</gray>");
            }
            case "mute" -> {
                boolean newMuted = !chatModule.isChatMuted();
                chatModule.setChatMuted(newMuted, sender.getName(), true);
            }
            case "clear", "cc" -> {
                chatModule.clearChat(sender.getName(), true);
            }
            case "broadcast", "say" -> {
                if (args.length < 2) {
                    plugin.getTextService().sendMessage(sender, "<red>Usage: /" + label + " broadcast <chat|actionbar|title> <message></red>");
                    return true;
                }
                String type = args[1].toLowerCase();
                String message;
                if (type.equals("chat") || type.equals("actionbar") || type.equals("title")) {
                    if (args.length < 3) {
                        plugin.getTextService().sendMessage(sender, "<red>Usage: /" + label + " broadcast " + type + " <message></red>");
                        return true;
                    }
                    message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                } else {
                    type = "chat";
                    message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                }

                chatModule.broadcast(type, message, sender.getName(), true);
            }
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        plugin.getTextService().sendMessage(sender, "<gradient:#00d2ff:#3a7bd5><b>FriendlyCore Chat Administration</b></gradient>");
        plugin.getTextService().sendMessage(sender, "<gray>» <yellow>/" + label + " reload</yellow> <dark_gray>-</dark_gray> <white>Reload chat configs and filter rules</white></gray>");
        plugin.getTextService().sendMessage(sender, "<gray>» <yellow>/" + label + " similar <word1> <word2></yellow> <dark_gray>-</dark_gray> <white>Test word similarity</white></gray>");
        plugin.getTextService().sendMessage(sender, "<gray>» <yellow>/" + label + " broadcast <chat|actionbar|title> <message></yellow> <dark_gray>-</dark_gray> <white>Broadcast message</white></gray>");
        plugin.getTextService().sendMessage(sender, "<gray>» <yellow>/" + label + " mute</yellow> <dark_gray>-</dark_gray> <white>Toggle server chat mute</white></gray>");
        plugin.getTextService().sendMessage(sender, "<gray>» <yellow>/" + label + " clear</yellow> <dark_gray>-</dark_gray> <white>Clear server chat</white></gray>");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            for (String sub : List.of("reload", "similar", "broadcast", "mute", "clear", "help")) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    list.add(sub);
                }
            }
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("broadcast")) {
            List<String> list = new ArrayList<>();
            for (String type : List.of("chat", "actionbar", "title")) {
                if (type.startsWith(args[1].toLowerCase())) {
                    list.add(type);
                }
            }
            return list;
        }
        return Collections.emptyList();
    }
}
