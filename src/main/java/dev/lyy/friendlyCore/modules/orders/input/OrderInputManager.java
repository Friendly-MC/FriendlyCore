package dev.lyy.friendlycore.modules.orders.input;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.math.Position;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.time.Duration;

/**
 * Multi-modal input manager for Orders: Native Dialogs (1.21.6+), client virtual signs,
 * anvil input, and chat fallback.
 */
public class OrderInputManager implements Listener {

    public enum InputMode {
        AUTO,
        DIALOG,
        SIGN,
        ANVIL,
        CHAT
    }

    public record SignSession(UUID playerUuid, Location location, Consumer<String> callback, Runnable onCancel, Object timeoutTask) {}
    public record AnvilSession(AnvilView view, Consumer<String> callback, Runnable onCancel) {}
    public record ChatSession(Consumer<String> callback, Runnable onCancel) {}
    private record TextSession(UUID token, Object timeoutTask) {}

    private static final boolean DIALOG_SUPPORTED;

    static {
        boolean supported = false;
        try {
            Class.forName("io.papermc.paper.dialog.Dialog", false, OrderInputManager.class.getClassLoader());
            supported = true;
        } catch (Throwable ignored) {
            supported = false;
        }
        DIALOG_SUPPORTED = supported;
    }

    private final FriendlyCore plugin;
    private final Map<UUID, SignSession> activeSignSessions = new ConcurrentHashMap<>();
    private final Map<UUID, AnvilSession> anvils = new ConcurrentHashMap<>();
    private final Map<UUID, ChatSession> pendingChatInputs = new ConcurrentHashMap<>();
    private final Map<UUID, TextSession> textInputs = new ConcurrentHashMap<>();

    public OrderInputManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void openTextInput(Player player, String title, String label, String initial, int maxLength,
                              Consumer<String> onSuccess, Runnable onCancel) {
        if (player == null || !player.isOnline()) return;
        cleanupPlayer(player.getUniqueId());
        player.closeInventory();
        UUID token = UUID.randomUUID();
        Consumer<String> submit = raw -> {
            TextSession session = textInputs.get(player.getUniqueId());
            if (session == null || !session.token().equals(token) || !textInputs.remove(player.getUniqueId(), session)) return;
            plugin.getScheduler().cancel(session.timeoutTask());
            if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("cancel")) {
                if (onCancel != null) onCancel.run();
            } else {
                onSuccess.accept(raw);
            }
        };
        Object timeout = plugin.getScheduler().runEntityLater(player, () -> submit.accept(null), 600L);
        textInputs.put(player.getUniqueId(), new TextSession(token, timeout));
        if (supportsTextDialog(player)) {
            try {
                showTextDialog(player, title, label, initial, maxLength, submit, () -> submit.accept(null));
                return;
            } catch (RuntimeException | LinkageError unavailable) {
                plugin.getLogger().warning("Text dialog unavailable; using sign input: " + unavailable.getMessage());
            }
        }
        try {
            showTextSign(player, List.of(Component.text(initial != null ? initial : ""), Messages.component(plugin, "orders", "sign-name-line-2"),
                    Messages.component(plugin, "orders", "sign-name-line-3"), Component.empty()), submit, () -> submit.accept(null));
        } catch (RuntimeException | LinkageError unavailable) {
            cleanupPlayer(player.getUniqueId());
            Messages.send(plugin, player, "orders", "name-input-unavailable");
        }
    }

    protected boolean supportsTextDialog(Player player) {
        if (!DIALOG_SUPPORTED) return false;
        if (plugin.getHookManager() != null && plugin.getHookManager().getFloodgateHook() != null
                && plugin.getHookManager().getFloodgateHook().isBedrockPlayer(player)) return false;
        return getClientProtocolVersion(player) >= 771; // Java 1.21.6
    }

    protected int getClientProtocolVersion(Player player) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            return (int) Class.forName("com.viaversion.viaversion.api.ViaAPI")
                    .getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId());
        } catch (ClassNotFoundException absent) {
            return plugin.getServer().getUnsafe().getProtocolVersion();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return -1; // Unknown translated client: use the compatible sign input.
        }
    }

    protected void showTextDialog(Player player, String title, String label, String initial, int maxLength,
                                  Consumer<String> onSuccess, Runnable onCancel) {
        DialogHelper.openTextDialog(plugin, player, title, label, initial, maxLength, onSuccess, onCancel);
    }

    protected void showTextSign(Player player, List<Component> lines, Consumer<String> callback, Runnable onCancel) {
        showSign(player, lines, callback, onCancel);
    }

    public void openAmountInput(Player player, long currentAmount, Consumer<Long> onSuccess, Runnable onCancel) {
        if (player == null || !player.isOnline()) return;

        InputMode mode = getEffectiveMode();
        if (mode == InputMode.DIALOG) {
            try {
                DialogHelper.openAmountDialog(plugin, player, currentAmount, onSuccess, onCancel);
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to open amount dialog, falling back to sign input: " + t.getMessage());
                mode = InputMode.SIGN;
            }
        }

        Consumer<String> parser = raw -> {
            if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("cancel")) {
                plugin.getTextService().send(player, "<gradient:#E63A35:#FF6B6B><b>Orders</b></gradient> &7» &cInput cancelled.");
                if (onCancel != null) onCancel.run();
                return;
            }
            try {
                long val = Long.parseLong(raw.trim());
                if (val > 0) {
                    onSuccess.accept(val);
                } else {
                    plugin.getTextService().send(player, "invalid_amount");
                    if (onCancel != null) onCancel.run();
                }
            } catch (NumberFormatException e) {
                plugin.getTextService().send(player, "invalid_amount");
                if (onCancel != null) onCancel.run();
            }
        };

        if (mode == InputMode.SIGN) {
            try {
                openSignInput(player, "signs.amount", parser, onCancel);
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to open amount sign, falling back to chat input: " + t.getMessage());
                mode = InputMode.CHAT;
            }
        }

        if (mode == InputMode.ANVIL) {
            try {
                openAnvilInput(player, String.valueOf(currentAmount), "Enter Amount", parser, onCancel);
                return;
            } catch (Throwable t) {
                mode = InputMode.CHAT;
            }
        }

        openChatInput(player, "<gradient:#E63A35:#FF6B6B><b>Orders</b></gradient> &7» &fType the amount in chat &8(or type &ccancel&8):", parser, onCancel);
    }

    public void openPriceInput(Player player, double currentPrice, Consumer<Double> onSuccess, Runnable onCancel) {
        if (player == null || !player.isOnline()) return;

        InputMode mode = getEffectiveMode();
        if (mode == InputMode.DIALOG) {
            try {
                DialogHelper.openPriceDialog(plugin, player, currentPrice, onSuccess, onCancel);
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to open price dialog, falling back to sign input: " + t.getMessage());
                mode = InputMode.SIGN;
            }
        }

        Consumer<String> parser = raw -> {
            if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("cancel")) {
                plugin.getTextService().send(player, "<gradient:#E63A35:#FF6B6B><b>Orders</b></gradient> &7» &cInput cancelled.");
                if (onCancel != null) onCancel.run();
                return;
            }
            try {
                onSuccess.accept(parsePrice(raw));
            } catch (NumberFormatException e) {
                plugin.getTextService().send(player, "invalid_price");
                if (onCancel != null) onCancel.run();
            }
        };

        if (mode == InputMode.SIGN) {
            try {
                openSignInput(player, "signs.price_per_item", parser, onCancel);
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to open price sign, falling back to chat input: " + t.getMessage());
                mode = InputMode.CHAT;
            }
        }

        if (mode == InputMode.ANVIL) {
            try {
                String initialPrice = (currentPrice == (long) currentPrice) ? String.valueOf((long) currentPrice) : String.valueOf(currentPrice);
                openAnvilInput(player, initialPrice, "Enter Price", parser, onCancel);
                return;
            } catch (Throwable t) {
                mode = InputMode.CHAT;
            }
        }

        openChatInput(player, "<gradient:#E63A35:#FF6B6B><b>Orders</b></gradient> &7» &fType the price per unit in chat &8(or type &ccancel&8):", parser, onCancel);
    }

    static double parsePrice(String raw) {
        double price = Double.parseDouble(raw.trim().replace("$", "").replace(",", ""));
        if (!Double.isFinite(price) || price <= 0) throw new NumberFormatException("Price must be positive and finite");
        return price;
    }

    public void openSearchInput(Player player, String currentQuery, Consumer<String> onSuccess, Runnable onCancel) {
        if (player == null || !player.isOnline()) return;

        InputMode mode = getEffectiveMode();
        if (mode == InputMode.DIALOG) {
            try {
                DialogHelper.openSearchDialog(plugin, player, currentQuery, onSuccess, onCancel);
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to open search dialog, falling back to sign input: " + t.getMessage());
                mode = InputMode.SIGN;
            }
        }

        Consumer<String> parser = raw -> {
            if (raw != null && raw.equalsIgnoreCase("cancel")) {
                plugin.getTextService().send(player, "<gradient:#E63A35:#FF6B6B><b>Orders</b></gradient> &7» &cSearch cancelled.");
                if (onCancel != null) onCancel.run();
                return;
            }
            onSuccess.accept(raw == null ? "" : raw.trim());
        };

        if (mode == InputMode.SIGN) {
            try {
                openSignInput(player, "signs.search", parser, onCancel);
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to open search sign, falling back to chat input: " + t.getMessage());
                mode = InputMode.CHAT;
            }
        }

        if (mode == InputMode.ANVIL) {
            try {
                openAnvilInput(player, currentQuery != null ? currentQuery : "", "Search Here", parser, onCancel);
                return;
            } catch (Throwable t) {
                mode = InputMode.CHAT;
            }
        }

        openChatInput(player, "<gradient:#E63A35:#FF6B6B><b>Orders</b></gradient> &7» &fType your search query in chat &8(or type &ccancel&8):", parser, onCancel);
    }

    @Deprecated
    public void startAmountPrompt(Player player, Consumer<Long> onComplete) {
        openAmountInput(player, 1L, onComplete, null);
    }

    @Deprecated
    public void startPricePrompt(Player player, Consumer<Double> onComplete) {
        openPriceInput(player, 10.0, onComplete, null);
    }

    @Deprecated
    public void startChatSearchPrompt(Player player, Consumer<String> onComplete) {
        openSearchInput(player, "", onComplete, null);
    }

    private InputMode getEffectiveMode() {
        var cfg = plugin.getConfigService().get("modules/orders/config.yml");
        String configured = cfg != null ? cfg.getString("input-mode", "AUTO").toUpperCase(Locale.ROOT) : "AUTO";
        InputMode mode;
        try {
            mode = InputMode.valueOf(configured);
        } catch (IllegalArgumentException e) {
            mode = InputMode.AUTO;
        }

        if (mode == InputMode.AUTO) {
            return DIALOG_SUPPORTED ? InputMode.DIALOG : InputMode.SIGN;
        }
        if (mode == InputMode.DIALOG && !DIALOG_SUPPORTED) {
            return InputMode.SIGN;
        }
        return mode;
    }

    public void openSignInput(Player player, String messageKey, Consumer<String> callback, Runnable onCancel) {
        cleanupPlayer(player.getUniqueId());
        player.closeInventory();

        var messages = plugin.getConfigService().get("modules/orders/messages.yml");
        List<String> raw = messages != null ? messages.getStringList(messageKey) : List.of();
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(plugin.getTextService().parse(i < raw.size() ? raw.get(i) : ""));
        }

        showSign(player, lines, callback, onCancel);
    }

    private void showSign(Player player, List<Component> lines, Consumer<String> callback, Runnable onCancel) {
        Location loc = player.getLocation().getBlock().getLocation();

        Object timeout = plugin.getScheduler().runEntityLater(player, () -> {
            SignSession s = activeSignSessions.remove(player.getUniqueId());
            if (s != null) {
                restoreClient(player, s);
                if (s.onCancel() != null) s.onCancel().run();
            }
        }, 600L); // 30-second timeout (600 ticks)

        activeSignSessions.put(player.getUniqueId(), new SignSession(player.getUniqueId(), loc, callback, onCancel, timeout));
        player.sendBlockChange(loc, Material.OAK_SIGN.createBlockData());
        player.sendSignChange(loc, lines);
        player.openVirtualSign(Position.block(loc), Side.FRONT);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVirtualSign(UncheckedSignChangeEvent event) {
        Player player = event.getPlayer();
        SignSession session = activeSignSessions.remove(player.getUniqueId());
        if (session == null) return;

        var position = event.getEditedBlockPosition();
        var loc = session.location();
        if (!player.getWorld().equals(loc.getWorld()) || event.getSide() != Side.FRONT
                || position.blockX() != loc.getBlockX() || position.blockY() != loc.getBlockY() || position.blockZ() != loc.getBlockZ()) {
            activeSignSessions.put(player.getUniqueId(), session);
            return;
        }

        event.setCancelled(true);
        restoreClient(player, session);

        String text = "";
        if (!event.lines().isEmpty()) {
            text = PlainTextComponentSerializer.plainText().serialize(event.lines().getFirst()).trim();
            if (text.isEmpty() && event.lines().size() > 3) {
                String bottom = PlainTextComponentSerializer.plainText().serialize(event.lines().get(3)).trim();
                if (!bottom.isEmpty()) text = bottom;
            }
        }
        final String result = text;
        plugin.getScheduler().runEntity(player, () -> session.callback().accept(result));
    }

    private void restoreClient(Player player, SignSession session) {
        if (session.timeoutTask() != null) plugin.getScheduler().cancel(session.timeoutTask());
        plugin.getScheduler().runLocation(session.location(), () -> {
            var data = session.location().getBlock().getBlockData();
            plugin.getScheduler().runEntity(player, () -> {
                if (player.getWorld().equals(session.location().getWorld())) {
                    player.sendBlockChange(session.location(), data);
                }
            });
        });
    }

    private void openAnvilInput(Player player, String initialText, String title, Consumer<String> callback, Runnable onCancel) {
        cleanupPlayer(player.getUniqueId());
        player.closeInventory();

        AnvilView view = MenuType.ANVIL.create(player, Messages.component(plugin, "orders", "anvil-input-title", "%title_formatted%", String.valueOf(title)));
        ItemStack paper = new ItemStack(Material.PAPER);
        var meta = paper.getItemMeta();
        meta.displayName(Messages.component(plugin, "orders", "anvil-input-text", "%text%",
                initialText.isEmpty() ? Messages.raw(plugin, "orders", "anvil-input-empty") : initialText));
        paper.setItemMeta(meta);

        anvils.put(player.getUniqueId(), new AnvilSession(view, callback, onCancel));
        view.getTopInventory().setItem(0, paper);
        player.openInventory(view);
    }

    public void completeAnvil(Player player) {
        AnvilSession session = anvils.remove(player.getUniqueId());
        if (session == null) return;
        String query = session.view().getRenameText();
        session.view().getTopInventory().clear();
        player.closeInventory();
        plugin.getScheduler().runEntityLater(player, () -> {
            session.callback().accept(query == null ? "" : query.trim());
        }, 1L);
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        var session = anvils.get(player.getUniqueId());
        if (session == null || !session.view().getTopInventory().equals(event.getInventory())) return;
        var item = event.getInventory().getItem(0);
        if (item != null) event.setResult(item.clone());
        event.getView().setRepairCost(0);
        event.getView().setRepairItemCountCost(0);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnvilClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        var session = anvils.get(player.getUniqueId());
        if (session == null || !session.view().getTopInventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        if (event.getRawSlot() == 2) {
            plugin.getScheduler().runEntityLater(player, () -> completeAnvil(player), 1L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnvilDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        var session = anvils.get(player.getUniqueId());
        if (session != null && session.view().getTopInventory().equals(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onAnvilClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        AnvilSession session = anvils.remove(player.getUniqueId());
        if (session != null && session.view().getTopInventory().equals(event.getInventory())) {
            session.view().getTopInventory().clear();
            if (session.onCancel() != null) {
                plugin.getScheduler().runEntityLater(player, session.onCancel(), 1L);
            }
        }
    }

    private void openChatInput(Player player, String promptMessage, Consumer<String> callback, Runnable onCancel) {
        cleanupPlayer(player.getUniqueId());
        player.closeInventory();
        plugin.getTextService().send(player, promptMessage);
        pendingChatInputs.put(player.getUniqueId(), new ChatSession(callback, onCancel));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ChatSession session = pendingChatInputs.remove(uuid);
        if (session == null) return;

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (message.equalsIgnoreCase("cancel")) {
            plugin.getTextService().send(player, "&cCancelled input.");
            if (session.onCancel() != null) {
                plugin.getScheduler().runEntity(player, session.onCancel());
            }
            return;
        }

        plugin.getScheduler().runEntity(player, () -> session.callback().accept(message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer().getUniqueId());
    }

    public void cleanupPlayer(UUID uuid) {
        TextSession text = textInputs.remove(uuid);
        if (text != null) plugin.getScheduler().cancel(text.timeoutTask());
        pendingChatInputs.remove(uuid);
        SignSession sign = activeSignSessions.remove(uuid);
        Player player = plugin.getServer().getPlayer(uuid);
        if (sign != null) {
            if (sign.timeoutTask() != null) plugin.getScheduler().cancel(sign.timeoutTask());
            if (player != null) restoreClient(player, sign);
        }
        AnvilSession anvil = anvils.remove(uuid);
        if (anvil != null) {
            anvil.view().getTopInventory().clear();
        }
    }

    public void shutdown() {
        for (UUID uuid : new ArrayList<>(textInputs.keySet())) cleanupPlayer(uuid);
        for (UUID uuid : new ArrayList<>(activeSignSessions.keySet())) {
            cleanupPlayer(uuid);
        }
        for (UUID uuid : new ArrayList<>(anvils.keySet())) {
            cleanupPlayer(uuid);
        }
        activeSignSessions.clear();
        anvils.clear();
        pendingChatInputs.clear();
    }

    private static class DialogHelper {
        // Adventure's default lifetime is 12h; unclicked buttons would pin the player's handle that long.
        private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();

        static void openTextDialog(FriendlyCore plugin, Player player, String title, String label, String initial,
                                   int maxLength, Consumer<String> onSuccess, Runnable onCancel) {
            Dialog dialog = Dialog.create(factory -> {
                ClickCallback.Options options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofSeconds(30)).build();
                ActionButton confirm = ActionButton.builder(Messages.component(plugin, "orders", "dialog-rename"))
                        .action(DialogAction.customClick((view, audience) -> {
                            if (audience instanceof Player actor && actor.getUniqueId().equals(player.getUniqueId())) {
                                String value = view.getText("name");
                                plugin.getScheduler().runEntity(player, () -> onSuccess.accept(value));
                            }
                        }, options)).build();
                ActionButton cancel = ActionButton.builder(Messages.component(plugin, "orders", "dialog-cancel"))
                        .action(DialogAction.customClick((view, audience) -> {
                            if (audience instanceof Player actor && actor.getUniqueId().equals(player.getUniqueId())) {
                                plugin.getScheduler().runEntity(player, onCancel);
                            }
                        }, options)).build();
                factory.empty().base(DialogBase.builder(Component.text(title))
                        .inputs(List.of(DialogInput.text("name", Component.text(label))
                                .initial(initial != null ? initial : "").maxLength(maxLength).build()))
                        .build()).type(DialogType.confirmation(confirm, cancel));
            });
            player.showDialog(dialog);
        }

        static void openAmountDialog(FriendlyCore plugin, Player player, long currentAmount,
                                     Consumer<Long> onSuccess, Runnable onCancel) {
            Dialog dialog = Dialog.create(factory -> {
                ActionButton confirmButton = ActionButton.builder(Messages.component(plugin, "orders", "dialog-confirm"))
                        .action(DialogAction.customClick((view, audience) -> {
                            String raw = view.getText("amount");
                            plugin.getScheduler().runEntity(player, () -> {
                                if (raw == null || raw.isBlank()) {
                                    if (onCancel != null) onCancel.run();
                                    return;
                                }
                                try {
                                    long val = Long.parseLong(raw.trim());
                                    if (val > 0) {
                                        onSuccess.accept(val);
                                    } else {
                                        plugin.getTextService().send(player, "invalid_amount");
                                        if (onCancel != null) onCancel.run();
                                    }
                                } catch (NumberFormatException e) {
                                    plugin.getTextService().send(player, "invalid_amount");
                                    if (onCancel != null) onCancel.run();
                                }
                            });
                        }, CALLBACK_OPTIONS))
                        .build();

                ActionButton cancelButton = ActionButton.builder(Messages.component(plugin, "orders", "dialog-cancel"))
                        .action(DialogAction.customClick((view, audience) -> {
                            if (onCancel != null) {
                                plugin.getScheduler().runEntity(player, onCancel);
                            }
                        }, CALLBACK_OPTIONS))
                        .build();

                DialogBase base = DialogBase.builder(Messages.component(plugin, "orders", "dialog-amount-title"))
                        .inputs(List.of(
                                DialogInput.text("amount", Messages.component(plugin, "orders", "dialog-amount-label"))
                                        .initial(String.valueOf(currentAmount))
                                        .build()
                        ))
                        .build();

                factory.empty()
                        .base(base)
                        .type(DialogType.confirmation(confirmButton, cancelButton));
            });

            player.showDialog(dialog);
        }

        static void openPriceDialog(FriendlyCore plugin, Player player, double currentPrice,
                                    Consumer<Double> onSuccess, Runnable onCancel) {
            Dialog dialog = Dialog.create(factory -> {
                ActionButton confirmButton = ActionButton.builder(Messages.component(plugin, "orders", "dialog-confirm"))
                        .action(DialogAction.customClick((view, audience) -> {
                            String raw = view.getText("price");
                            plugin.getScheduler().runEntity(player, () -> {
                                if (raw == null || raw.isBlank()) {
                                    if (onCancel != null) onCancel.run();
                                    return;
                                }
                                try {
                                    onSuccess.accept(parsePrice(raw));
                                } catch (NumberFormatException e) {
                                    plugin.getTextService().send(player, "invalid_price");
                                    if (onCancel != null) onCancel.run();
                                }
                            });
                        }, CALLBACK_OPTIONS))
                        .build();

                ActionButton cancelButton = ActionButton.builder(Messages.component(plugin, "orders", "dialog-cancel"))
                        .action(DialogAction.customClick((view, audience) -> {
                            if (onCancel != null) {
                                plugin.getScheduler().runEntity(player, onCancel);
                            }
                        }, CALLBACK_OPTIONS))
                        .build();

                String initialPrice = (currentPrice == (long) currentPrice) ? String.valueOf((long) currentPrice) : String.valueOf(currentPrice);
                DialogBase base = DialogBase.builder(Messages.component(plugin, "orders", "dialog-price-title"))
                        .inputs(List.of(
                                DialogInput.text("price", Messages.component(plugin, "orders", "dialog-price-label"))
                                        .initial(initialPrice)
                                        .build()
                        ))
                        .build();

                factory.empty()
                        .base(base)
                        .type(DialogType.confirmation(confirmButton, cancelButton));
            });

            player.showDialog(dialog);
        }

        static void openSearchDialog(FriendlyCore plugin, Player player, String currentQuery,
                                     Consumer<String> onSuccess, Runnable onCancel) {
            Dialog dialog = Dialog.create(factory -> {
                ActionButton confirmButton = ActionButton.builder(Messages.component(plugin, "orders", "dialog-search"))
                        .action(DialogAction.customClick((view, audience) -> {
                            String raw = view.getText("search");
                            plugin.getScheduler().runEntity(player, () -> {
                                onSuccess.accept(raw == null ? "" : raw.trim());
                            });
                        }, CALLBACK_OPTIONS))
                        .build();

                ActionButton cancelButton = ActionButton.builder(Messages.component(plugin, "orders", "dialog-cancel"))
                        .action(DialogAction.customClick((view, audience) -> {
                            if (onCancel != null) {
                                plugin.getScheduler().runEntity(player, onCancel);
                            }
                        }, CALLBACK_OPTIONS))
                        .build();

                DialogBase base = DialogBase.builder(Messages.component(plugin, "orders", "dialog-search-title"))
                        .inputs(List.of(
                                DialogInput.text("search", Messages.component(plugin, "orders", "dialog-query-label"))
                                        .initial(currentQuery != null ? currentQuery : "")
                                        .build()
                        ))
                        .build();

                factory.empty()
                        .base(base)
                        .type(DialogType.confirmation(confirmButton, cancelButton));
            });

            player.showDialog(dialog);
        }
    }
}
