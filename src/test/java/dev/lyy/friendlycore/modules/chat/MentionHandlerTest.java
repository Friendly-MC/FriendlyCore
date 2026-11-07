package dev.lyy.friendlycore.modules.chat;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.modules.chat.model.ChatChannel;
import dev.lyy.friendlycore.modules.chat.model.ChatMessage;
import dev.lyy.friendlycore.modules.chat.model.ChatPlayerData;
import dev.lyy.friendlycore.modules.chat.model.RenderSnapshot;
import dev.lyy.friendlycore.modules.chat.listener.ChatListener;
import dev.lyy.friendlycore.modules.chat.listener.ChatPlayerListener;
import dev.lyy.friendlycore.modules.chat.render.MentionHandler;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerHideEntityEvent;
import org.bukkit.event.player.PlayerShowEntityEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MentionHandlerTest {
    @Test
    void reloadReadsMentionChangesFromDiskAndUpdatesSuggestions(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        Fixture f = new Fixture();
        set(f.plugin, "dataFolder", folder.toFile());
        ConfigService service = allocate(ConfigService.class);
        set(service, "plugin", f.plugin);
        set(service, "configs", new java.util.concurrent.ConcurrentHashMap<>());
        set(service, "files", new java.util.concurrent.ConcurrentHashMap<>());
        set(service, "aliases", new HashMap<>());
        set(service, "moduleContainers", new java.util.concurrent.ConcurrentHashMap<>());
        set(service, "invalidFiles", java.util.concurrent.ConcurrentHashMap.newKeySet());
        set(service, "loadErrors", java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>()));
        set(f.plugin, "configService", service);
        java.nio.file.Path configPath = folder.resolve("modules/chat/config.yml");
        java.nio.file.Files.createDirectories(configPath.getParent());
        for (String name : List.of("config.yml", "chat-filter.yml", "filter-rules.yml", "filter-whitelist.yml",
                "leetmap.yml", "placeholders.yml", "renders-gui.yml", "messages.yml")) {
            try (var resource = getClass().getClassLoader().getResourceAsStream("modules/chat/" + name)) {
                assertNotNull(resource);
                java.nio.file.Files.copy(resource, configPath.resolveSibling(name));
            }
        }
        ChatModule module = new ChatModule(f.plugin);
        set(module.getRenderManager(), "mentionHandler", f.handler);
        withServer(f, () -> {
            module.reload();
            f.scheduler.flush();
            assertTrue(f.alice.completions.contains("@Bob"));
            var updated = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configPath.toFile());
            updated.set("features.mentions.enabled", false);
            try { updated.save(configPath.toFile()); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
            module.reload();
            f.scheduler.flush();
            assertTrue(f.alice.completions.isEmpty());
            assertEquals(Component.text("@Bob"), f.remote("@Bob"));
        });
    }

    @Test
    void bundledMentionPrefixKeepsNormalWeightInsideBoldMessages() throws Exception {
        Fixture f = new Fixture();
        try (var resource = getClass().getClassLoader().getResourceAsStream("modules/chat/config.yml")) {
            assertNotNull(resource);
            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8));
            set(f.handler, "mentionPrefix", config.getString("features.mentions.prefix"));
        }
        Component result = f.handler.processCrossServerMentions("Alice", f.alice.uuid,
                Component.text("@Bob after").decorate(TextDecoration.BOLD), f.viewers, f.muted);
        assertEquals("@Bob after", plain(result));
        List<Boolean> bold = new ArrayList<>();
        appendBoldState(result, false, bold);
        assertEquals(List.of(false, false, false, false), bold.subList(0, 4));
        assertTrue(bold.subList(4, bold.size()).stream().allMatch(Boolean::booleanValue));
    }

    private static void appendBoldState(Component component, boolean inherited, List<Boolean> states) {
        var decoration = component.decoration(TextDecoration.BOLD);
        boolean bold = decoration == TextDecoration.State.NOT_SET ? inherited : decoration == TextDecoration.State.TRUE;
        if (component instanceof TextComponent text) {
            for (int i = 0; i < text.content().length(); i++) states.add(bold);
        }
        for (Component child : component.children()) appendBoldState(child, bold, states);
    }

    @Test
    void mentionsSplitByGradientsOrColorsStillHighlightAndPingOnce() throws Exception {
        Fixture f = new Fixture();
        String raw = "<gradient:red:blue>@bOB</gradient> and <red>@B</red><blue>ob</blue>!";
        Component input = f.plugin.getTextService().parse(raw);
        Component local = f.handler.processMentions(f.chat(raw), input, f.viewers, f.muted);
        assertEquals("@Bob and @Bob!", plain(local));
        assertTrue(hasColor(local, NamedTextColor.GOLD));
        assertEquals(1, f.scheduler.tasks.size());
        f.scheduler.tasks.clear();
        Component remote = f.handler.processCrossServerMentions("Alice", f.alice.uuid, input, f.viewers, f.muted);
        assertEquals(local, remote);
        assertEquals(1, f.scheduler.tasks.size());
        f.scheduler.flush();
        assertEquals(1, f.bob.titles);
        assertEquals(1, f.bob.sounds);
        assertEquals("minecraft:block.note_block.pling", f.bob.playedSounds.getFirst().name().asString());
    }

    @Test
    void textNodeBoundariesDoNotCreateFalseMentionsOrAlterSurroundingText() throws Exception {
        Fixture f = new Fixture();
        Component email = f.plugin.getTextService().parse("<red>mail</red><blue>@Bob</blue>");
        assertEquals(email, f.handler.processCrossServerMentions("Alice", f.alice.uuid, email, f.viewers, f.muted));
        Component partial = f.plugin.getTextService().parse("<red>@Bob</red><blue>by</blue>");
        assertEquals(partial, f.handler.processCrossServerMentions("Alice", f.alice.uuid, partial, f.viewers, f.muted));
        assertTrue(f.scheduler.tasks.isEmpty());
        Component input = f.plugin.getTextService().parse("<red>Before @B</red><blue>ob after @</blue><green>Bob end</green>");
        Component result = f.handler.processCrossServerMentions("Alice", f.alice.uuid, input, f.viewers, f.muted);
        assertEquals("Before @Bob after @Bob end", plain(result));
        assertTrue(hasColor(result, NamedTextColor.RED));
        assertTrue(hasColor(result, NamedTextColor.BLUE));
        assertTrue(hasColor(result, NamedTextColor.GREEN));
        assertEquals(1, f.scheduler.tasks.size());
    }

    @Test
    void mentionCompletionsFollowJoinQuitAndVisibilityWithoutRemovingOtherPluginsSuggestions() throws Exception {
        Fixture f = new Fixture();
        withServer(f, () -> {
            ChatModule module = new ChatModule(f.plugin);
            try { set(module.getRenderManager(), "mentionHandler", f.handler); }
            catch (Exception e) { throw new AssertionError(e); }
            ChatPlayerListener listener = new ChatPlayerListener(f.plugin, module);
            f.alice.completions.add("other-plugin");
            listener.onJoin(new PlayerJoinEvent(f.bob.player, Component.empty()));
            assertEquals(Set.of("other-plugin"), f.alice.completions, "Must wait for the viewer's entity scheduler");
            f.scheduler.flush();
            assertEquals(Set.of("other-plugin", "@Bob"), f.alice.completions);
            assertEquals(Set.of("@Alice"), f.bob.completions);
            int additions = f.alice.completionAdds;
            f.handler.refreshCompletions();
            f.scheduler.flush();
            assertEquals(additions, f.alice.completionAdds, "Unchanged suggestions must not be resent");

            f.alice.visible = false;
            listener.onHide(new PlayerHideEntityEvent(f.alice.player, f.bob.player));
            f.scheduler.flush();
            assertEquals(Set.of("other-plugin"), f.alice.completions);
            f.alice.visible = true;
            listener.onShow(new PlayerShowEntityEvent(f.alice.player, f.bob.player));
            f.scheduler.flush();
            assertTrue(f.alice.completions.contains("@Bob"));

            f.bob.online = false;
            listener.onQuit(new PlayerQuitEvent(f.bob.player, Component.empty()));
            f.scheduler.flush();
            assertEquals(Set.of("other-plugin"), f.alice.completions);
        });
    }

    @Test
    void disabledMentionsAndVanishedPlayersAreRemovedFromCompletions() throws Exception {
        Fixture f = new Fixture();
        withServer(f, () -> {
            f.handler.refreshCompletions();
            f.scheduler.flush();
            assertTrue(f.alice.completions.contains("@Bob"));
            f.bob.vanished = true;
            f.handler.refreshCompletions();
            f.scheduler.flush();
            assertFalse(f.alice.completions.contains("@Bob"));
            f.bob.vanished = false;
            f.handler.refreshCompletions();
            f.scheduler.flush();
            assertTrue(f.alice.completions.contains("@Bob"));
            try { set(f.handler, "mentionsEnabled", false); }
            catch (Exception e) { throw new AssertionError(e); }
            f.handler.refreshCompletions();
            f.scheduler.flush();
            assertTrue(f.alice.completions.isEmpty());
            assertTrue(f.bob.completions.isEmpty());
        });
    }

    @Test
    void joinsAndVisibilityChangesOnlyCheckTheChangedTargetForExistingViewers() throws Exception {
        Fixture f = new Fixture();
        TestPlayer charlie = new TestPlayer("Charlie");
        withServer(f, () -> {
            f.bob.online = false;
            f.handler.refreshCompletions();
            f.scheduler.flush();
            f.alice.visibilityChecks = charlie.visibilityChecks = 0;
            f.bob.online = true;
            f.handler.addCompletions(f.bob.player);
            f.scheduler.flush();
            assertEquals(Set.of("@Bob", "@Charlie"), f.alice.completions);
            assertEquals(Set.of("@Alice", "@Charlie"), f.bob.completions);
            assertEquals(Set.of("@Alice", "@Bob"), charlie.completions);
            assertEquals(1, f.alice.visibilityChecks);
            assertEquals(1, charlie.visibilityChecks);

            f.alice.visibilityChecks = 0;
            f.alice.visible = false;
            f.handler.updateCompletion(f.alice.player, f.bob.player);
            f.scheduler.flush();
            assertEquals(Set.of("@Charlie"), f.alice.completions, "Unrelated completions must stay untouched");
            assertEquals(1, f.alice.visibilityChecks);
            f.alice.visible = true;
            f.handler.updateCompletion(f.alice.player, f.bob.player);
            f.scheduler.flush();
            assertEquals(Set.of("@Bob", "@Charlie"), f.alice.completions);
        }, charlie.player);
    }

    @Test
    void messagesWithoutMentionTokensDoNotInspectPlayers() throws Exception {
        Fixture f = new Fixture();
        for (String raw : List.of("Hello everyone", "mail@Bob @@Bob @abcdefghijklmnopq")) {
            Component input = Component.text(raw);
            assertSame(input, f.handler.processCrossServerMentions("Alice", f.alice.uuid, input, f.viewers, f.muted));
        }
        assertEquals(0, f.alice.onlineChecks);
        assertEquals(0, f.bob.onlineChecks);
        assertTrue(f.scheduler.tasks.isEmpty());
    }

    private static void withServer(Fixture f, Runnable test, Player... extraPlayers) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        Object old = field.get(null);
        List<Player> online = new ArrayList<>(List.of(f.alice.player, f.bob.player));
        online.addAll(List.of(extraPlayers));
        Server server = (Server) Proxy.newProxyInstance(MentionHandlerTest.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> method.getName().equals("getOnlinePlayers") ? online : null);
        try { field.set(null, server); test.run(); }
        finally { field.set(null, old); }
    }

    @Test
    void publicChatFiltersAudienceBeforeMentioningAndSendsUnstyledTransportText() throws Exception {
        Fixture f = new Fixture();
        CapturingModule module = new CapturingModule(f.plugin);
        set(module, "crossServerEnabled", true);
        set(module, "syncPublicChat", true);
        set(module, "isStructuredFormat", true);
        set(module, "structuredFormatMessage", "Header: ");
        set(module.getRenderManager(), "mentionHandler", f.handler);
        module.getAllPlayerData().put(f.bob.uuid, new ChatPlayerData(f.bob.uuid, "Bob"));
        ChatListener listener = new ChatListener(f.plugin, module);

        AsyncChatEvent event = event(f, "@bOB");
        listener.onAsyncChat(event);
        assertFalse(event.isCancelled());
        assertTrue(hasColor(event.renderer().render(f.alice.player, Component.text("Alice"), event.message(), f.bob.player), NamedTextColor.GOLD));
        assertEquals("@bOB", module.transported);
        assertEquals(1, f.scheduler.tasks.size());

        f.scheduler.tasks.clear();
        module.setChatToggled(f.bob.uuid, true);
        AsyncChatEvent toggled = event(f, "@Bob");
        listener.onAsyncChat(toggled);
        assertFalse(toggled.viewers().contains(f.bob.player));
        assertTrue(f.scheduler.tasks.isEmpty());

        module.setChatToggled(f.bob.uuid, false);
        module.getPlayerData(f.bob.uuid).addIgnored(f.alice.uuid);
        AsyncChatEvent ignored = event(f, "@Bob");
        listener.onAsyncChat(ignored);
        assertFalse(ignored.viewers().contains(f.bob.player));
        assertTrue(f.scheduler.tasks.isEmpty());

        module.getAllPlayerData().clear();
        AsyncChatEvent loading = event(f, "@Bob");
        listener.onAsyncChat(loading);
        assertFalse(loading.viewers().contains(f.bob.player));
        assertTrue(f.scheduler.tasks.isEmpty());
    }

    @Test
    void incomingPublicChatDoesNotPingRecipientsWhoseChatIsHidden() throws Exception {
        Fixture f = new Fixture();
        ChatModule module = new ChatModule(f.plugin);
        set(module, "syncPublicChat", true);
        set(module, "isStructuredFormat", true);
        set(module, "structuredFormatMessage", "Header: ");
        set(module.getRenderManager(), "mentionHandler", f.handler);
        module.getAllPlayerData().put(f.bob.uuid, new ChatPlayerData(f.bob.uuid, "Bob"));
        String payload = new com.google.gson.Gson().toJson(Map.of(
                "senderName", "Alice", "senderUuid", f.alice.uuid.toString(), "message", "@bOB"));
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Server oldServer = (Server) serverField.get(null);
        ConsoleCommandSender console = (ConsoleCommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class}, (proxy, method, args) -> null);
        Server server = (Server) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getOnlinePlayers" -> List.of(f.bob.player);
                    case "getConsoleSender" -> console;
                    default -> null;
                });
        try {
            serverField.set(null, server);
            module.setChatToggled(f.bob.uuid, true);
            module.handleIncomingPublicChat(payload);
            f.scheduler.flush();
            assertEquals(0, f.bob.titles);
            assertTrue(f.bob.messages.isEmpty());

            module.setChatToggled(f.bob.uuid, false);
            module.handleIncomingPublicChat(payload);
            f.scheduler.flush();
            assertEquals(1, f.bob.titles);
            assertEquals(1, f.bob.messages.size());
            assertEquals("Header: @Bob", plain(f.bob.messages.getFirst()));
            assertTrue(hasColor(f.bob.messages.getFirst(), NamedTextColor.GOLD));
        } finally {
            serverField.set(null, oldServer);
        }
    }

    private static AsyncChatEvent event(Fixture f, String message) {
        return new AsyncChatEvent(true, f.alice.player, new HashSet<>(f.viewers),
                ChatRenderer.defaultRenderer(), Component.text(message), Component.text(message),
                net.kyori.adventure.chat.SignedMessage.system(message, Component.text(message)));
    }

    @Test
    void localAndRemoteMentionsUseCanonicalNamesAndNotifyOnce() throws Exception {
        Fixture f = new Fixture();
        ChatMessage chat = f.chat("Hey @bOB, @Bob! @Nobody");
        Component local = f.handler.processMentions(chat, Component.text(chat.getRawMessage()), f.viewers, f.muted);
        assertEquals("Hey @Bob, @Bob! @Nobody", plain(local));
        assertTrue(hasColor(local, NamedTextColor.GOLD));
        assertEquals(Set.of(f.bob.uuid), chat.getMentions());
        assertEquals(1, f.scheduler.tasks.size());
        assertEquals("Hey @bOB, @Bob! @Nobody", chat.getRawMessage(), "Transport text must remain unstyled");

        f.scheduler.tasks.clear();
        set(f.handler, "mentionPrefix", "<gold>@{player}</gold>");
        Component remote = f.remote(chat.getRawMessage());
        assertEquals(local, remote);
        assertEquals(1, f.scheduler.tasks.size());
    }

    @Test
    void emailsPartialUsernamesAndDoubleAtAreNotMentions() throws Exception {
        Fixture f = new Fixture();
        String input = "mail@Bob example@Bob.com @Bobby @Bob_1 @Bobé @Bob123456789012345 @@Bob é@Bob";
        Component result = f.remote(input);
        assertEquals(input, plain(result));
        assertFalse(hasColor(result, NamedTextColor.GOLD));
        assertTrue(f.scheduler.tasks.isEmpty());
        assertEquals("(@Bob)!", plain(f.remote("(@bOb)!")));
        assertEquals(1, f.scheduler.tasks.size());
    }

    @Test
    void hoverTextAndClickCommandsCannotTriggerMentions() throws Exception {
        Fixture f = new Fixture();
        Component input = Component.text("Preview")
                .hoverEvent(HoverEvent.showText(Component.text("@Bob")))
                .clickEvent(ClickEvent.suggestCommand("/msg @Bob"));
        assertEquals(input, f.handler.processCrossServerMentions("Alice", f.alice.uuid, input, f.viewers, f.muted));
        assertTrue(f.scheduler.tasks.isEmpty());

        Component visible = input.append(Component.text(" @Bob"));
        Component result = f.handler.processCrossServerMentions("Alice", f.alice.uuid, visible, f.viewers, f.muted);
        assertEquals(input.hoverEvent(), result.hoverEvent());
        assertEquals(input.clickEvent(), result.clickEvent());
        assertEquals(1, f.scheduler.tasks.size());
    }

    @Test
    void selfHiddenVanishedOfflineAndExcludedTargetsAreNotMentioned() throws Exception {
        Fixture f = new Fixture();
        assertEquals(Component.text("@Alice"), f.remote("@Alice"));
        f.bob.vanished = true;
        assertEquals(Component.text("@Bob"), f.remote("@Bob"));
        f.bob.vanished = false;
        f.bob.online = false;
        assertEquals(Component.text("@Bob"), f.remote("@Bob"));
        f.bob.online = true;
        f.viewers.remove(f.bob.player);
        assertEquals(Component.text("@Bob"), f.remote("@Bob"));
        f.viewers.add(f.bob.player);
        f.alice.visible = false;
        ChatMessage chat = f.chat("@Bob");
        assertEquals(Component.text("@Bob"), f.handler.processMentions(chat, Component.text("@Bob"), f.viewers, f.muted));
        assertTrue(chat.getMentions().isEmpty());
        assertTrue(f.scheduler.tasks.isEmpty());
    }

    @Test
    void pingMuteKeepsHighlightAndStopsQueuedNotifications() throws Exception {
        Fixture f = new Fixture();
        f.muted.add(f.bob.uuid);
        assertTrue(hasColor(f.remote("@Bob"), NamedTextColor.GOLD));
        assertTrue(f.scheduler.tasks.isEmpty());
        f.muted.clear();
        f.remote("@Bob");
        f.muted.add(f.bob.uuid);
        f.scheduler.flush();
        assertEquals(0, f.bob.titles);
        f.muted.clear();
        f.remote("@Bob @Bob");
        f.scheduler.flush();
        assertEquals(1, f.bob.titles);
    }

    @Test
    void disabledOrCancelledMessagesDoNotNotify() throws Exception {
        Fixture f = new Fixture();
        ChatMessage chat = f.chat("@Bob");
        chat.cancel("filter");
        assertEquals(Component.text("@Bob"), f.handler.processMentions(chat, Component.text("@Bob"), f.viewers, f.muted));
        set(f.handler, "mentionsEnabled", false);
        assertEquals(Component.text("@Bob"), f.remote("@Bob"));
        assertTrue(f.scheduler.tasks.isEmpty());
    }

    @Test
    void publicAudienceFailsClosedDuringLoadAndHonorsBothBypasses() throws Exception {
        ChatModule module = allocate(ChatModule.class);
        Map<UUID, ChatPlayerData> data = new HashMap<>();
        Set<UUID> toggled = new HashSet<>();
        set(module, "playerDataMap", data);
        set(module, "chatToggledPlayers", toggled);
        UUID viewer = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        assertFalse(module.canReceivePublicChat(viewer, sender, true, true));
        ChatPlayerData prefs = new ChatPlayerData(viewer, "Bob");
        data.put(viewer, prefs);
        assertTrue(module.canReceivePublicChat(viewer, sender, false, false));
        prefs.addIgnored(sender);
        assertFalse(module.canReceivePublicChat(viewer, sender, false, false));
        assertTrue(module.canReceivePublicChat(viewer, sender, true, false));
        toggled.add(viewer);
        assertFalse(module.canReceivePublicChat(viewer, sender, true, false));
        assertFalse(module.canReceivePublicChat(viewer, sender, false, true));
        assertTrue(module.canReceivePublicChat(viewer, sender, true, true));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static boolean hasColor(Component component, NamedTextColor color) {
        return color.equals(component.color()) || component.children().stream().anyMatch(child -> hasColor(child, color));
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Class<?> type = object.getClass();
        while (type != null) {
            try { type.getDeclaredField(name); break; }
            catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static class Scheduler extends FriendlyScheduler {
        final List<Runnable> tasks = new ArrayList<>();
        Scheduler() { super(null); }
        @Override public Object runEntity(Entity entity, Runnable task) { tasks.add(task); return null; }
        void flush() { List.copyOf(tasks).forEach(Runnable::run); tasks.clear(); }
    }

    private static class CapturingModule extends ChatModule {
        String transported;
        CapturingModule(FriendlyCore plugin) { super(plugin); }
        @Override public void broadcastCrossServerPublicChat(Player sender, String raw, List<RenderSnapshot> snapshots) {
            transported = raw;
        }
    }

    private static class TestPlayer {
        final UUID uuid = UUID.randomUUID();
        final Player player;
        boolean online = true;
        boolean vanished;
        boolean visible = true;
        int titles;
        int sounds;
        final List<net.kyori.adventure.sound.Sound> playedSounds = new ArrayList<>();
        int completionAdds;
        int visibilityChecks;
        int onlineChecks;
        final Set<String> completions = new HashSet<>();
        final List<Component> messages = new ArrayList<>();

        TestPlayer(String name) {
            MetadataValue metadata = (MetadataValue) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{MetadataValue.class}, (proxy, method, args) -> method.getName().equals("asBoolean") ? vanished : null);
            player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getName" -> name;
                        case "getUniqueId" -> uuid;
                        case "isOnline" -> { onlineChecks++; yield online; }
                        case "canSee" -> { visibilityChecks++; yield visible; }
                        case "hasPermission" -> false;
                        case "getMetadata" -> vanished ? List.of(metadata) : List.of();
                        case "showTitle" -> { titles++; yield null; }
                        case "playSound" -> {
                            sounds++;
                            playedSounds.add((net.kyori.adventure.sound.Sound) args[0]);
                            yield null;
                        }
                        case "sendMessage" -> { messages.add((Component) args[0]); yield null; }
                        case "addCustomChatCompletions" -> {
                            completionAdds++;
                            completions.addAll((java.util.Collection<String>) args[0]);
                            yield null;
                        }
                        case "removeCustomChatCompletions" -> {
                            completions.removeAll((java.util.Collection<String>) args[0]);
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> name;
                        default -> null;
                    });
        }
    }

    private static class Fixture {
        final TestPlayer alice = new TestPlayer("Alice");
        final TestPlayer bob = new TestPlayer("Bob");
        final List<Audience> viewers = new ArrayList<>(List.of(alice.player, bob.player));
        final Set<UUID> muted = new HashSet<>();
        final Scheduler scheduler = new Scheduler();
        final FriendlyCore plugin;
        final MentionHandler handler;

        Fixture() throws Exception {
            plugin = allocate(FriendlyCore.class);
            set(plugin, "classLoader", MentionHandlerTest.class.getClassLoader());
            set(plugin, "logger", java.util.logging.Logger.getAnonymousLogger());
            set(plugin, "textService", new TextService());
            set(plugin, "scheduler", scheduler);
            handler = new MentionHandler(plugin);
            set(handler, "mentionsEnabled", true);
            set(handler, "mentionPrefix", "<gold>@%player%</gold>");
            set(handler, "titleEnabled", true);
            set(handler, "soundEnabled", true);
            set(handler, "soundName", "block.note_block.pling");
            set(handler, "soundVolume", 1.0f);
            set(handler, "soundPitch", 2.0f);
            set(handler, "titleText", "Mentioned!");
            set(handler, "subtitleText", "%sender% mentioned %player%");
        }

        ChatMessage chat(String raw) { return new ChatMessage(alice.player, raw, Component.text(raw), ChatChannel.PUBLIC); }
        Component remote(String raw) {
            return handler.processCrossServerMentions("Alice", alice.uuid, Component.text(raw), viewers, muted);
        }
    }
}
