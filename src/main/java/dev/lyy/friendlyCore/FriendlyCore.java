package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.*;
import dev.lyy.friendlycore.common.database.DatabaseManager;
import dev.lyy.friendlycore.common.database.MySQLDatabase;
import dev.lyy.friendlycore.common.database.SQLiteDatabase;
import dev.lyy.friendlycore.common.gui.GuiListener;
import dev.lyy.friendlycore.common.hooks.HookManager;
import dev.lyy.friendlycore.common.redis.RedisService;
import dev.lyy.friendlycore.modules.economy.EconomyManager;
import dev.lyy.friendlycore.modules.economy.commands.*;
import dev.lyy.friendlycore.modules.homes.HomesManager;
import dev.lyy.friendlycore.modules.kits.KitManager;
import dev.lyy.friendlycore.modules.kits.commands.FriendlyCoreCommand;
import dev.lyy.friendlycore.modules.kits.commands.KitCommand;
import dev.lyy.friendlycore.modules.nightvision.NightVisionListener;
import dev.lyy.friendlycore.modules.nightvision.NightVisionManager;
import dev.lyy.friendlycore.modules.nightvision.commands.NightVisionCommand;
import dev.lyy.friendlycore.modules.placeholders.FriendlyExpansion;
import dev.lyy.friendlycore.modules.pvp.PvpManager;
import dev.lyy.friendlycore.modules.pvp.StatsManager;
import dev.lyy.friendlycore.modules.rtp.RtpListener;
import dev.lyy.friendlycore.modules.rtp.RtpManager;
import dev.lyy.friendlycore.modules.rtp.commands.RtpCommand;
import dev.lyy.friendlycore.modules.spawn.SpawnManager;
import dev.lyy.friendlycore.modules.spawn.commands.SetSpawnCommand;
import dev.lyy.friendlycore.modules.spawn.commands.SpawnCommand;
import dev.lyy.friendlycore.modules.spawn.commands.TelegramCommand;
import dev.lyy.friendlycore.modules.tools.ChopperHandler;
import dev.lyy.friendlycore.modules.tools.DrillHandler;
import dev.lyy.friendlycore.modules.tools.LeafDecayManager;
import dev.lyy.friendlycore.modules.tools.ToolManager;
import dev.lyy.friendlycore.modules.tools.commands.ToolsCommand;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import dev.lyy.friendlycore.modules.tpa.commands.*;
import dev.lyy.friendlycore.modules.tpa.listeners.TpaMoveListener;
import dev.lyy.friendlycore.modules.tpa.listeners.TpaPlayerListener;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Main plugin entry point for FriendlyCore.
 */
public final class FriendlyCore extends JavaPlugin {

    private static FriendlyCore instance;
    private static final int BSTATS_PLUGIN_ID = 33925;

    private Metrics metrics;

    private FriendlyScheduler scheduler;
    private TextService textService;
    private dev.lyy.friendlycore.modules.orders.input.OrderInputManager textInputManager;
    private ConfigService configService;
    private NumberFormatter numberFormatter;
    private CooldownManager cooldownManager;
    private DatabaseManager databaseManager;
    private RedisService redisService;
    private HookManager hookManager;
    private TransferFreezeService transferFreezeService;
    private dev.lyy.friendlycore.nms.common.NMSBridge nmsBridge;

    private EconomyManager economyManager;
    private KitManager kitManager;
    private dev.lyy.friendlycore.modules.kits.gui.KitGuiManager kitGuiManager;
    private ToolManager toolManager;
    private LeafDecayManager leafDecayManager;
    private StatsManager statsManager;
    private PvpManager pvpManager;
    private SpawnManager spawnManager;
    private dev.lyy.friendlycore.modules.afk.AfkCommand afkCommand;
    private TpaManager tpaManager;
    private RtpManager rtpManager;
    private NightVisionManager nightVisionManager;
    private dev.lyy.friendlycore.modules.teams.TeamsModule teamsModule;
    private HomesManager homesManager;
    private dev.lyy.friendlycore.modules.joinquit.JoinQuitManager joinQuitManager;
    private dev.lyy.friendlycore.modules.essentials.EssentialsManager essentialsManager;
    private dev.lyy.friendlycore.modules.pets.PetManager petManager;
    private dev.lyy.friendlycore.modules.chat.ChatModule chatModule;
    private dev.lyy.friendlycore.modules.settings.SettingsManager settingsManager;
    private dev.lyy.friendlycore.modules.auctions.AuctionsModule auctionsModule;
    private dev.lyy.friendlycore.modules.orders.OrdersModule ordersModule;
    private dev.lyy.friendlycore.tools.converter.DatabaseConverterService databaseConverterService;

    @Override
    public void onEnable() {
        instance = this;

        long startTime = System.currentTimeMillis();
        getLogger().info("Initializing FriendlyCore v" + getPluginMeta().getVersion() + " (Folia-Supported)...");

        this.scheduler = new FriendlyScheduler(this);
        this.nmsBridge = dev.lyy.friendlycore.nms.NMSLoader.load(this);
        this.textService = new TextService();
        this.configService = new ConfigService(this);
        this.textService.setConfigService(this.configService);
        this.numberFormatter = new NumberFormatter();
        this.numberFormatter.updateSettings(configService.getEconomyConfig());
        this.cooldownManager = new CooldownManager();
        this.scheduler.runAsyncTimer(this.cooldownManager::cleanup, 6000L, 6000L);
        this.databaseConverterService = new dev.lyy.friendlycore.tools.converter.DatabaseConverterService(this);

        // Core admin command remains accessible even if individual modules are disabled
        registerCommand("friendlycore", new FriendlyCoreCommand(this));

        String dbType = configService.getMainConfig().getString("database.type", "sqlite");
        if (dbType.equalsIgnoreCase("mysql")) {
            this.databaseManager = new MySQLDatabase(this);
        } else {
            this.databaseManager = new SQLiteDatabase(this);
        }
        this.databaseManager.initialize();

        // Cross-server Redis sync
        this.redisService = new RedisService(this);
        if (configService.getMainConfig().getBoolean("redis.enabled", false)) {
            this.redisService.initialize();
        }

        this.hookManager = new HookManager(this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        this.transferFreezeService = new TransferFreezeService(this);
        this.transferFreezeService.initialize();

        boolean ecoEnabled = configService.getMainConfig().getBoolean("modules.economy", true);
        if (ecoEnabled) {
            this.economyManager = new EconomyManager(this);
            this.economyManager.initialize();
            registerCommand("bal", new BalCommand(this));
            registerCommand("baltop", new BaltopCommand(this));
            registerCommand("pay", new PayCommand(this));
            registerCommand("paytoggle", new PayToggleCommand(this));
            registerCommand("payhistory", new PayHistoryCommand(this));
            registerCommand("economy", new EconomyCommand(this));
            registerCommand("payreload", new PayReloadCommand(this));
        }

        boolean kitsEnabled = configService.getMainConfig().getBoolean("modules.kits", true);
        if (kitsEnabled) {
            this.kitManager = new KitManager(this);
            this.kitManager.initialize();
            this.kitGuiManager = new dev.lyy.friendlycore.modules.kits.gui.KitGuiManager(this);
            this.kitGuiManager.initialize();
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.kits.listeners.KitListener(this, kitGuiManager), this);

            registerCommand("kit", new dev.lyy.friendlycore.modules.kits.commands.KitCommand(this, kitGuiManager));
        }

        boolean toolsEnabled = configService.getMainConfig().getBoolean("modules.tools", true);
        if (toolsEnabled) {
            this.toolManager = new ToolManager(this);
            this.leafDecayManager = new LeafDecayManager(this);
            getServer().getPluginManager().registerEvents(new DrillHandler(this), this);
            getServer().getPluginManager().registerEvents(new ChopperHandler(this), this);
            registerCommand("tools", new ToolsCommand(this));
        }

        boolean pvpEnabled = configService.getMainConfig().getBoolean("modules.pvp", true);
        if (pvpEnabled) {
            this.statsManager = new StatsManager(this);
            this.statsManager.initialize();
            this.pvpManager = new PvpManager(this);
            this.pvpManager.initialize();

            registerCommand("pvp", new dev.lyy.friendlycore.modules.pvp.commands.PvpCommand(this));
            registerCommand("combattag", new dev.lyy.friendlycore.modules.pvp.commands.CombatTagCommand(this));
            registerCommand("newbie", new dev.lyy.friendlycore.modules.pvp.commands.NewbieCommand(this));
            registerCommand("togglepvp", new dev.lyy.friendlycore.modules.pvp.commands.TogglePvpCommand(this));
        }

        boolean spawnEnabled = configService.getMainConfig().getBoolean("modules.spawn", true);
        if (spawnEnabled) {
            this.spawnManager = new SpawnManager(this);
            this.spawnManager.initialize();
            registerCommand("setspawn", new SetSpawnCommand(this));
            registerCommand("spawn", new SpawnCommand(this));
            registerCommand("telegram", new TelegramCommand(this));
        }

        boolean tpaEnabled = configService.getMainConfig().getBoolean("modules.tpa", true);
        if (tpaEnabled) {
            this.tpaManager = new TpaManager(this);
            this.tpaManager.initialize();
            getServer().getPluginManager().registerEvents(new TpaMoveListener(this, tpaManager), this);
            getServer().getPluginManager().registerEvents(new TpaPlayerListener(this, tpaManager), this);

            registerCommand("tpa", new TpaCommand(this, tpaManager));
            registerCommand("tpahere", new TpaHereCommand(this, tpaManager));
            registerCommand("tpaccept", new TpAcceptCommand(this, tpaManager));
            registerCommand("tpdeny", new TpDenyCommand(this, tpaManager));
            registerCommand("tpacancel", new TpaCancelCommand(this, tpaManager));
            registerCommand("tpaall", new TpaAllCommand(this, tpaManager));
            registerCommand("tphereall", new TpHereAllCommand(this, tpaManager));
            registerCommand("tpatoggle", new TpaToggleCommand(this, tpaManager));
            registerCommand("tpaheretoggle", new TpaHereToggleCommand(this, tpaManager));
            registerCommand("tpauto", new TpAutoCommand(this, tpaManager));
            registerCommand("tpaguitoggle", new TpaGuiToggleCommand(this, tpaManager));
            registerCommand("tpasetting", new TpaSettingCommand(this, tpaManager));
            registerCommand("tpaadmin", new TpaAdminCommand(this, tpaManager));
        }

        boolean rtpEnabled = configService.getMainConfig().getBoolean("modules.rtp", true);
        if (rtpEnabled) {
            this.rtpManager = new RtpManager(this);
            this.rtpManager.initialize();
            getServer().getPluginManager().registerEvents(new RtpListener(this, rtpManager), this);
            registerCommand("rtp", new RtpCommand(this, rtpManager));
        }

        boolean nvEnabled = configService.getMainConfig().getBoolean("modules.nightvision", true);
        if (nvEnabled) {
            this.nightVisionManager = new NightVisionManager(this);
            this.nightVisionManager.initialize();
            getServer().getPluginManager().registerEvents(new NightVisionListener(this, nightVisionManager), this);
            registerCommand("nv", new NightVisionCommand(this, nightVisionManager));
        }

        boolean teamsEnabled = configService.getMainConfig().getBoolean("modules.teams", true);
        if (teamsEnabled) {
            this.teamsModule = new dev.lyy.friendlycore.modules.teams.TeamsModule(this);
            this.teamsModule.initialize();
        }

        boolean homesEnabled = configService.getMainConfig().getBoolean("modules.homes", true);
        if (homesEnabled) {
            this.homesManager = new HomesManager(this);
            this.homesManager.initialize();
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.homes.listeners.HomeListener(this, homesManager), this);

            registerCommand("home", new dev.lyy.friendlycore.modules.homes.commands.HomeCommand(this, homesManager));
            registerCommand("homes", new dev.lyy.friendlycore.modules.homes.commands.HomeCommand(this, homesManager));
            registerCommand("sethome",
                    new dev.lyy.friendlycore.modules.homes.commands.SetHomeCommand(this, homesManager));
            registerCommand("delhome",
                    new dev.lyy.friendlycore.modules.homes.commands.DelHomeCommand(this, homesManager));
            registerCommand("ahome", new dev.lyy.friendlycore.modules.homes.commands.AHomeCommand(this, homesManager));
        }

        boolean joinQuitEnabled = configService.getMainConfig().getBoolean("modules.joinquit", true);
        if (joinQuitEnabled) {
            this.joinQuitManager = new dev.lyy.friendlycore.modules.joinquit.JoinQuitManager(this);
            this.joinQuitManager.initialize();
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.joinquit.JoinQuitListener(this, joinQuitManager), this);

            registerCommand("joinquit",
                    new dev.lyy.friendlycore.modules.joinquit.commands.JoinQuitCommand(this, joinQuitManager));
            registerCommand("togglejoinquit",
                    new dev.lyy.friendlycore.modules.joinquit.commands.JoinQuitCommand(this, joinQuitManager));
        } else {
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.joinquit.VanillaJoinQuitSuppressListener(), this);
        }

        boolean essentialsEnabled = configService.getMainConfig().getBoolean("modules.essentials", true);
        if (essentialsEnabled) {
            this.essentialsManager = new dev.lyy.friendlycore.modules.essentials.EssentialsManager(this);
            this.essentialsManager.initialize();
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.essentials.EssentialsListener(this, essentialsManager), this);

            var gmCmd = new dev.lyy.friendlycore.modules.essentials.commands.GamemodeCommand(this, essentialsManager);
            registerCommand("gamemode", gmCmd);
            registerCommand("gm", gmCmd);
            registerCommand("gmc", gmCmd);
            registerCommand("gms", gmCmd);
            registerCommand("gma", gmCmd);
            registerCommand("gmsp", gmCmd);

            registerCommand("invsee",
                    new dev.lyy.friendlycore.modules.essentials.commands.InvseeCommand(this, essentialsManager));

            var ecCmd = new dev.lyy.friendlycore.modules.essentials.commands.EnderchestCommand(this, essentialsManager);
            registerCommand("enderchest", ecCmd);
            registerCommand("ec", ecCmd);

            var speedCmd = new dev.lyy.friendlycore.modules.essentials.commands.SpeedCommand(this, essentialsManager);
            registerCommand("speed", speedCmd);
            registerCommand("flyspeed", speedCmd);
            registerCommand("walkspeed", speedCmd);

            var timeCmd = new dev.lyy.friendlycore.modules.essentials.commands.TimeCommand(this, essentialsManager);
            registerCommand("time", timeCmd);
            registerCommand("day", timeCmd);
            registerCommand("night", timeCmd);

            var weatherCmd = new dev.lyy.friendlycore.modules.essentials.commands.WeatherCommand(this,
                    essentialsManager);
            registerCommand("weather", weatherCmd);
            registerCommand("sun", weatherCmd);
            registerCommand("rain", weatherCmd);
            registerCommand("storm", weatherCmd);

            var tpCmd = new dev.lyy.friendlycore.modules.essentials.commands.TeleportCommand(this, essentialsManager);
            registerCommand("tp", tpCmd);
            registerCommand("tppos", tpCmd);

            registerCommand("tphere",
                    new dev.lyy.friendlycore.modules.essentials.commands.TeleportHereCommand(this, essentialsManager));
            registerCommand("tpall",
                    new dev.lyy.friendlycore.modules.essentials.commands.TeleportAllCommand(this, essentialsManager));

            registerCommand("fly",
                    new dev.lyy.friendlycore.modules.essentials.commands.FlyCommand(this, essentialsManager));
            registerCommand("god",
                    new dev.lyy.friendlycore.modules.essentials.commands.GodCommand(this, essentialsManager));
            registerCommand("heal",
                    new dev.lyy.friendlycore.modules.essentials.commands.HealCommand(this, essentialsManager));
            registerCommand("feed",
                    new dev.lyy.friendlycore.modules.essentials.commands.FeedCommand(this, essentialsManager));

            var wbCmd = new dev.lyy.friendlycore.modules.essentials.commands.VirtualWorkbenchCommand(this,
                    essentialsManager);
            registerCommand("craft", wbCmd);
            registerCommand("workbench", wbCmd);
            registerCommand("wb", wbCmd);
            registerCommand("anvil", wbCmd);
            registerCommand("grindstone", wbCmd);
            registerCommand("stonecutter", wbCmd);
            registerCommand("smithingtable", wbCmd);
            registerCommand("loom", wbCmd);
            registerCommand("cartographytable", wbCmd);
            registerCommand("disposal", wbCmd);
            registerCommand("trash", wbCmd);

            var jumpCmd = new dev.lyy.friendlycore.modules.essentials.commands.JumpTopBottomCommand(this,
                    essentialsManager);
            registerCommand("top", jumpCmd);
            registerCommand("bottom", jumpCmd);
            registerCommand("jump", jumpCmd);
            registerCommand("suicide", jumpCmd);

            var clearCmd = new dev.lyy.friendlycore.modules.essentials.commands.ClearInventoryCommand(this,
                    essentialsManager);
            registerCommand("clearinventory", clearCmd);
            registerCommand("clear", clearCmd);
            registerCommand("ci", clearCmd);
            registerCommand("clean", clearCmd);
            registerCommand("eclear", clearCmd);
            registerCommand("eclean", clearCmd);

            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.essentials.vanish.VanishListener(this, essentialsManager.getVanishManager()), this);

            var vanishCmd = new dev.lyy.friendlycore.modules.essentials.commands.VanishCommand(this, essentialsManager, essentialsManager.getVanishManager());
            registerCommand("vanish", vanishCmd);
            registerCommand("v", vanishCmd);
            registerCommand("evanish", vanishCmd);
            registerCommand("hide", vanishCmd);

            var onlineCmd = new dev.lyy.friendlycore.modules.essentials.commands.OnlineCommand(this, essentialsManager, essentialsManager.getVanishManager());
            registerCommand("online", onlineCmd);
            registerCommand("list", onlineCmd);
            registerCommand("who", onlineCmd);
            registerCommand("playerlist", onlineCmd);
            registerCommand("elist", onlineCmd);
            registerCommand("eonline", onlineCmd);
        }

        boolean petsEnabled = configService.getMainConfig().getBoolean("modules.pets", true);
        if (petsEnabled) {
            this.petManager = new dev.lyy.friendlycore.modules.pets.PetManager(this);
            this.petManager.initialize();
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.pets.listeners.PetEventListener(this, petManager), this);
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.pets.listeners.PetSpawnEggListener(this, petManager), this);

            registerCommand("pets", new dev.lyy.friendlycore.modules.pets.commands.PetsCommand(this, petManager));
            registerCommand("ecopets", new dev.lyy.friendlycore.modules.pets.commands.EcoPetsCommand(this, petManager));
        }

        boolean chatEnabled = configService.getMainConfig().getBoolean("modules.chat", true);
        if (chatEnabled) {
            this.chatModule = new dev.lyy.friendlycore.modules.chat.ChatModule(this);
            this.chatModule.initialize();
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.chat.listener.ChatListener(this, chatModule), this);
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.chat.listener.ChatPlayerListener(this, chatModule), this);
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.chat.listener.ChatGuiListener(), this);

            registerCommand("chat", new dev.lyy.friendlycore.modules.chat.commands.ChatAdminCommand(this, chatModule));
            registerCommand("msg", new dev.lyy.friendlycore.modules.chat.commands.MsgCommand(this, chatModule));
            registerCommand("tell", new dev.lyy.friendlycore.modules.chat.commands.MsgCommand(this, chatModule));
            registerCommand("whisper", new dev.lyy.friendlycore.modules.chat.commands.MsgCommand(this, chatModule));
            registerCommand("reply", new dev.lyy.friendlycore.modules.chat.commands.ReplyCommand(this, chatModule));
            registerCommand("ignore", new dev.lyy.friendlycore.modules.chat.commands.IgnoreCommand(this, chatModule));
            registerCommand("unignore", new dev.lyy.friendlycore.modules.chat.commands.UnignoreCommand(this, chatModule));
            registerCommand("ignoreall", new dev.lyy.friendlycore.modules.chat.commands.IgnoreAllCommand(this, chatModule));
            registerCommand("unignoreall", new dev.lyy.friendlycore.modules.chat.commands.UnignoreAllCommand(this, chatModule));
            registerCommand("spymsg", new dev.lyy.friendlycore.modules.chat.commands.SocialSpyCommand(this, chatModule));
            registerCommand("staffchat", new dev.lyy.friendlycore.modules.chat.commands.StaffChatCommand(this, chatModule));
            registerCommand("chatping", new dev.lyy.friendlycore.modules.chat.commands.ChatPingCommand(this, chatModule));
            registerCommand("chattoggle", new dev.lyy.friendlycore.modules.chat.commands.ChatToggleCommand(this, chatModule));
            registerCommand("mutechat", new dev.lyy.friendlycore.modules.chat.commands.MuteChatCommand(this, chatModule));
            registerCommand("clearchat", new dev.lyy.friendlycore.modules.chat.commands.ClearChatCommand(this, chatModule));
            registerCommand("chatinv", new dev.lyy.friendlycore.modules.chat.commands.ChatInvCommand(this, chatModule));
        }

        boolean settingsEnabled = configService.getMainConfig().getBoolean("modules.settings", true);
        if (settingsEnabled) {
            this.settingsManager = new dev.lyy.friendlycore.modules.settings.SettingsManager(this);
            this.settingsManager.initialize();
            getServer().getPluginManager().registerEvents(
                    new dev.lyy.friendlycore.modules.settings.listeners.SettingsListener(this, settingsManager), this);

            registerCommand("settings", new dev.lyy.friendlycore.modules.settings.commands.SettingsCommand(this, settingsManager));
            registerCommand("sb", new dev.lyy.friendlycore.modules.settings.commands.QuickToggleCommands(this, settingsManager, dev.lyy.friendlycore.modules.settings.SettingType.SCOREBOARD));
            registerCommand("scoreboards", new dev.lyy.friendlycore.modules.settings.commands.QuickToggleCommands(this, settingsManager, dev.lyy.friendlycore.modules.settings.SettingType.SCOREBOARD));
            registerCommand("togglesb", new dev.lyy.friendlycore.modules.settings.commands.QuickToggleCommands(this, settingsManager, dev.lyy.friendlycore.modules.settings.SettingType.SCOREBOARD));
            registerCommand("toggletpa", new dev.lyy.friendlycore.modules.settings.commands.QuickToggleCommands(this, settingsManager, dev.lyy.friendlycore.modules.settings.SettingType.TPA));
            registerCommand("togglepm", new dev.lyy.friendlycore.modules.settings.commands.QuickToggleCommands(this, settingsManager, dev.lyy.friendlycore.modules.settings.SettingType.PRIVATE_MESSAGES));
            registerCommand("togglepay", new dev.lyy.friendlycore.modules.settings.commands.QuickToggleCommands(this, settingsManager, dev.lyy.friendlycore.modules.settings.SettingType.PAY_NOTIFICATIONS));
        }

        boolean auctionsEnabled = configService.getMainConfig().getBoolean("modules.auctions", false);
        if (auctionsEnabled) {
            getLogger().warning("The experimental Auctions module is enabled. Do not run it alongside AxAuctions or another auction plugin.");
            this.auctionsModule = new dev.lyy.friendlycore.modules.auctions.AuctionsModule(this);
            this.auctionsModule.initialize();
        } else {
            unregisterCommand("ah", "auction", "auctions", "ahmarket", "axah", "auctionhouse", "axauction", "axauctionhouse",
                    "ahadmin", "aha", "auctionadmin", "auctionsadmin", "axahadmin", "auctionhouseadmin", "axauctionadmin", "axauctionhouseadmin");
        }

        boolean ordersEnabled = configService.getMainConfig().getBoolean("modules.orders", false);
        if (ordersEnabled) {
            getLogger().warning("The experimental Orders module is enabled. Do not run it alongside DonutOrder or another orders plugin.");
            this.ordersModule = new dev.lyy.friendlycore.modules.orders.OrdersModule(this);
            this.ordersModule.initialize();
        } else {
            unregisterCommand("order", "orders", "dorder", "orderadmin", "ordersadmin", "dorderadmin");
        }

        try {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            getServer().getMessenger().registerOutgoingPluginChannel(this, "friendlycore:rtp_return");
        } catch (Throwable ignored) {
        }

        this.afkCommand = new dev.lyy.friendlycore.modules.afk.AfkCommand(this);
        registerCommand("afk", afkCommand);
        getServer().getPluginManager().registerEvents(afkCommand, this);

        if (hookManager.isPlaceholderApiAvailable()) {
            FriendlyExpansion friendlyExp = new FriendlyExpansion(this);
            friendlyExp.register();
            new dev.lyy.friendlycore.modules.placeholders.PlayerKitsExpansion(this, friendlyExp).register();
            new dev.lyy.friendlycore.modules.placeholders.SwiftPayExpansion(this, friendlyExp).register();
            new dev.lyy.friendlycore.modules.placeholders.MoneyTitlesExpansion(this, friendlyExp).register();
            if (auctionsModule != null) {
                new dev.lyy.friendlycore.modules.placeholders.AxAuctionsExpansion(this, friendlyExp).register();
            }
            if (ordersModule != null) {
                new dev.lyy.friendlycore.modules.placeholders.DonutOrderExpansion(this, friendlyExp).register();
            }
            getLogger().info(
                    "Registered PlaceholderAPI Expansion and compatibility bridges (PlayerKits, SwiftPay, MoneyTitles, AxAuctions, DonutOrder)!");
        }

        // Enabled by default; bStats manages the server-wide opt-out.
        this.metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        long duration = System.currentTimeMillis() - startTime;
        getLogger().info("FriendlyCore successfully enabled in " + duration + "ms on "
                + (scheduler.isFolia() ? "Folia" : "Paper/Spigot") + "!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling FriendlyCore...");
        if (afkCommand != null) afkCommand.shutdown();
        if (textInputManager != null) {
            textInputManager.shutdown();
            textInputManager = null;
        }
        if (pvpManager != null)
            pvpManager.getCombatTagManager().setShuttingDown(true);

        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }

        if (ordersModule != null)
            ordersModule.shutdown();
        if (auctionsModule != null)
            auctionsModule.shutdown();
        if (settingsManager != null)
            settingsManager.shutdown();
        if (chatModule != null)
            chatModule.shutdown();
        if (petManager != null)
            petManager.shutdown();
        if (essentialsManager != null)
            essentialsManager.shutdown();
        if (joinQuitManager != null)
            joinQuitManager.shutdown();
        if (teamsModule != null)
            teamsModule.shutdown();
        if (homesManager != null)
            homesManager.shutdown();
        if (kitGuiManager != null)
            kitGuiManager.shutdown();
        if (kitManager != null)
            kitManager.shutdown();
        if (spawnManager != null)
            spawnManager.shutdown();
        if (nightVisionManager != null)
            nightVisionManager.shutdown();
        if (rtpManager != null)
            rtpManager.shutdown();
        if (tpaManager != null)
            tpaManager.shutdown();
        if (transferFreezeService != null)
            transferFreezeService.shutdown();
        if (pvpManager != null)
            pvpManager.shutdown();
        if (statsManager != null)
            statsManager.shutdown();
        if (economyManager != null)
            economyManager.shutdown();
        if (redisService != null)
            redisService.shutdown();
        if (databaseManager != null)
            databaseManager.shutdown();
        if (scheduler != null)
            scheduler.shutdown();

        dev.lyy.friendlycore.common.SafeTeleportUtil.clearAll();

        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, "friendlycore:rtp_return");
            getServer().getMessenger().unregisterIncomingPluginChannel(this);
        } catch (Throwable ignored) {}

        instance = null;
        getLogger().info("FriendlyCore disabled cleanly.");
    }

    /**
     * In-memory refresh for each reloadable module, keyed by its {@code modules/<id>/} folder.
     * "core" covers config.yml, messages.yml, Redis and hooks.
     */
    private Map<String, Runnable> moduleReloaders() {
        Map<String, Runnable> r = new java.util.LinkedHashMap<>();
        r.put("core", () -> {
            if (redisService != null) redisService.reload();
            if (hookManager != null) hookManager.checkHooks();
        });
        r.put("economy", () -> {
            numberFormatter.updateSettings(configService.getEconomyConfig());
            if (economyManager != null) economyManager.invalidateBaltopCache();
        });
        r.put("pets", () -> { if (petManager != null) petManager.reload(); });
        r.put("essentials", () -> { if (essentialsManager != null) essentialsManager.reload(); });
        r.put("joinquit", () -> { if (joinQuitManager != null) joinQuitManager.reload(); });
        r.put("teams", () -> { if (teamsModule != null) teamsModule.reload(); });
        r.put("homes", () -> { if (homesManager != null) homesManager.reload(); });
        r.put("kits", () -> {
            if (kitManager != null) {
                kitManager.loadSettings();
                kitManager.loadKits();
            }
            if (kitGuiManager != null) kitGuiManager.loadConfig();
        });
        r.put("spawn", () -> { if (spawnManager != null) spawnManager.loadSpawnLocation(); });
        r.put("tpa", () -> { if (tpaManager != null) tpaManager.reload(); });
        r.put("rtp", () -> { if (rtpManager != null) rtpManager.reload(); });
        r.put("nightvision", () -> { if (nightVisionManager != null) nightVisionManager.loadSettings(); });
        r.put("chat", () -> { if (chatModule != null) chatModule.reload(); });
        r.put("auctions", () -> { if (auctionsModule != null) auctionsModule.reload(); });
        r.put("orders", () -> { if (ordersModule != null) ordersModule.reload(); });
        r.put("pvp", () -> { if (pvpManager != null) pvpManager.reload(); });
        // Read their config on every use; reloading the files is enough.
        r.put("settings", () -> { });
        r.put("tools", () -> { });
        return r;
    }

    public java.util.Set<String> getReloadableModules() {
        return moduleReloaders().keySet();
    }

    /** Reloads every file and module. Returns problems; an empty list means a clean reload. */
    public List<String> reloadAll() {
        configService.reloadAll();
        List<String> problems = new ArrayList<>(configService.getLoadErrors());
        moduleReloaders().forEach((id, reloader) -> runReloader(id, reloader, problems));
        return problems;
    }

    /** Reloads one module's files and in-memory state. Returns problems, or null for an unknown module. */
    public List<String> reloadModule(String id) {
        String key = id.toLowerCase(java.util.Locale.ROOT);
        Runnable reloader = moduleReloaders().get(key);
        if (reloader == null) return null;
        if (key.equals("core")) {
            configService.reloadCore();
        } else {
            configService.reloadModule(key);
        }
        List<String> problems = new ArrayList<>(configService.getLoadErrors());
        runReloader(key, reloader, problems);
        return problems;
    }

    /** Reports a reload outcome; {@code module} is null for a full reload. */
    public void sendReloadResult(org.bukkit.command.CommandSender sender, String module, List<String> problems, long millis) {
        var messages = configService.getMessages();
        Map<String, String> placeholders = Map.of(
                "%time%", String.valueOf(millis),
                "%module%", module == null ? "all" : module,
                "%count%", String.valueOf(problems.size()));
        if (problems.isEmpty()) {
            String key = module == null ? "command-reloaded" : "reload-module-done";
            textService.sendMessage(sender, messages.getString(key, "<green>Reloaded %module% in %time%ms."), placeholders);
            return;
        }
        textService.sendMessage(sender, messages.getString("reload-problems",
                "<yellow>Reloaded %module% with %count% problem(s). Broken files kept their last good version:"), placeholders);
        String line = messages.getString("reload-problem-line", " <dark_gray>• <red>%problem%");
        for (String problem : problems) {
            // Placeholder values are inserted unparsed, so YAML error text cannot inject tags.
            textService.sendMessage(sender, line, Map.of("%problem%", problem));
        }
    }

    private void runReloader(String id, Runnable reloader, List<String> problems) {
        try {
            reloader.run();
        } catch (Throwable t) {
            problems.add(id + ": " + t);
            getLogger().log(java.util.logging.Level.SEVERE, "Failed to reload module " + id, t);
        }
    }

    public dev.lyy.friendlycore.nms.common.NMSBridge getNmsBridge() {
        return nmsBridge;
    }

    public void registerCommand(String name, Object executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            if (executor instanceof org.bukkit.command.CommandExecutor ce) {
                cmd.setExecutor(ce);
            }
            if (executor instanceof org.bukkit.command.TabCompleter tc) {
                cmd.setTabCompleter(tc);
            }
        } else {
            getLogger().log(Level.WARNING, "Command '" + name + "' not found in plugin.yml");
        }
    }

    public void unregisterCommand(String... names) {
        if (names == null || names.length == 0) return;
        try {
            org.bukkit.command.CommandMap commandMap = null;
            try {
                java.lang.reflect.Method getCommandMap = Bukkit.getServer().getClass().getMethod("getCommandMap");
                commandMap = (org.bukkit.command.CommandMap) getCommandMap.invoke(Bukkit.getServer());
            } catch (Throwable ignored) {
                java.lang.reflect.Field field = Bukkit.getServer().getClass().getDeclaredField("commandMap");
                field.setAccessible(true);
                commandMap = (org.bukkit.command.CommandMap) field.get(Bukkit.getServer());
            }

            if (commandMap instanceof org.bukkit.command.SimpleCommandMap scm) {
                java.lang.reflect.Field knownCommandsField = org.bukkit.command.SimpleCommandMap.class.getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, org.bukkit.command.Command> knownCommands = (java.util.Map<String, org.bukkit.command.Command>) knownCommandsField.get(scm);
                String pluginPrefix = getName().toLowerCase(java.util.Locale.ROOT) + ":";

                for (String name : names) {
                    if (name == null || name.isBlank()) continue;
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    // The prefixed label is always ours; the bare label may belong to another plugin.
                    org.bukkit.command.Command cmd = knownCommands.remove(pluginPrefix + lower);
                    if (cmd != null) {
                        knownCommands.remove(lower, cmd);
                        cmd.unregister(scm);
                    }
                    PluginCommand pluginCmd = getCommand(name);
                    if (pluginCmd != null) {
                        pluginCmd.setExecutor((sender, command, label, args) -> {
                            textService.send(sender, "<red>This command is disabled in FriendlyCore configuration.</red>");
                            return true;
                        });
                        pluginCmd.setTabCompleter((sender, command, alias, args) -> java.util.Collections.emptyList());
                    }
                }
            } else {
                for (String name : names) {
                    PluginCommand pluginCmd = getCommand(name);
                    if (pluginCmd != null) {
                        pluginCmd.setExecutor((sender, command, label, args) -> {
                            textService.send(sender, "<red>This command is disabled in FriendlyCore configuration.</red>");
                            return true;
                        });
                        pluginCmd.setTabCompleter((sender, command, alias, args) -> java.util.Collections.emptyList());
                    }
                }
            }
        } catch (Throwable t) {
            for (String name : names) {
                PluginCommand pluginCmd = getCommand(name);
                if (pluginCmd != null) {
                    pluginCmd.setExecutor((sender, command, label, args) -> {
                        textService.send(sender, "<red>This command is disabled in FriendlyCore configuration.</red>");
                        return true;
                    });
                    pluginCmd.setTabCompleter((sender, command, alias, args) -> java.util.Collections.emptyList());
                }
            }
        }
    }

    @Override
    public boolean onCommand(@org.jetbrains.annotations.NotNull org.bukkit.command.CommandSender sender, @org.jetbrains.annotations.NotNull org.bukkit.command.Command command, @org.jetbrains.annotations.NotNull String label, @org.jetbrains.annotations.NotNull String[] args) {
        textService.send(sender, "<red>This command is disabled in FriendlyCore configuration.</red>");
        return true;
    }

    public static FriendlyCore getInstance() {
        return instance;
    }

    public FriendlyScheduler getScheduler() {
        return scheduler;
    }

    public dev.lyy.friendlycore.modules.orders.input.OrderInputManager getTextInputManager() {
        if (textInputManager == null) {
            textInputManager = new dev.lyy.friendlycore.modules.orders.input.OrderInputManager(this);
            getServer().getPluginManager().registerEvents(textInputManager, this);
        }
        return textInputManager;
    }

    public TextService getTextService() {
        return textService;
    }

    public ConfigService getConfigService() {
        return configService;
    }

    public NumberFormatter getNumberFormatter() {
        return numberFormatter;
    }

    public CooldownManager getCooldownManager() {
        return cooldownManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public RedisService getRedisService() {
        return redisService;
    }

    public TransferFreezeService getTransferFreezeService() {
        return transferFreezeService;
    }

    public HookManager getHookManager() {
        return hookManager;
    }

    public net.milkbowl.vault.economy.Economy getVaultEconomy() {
        return hookManager != null ? hookManager.getVaultEconomy() : null;
    }

    private final com.google.gson.Gson gson = new com.google.gson.Gson();

    public com.google.gson.Gson getGson() {
        return gson;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public dev.lyy.friendlycore.modules.kits.gui.KitGuiManager getKitGuiManager() {
        return kitGuiManager;
    }

    public ToolManager getToolManager() {
        return toolManager;
    }

    public LeafDecayManager getLeafDecayManager() {
        return leafDecayManager;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public PvpManager getPvpManager() {
        return pvpManager;
    }

    public SpawnManager getSpawnManager() {
        return spawnManager;
    }

    public TpaManager getTpaManager() {
        return tpaManager;
    }

    public RtpManager getRtpManager() {
        return rtpManager;
    }

    public NightVisionManager getNightVisionManager() {
        return nightVisionManager;
    }

    public dev.lyy.friendlycore.modules.teams.TeamsModule getTeamsModule() {
        return teamsModule;
    }

    public HomesManager getHomesManager() {
        return homesManager;
    }

    public dev.lyy.friendlycore.modules.joinquit.JoinQuitManager getJoinQuitManager() {
        return joinQuitManager;
    }

    public dev.lyy.friendlycore.modules.essentials.EssentialsManager getEssentialsManager() {
        return essentialsManager;
    }

    public dev.lyy.friendlycore.modules.pets.PetManager getPetManager() {
        return petManager;
    }

    public dev.lyy.friendlycore.modules.chat.ChatModule getChatModule() {
        return chatModule;
    }

    public dev.lyy.friendlycore.modules.settings.SettingsManager getSettingsManager() {
        return settingsManager;
    }

    public dev.lyy.friendlycore.modules.auctions.AuctionsModule getAuctionsModule() {
        return auctionsModule;
    }

    public dev.lyy.friendlycore.modules.orders.OrdersModule getOrdersModule() {
        return ordersModule;
    }

    public dev.lyy.friendlycore.tools.converter.DatabaseConverterService getDatabaseConverterService() {
        return databaseConverterService;
    }
}
