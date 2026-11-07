package dev.lyy.friendlycore.modules.pets;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.database.DatabaseReadRetry;
import dev.lyy.friendlycore.common.EffectUtil;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.pets.display.PetDisplayManager;
import dev.lyy.friendlycore.modules.pets.effects.PetEffect;
import dev.lyy.friendlycore.modules.pets.effects.PetTrigger;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import dev.lyy.friendlycore.modules.pets.model.WithdrawResult;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Master manager for Pet registry, player pet data, attributes, and lifecycle.
 */
public class PetManager {

    private static final NamespacedKey HEALTH_MODIFIER_KEY = new NamespacedKey("friendlycore", "pet_bonus_health");
    private static final NamespacedKey SPEED_MODIFIER_KEY = new NamespacedKey("friendlycore", "pet_bonus_speed");

    private final FriendlyCore plugin;
    private final Map<String, Pet> registeredPets = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerPetData> playerDataCache = new ConcurrentHashMap<>();
    private final Map<UUID, Object> loadTokens = new ConcurrentHashMap<>();
    private final Map<UUID, Map<PotionEffectType, PotionEffect>> activePotionEffects = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> inFlightSaves = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompletableFuture<?>> pendingPetWrites = new ConcurrentHashMap<>();
    private final PetDisplayManager displayManager;

    private void trackInFlight(CompletableFuture<?> future) {
        if (future != null) {
            inFlightSaves.add(future);
            future.whenComplete((res, ex) -> inFlightSaves.remove(future));
        }
    }

    private boolean autoDeactivateOnFail = true;
    private String serverId = "server";
    private Object displayTask;
    private Object conditionTask;
    private Object autoSaveTask;

    public PetManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.displayManager = new PetDisplayManager(plugin, this);
        this.serverId = plugin.getConfigService().getMainConfig().getString("redis.server-id", "server");
    }

    public void initialize() {
        plugin.getLogger().info("Initializing Pets Module...");
        loadPets();

        // Load data for any players already online (e.g. on plugin reload)
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerData(player.getUniqueId(), player.getName()).thenAccept(data -> {
                if (data != null) {
                    plugin.getScheduler().runEntity(player, () -> {
                        if (player.isOnline()) applyPetAttributes(player);
                    });
                }
            });
        }

        restartDisplayTask();

        this.conditionTask = plugin.getScheduler().runGlobalTimer(this::checkAutoDeactivate, 20L, 20L);

        // Start periodic autosave (6000t / 5 min)
        this.autoSaveTask = plugin.getScheduler().runAsyncTimer(this::saveAllDirty, 6000L, 6000L);
    }

    public void reload() {
        plugin.getConfigService().reload("modules/pets/config.yml");
        plugin.getConfigService().reload("modules/pets/inventories/pets.yml");
        plugin.getConfigService().reload("modules/pets/inventories/confirm.yml");
        loadPets();
        displayManager.reloadSettings();
        restartDisplayTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) return;
                applyPetAttributes(player);
            });
        }
    }

    public void shutdown() {
        plugin.getScheduler().cancel(displayTask);
        plugin.getScheduler().cancel(conditionTask);
        plugin.getScheduler().cancel(autoSaveTask);
        displayTask = null;
        conditionTask = null;
        autoSaveTask = null;
        displayManager.despawnAll();
        saveAllDirty();
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getScheduler().runEntity(player, () -> {
                removePetAttributes(player);
            });
        }
        if (!inFlightSaves.isEmpty()) {
            try {
                CompletableFuture.allOf(inFlightSaves.toArray(new CompletableFuture[0]))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Timed out waiting for pet saves on shutdown", e);
            }
        }
        playerDataCache.clear();
        loadTokens.clear();
        registeredPets.clear();
        activePotionEffects.clear();
    }

    private void restartDisplayTask() {
        if (displayTask != null) {
            plugin.getScheduler().cancel(displayTask);
        }
        // Motion state and interpolation use server ticks; old intervals made followers stutter.
        this.displayTask = plugin.getScheduler().runGlobalTimer(displayManager::tick, 1L, 1L);
    }

    public FileConfiguration getConfig() {
        return plugin.getConfigService().get("modules/pets/config.yml");
    }

    public FileConfiguration getMessages() {
        return plugin.getConfigService().get("modules/pets/messages.yml");
    }

    private void loadPets() {
        File petsFolder = new File(plugin.getDataFolder(), "modules/pets/pets");
        if (!petsFolder.exists()) {
            petsFolder.mkdirs();
            saveDefaultPetFiles(petsFolder);
        }

        File[] files = petsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            saveDefaultPetFiles(petsFolder);
            files = petsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        }

        // Build off to the side: the display tick despawns pets whose id is briefly missing.
        Map<String, Pet> loaded = new HashMap<>();
        if (files != null) {
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase();
                if (id.startsWith("_")) continue; // Skip _example.yml
                try {
                    YamlConfiguration cfg = ConfigService.loadStrict(file);
                    Pet pet = Pet.fromConfig(id, cfg);
                    if (pet != null) {
                        loaded.put(id, pet);
                    }
                } catch (Exception e) {
                    Pet previous = registeredPets.get(id);
                    if (previous != null) loaded.put(id, previous);
                    plugin.getLogger().log(Level.SEVERE, "Failed to load pet file: " + file.getName()
                            + (previous != null ? "; keeping the last loaded version" : ""), e);
                }
            }
        }
        registeredPets.keySet().retainAll(loaded.keySet());
        registeredPets.putAll(loaded);

        FileConfiguration config = getConfig();
        if (config != null) {
            this.autoDeactivateOnFail = config.getBoolean("auto-deactivate-on-condition-fail", true);
        }

        plugin.getLogger().info("Loaded " + registeredPets.size() + " pets.");
    }

    private void saveDefaultPetFiles(File targetDir) {
        String[] defaults = {
                "_example.yml", "allay.yml", "blaze.yml", "mancubus.yml", "ravager.yml",
                "sea_serpent.yml", "skeleton.yml", "tiger.yml", "vampire.yml",
                "king.yml", "mvp.yml", "mvpp.yml", "vip.yml"
        };
        for (String def : defaults) {
            File outFile = new File(targetDir, def);
            if (!outFile.exists()) {
                try (InputStream in = plugin.getResource("modules/pets/pets/" + def)) {
                    if (in != null) {
                        java.nio.file.Files.copy(in, outFile.toPath());
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public Pet getPet(String id) {
        if (id == null) return null;
        return registeredPets.get(id.toLowerCase());
    }

    public Collection<Pet> getAllPets() {
        return Collections.unmodifiableCollection(registeredPets.values());
    }

    public PlayerPetData getPlayerData(UUID uuid) {
        if (uuid == null) return null;
        return playerDataCache.get(uuid);
    }

    public CompletableFuture<PlayerPetData> loadPlayerData(UUID uuid, String username) {
        if (uuid == null) return CompletableFuture.completedFuture(null);
        Object token = new Object();
        loadTokens.put(uuid, token);
        return pendingPetWrites.getOrDefault(uuid, CompletableFuture.completedFuture(null))
                .handle((ignored, error) -> null).thenCompose(ignored ->
                DatabaseReadRetry.load(plugin, () -> plugin.getDatabaseManager().loadPlayerPetData(uuid, username),
                () -> loadTokens.get(uuid) == token).thenApply(data -> {
            if (data == null || loadTokens.get(uuid) != token) return null;
            playerDataCache.put(uuid, data);
            return data;
        }));
    }

    public void unloadPlayerData(UUID uuid) {
        if (uuid == null) return;
        loadTokens.remove(uuid);
        PlayerPetData data = playerDataCache.remove(uuid);
        if (data != null && data.isDirty()) {
            CompletableFuture<Void> future = queuePetWrite(uuid, () -> plugin.getDatabaseManager().savePlayerPetData(data)).exceptionally(error -> {
                plugin.getLogger().log(Level.SEVERE, "Failed to save pet data on unload for " + uuid, error);
                return null;
            });
            trackInFlight(future);
        }
        displayManager.despawn(uuid);
        activePotionEffects.remove(uuid);
    }

    public void saveAllDirty() {
        List<PlayerPetData> dirtyList = new ArrayList<>();
        for (PlayerPetData data : playerDataCache.values()) {
            if (data.isDirty()) {
                dirtyList.add(data);
            }
        }
        if (!dirtyList.isEmpty()) {
            try {
                CompletableFuture<Void> future = CompletableFuture.allOf(dirtyList.stream()
                        .map(data -> queuePetWrite(data.getUuid(), () -> plugin.getDatabaseManager().savePlayerPetData(data)))
                        .toArray(CompletableFuture[]::new));
                trackInFlight(future);
                future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save dirty pet data on shutdown", e);
            }
        }
    }

    /** Completes true when the database gained the pet, false when the account already owned it. */
    public CompletableFuture<Boolean> unlockPet(UUID uuid, String petId, boolean syncRedis) {
        if (uuid == null || petId == null) return CompletableFuture.completedFuture(false);
        String id = petId.toLowerCase();
        PlayerPetData data = playerDataCache.get(uuid);
        if (data != null) data.unlockPet(id);
        // Always ask the database: a stale cache must not decide ownership.
        CompletableFuture<Boolean> write = queuePetWrite(uuid, () -> plugin.getDatabaseManager().unlockPet(uuid, id));
        trackInFlight(write);
        if (syncRedis) {
            broadcastRedisPacket(CrossServerPacket.Action.PET_UNLOCK_SYNC, uuid, data != null ? data.getUsername() : "", id);
        }
        return write;
    }

    /** Completes true when the database row was removed, false when the account did not own the pet. */
    public CompletableFuture<Boolean> lockPet(UUID uuid, String petId, boolean syncRedis) {
        if (uuid == null || petId == null) return CompletableFuture.completedFuture(false);
        String id = petId.toLowerCase();
        PlayerPetData data = playerDataCache.get(uuid);
        CompletableFuture<Boolean> write = queuePetWrite(uuid, () -> plugin.getDatabaseManager().lockPet(uuid, id));
        trackInFlight(write);
        if (data != null) {
            boolean wasActive = data.isActivePet(id);
            if (data.lockPet(id)) {
                // The row is still there if the delete failed; keep the cache honest.
                write.whenComplete((removed, error) -> {
                    if (error != null) data.unlockPet(id);
                });
                if (wasActive) {
                    persistActivePets(data);
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        plugin.getScheduler().runEntity(player, () -> {
                            applyPetAttributes(player);
                            displayManager.refreshPlayerPet(player);
                        });
                    }
                }
            }
        }
        if (syncRedis) {
            broadcastRedisPacket(CrossServerPacket.Action.PET_LOCK_SYNC, uuid, data != null ? data.getUsername() : "", id);
        }
        return write;
    }

    public boolean activatePet(Player player, Pet pet, boolean syncRedis) {
        if (player == null || pet == null) return false;
        PlayerPetData data = playerDataCache.get(player.getUniqueId());
        if (data == null) return false;

        if (!data.hasPet(pet.getId())) {
            player.sendMessage(plugin.getTextService().toComponent(getMessages().getString("messages.doesnt-have-pet", "&cYou don't own this pet!")));
            return false;
        }

        if (!pet.canActivate(player)) {
            String msg = getMessages().getString("messages.cannot-activate-pet", "&cYou don't meet the conditions to activate %pet%!")
                    .replace("%pet%", pet.getName());
            player.sendMessage(plugin.getTextService().toComponent(msg));
            return false;
        }

        if (data.isActivePet(pet.getId())) {
            player.sendMessage(plugin.getTextService().toComponent(getMessages().getString("messages.pet-already-active", "&eThis pet is already equipped.")));
            return false;
        }
        if (!data.equipPet(pet.getId())) {
            player.sendMessage(plugin.getTextService().toComponent(getMessages().getString("messages.pet-slots-full", "&eBoth pet slots are full. Unequip a pet first.")));
            return false;
        }
        persistActivePets(data);
        applyPetAttributes(player);

        String msg = getMessages().getString("messages.activated-pet", "&fYou have activated the %pet%&f pet!")
                .replace("%pet%", pet.getName());
        player.sendMessage(plugin.getTextService().toComponent(msg));

        if (syncRedis) {
            broadcastActivePets(player, data);
        }

        return true;
    }

    public boolean deactivatePet(Player player, boolean syncRedis) {
        if (player == null) return false;
        PlayerPetData data = getPlayerData(player.getUniqueId());
        if (data == null || data.getActivePets().isEmpty()) return false;
        for (String petId : data.getActivePets()) deactivatePet(player, petId, false);
        if (syncRedis) broadcastActivePets(player, data);
        return true;
    }

    public boolean deactivatePet(Player player, String petId, boolean syncRedis) {
        if (player == null) return false;
        PlayerPetData data = playerDataCache.get(player.getUniqueId());
        if (data == null || !data.unequipPet(petId)) return false;

        String oldPetId = petId;
        Pet pet = getPet(oldPetId);
        String petName = pet != null ? pet.getName() : oldPetId;

        persistActivePets(data);
        applyPetAttributes(player);
        displayManager.refreshPlayerPet(player);

        String msg = getMessages().getString("messages.deactivated-pet", "&fYou have deactivated the %pet%&f pet!")
                .replace("%pet%", petName);
        player.sendMessage(plugin.getTextService().toComponent(msg));

        if (syncRedis) {
            broadcastActivePets(player, data);
        }

        return true;
    }

    private void persistActivePets(PlayerPetData data) {
        List<String> pets = data.getActivePets();
        trackInFlight(queuePetWrite(data.getUuid(), () -> plugin.getDatabaseManager().setActivePets(data.getUuid(), pets))
                .exceptionally(error -> {
                    data.setDirty(true);
                    plugin.getLogger().log(Level.SEVERE, "Failed to save equipped pets for " + data.getUuid(), error);
                    return null;
                }));
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> queuePetWrite(UUID uuid, java.util.function.Supplier<CompletableFuture<T>> write) {
        CompletableFuture<T> next = (CompletableFuture<T>) pendingPetWrites.compute(uuid, (id, previous) ->
                (previous == null ? CompletableFuture.completedFuture(null) : previous)
                        .handle((ignored, error) -> null).thenCompose(ignored -> write.get()));
        next.whenComplete((ignored, error) -> pendingPetWrites.remove(uuid, next));
        return next;
    }

    private void broadcastActivePets(Player player, PlayerPetData data) {
        broadcastRedisPacket(CrossServerPacket.Action.PET_ACTIVATE_SYNC, player.getUniqueId(),
                player.getName(), String.join(",", data.getActivePets()));
    }

    public void toggleHidePet(Player player) {
        if (player == null) return;
        PlayerPetData data = playerDataCache.get(player.getUniqueId());
        if (data == null) return;

        boolean newHide = !data.isHidePet();
        data.setHidePet(newHide);
        trackInFlight(queuePetWrite(player.getUniqueId(), () -> plugin.getDatabaseManager().setHidePet(player.getUniqueId(), newHide)));

        if (newHide) {
            displayManager.despawn(player.getUniqueId());
        }

        broadcastRedisPacket(CrossServerPacket.Action.PET_HIDE_TOGGLE_SYNC, player.getUniqueId(), player.getName(), String.valueOf(newHide));
    }

    public WithdrawResult withdrawPet(Player player, Pet pet) {
        if (player == null || pet == null) return WithdrawResult.NO_ACTIVE;
        PlayerPetData data = playerDataCache.get(player.getUniqueId());
        if (data == null || !data.isActivePet(pet.getId())) {
            return WithdrawResult.NO_ACTIVE;
        }

        if (!pet.getSpawnEggConfig().isWithdrawable()) {
            return WithdrawResult.NOT_WITHDRAWABLE;
        }

        ItemStack egg = pet.createSpawnEgg();
        if (egg == null) return WithdrawResult.NOT_WITHDRAWABLE;
        if (player.getInventory().firstEmpty() == -1) return WithdrawResult.INVENTORY_FULL;

        if (!payWithdrawPrice(player, pet, false)) return WithdrawResult.CANNOT_AFFORD;

        // Hand the egg out only once the database confirms the pet left the account;
        // handing it out first duplicated the pet whenever the delete failed.
        UUID uuid = player.getUniqueId();
        lockPet(uuid, pet.getId(), true).whenComplete((removed, error) -> {
            boolean confirmed = error == null && Boolean.TRUE.equals(removed);
            Runnable restore = () -> {
                if (confirmed) unlockPet(uuid, pet.getId(), false);
            };
            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) {
                    restore.run();
                    return;
                }
                if (!confirmed) {
                    sendPetMessage(player, pet, "messages.withdraw-failed", "&cYour pet could not be withdrawn. Nothing was charged.");
                    return;
                }
                if (!payWithdrawPrice(player, pet, true)) {
                    restore.run();
                    sendPetMessage(player, pet, "messages.cannot-afford-withdraw", "&cYou cannot afford to withdraw this pet!");
                    return;
                }
                for (ItemStack leftover : player.getInventory().addItem(egg).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
                sendPetMessage(player, pet, "messages.pet-withdrawn", "&fYou withdrew your %pet%&f pet into an egg!");
            }, restore);
        });
        return WithdrawResult.OK;
    }

    private void sendPetMessage(Player player, Pet pet, String key, String fallback) {
        player.sendMessage(plugin.getTextService().toComponent(getMessages().getString(key, fallback)
                .replace("%pet%", pet.getName())));
    }

    /** Checks the withdraw price, and takes it when {@code charge} is set. */
    private boolean payWithdrawPrice(Player player, Pet pet, boolean charge) {
        var config = pet.getSpawnEggConfig();
        if (!config.isWithdrawPriceEnabled() || config.getWithdrawPriceValue() <= 0) return true;
        double price = config.getWithdrawPriceValue();
        if (!Double.isFinite(price)) return false;
        String type = config.getWithdrawPriceType().toLowerCase();
        if (!type.equals("coins") && !type.equals("money") && !type.equals("economy")) return false;
        if (plugin.getHookManager() != null && plugin.getHookManager().isVaultEconomyAvailable()) {
            net.milkbowl.vault.economy.Economy vault = plugin.getHookManager().getVaultEconomy();
            if (vault == null || !vault.has(player, price)) return false;
            if (!charge) return true;
            net.milkbowl.vault.economy.EconomyResponse response = vault.withdrawPlayer(player, price);
            return response != null && response.transactionSuccess();
        }
        if (plugin.getEconomyManager() == null) return false;
        var account = plugin.getEconomyManager().getCachedAccount(player.getUniqueId());
        if (account == null || account.getBalance() < price) return false;
        if (charge) {
            account.setBalance(account.getBalance() - price);
            account.setDirty(true);
        }
        return true;
    }

    public List<PetEffect> getActiveEffects(Player player) {
        PlayerPetData data = getPlayerData(player.getUniqueId());
        if (data == null) return List.of();
        List<PetEffect> effects = new ArrayList<>();
        for (String id : data.getActivePets()) {
            Pet pet = getPet(id);
            if (pet == null || !data.hasPet(id) || !pet.canActivate(player) || !pet.areConditionsMet(player)) continue;
            for (PetEffect effect : pet.getEffects()) {
                if (effect.getConditions().stream().allMatch(condition -> condition.test(player))) effects.add(effect);
            }
        }
        return effects;
    }

    public void applyPetAttributes(Player player) {
        if (player == null) return;
        double health = 0.0;
        double speed = 0.0;
        Map<PotionEffectType, PotionEffect> desired = new HashMap<>();
        for (PetEffect effect : getActiveEffects(player)) {
            if (!effect.hasTrigger(PetTrigger.PASSIVE)) continue;
            String id = effect.getId();
            if (id.equals("bonus_health") || id.equals("health_boost")) {
                double amount = effect.getDoubleArg("health", effect.getDoubleArg("amount", 0.0));
                if (Double.isFinite(amount) && amount > 0) health += amount;
            } else if (id.equals("movement_speed_multiplier") || id.equals("speed")) {
                double multiplier = effect.getDoubleArg("multiplier", 1.0);
                if (Double.isFinite(multiplier) && multiplier > 1.0) speed += multiplier - 1.0;
            } else if (id.equals("potion_effect") || id.equals("potion") || id.equals("effect")) {
                String name = effect.getStringArg("effect");
                if (name == null || name.isBlank()) name = effect.getStringArg("type");
                PotionEffectType type = resolvePotionEffectType(name);
                if (type == null) continue;
                PotionEffect potion = new PotionEffect(type, PotionEffect.INFINITE_DURATION,
                        Math.max(0, effect.getIntArg("level", 1) - 1),
                        effect.getBooleanArg("ambient", false), effect.getBooleanArg("particles", false),
                        effect.getBooleanArg("icon", true));
                desired.merge(type, potion, (first, second) ->
                        first.getAmplifier() >= second.getAmplifier() ? first : second);
            }
        }
        updateModifier(player, Attribute.MAX_HEALTH, HEALTH_MODIFIER_KEY, health, AttributeModifier.Operation.ADD_NUMBER);
        updateModifier(player, Attribute.MOVEMENT_SPEED, SPEED_MODIFIER_KEY, speed, AttributeModifier.Operation.ADD_SCALAR);

        Map<PotionEffectType, PotionEffect> previous = activePotionEffects.getOrDefault(player.getUniqueId(), Map.of());
        for (var entry : previous.entrySet()) {
            if (!desired.containsKey(entry.getKey()) && entry.getValue().equals(player.getPotionEffect(entry.getKey()))) {
                player.removePotionEffect(entry.getKey());
            }
        }
        Map<PotionEffectType, PotionEffect> applied = new HashMap<>();
        for (var entry : desired.entrySet()) {
            PotionEffect current = player.getPotionEffect(entry.getKey());
            PotionEffect old = previous.get(entry.getKey());
            if (old != null && old.equals(current) && !old.equals(entry.getValue())) {
                player.removePotionEffect(entry.getKey());
                current = null;
            }
            // A stronger external potion keeps its duration; the pet resumes when it expires.
            if (current == null || current.equals(old) || current.getAmplifier() < entry.getValue().getAmplifier()) {
                if (!entry.getValue().equals(current)) player.addPotionEffect(entry.getValue());
                applied.put(entry.getKey(), entry.getValue());
            }
        }
        if (applied.isEmpty()) activePotionEffects.remove(player.getUniqueId());
        else activePotionEffects.put(player.getUniqueId(), applied);
    }

    private void updateModifier(Player player, Attribute attribute, NamespacedKey key, double amount,
                                AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;
        AttributeModifier current = instance.getModifier(key);
        if (current != null && current.getAmount() == amount) return;
        instance.removeModifier(key);
        if (Double.isFinite(amount) && amount > 0) instance.addModifier(new AttributeModifier(key, amount, operation));
        if (attribute == Attribute.MAX_HEALTH && player.getHealth() > instance.getValue()) {
            player.setHealth(instance.getValue());
        }
    }

    public void removePetAttributes(Player player) {
        if (player == null) return;
        updateModifier(player, Attribute.MAX_HEALTH, HEALTH_MODIFIER_KEY, 0, AttributeModifier.Operation.ADD_NUMBER);
        updateModifier(player, Attribute.MOVEMENT_SPEED, SPEED_MODIFIER_KEY, 0, AttributeModifier.Operation.ADD_SCALAR);
        Map<PotionEffectType, PotionEffect> effects = activePotionEffects.remove(player.getUniqueId());
        if (effects != null) {
            effects.forEach((type, effect) -> {
                if (effect.equals(player.getPotionEffect(type))) player.removePotionEffect(type);
            });
        }
    }

    public static PotionEffectType resolvePotionEffectType(String name) {
        if (name == null || name.isBlank()) return null;
        String clean = name.trim().toUpperCase().replace("-", "_").replace(" ", "_");

        switch (clean) {
            case "HASTE":
            case "FAST_DIGGING":
            case "FAST_MINE":
                return PotionEffectType.HASTE;
            case "MINING_FATIGUE":
            case "SLOW_DIGGING":
                return PotionEffectType.MINING_FATIGUE;
            case "STRENGTH":
            case "INCREASE_DAMAGE":
                return PotionEffectType.STRENGTH;
            case "SPEED":
                return PotionEffectType.SPEED;
            case "SLOWNESS":
            case "SLOW":
                return PotionEffectType.SLOWNESS;
            case "JUMP_BOOST":
            case "JUMP":
                return PotionEffectType.JUMP_BOOST;
            case "NAUSEA":
            case "CONFUSION":
                return PotionEffectType.NAUSEA;
            case "REGENERATION":
            case "REGEN":
                return PotionEffectType.REGENERATION;
            case "RESISTANCE":
            case "DAMAGE_RESISTANCE":
                return PotionEffectType.RESISTANCE;
            case "FIRE_RESISTANCE":
            case "FIRE_RESIST":
            case "FIRESSISTANI":
            case "FIRE_ASPECT":
                return PotionEffectType.FIRE_RESISTANCE;
            case "WATER_BREATHING":
                return PotionEffectType.WATER_BREATHING;
            case "INVISIBILITY":
            case "INVIS":
                return PotionEffectType.INVISIBILITY;
            case "BLINDNESS":
                return PotionEffectType.BLINDNESS;
            case "NIGHT_VISION":
                return PotionEffectType.NIGHT_VISION;
            case "HUNGER":
                return PotionEffectType.HUNGER;
            case "WEAKNESS":
                return PotionEffectType.WEAKNESS;
            case "POISON":
                return PotionEffectType.POISON;
            case "WITHER":
                return PotionEffectType.WITHER;
            case "HEALTH_BOOST":
                return PotionEffectType.HEALTH_BOOST;
            case "ABSORPTION":
                return PotionEffectType.ABSORPTION;
            case "SATURATION":
                return PotionEffectType.SATURATION;
            case "GLOWING":
                return PotionEffectType.GLOWING;
            case "LEVITATION":
                return PotionEffectType.LEVITATION;
            case "LUCK":
                return PotionEffectType.LUCK;
            case "BAD_LUCK":
            case "UNLUCK":
                return PotionEffectType.UNLUCK;
            case "SLOW_FALLING":
                return PotionEffectType.SLOW_FALLING;
            case "CONDUIT_POWER":
                return PotionEffectType.CONDUIT_POWER;
            case "DOLPHINS_GRACE":
            case "DOLPHIN_GRACE":
                return PotionEffectType.DOLPHINS_GRACE;
            case "HERO_OF_THE_VILLAGE":
                return PotionEffectType.HERO_OF_THE_VILLAGE;
            case "DARKNESS":
                return PotionEffectType.DARKNESS;
            default:
                try {
                    PotionEffectType type = PotionEffectType.getByName(clean);
                    if (type != null) return type;
                } catch (Throwable ignored) {}
                try {
                    return org.bukkit.Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(clean.toLowerCase()));
                } catch (Throwable ignored) {}
                return null;
        }
    }

    private void checkAutoDeactivate() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline() || player.isDead()) return;
                PlayerPetData data = getPlayerData(player.getUniqueId());
                if (data == null) return;
                for (String id : data.getActivePets()) {
                    Pet pet = getPet(id);
                    if (pet == null || !data.hasPet(id) || (autoDeactivateOnFail && !pet.canActivate(player))) {
                        deactivatePet(player, id, true);
                        if (pet != null) {
                            player.sendMessage(plugin.getTextService().toComponent(getMessages()
                                    .getString("messages.pet-auto-deactivated", "&eYour %pet% was unequipped because its requirements changed.")
                                    .replace("%pet%", pet.getName())));
                        }
                    }
                }
                applyPetAttributes(player);
            });
        }
    }

    public void handleCrossServerPacket(CrossServerPacket packet) {
        if (packet == null || packet.getAction() == null) return;
        UUID uuid = packet.getTargetUuid();

        switch (packet.getAction()) {
            case PET_ACTIVATE_SYNC:
            case PET_DEACTIVATE_SYNC: {
                if (uuid == null) break;
                PlayerPetData data = playerDataCache.get(uuid);
                if (data == null) break;
                Player player = Bukkit.getPlayer(uuid);
                Runnable update = () -> {
                    if (playerDataCache.get(uuid) != data) return;
                    if (packet.getAction() == CrossServerPacket.Action.PET_ACTIVATE_SYNC) {
                        data.setActivePets(packet.getExtra() == null ? List.of() : Arrays.asList(packet.getExtra().split(",")));
                    } else if (packet.getExtra() == null || packet.getExtra().isBlank()) {
                        data.setActivePet(null);
                    } else {
                        data.unequipPet(packet.getExtra());
                    }
                    if (player != null && player.isOnline()) {
                        applyPetAttributes(player);
                        displayManager.refreshPlayerPet(player);
                    }
                };
                if (player != null) plugin.getScheduler().runEntity(player, update);
                else update.run();
                break;
            }
            case PET_UNLOCK_SYNC: {
                if (uuid != null && packet.getExtra() != null) {
                    PlayerPetData data = playerDataCache.get(uuid);
                    if (data != null) {
                        data.unlockPet(packet.getExtra());
                    }
                }
                break;
            }
            case PET_LOCK_SYNC: {
                if (uuid != null && packet.getExtra() != null) {
                    PlayerPetData data = playerDataCache.get(uuid);
                    if (data != null) {
                        boolean wasActive = data.isActivePet(packet.getExtra());
                        data.lockPet(packet.getExtra());
                        if (wasActive) {
                            Player player = Bukkit.getPlayer(uuid);
                            if (player != null && player.isOnline()) {
                                plugin.getScheduler().runEntity(player, () -> {
                                    applyPetAttributes(player);
                                    displayManager.refreshPlayerPet(player);
                                });
                            } else {
                                displayManager.despawn(uuid);
                            }
                        }
                    }
                }
                break;
            }
            case PET_HIDE_TOGGLE_SYNC: {
                if (uuid != null && packet.getExtra() != null) {
                    PlayerPetData data = playerDataCache.get(uuid);
                    if (data != null) {
                        boolean hide = Boolean.parseBoolean(packet.getExtra());
                        data.setHidePet(hide);
                        if (hide) {
                            Player player = Bukkit.getPlayer(uuid);
                            if (player != null && player.isOnline()) {
                                plugin.getScheduler().runEntity(player, () -> displayManager.despawn(uuid));
                            } else {
                                displayManager.despawn(uuid);
                            }
                        }
                    }
                }
                break;
            }
            case PET_RELOAD_SYNC: {
                plugin.getScheduler().runGlobal(this::reload);
                break;
            }
            default:
                break;
        }
    }

    public void broadcastReload() {
        broadcastRedisPacket(CrossServerPacket.Action.PET_RELOAD_SYNC, null, "", "");
    }

    public void broadcastRedisPacket(CrossServerPacket.Action action, UUID uuid, String name, String extra) {
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            CrossServerPacket packet = new CrossServerPacket(serverId, action, uuid, name, 0.0, extra);
            plugin.getRedisService().publish(packet);
        }
    }

    public PetDisplayManager getDisplayManager() {
        return displayManager;
    }
}
