package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Command executor for clearing inventories (/clear, /ci, /clearinventory).
 * Supports clearing personal inventory, specific targets, wildcard all (*), material filters, and equipped armor.
 */
public class ClearInventoryCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    private static final List<String> COMMON_AMOUNTS = List.of("1", "16", "32", "64", "all");
    private static final List<String> MATERIAL_NAMES;

    static {
        List<String> materials = new ArrayList<>();
        for (Material mat : Material.values()) {
            if (mat != Material.AIR && mat != Material.CAVE_AIR && mat != Material.VOID_AIR && !mat.name().startsWith("LEGACY_")) {
                materials.add(mat.name().toLowerCase(Locale.ROOT));
            }
        }
        Collections.sort(materials);
        MATERIAL_NAMES = Collections.unmodifiableList(materials);
    }

    public ClearInventoryCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Clear own inventory when no arguments provided
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "usage-item-armor-amount", "%label%", String.valueOf(label));
                return true;
            }
            if (!hasSelfPermission(player)) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }

            handleSelfClear(player, label, false, false);
            return true;
        }

        String firstArg = args[0];

        if (firstArg.equalsIgnoreCase("confirm") && sender instanceof Player player) {
            if (!hasSelfPermission(player)) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }
            if (essentialsManager.isPendingConfirmation(player.getUniqueId())) {
                essentialsManager.clearPendingConfirmation(player.getUniqueId());
                executeSelfClear(player, null, -1, essentialsManager.isClearArmorByDefault(), false);
            } else {
                handleSelfClear(player, label, false, false);
            }
            return true;
        }

        // Handle self clear with equipment flags (** or armor)
        if (firstArg.equalsIgnoreCase("**") || firstArg.equalsIgnoreCase("armor")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "usage-item-armor-amount", "%label%", String.valueOf(label));
                return true;
            }
            if (!hasSelfPermission(player)) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }

            boolean armorOnly = firstArg.equalsIgnoreCase("armor");
            if (armorOnly && !hasArmorPermission(player)) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }

            handleSelfClear(player, label, true, armorOnly);
            return true;
        }

        // Wildcard clear for all online players (* or all)
        if (firstArg.equals("*") || firstArg.equalsIgnoreCase("all")) {
            if (!hasAllPermission(sender)) {
                essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
                return true;
            }

            Material filter = null;
            int amount = -1;
            boolean includeArmor = essentialsManager.isClearArmorByDefault();
            boolean armorOnly = false;

            if (args.length > 1) {
                String secondArg = args[1];
                if (secondArg.equalsIgnoreCase("**")) {
                    includeArmor = true;
                } else if (secondArg.equalsIgnoreCase("armor")) {
                    armorOnly = true;
                } else {
                    filter = parseMaterial(secondArg);
                    if (filter == null) {
                        essentialsManager.sendMessage(sender, "clear.invalid-item", Map.of("%item%", secondArg));
                        return true;
                    }
                    if (args.length > 2) {
                        Integer amt = parseAmount(args[2]);
                        if (amt == null) {
                            essentialsManager.sendMessage(sender, "clear.invalid-amount", Map.of("%amount%", args[2]));
                            return true;
                        }
                        amount = amt;
                    }
                }
            }

            clearAllPlayers(sender, filter, amount, includeArmor, armorOnly);
            return true;
        }

        // Clear specific material from own inventory (e.g. /clear dirt or /clear stone 64)
        Material directSelfMat = parseMaterial(firstArg);
        if (directSelfMat != null && sender instanceof Player player && (!hasOthersPermission(sender) || Bukkit.getPlayerExact(firstArg) == null)) {
            if (!hasSelfPermission(player)) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }

            int amount = -1;
            if (args.length > 1) {
                Integer amt = parseAmount(args[1]);
                if (amt == null) {
                    essentialsManager.sendMessage(player, "clear.invalid-amount", Map.of("%amount%", args[1]));
                    return true;
                }
                amount = amt;
            }

            executeSelfClear(player, directSelfMat, amount, false, false);
            return true;
        }

        if (!hasOthersPermission(sender)) {
            essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
            return true;
        }

        String targetName = firstArg;
        Material filter = null;
        int amount = -1;
        boolean includeArmor = essentialsManager.isClearArmorByDefault();
        boolean armorOnly = false;

        if (args.length > 1) {
            String secondArg = args[1];
            if (secondArg.equalsIgnoreCase("**")) {
                includeArmor = true;
            } else if (secondArg.equalsIgnoreCase("armor")) {
                armorOnly = true;
            } else {
                filter = parseMaterial(secondArg);
                if (filter == null) {
                    essentialsManager.sendMessage(sender, "clear.invalid-item", Map.of("%item%", secondArg));
                    return true;
                }
                if (args.length > 2) {
                    Integer amt = parseAmount(args[2]);
                    if (amt == null) {
                        essentialsManager.sendMessage(sender, "clear.invalid-amount", Map.of("%amount%", args[2]));
                        return true;
                    }
                    amount = amt;
                }
            }
        }

        clearTargetPlayer(sender, targetName, filter, amount, includeArmor, armorOnly);
        return true;
    }

    private void handleSelfClear(Player player, String label, boolean includeArmor, boolean armorOnly) {
        if (essentialsManager.isConfirmSelfClearEnabled() && !player.hasPermission("friendlycore.clearinventory.confirm.bypass")
                && !player.hasPermission("essentials.clearinventory.confirm.bypass")) {
            if (!essentialsManager.isPendingConfirmation(player.getUniqueId())) {
                essentialsManager.setPendingConfirmation(player.getUniqueId());
                essentialsManager.sendMessage(player, "clear.confirm-prompt", Map.of("%label%", label));
                return;
            }
            essentialsManager.clearPendingConfirmation(player.getUniqueId());
        }

        executeSelfClear(player, null, -1, includeArmor, armorOnly);
    }

    private void executeSelfClear(Player player, Material filter, int amount, boolean includeArmor, boolean armorOnly) {
        plugin.getScheduler().runEntity(player, () -> {
            if (!player.isOnline()) return;
            int removed = essentialsManager.clearInventory(player, filter, amount, includeArmor, armorOnly);

            if (filter != null) {
                if (removed > 0) {
                    essentialsManager.sendMessage(player, "clear.cleared-item-self",
                            Map.of("%amount%", String.valueOf(removed), "%item%", filter.name().toLowerCase(Locale.ROOT)));
                    TextService.playSound(player, "entity.item.break", 1.0f, 1.2f);
                } else {
                    essentialsManager.sendMessage(player, "clear.no-items-found", Map.of());
                }
            } else {
                if (includeArmor) {
                    essentialsManager.sendMessage(player, "clear.cleared-self-armor", Map.of("%count%", String.valueOf(removed)));
                } else {
                    essentialsManager.sendMessage(player, "clear.cleared-self", Map.of("%count%", String.valueOf(removed)));
                }
                TextService.playSound(player, "entity.item.break", 1.0f, 1.2f);
            }
        });
    }

    private void clearTargetPlayer(CommandSender sender, String targetName, Material filter, int amount, boolean includeArmor, boolean armorOnly) {
        // Exact match: a prefix match would clear "Bobby" when "Bob" is offline.
        Player localTarget = Bukkit.getPlayerExact(targetName);
        if (localTarget != null && localTarget.isOnline()) {
            if (isExempt(localTarget, sender)) {
                essentialsManager.sendMessage(sender, "clear.exempt", Map.of("%player%", localTarget.getName()));
                return;
            }

            plugin.getScheduler().runEntity(localTarget, () -> {
                if (!localTarget.isOnline()) {
                    essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", targetName));
                    return;
                }

                int removed = essentialsManager.clearInventory(localTarget, filter, amount, includeArmor, armorOnly);

                if (filter != null) {
                    if (removed > 0) {
                        essentialsManager.sendMessage(localTarget, "clear.cleared-item-self",
                                Map.of("%amount%", String.valueOf(removed), "%item%", filter.name().toLowerCase(Locale.ROOT)));
                        essentialsManager.sendMessage(sender, "clear.cleared-item-other",
                                Map.of("%player%", localTarget.getName(), "%amount%", String.valueOf(removed), "%item%", filter.name().toLowerCase(Locale.ROOT)));
                        TextService.playSound(localTarget, "entity.item.break", 1.0f, 1.2f);
                    } else {
                        essentialsManager.sendMessage(sender, "clear.no-items-found", Map.of());
                    }
                } else {
                    if (includeArmor) {
                        essentialsManager.sendMessage(localTarget, "clear.cleared-self-armor", Map.of("%count%", String.valueOf(removed)));
                    } else {
                        essentialsManager.sendMessage(localTarget, "clear.cleared-self", Map.of("%count%", String.valueOf(removed)));
                    }
                    essentialsManager.sendMessage(sender, "clear.cleared-other",
                            Map.of("%player%", localTarget.getName(), "%count%", String.valueOf(removed)));
                    TextService.playSound(localTarget, "entity.item.break", 1.0f, 1.2f);
                }
            });
            return;
        }

        String networkName = essentialsManager.getCrossServerService().findNetworkPlayer(targetName);
        if (networkName != null) {
            UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(networkName);
            String filterName = filter != null ? filter.name() : null;
            essentialsManager.getCrossServerService().dispatchClearInventory(targetUuid, networkName, filterName, amount, includeArmor, armorOnly, canClearExempt(sender));

            if (filter != null) {
                essentialsManager.sendMessage(sender, "clear.cleared-item-other-network",
                        Map.of("%player%", networkName, "%amount%", String.valueOf(amount > 0 ? amount : 1), "%item%", filter.name().toLowerCase(Locale.ROOT)));
            } else {
                essentialsManager.sendMessage(sender, "clear.cleared-other-network", Map.of("%player%", networkName));
            }
            return;
        }

        essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", targetName));
    }

    private void clearAllPlayers(CommandSender sender, Material filter, int amount, boolean includeArmor, boolean armorOnly) {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        int targetCount = 0;

        for (Player p : players) {
            if (isExempt(p, sender)) continue;
            targetCount++;
            plugin.getScheduler().runEntity(p, () -> {
                if (!p.isOnline()) return;
                int removed = essentialsManager.clearInventory(p, filter, amount, includeArmor, armorOnly);
                if (removed > 0) {
                    if (filter != null) {
                        essentialsManager.sendMessage(p, "clear.cleared-item-self",
                                Map.of("%amount%", String.valueOf(removed), "%item%", filter.name().toLowerCase(Locale.ROOT)));
                    } else if (includeArmor) {
                        essentialsManager.sendMessage(p, "clear.cleared-self-armor", Map.of("%count%", String.valueOf(removed)));
                    } else {
                        essentialsManager.sendMessage(p, "clear.cleared-self", Map.of("%count%", String.valueOf(removed)));
                    }
                    TextService.playSound(p, "entity.item.break", 1.0f, 1.2f);
                }
            });
        }

        essentialsManager.sendMessage(sender, "clear.cleared-all", Map.of("%count%", String.valueOf(targetCount)));
    }

    private boolean isExempt(Player target, CommandSender sender) {
        if (target == null) return false;
        if (sender instanceof Player p && p.equals(target)) return false;
        if (canClearExempt(sender)) return false;
        return target.hasPermission("friendlycore.clearinventory.bypass") || target.hasPermission("essentials.clearinventory.bypass");
    }

    private static boolean canClearExempt(CommandSender sender) {
        return !(sender instanceof Player) || sender.isOp() || sender.hasPermission("friendlycore.clearinventory.bypass.exempt");
    }

    private boolean hasSelfPermission(CommandSender sender) {
        return sender.hasPermission("friendlycore.clearinventory")
                || sender.hasPermission("friendlycore.clear")
                || sender.hasPermission("essentials.clearinventory")
                || sender.hasPermission("friendlycore.admin");
    }

    private boolean hasOthersPermission(CommandSender sender) {
        return sender.hasPermission("friendlycore.clearinventory.others")
                || sender.hasPermission("friendlycore.clear.others")
                || sender.hasPermission("essentials.clearinventory.others")
                || sender.hasPermission("friendlycore.admin");
    }

    private boolean hasAllPermission(CommandSender sender) {
        return sender.hasPermission("friendlycore.clearinventory.all")
                || sender.hasPermission("friendlycore.clear.all")
                || sender.hasPermission("essentials.clearinventory.all")
                || sender.hasPermission("essentials.clearinventory.multiple")
                || sender.hasPermission("friendlycore.admin");
    }

    private boolean hasArmorPermission(CommandSender sender) {
        return sender.hasPermission("friendlycore.clearinventory.armor")
                || sender.hasPermission("friendlycore.clear.armor")
                || sender.hasPermission("friendlycore.admin");
    }

    public static Material parseMaterial(String input) {
        if (input == null || input.isBlank()) return null;
        String clean = input.toLowerCase(Locale.ROOT).trim();
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }
        Material mat = Material.matchMaterial(clean);
        if (mat != null && mat != Material.AIR && mat != Material.CAVE_AIR && mat != Material.VOID_AIR && !mat.name().startsWith("LEGACY_")) {
            return mat;
        }
        return null;
    }

    public static Integer parseAmount(String input) {
        if (input == null || input.isBlank()) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.equals("*") || s.equals("all") || s.equals("-1")) {
            return -1;
        }
        try {
            int val = Integer.parseInt(s);
            return val > 0 ? val : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (hasOthersPermission(sender)) {
                options.addAll(plugin.getRedisService() != null ? plugin.getRedisService().getOnlinePlayerNames() : getLocalPlayerNames());
            }
            if (hasAllPermission(sender)) {
                options.add("*");
            }
            options.add("**");
            options.add("armor");
            options.addAll(MATERIAL_NAMES);
            return filterList(options, args[0]);
        } else if (args.length == 2) {
            String first = args[0];
            if (first.equals("*") || first.equalsIgnoreCase("all") || Bukkit.getPlayerExact(first) != null
                    || (plugin.getRedisService() != null && plugin.getRedisService().getNetworkOnlinePlayers().contains(first))) {
                List<String> options = new ArrayList<>();
                options.add("**");
                options.add("armor");
                options.addAll(MATERIAL_NAMES);
                return filterList(options, args[1]);
            } else if (parseMaterial(first) != null) {
                return filterList(COMMON_AMOUNTS, args[1]);
            }
        } else if (args.length == 3) {
            String second = args[1];
            if (parseMaterial(second) != null) {
                return filterList(COMMON_AMOUNTS, args[2]);
            }
        }

        return Collections.emptyList();
    }

    private Set<String> getLocalPlayerNames() {
        Set<String> set = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            set.add(p.getName());
        }
        return set;
    }

    private List<String> filterList(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> res = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) {
                res.add(s);
            }
        }
        return res;
    }
}
