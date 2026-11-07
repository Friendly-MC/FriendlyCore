package dev.lyy.friendlycore.modules.teams.model;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.UUID;

/** Recovery evidence only. Never replay a snapshot without reconciling player data. */
public final class VaultRecoveryJournal {
    private VaultRecoveryJournal() {}

    public static Path write(Path directory, UUID teamUuid, VaultSession session, String snapshot) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(teamUuid + "-" + session.token() + ".properties");
        Path temporary = directory.resolve(target.getFileName() + ".tmp");
        Properties data = new Properties();
        data.setProperty("team", teamUuid.toString());
        data.setProperty("token", session.token().toString());
        data.setProperty("player", session.playerUuid().toString());
        data.setProperty("server", session.serverId());
        data.setProperty("snapshot", snapshot);
        StringWriter writer = new StringWriter();
        data.store(writer, "Pending team vault close; reconcile before recovery");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(writer.toString());
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
