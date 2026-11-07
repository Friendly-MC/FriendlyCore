package dev.lyy.friendlycore.modules.pets.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.pets.PetManager;
import dev.lyy.friendlycore.modules.pets.gui.PetsGui;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Player command for opening the pets menu and controlling active pet.
 */
public class PetsCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final PetManager petManager;

    public PetsCommand(FriendlyCore plugin, PetManager petManager) {
        this.plugin = plugin;
        this.petManager = petManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.not-player", "&cThis command can only be used by players!")));
            return true;
        }

        if (!player.hasPermission("friendlycore.pets.use") && !player.hasPermission("ecopets.command.pets")) {
            player.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.no-permission", "&cYou don't have permission to do this!")));
            return true;
        }

        if (args.length == 0) {
            PetsGui.open(plugin, player, petManager, 1);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "activate": {
                if (args.length < 2) {
                    player.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.needs-pet", "&cYou must specify a pet!")));
                    return true;
                }
                Pet pet = petManager.getPet(args[1]);
                if (pet == null) {
                    player.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-pet", "&cInvalid pet!")));
                    return true;
                }
                petManager.activatePet(player, pet, true);
                break;
            }
            case "deactivate": {
                if (args.length > 1) petManager.deactivatePet(player, args[1], true);
                else petManager.deactivatePet(player, true);
                break;
            }
            case "toggle":
            case "hide":
            case "show": {
                petManager.toggleHidePet(player);
                break;
            }
            case "gui":
            case "menu":
            default: {
                PetsGui.open(plugin, player, petManager, 1);
                break;
            }
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!(sender instanceof Player player)) return completions;

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("activate", "deactivate", "toggle", "gui"), completions);
            return completions;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("activate") || args[0].equalsIgnoreCase("deactivate"))) {
            var data = petManager.getPlayerData(player.getUniqueId());
            if (data != null) {
                StringUtil.copyPartialMatches(args[1], args[0].equalsIgnoreCase("deactivate")
                        ? data.getActivePets() : data.getUnlockedPets(), completions);
            }
            return completions;
        }

        return completions;
    }
}
