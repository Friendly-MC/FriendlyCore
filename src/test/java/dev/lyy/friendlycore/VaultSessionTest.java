package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.teams.model.VaultSession;
import dev.lyy.friendlycore.modules.teams.model.VaultRecoveryJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VaultSessionTest {
    @Test
    void openingCannotBeSavedAndRepeatedCloseOnlySavesOnce() throws Exception {
        VaultSession session = new VaultSession(UUID.randomUUID(), "one");
        assertFalse(session.beginSave());
        session.ready();
        assertFalse(session.beginSave());
        session.open();
        AtomicInteger saves = new AtomicInteger();
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (int i = 0; i < 32; i++) work.add(CompletableFuture.runAsync(() -> {
            if (session.beginSave()) saves.incrementAndGet();
        }));
        CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).join();
        assertEquals(1, saves.get());
        assertFalse(session.isOpen());
        assertFalse(session.beginRelease());
        session.ready(); session.open();
        assertFalse(session.isOpen(), "A closing session must not reopen");
    }

    @Test
    void reopeningSamePlayerUsesDifferentToken() {
        UUID player = UUID.randomUUID();
        assertNotEquals(new VaultSession(player, "one").token(), new VaultSession(player, "one").token());
    }

    @Test
    void canceledOpenCannotLaterBecomeActive() {
        VaultSession session = new VaultSession(UUID.randomUUID(), "one");
        session.ready();
        assertTrue(session.beginRelease());
        session.open();
        assertFalse(session.isOpen());
        assertFalse(session.beginSave());
    }

    @Test
    void journalPreservesExactSnapshotAndIdentity(@TempDir Path directory) throws Exception {
        VaultSession session = new VaultSession(UUID.randomUUID(), "one");
        UUID team = UUID.randomUUID();
        Path file = VaultRecoveryJournal.write(directory, team, session, "ABC+/==");
        Properties data = new Properties();
        try (var reader = Files.newBufferedReader(file)) { data.load(reader); }
        assertEquals("ABC+/==", data.getProperty("snapshot"));
        assertEquals(session.token().toString(), data.getProperty("token"));
        assertEquals(session.playerUuid().toString(), data.getProperty("player"));
        assertEquals(team.toString(), data.getProperty("team"));
        assertFalse(Files.exists(directory.resolve(file.getFileName() + ".tmp")));
    }
}
