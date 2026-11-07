package dev.lyy.friendlycore.modules.kits.gui;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.modules.kits.ClaimResult;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.KitItemParser;
import dev.lyy.friendlycore.modules.kits.model.KitAction;
import dev.lyy.friendlycore.modules.kits.model.KitItem;
import dev.lyy.friendlycore.modules.kits.model.KitRequirements;
import dev.lyy.friendlycore.modules.kits.model.PlayerKitData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Modern, high-performance Kit GUI system supporting customizable inventories,
 * live flicker-free cooldown countdowns, kit previews, and buy confirmation.
 */
public class KitGuiManager {

    private final FriendlyCore plugin;
    private final NamespacedKey ACTION_KEY;
    private final NamespacedKey KIT_KEY;
    private final NamespacedKey OPEN_INV_KEY;
    private final NamespacedKey BUY_KEY;

    private FileConfiguration guiConfig;
    private final Map<UUID, OpenGuiSession> openSessions = new ConcurrentHashMap<>();
    private final Set<String> loggedPreviewWarnings = ConcurrentHashMap.newKeySet();
    private Object timerTask;

    public KitGuiManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.ACTION_KEY = new NamespacedKey(plugin, "kit_gui_action");
        this.KIT_KEY = new NamespacedKey(plugin, "kit_gui_kit");
        this.OPEN_INV_KEY = new NamespacedKey(plugin, "kit_gui_open_inv");
        this.BUY_KEY = new NamespacedKey(plugin, "kit_gui_buy");
    }

    public void initialize() {
        loadConfig();

        this.timerTask = plugin.getScheduler().runGlobalTimer(this::updateLiveCooldowns, 20L, 20L);
    }

    public void shutdown() {
        if (timerTask != null) {
            plugin.getScheduler().cancel(timerTask);
            timerTask = null;
        }
        openSessions.clear();
    }

    public void closeSession(UUID uuid) {
        if (uuid != null) {
            openSessions.remove(uuid);
        }
    }

    public void loadConfig() {
        this.guiConfig = plugin.getConfigService().get("modules/kits/inventories/kits.yml");
        loggedPreviewWarnings.clear();
    }

    public void openMainInventory(Player player) {
        openInventory(player, "main_inventory", null);
    }

    public void openInventory(Player player, String inventoryName, String previousInventory) {
        if (player == null || !player.isOnline()) return;

        ConfigurationSection invSec = guiConfig.getConfigurationSection("inventories." + inventoryName);
        if (invSec == null) {
            // Default fallback 54-slot menu if config section is missing
            openDefaultKitMenu(player);
            return;
        }

        int slots = invSec.getInt("slots", 54);
        String rawTitle = invSec.getString("title", "<gradient:#7BD88F:#B9FBC0><bold>📦 ᴋɪᴛѕ</bold></gradient>");
        Component title = plugin.getTextService().parse(rawTitle);

        KitGuiHolder holder = new KitGuiHolder(inventoryName, previousInventory, null);
        Inventory inv = Bukkit.createInventory(holder, slots, title);
        holder.setInventory(inv);

        for (String slotKey : invSec.getKeys(false)) {
            if (slotKey.equalsIgnoreCase("slots") || slotKey.equalsIgnoreCase("title")) continue;

            ConfigurationSection itemSec = invSec.getConfigurationSection(slotKey);
            if (itemSec == null) continue;

            List<Integer> targetSlots = parseSlots(slotKey);
            String type = itemSec.getString("type", "");
            String openInv = itemSec.getString("open_inventory", null);
            List<String> actions = itemSec.getStringList("actions");

            if (type.toLowerCase(Locale.ROOT).startsWith("kit: ")) {
                String kitName = type.substring(5).trim();
                Kit kit = plugin.getKitManager().getKit(kitName);
                if (kit != null) {
                    ItemStack kitItem = buildKitDisplayItem(player, kit);
                    for (int s : targetSlots) {
                        if (s >= 0 && s < slots) inv.setItem(s, kitItem);
                    }
                }
            } else if (itemSec.contains("item")) {
                KitItem ki = KitItemParser.loadKitItem(itemSec, "item");
                if (ki != null) {
                    ItemStack is = KitItemParser.toItemStack(ki, player, null);
                    if (is != null) {
                        ItemMeta meta = is.getItemMeta();
                        PersistentDataContainer pdc = meta.getPersistentDataContainer();
                        if (openInv != null) pdc.set(OPEN_INV_KEY, PersistentDataType.STRING, openInv);
                        if (!actions.isEmpty()) pdc.set(ACTION_KEY, PersistentDataType.STRING, String.join("|", actions));
                        is.setItemMeta(meta);

                        for (int s : targetSlots) {
                            if (s >= 0 && s < slots) inv.setItem(s, is);
                        }
                    }
                }
            }
        }

        plugin.getScheduler().runEntity(player, () -> {
            player.openInventory(inv);
            openSessions.put(player.getUniqueId(), new OpenGuiSession(inventoryName, previousInventory, null));
        });
    }

    public void openPreview(Player player, String kitName, String prevInv) {
        Kit kit = plugin.getKitManager().getKit(kitName);
        if (kit == null) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessages().getString("kits.kit-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜᴀᴛ ᴋɪᴛ ᴅᴏᴇѕ ɴᴏᴛ ᴇxɪѕᴛ."), Map.of("%kit%", kitName));
            return;
        }

        if (plugin.getKitManager().isKitPreviewRequiresPermission() && !kit.hasPermission(player)) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessages().getString("kits.cant-preview", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴘʀᴇᴠɪᴇᴡ ᴋɪᴛ <color:#E7B85C>%kit%</color>."), Map.of("%kit%", kitName));
            return;
        }

        int slots = guiConfig.getInt("inventories.preview_inventory.slots", 54);
        String rawTitle = guiConfig.getString("inventories.preview_inventory.title", "<gradient:#7BD88F:#B9FBC0><bold>👁 ᴘʀᴇᴠɪᴇᴡ</bold></gradient> <dark_gray>» <color:#E7B85C>%kit%</color>").replace("%kit%", kit.getName());
        Component title = plugin.getTextService().parse(rawTitle);

        KitGuiHolder holder = new KitGuiHolder("preview_inventory", prevInv != null ? prevInv : "main_inventory", kitName);
        Inventory inv = Bukkit.createInventory(holder, slots, title);
        holder.setInventory(inv);

        // Render first so invalid items do not reserve otherwise usable preview slots.
        List<ItemStack> previewItems = new ArrayList<>();
        List<Integer> requestedSlots = new ArrayList<>();
        for (KitItem ki : kit.getKitItems()) {
            ItemStack is = KitItemParser.toItemStack(ki, player, kit);
            if (is != null) {
                previewItems.add(is);
                requestedSlots.add(ki.getPreviewSlot());
            }
        }

        // Action display items share the same collision-free layout as kit items.
        for (KitAction action : kit.getClaimActions()) {
            if (action.getDisplayItem() != null) {
                ItemStack is = KitItemParser.toItemStack(action.getDisplayItem(), player, kit);
                if (is != null) {
                    previewItems.add(is);
                    requestedSlots.add(action.getDisplayItem().getPreviewSlot());
                }
            }
        }

        PreviewSlotPlan previewPlan = planPreviewSlots(requestedSlots, slots);
        for (int i = 0; i < previewItems.size(); i++) {
            int targetSlot = previewPlan.assignedSlots().get(i);
            if (targetSlot >= 0) {
                inv.setItem(targetSlot, previewItems.get(i));
            }
        }
        for (String warning : previewPlan.warnings()) {
            String warningKey = kit.getName().toLowerCase(Locale.ROOT) + '|' + warning;
            if (loggedPreviewWarnings.add(warningKey)) {
                plugin.getLogger().warning("Kit '" + kit.getName() + "' preview: " + warning);
            }
        }

        ItemStack backButton = new ItemBuilder(Material.ARROW)
                .name(Messages.component(plugin, "kits", "back"))
                .lore(List.of(Messages.component(plugin, "kits", "return-to-kit-menu")))
                .build();
        ItemMeta bm = backButton.getItemMeta();
        bm.getPersistentDataContainer().set(OPEN_INV_KEY, PersistentDataType.STRING, "previous");
        backButton.setItemMeta(bm);
        inv.setItem(slots - 9, backButton);

        plugin.getScheduler().runEntity(player, () -> {
            player.openInventory(inv);
            openSessions.put(player.getUniqueId(), new OpenGuiSession("preview_inventory", prevInv, kitName));
        });
    }

    /**
     * Allocates preview cells while reserving the entire bottom control row. Valid,
     * unique explicit slots win first; implicit and rejected slots then use the next
     * available content cell. A value of {@code -1} means the preview has no room.
     */
    static PreviewSlotPlan planPreviewSlots(List<Integer> requestedSlots, int inventorySlots) {
        Objects.requireNonNull(requestedSlots, "requestedSlots");

        int contentSlots = Math.max(0, inventorySlots - 9);
        List<Integer> assigned = new ArrayList<>(Collections.nCopies(requestedSlots.size(), -1));
        List<String> warnings = new ArrayList<>();
        BitSet occupied = new BitSet(contentSlots);

        // Reserve every valid explicit slot before filling implicit positions so an
        // earlier implicit item cannot displace a later explicitly positioned item.
        for (int i = 0; i < requestedSlots.size(); i++) {
            Integer requested = requestedSlots.get(i);
            if (requested == null || requested == -1) continue;

            if (requested < -1 || requested >= contentSlots) {
                warnings.add("item " + (i + 1) + " requested preview_slot " + requested
                        + " outside content slots 0-" + Math.max(-1, contentSlots - 1)
                        + "; using the next free slot");
            } else if (occupied.get(requested)) {
                warnings.add("item " + (i + 1) + " duplicates preview_slot " + requested
                        + "; using the next free slot");
            } else {
                assigned.set(i, requested);
                occupied.set(requested);
            }
        }

        int cursor = 0;
        for (int i = 0; i < assigned.size(); i++) {
            if (assigned.get(i) >= 0) continue;
            cursor = occupied.nextClearBit(cursor);
            if (cursor >= contentSlots) {
                warnings.add("item " + (i + 1) + " could not be previewed because all "
                        + contentSlots + " content slots are occupied");
                continue;
            }
            assigned.set(i, cursor);
            occupied.set(cursor);
            cursor++;
        }

        return new PreviewSlotPlan(List.copyOf(assigned), List.copyOf(warnings));
    }

    record PreviewSlotPlan(List<Integer> assignedSlots, List<String> warnings) {}

    public void openBuyConfirmation(Player player, String kitName, String prevInv) {
        Kit kit = plugin.getKitManager().getKit(kitName);
        if (kit == null || kit.getRequirements() == null) return;

        KitRequirements req = kit.getRequirements();
        int slots = guiConfig.getInt("inventories.buy_requirements_inventory.slots", 27);
        String rawTitle = guiConfig.getString("inventories.buy_requirements_inventory.title", "<gradient:#7BD88F:#B9FBC0><bold>✦ ᴜɴʟᴏᴄᴋ ᴋɪᴛ</bold></gradient> <dark_gray>» <color:#E7B85C>%kit%</color>").replace("%kit%", kit.getName());
        Component title = plugin.getTextService().parse(rawTitle);

        KitGuiHolder holder = new KitGuiHolder("buy_requirements_inventory", prevInv != null ? prevInv : "main_inventory", kitName);
        Inventory inv = Bukkit.createInventory(holder, slots, title);
        holder.setInventory(inv);

        List<Component> lore = new ArrayList<>();
        lore.add(Messages.component(plugin, "kits", "price", "%price%", String.valueOf(plugin.getNumberFormatter().format(req.getPrice()))));
        lore.add(Messages.component(plugin, "kits", "your-balance", "%player_balance%", String.valueOf(plugin.getNumberFormatter().format(plugin.getKitManager().getRequirementChecker().getPlayerBalance(player)))));
        if (!req.getMessage().isEmpty()) {
            lore.add(Component.empty());
            for (String m : req.getMessage()) {
                lore.add(plugin.getTextService().parse(m));
            }
        }

        ItemStack info = new ItemBuilder(Material.BOOK)
                .name(Messages.component(plugin, "kits", "requirements-for", "%kit%", kit.getName()))
                .lore(lore)
                .build();
        inv.setItem(13, info);

        ItemStack yes = new ItemBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .name(Messages.component(plugin, "kits", "confirm-purchase"))
                .lore(List.of(Messages.component(plugin, "kits", "click-to-unlock-kit")))
                .build();
        ItemMeta ym = yes.getItemMeta();
        ym.getPersistentDataContainer().set(BUY_KEY, PersistentDataType.STRING, "yes");
        ym.getPersistentDataContainer().set(KIT_KEY, PersistentDataType.STRING, kit.getName());
        yes.setItemMeta(ym);
        inv.setItem(15, yes);

        ItemStack no = new ItemBuilder(Material.RED_STAINED_GLASS_PANE)
                .name(Messages.component(plugin, "kits", "cancel"))
                .lore(List.of(Messages.component(plugin, "kits", "click-to-cancel-and-return")))
                .build();
        ItemMeta nm = no.getItemMeta();
        nm.getPersistentDataContainer().set(BUY_KEY, PersistentDataType.STRING, "no");
        no.setItemMeta(nm);
        inv.setItem(11, no);

        plugin.getScheduler().runEntity(player, () -> {
            player.openInventory(inv);
            openSessions.put(player.getUniqueId(), new OpenGuiSession("buy_requirements_inventory", prevInv, kitName));
        });
    }

    private void openDefaultKitMenu(Player player) {
        Component title = Messages.component(plugin, "kits", "kits");
        KitGuiHolder holder = new KitGuiHolder("main_inventory", null, null);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        int slot = 10;
        for (Kit kit : plugin.getKitManager().getAllKits()) {
            if (slot >= 44) break;
            if (slot % 9 == 8) slot += 2;

            ItemStack kitItem = buildKitDisplayItem(player, kit);
            inv.setItem(slot, kitItem);
            slot++;
        }

        plugin.getScheduler().runEntity(player, () -> {
            player.openInventory(inv);
            openSessions.put(player.getUniqueId(), new OpenGuiSession("main_inventory", null, null));
        });
    }

    public ItemStack buildKitDisplayItem(Player player, Kit kit) {
        PlayerKitData data = plugin.getKitManager().getPlayerData(player.getUniqueId());
        long now = System.currentTimeMillis();

        KitItem display = null;
        String status = "ready";

        if (!kit.hasPermission(player)) {
            display = kit.getDisplayItemNoPermission();
            status = "no_permission";
        } else if (kit.isOneTime() && data != null && data.isOneTimeClaimed(kit.getName()) && !plugin.getKitManager().hasOneTimeBypass(player, kit)) {
            display = kit.getDisplayItemOneTime();
            status = "one_time";
        } else if (kit.getRequirements() != null && kit.getRequirements().isOneTimeRequirements() && data != null && data.isBought(kit.getName())) {
            display = kit.getDisplayItemOneTimeRequirements();
            status = "bought";
        } else if (kit.getCooldownSeconds() > 0 && data != null && data.getCooldownUntil(kit.getName()) > now && !plugin.getKitManager().hasCooldownBypass(player, kit)) {
            display = kit.getDisplayItemCooldown();
            status = "cooldown";
        }

        if (display == null) {
            display = kit.getDisplayItemDefault();
            if (display == null) {
                display = new KitItem(kit.getKitItems().isEmpty() ? "CHEST" : kit.getKitItems().get(0).getId());
                display.setName("<gradient:#7BD88F:#B9FBC0><bold>" + kit.getName() + " Kit</bold></gradient>");
                display.setLore(List.of("<color:#D8D0C3>ɪᴛᴇᴍѕ <dark_gray>» <color:#6EDBFF>" + kit.getKitItems().size() + " ɪᴛᴇᴍѕ</color></color>", "", "<color:#8DE969>Left-Click to claim</color>", "<color:#6EDBFF>Right-Click to preview</color>"));
            }
        }

        ItemStack is = KitItemParser.toItemStack(display, player, kit);
        if (is == null) is = new ItemStack(Material.CHEST);

        ItemMeta meta = is.getItemMeta();
        meta.getPersistentDataContainer().set(KIT_KEY, PersistentDataType.STRING, kit.getName());
        is.setItemMeta(meta);

        return is;
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof KitGuiHolder holder)) return;

        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (pdc.has(KIT_KEY, PersistentDataType.STRING)) {
            String kitName = pdc.get(KIT_KEY, PersistentDataType.STRING);
            Kit kit = plugin.getKitManager().getKit(kitName);
            if (kit == null) return;

            if (event.getClick() == ClickType.RIGHT) {
                // Right Click -> Preview
                if (plugin.getKitManager().isKitPreviewEnabled()) {
                    openPreview(player, kitName, holder.getInventoryName());
                }
                return;
            }

            // Left Click -> Claim / Buy
            CompletableFuture<ClaimResult> claim = plugin.getKitManager()
                    .claimKitAsync(player, kit, false, true);
            handleClaimCompletion(player, kit, claim,
                    () -> {
                        if (!closesInventoryOnClaim()) {
                            openInventory(player, holder.getInventoryName(), holder.getPreviousInventory());
                        }
                    },
                    () -> openBuyConfirmation(player, kitName, holder.getInventoryName()));
            return;
        }

        if (pdc.has(BUY_KEY, PersistentDataType.STRING)) {
            String buyAction = pdc.get(BUY_KEY, PersistentDataType.STRING);
            if (buyAction.equalsIgnoreCase("yes")) {
                String kitName = pdc.get(KIT_KEY, PersistentDataType.STRING);
                Kit kit = plugin.getKitManager().getKit(kitName);
                if (kit != null) {
                    String returnInventory = holder.getPreviousInventory() != null
                            ? holder.getPreviousInventory() : "main_inventory";
                    CompletableFuture<ClaimResult> claim = plugin.getKitManager()
                            .claimKitAsync(player, kit, false, false);
                    handleClaimCompletion(player, kit, claim,
                            () -> {
                                if (!closesInventoryOnClaim()) {
                                    openInventory(player, returnInventory, null);
                                }
                            },
                            () -> openBuyConfirmation(player, kitName, returnInventory));
                }
                return;
            }
            openInventory(player, holder.getPreviousInventory() != null ? holder.getPreviousInventory() : "main_inventory", null);
            return;
        }

        if (pdc.has(OPEN_INV_KEY, PersistentDataType.STRING)) {
            String openInv = pdc.get(OPEN_INV_KEY, PersistentDataType.STRING);
            if (openInv.equalsIgnoreCase("previous")) {
                openInventory(player, holder.getPreviousInventory() != null ? holder.getPreviousInventory() : "main_inventory", null);
            } else {
                openInventory(player, openInv, holder.getInventoryName());
            }
            return;
        }

        if (pdc.has(ACTION_KEY, PersistentDataType.STRING)) {
            String actionStr = pdc.get(ACTION_KEY, PersistentDataType.STRING);
            String[] acts = actionStr.split("\\|");
            for (String act : acts) {
                plugin.getKitManager().getActionExecutor().executeAction(player, act, null);
            }
        }
    }

    private void handleClaimCompletion(Player player, Kit kit, CompletableFuture<ClaimResult> claim,
                                       Runnable onSuccess, Runnable onProceedToBuy) {
        claim.whenComplete((result, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to complete GUI kit claim '" + kit.getName() + "' for " + player.getName(),
                        failure);
            }

            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) return;
                if (failure != null || result == null) {
                    plugin.getTextService().sendMessage(player,
                            plugin.getConfigService().getMessages().getString("kits.claim-failed",
                                    "<red>Kit claim failed. Please try again."),
                            Map.of("%kit%", kit.getName()));
                } else if (result.proceedToBuy()) {
                    onProceedToBuy.run();
                } else if (!result.isSuccess()) {
                    if (result.message() != null) {
                        plugin.getTextService().sendMessage(player, result.message(),
                                Map.of("%kit%", kit.getName()));
                    }
                } else {
                    onSuccess.run();
                }
            });
        });
    }

    private boolean closesInventoryOnClaim() {
        return plugin.getConfigService().getKitsConfig().getBoolean("settings.close-inventory-on-claim",
                plugin.getConfig().getBoolean("kits.close-inventory-on-claim", false));
    }

    private void updateLiveCooldowns() {
        if (openSessions.isEmpty()) return;

        for (Map.Entry<UUID, OpenGuiSession> entry : openSessions.entrySet()) {
            UUID uuid = entry.getKey();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                openSessions.remove(uuid);
                continue;
            }

            // Strictly dispatch player inventory mutation to their own Folia Region/Entity thread
            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) {
                    openSessions.remove(uuid);
                    return;
                }

                if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof KitGuiHolder holder)) {
                    openSessions.remove(uuid);
                    return;
                }

                if (holder.getInventoryName().equalsIgnoreCase("preview_inventory") || holder.getInventoryName().equalsIgnoreCase("buy_requirements_inventory")) {
                    return;
                }

                Inventory top = player.getOpenInventory().getTopInventory();
                ItemStack[] contents = top.getContents();
                for (int i = 0; i < contents.length; i++) {
                    ItemStack item = contents[i];
                    if (item == null || item.getType().isAir()) continue;

                    ItemMeta meta = item.getItemMeta();
                    if (meta == null) continue;

                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    if (pdc.has(KIT_KEY, PersistentDataType.STRING)) {
                        String kitName = pdc.get(KIT_KEY, PersistentDataType.STRING);
                        Kit kit = plugin.getKitManager().getKit(kitName);
                        if (kit != null && kit.getCooldownSeconds() > 0) {
                            PlayerKitData pdata = plugin.getKitManager().getPlayerData(player.getUniqueId());
                            long cooldownUntil = pdata != null ? pdata.getCooldownUntil(kit.getName()) : 0L;
                            long now = System.currentTimeMillis();

                            if (cooldownUntil > now) {
                                long remainingSec = (cooldownUntil - now) / 1000L;
                                String formatted = plugin.getKitManager().formatCooldown(remainingSec);

                                KitItem cdDisplay = kit.getDisplayItemCooldown();
                                if (cdDisplay != null && cdDisplay.getLore() != null) {
                                    List<Component> updatedLore = new ArrayList<>();
                                    for (String l : cdDisplay.getLore()) {
                                        String line = l.replace("%time%", formatted).replace("%kit%", kit.getName());
                                        updatedLore.add(plugin.getTextService().parse(line));
                                    }
                                    meta.lore(updatedLore);
                                    item.setItemMeta(meta);
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    private List<Integer> parseSlots(String rawSlots) {
        List<Integer> list = new ArrayList<>();
        String[] parts = rawSlots.split(";");
        for (String p : parts) {
            p = p.trim();
            if (p.contains("-")) {
                String[] range = p.split("-");
                int start = Integer.parseInt(range[0].trim());
                int end = Integer.parseInt(range[1].trim());
                for (int s = start; s <= end; s++) list.add(s);
            } else {
                try {
                    list.add(Integer.parseInt(p));
                } catch (NumberFormatException ignored) {}
            }
        }
        return list;
    }

    public static class KitGuiHolder implements InventoryHolder {
        private final String inventoryName;
        private final String previousInventory;
        private final String kitName;
        private Inventory inventory;

        public KitGuiHolder(String inventoryName, String previousInventory, String kitName) {
            this.inventoryName = inventoryName;
            this.previousInventory = previousInventory;
            this.kitName = kitName;
        }

        public String getInventoryName() {
            return inventoryName;
        }

        public String getPreviousInventory() {
            return previousInventory;
        }

        public String getKitName() {
            return kitName;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record OpenGuiSession(String inventoryName, String previousInventory, String kitName) {}
}
