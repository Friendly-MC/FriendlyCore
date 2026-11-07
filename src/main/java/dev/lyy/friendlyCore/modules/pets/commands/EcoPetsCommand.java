package dev.lyy.friendlycore.modules.pets.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.pets.PetManager;
import dev.lyy.friendlycore.modules.pets.migration.EcoPetsMigrator;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Admin management command for EcoPets module.
 */
public class EcoPetsCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final PetManager petManager;
    private final EcoPetsMigrator migrator;

    public EcoPetsCommand(FriendlyCore plugin, PetManager petManager) {
        this.plugin = plugin;
        this.petManager = petManager;
        this.migrator = new EcoPetsMigrator(plugin, petManager);
    }

    private CompletableFuture<UUID> resolveTargetUuid(String targetName) {
        if (targetName == null || targetName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        Player online = Bukkit.getPlayer(targetName);
        if (online != null) {
            return CompletableFuture.completedFuture(online.getUniqueId());
        }
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            UUID netUuid = plugin.getRedisService().getNetworkPlayerUuid(targetName);
            if (netUuid != null) {
                return CompletableFuture.completedFuture(netUuid);
            }
        }
        if (plugin.getDatabaseManager() != null) {
            return plugin.getDatabaseManager().getUuidByUsername(targetName).thenCompose(dbUuid -> {
                if (dbUuid != null) {
                    return CompletableFuture.completedFuture(dbUuid);
                }
                try {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
                    if (offline.hasPlayedBefore() || offline.isOnline() || offline.getName() != null) {
                        return CompletableFuture.completedFuture(offline.getUniqueId());
                    }
                } catch (Throwable ignored) {}
                return CompletableFuture.completedFuture(null);
            });
        }
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            if (offline.hasPlayedBefore() || offline.isOnline() || offline.getName() != null) {
                return CompletableFuture.completedFuture(offline.getUniqueId());
            }
        } catch (Throwable ignored) {}
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("friendlycore.pets.admin") && !sender.hasPermission("ecopets.command.ecopets")) {
            sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.no-permission", "&cYou don't have permission to do this!")));
            return true;
        }

        if (args.length == 0) {
            Messages.send(plugin, sender, "pets", "ecopets-commands");
            Messages.send(plugin, sender, "pets", "ecopets-reload-reload-pets-and-configs");
            Messages.send(plugin, sender, "pets", "ecopets-give-unlock-a-pet-for");
            Messages.send(plugin, sender, "pets", "ecopets-take-revoke-a-pet-from");
            Messages.send(plugin, sender, "pets", "ecopets-giveegg-amount-give-spawn-egg");
            Messages.send(plugin, sender, "pets", "ecopets-activate-force-activate-a-pet");
            Messages.send(plugin, sender, "pets", "ecopets-deactivate-force-deactivate-a-player");
            Messages.send(plugin, sender, "pets", "ecopets-migrate-import-configs-from-old");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload": {
                long start = System.currentTimeMillis();
                petManager.reload();
                petManager.broadcastReload();
                long took = System.currentTimeMillis() - start;
                String msg = petManager.getMessages().getString("messages.reloaded", "Reloaded in %time%ms! Loaded %count% pets.")
                        .replace("%time%", String.valueOf(took))
                        .replace("%count%", String.valueOf(petManager.getAllPets().size()));
                sender.sendMessage(plugin.getTextService().toComponent(msg));
                break;
            }
            case "give": {
                if (args.length < 3) {
                    Messages.send(plugin, sender, "pets", "usage-ecopets-give");
                    return true;
                }
                String targetName = args[1];
                Pet pet = petManager.getPet(args[2]);
                if (pet == null) {
                    sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-pet", "&cInvalid pet!")));
                    return true;
                }
                resolveTargetUuid(targetName).thenAccept(uuid -> {
                    if (uuid == null) {
                        sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-player", "&cInvalid player!")));
                        return;
                    }
                    report(sender, petManager.unlockPet(uuid, pet.getId(), true), "messages.gave-pet",
                            "&fSuccessfully gave %player%&f the %pet%&f pet!", "messages.already-has-pet",
                            "&cPlayer already owns this pet!", targetName, pet);
                });
                break;
            }
            case "take": {
                if (args.length < 3) {
                    Messages.send(plugin, sender, "pets", "usage-ecopets-take");
                    return true;
                }
                String targetName = args[1];
                Pet pet = petManager.getPet(args[2]);
                if (pet == null) {
                    sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-pet", "&cInvalid pet!")));
                    return true;
                }
                resolveTargetUuid(targetName).thenAccept(uuid -> {
                    if (uuid == null) {
                        sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-player", "&cInvalid player!")));
                        return;
                    }
                    report(sender, petManager.lockPet(uuid, pet.getId(), true), "messages.took-pet",
                            "&fSuccessfully took %player%&f's %pet%&f pet!", "messages.doesnt-have-pet",
                            "&cPlayer does not own this pet!", targetName, pet);
                });
                break;
            }
            case "giveegg": {
                if (args.length < 3) {
                    Messages.send(plugin, sender, "pets", "usage-ecopets-giveegg-amount");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-player", "&cInvalid player!")));
                    return true;
                }
                Pet pet = petManager.getPet(args[2]);
                if (pet == null) {
                    sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-pet", "&cInvalid pet!")));
                    return true;
                }
                int amount = 1;
                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Integer.parseInt(args[3]));
                    } catch (NumberFormatException ignored) {}
                }
                ItemStack egg = pet.createSpawnEgg();
                if (egg == null) {
                    Messages.send(plugin, sender, "pets", "this-pet-has-spawn-eggs-disabled");
                    return true;
                }
                egg.setAmount(amount);
                final ItemStack finalEgg = egg;
                plugin.getScheduler().runEntity(target, () -> {
                    Map<Integer, ItemStack> overflow = target.getInventory().addItem(finalEgg);
                    for (ItemStack rem : overflow.values()) {
                        target.getWorld().dropItemNaturally(target.getLocation(), rem);
                    }
                });
                String msg = petManager.getMessages().getString("messages.gave-pet-egg", "&fSuccessfully gave %player%&f the %pet%&f pet egg!")
                        .replace("%player%", target.getName())
                        .replace("%pet%", pet.getName());
                sender.sendMessage(plugin.getTextService().toComponent(msg));
                break;
            }
            case "activate": {
                if (args.length < 3) {
                    Messages.send(plugin, sender, "pets", "usage-ecopets-activate");
                    return true;
                }
                String targetName = args[1];
                Pet pet = petManager.getPet(args[2]);
                if (pet == null) {
                    sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-pet", "&cInvalid pet!")));
                    return true;
                }
                Player target = Bukkit.getPlayer(targetName);
                if (target != null) {
                    petManager.unlockPet(target.getUniqueId(), pet.getId(), false);
                    plugin.getScheduler().runEntity(target, () -> {
                        boolean equipped = petManager.activatePet(target, pet, true);
                        Messages.send(plugin, sender, "pets", equipped ? "pet-equipped-for" : "pet-equip-failed",
                                "%pet_name_formatted%", pet.getName(), "%player%", target.getName());
                    });
                    return true;
                }

                resolveTargetUuid(targetName).thenAccept(uuid -> {
                    if (uuid == null) {
                        sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-player", "&cInvalid player!")));
                        return;
                    }
                    plugin.getDatabaseManager().loadPlayerPetData(uuid, targetName).thenCompose(data -> {
                        data.unlockPet(pet.getId());
                        if (!data.isActivePet(pet.getId()) && !data.equipPet(pet.getId())) {
                            Messages.send(plugin, sender, "pets", "both-pet-slots-are-full-deactivate");
                            return CompletableFuture.<Void>completedFuture(null);
                        }
                        return plugin.getDatabaseManager().savePlayerPetData(data).thenRun(() -> {
                            petManager.broadcastRedisPacket(CrossServerPacket.Action.PET_UNLOCK_SYNC, uuid, targetName, pet.getId());
                            petManager.broadcastRedisPacket(CrossServerPacket.Action.PET_ACTIVATE_SYNC, uuid, targetName, String.join(",", data.getActivePets()));
                            Messages.send(plugin, sender, "pets", "equipped-for", "%pet_name%", pet.getName(), "%player%", targetName);
                        });
                    }).exceptionally(error -> {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to equip offline player's pet", error);
                        Messages.send(plugin, sender, "pets", "could-not-save-this-pet-check");
                        return null;
                    });
                });
                break;
            }
            case "deactivate": {
                if (args.length < 2) {
                    Messages.send(plugin, sender, "pets", "usage-ecopets-deactivate");
                    return true;
                }
                String targetName = args[1];
                Player target = Bukkit.getPlayer(targetName);
                if (target != null) {
                    plugin.getScheduler().runEntity(target, () -> petManager.deactivatePet(target, true));
                    Messages.send(plugin, sender, "pets", "deactivated-active-pet-for", "%player%", target.getName());
                    return true;
                }

                resolveTargetUuid(targetName).thenAccept(uuid -> {
                    if (uuid == null) {
                        sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-player", "&cInvalid player!")));
                        return;
                    }
                    plugin.getDatabaseManager().setActivePet(uuid, null);
                    petManager.broadcastRedisPacket(CrossServerPacket.Action.PET_DEACTIVATE_SYNC, uuid, targetName, "");
                    Messages.send(plugin, sender, "pets", "deactivated-active-pet-for", "%player%", targetName);
                });
                break;
            }
            case "migrate": {
                File customFile = args.length >= 2 ? new File(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))) : null;
                migrator.migrateAll(sender, customFile);
                break;
            }
            default: {
                sender.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-command", "&cUnknown subcommand!")));
                break;
            }
        }

        return true;
    }

    /** Reports what the database did, not what the cache assumed. */
    private void report(CommandSender sender, CompletableFuture<Boolean> write, String changedKey, String changedFallback,
                        String unchangedKey, String unchangedFallback, String targetName, Pet pet) {
        write.whenComplete((changed, error) -> {
            if (error != null) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to update " + targetName + "'s pets", error);
                Messages.send(plugin, sender, "pets", "could-not-save-this-pet-check");
                return;
            }
            String message = changed ? petManager.getMessages().getString(changedKey, changedFallback)
                    : petManager.getMessages().getString(unchangedKey, unchangedFallback);
            sender.sendMessage(plugin.getTextService().toComponent(message
                    .replace("%player%", targetName).replace("%pet%", pet.getName())));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("friendlycore.pets.admin") && !sender.hasPermission("ecopets.command.ecopets")) {
            return completions;
        }

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("reload", "give", "take", "giveegg", "activate", "deactivate", "migrate"), completions);
            return completions;
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take") || args[0].equalsIgnoreCase("giveegg")
                    || args[0].equalsIgnoreCase("activate") || args[0].equalsIgnoreCase("deactivate")) {
                if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                    StringUtil.copyPartialMatches(args[1], plugin.getRedisService().getOnlinePlayerNames(), completions);
                } else {
                    StringUtil.copyPartialMatches(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), completions);
                }
                return completions;
            }
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take") || args[0].equalsIgnoreCase("giveegg") || args[0].equalsIgnoreCase("activate")) {
                StringUtil.copyPartialMatches(args[2], petManager.getAllPets().stream().map(Pet::getId).toList(), completions);
                return completions;
            }
        }

        return completions;
    }
}
