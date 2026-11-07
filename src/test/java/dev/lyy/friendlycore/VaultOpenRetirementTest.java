package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.modules.teams.model.VaultSession;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class VaultOpenRetirementTest {
    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private FriendlyScheduler scheduler(boolean enabled) throws Exception {
        Plugin plugin = proxy(Plugin.class, (object, method, args) -> {
            if (method.getName().equals("isEnabled")) return enabled;
            throw new UnsupportedOperationException(method.getName());
        });
        FriendlyScheduler scheduler = new FriendlyScheduler(plugin);
        var folia = FriendlyScheduler.class.getDeclaredField("isFolia");
        folia.setAccessible(true);
        folia.setBoolean(scheduler, true);
        return scheduler;
    }

    private Entity entity(EntityScheduler scheduler) {
        return proxy(Entity.class, (object, method, args) -> {
            if (method.getName().equals("getScheduler")) return scheduler;
            throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    void alreadyRetiredPlayerReleasesUnopenedVault() throws Exception {
        FriendlyScheduler scheduler = scheduler(true);
        try {
            VaultSession session = new VaultSession(UUID.randomUUID(), "one");
            session.ready();
            AtomicInteger releases = new AtomicInteger();
            EntityScheduler retired = proxy(EntityScheduler.class, (object, method, args) -> null);
            Object result = scheduler.runEntity(entity(retired), () -> fail("Retired player must not open"), () -> {
                if (session.beginRelease()) releases.incrementAndGet();
            });
            assertNull(result);
            assertEquals(1, releases.get());
            assertFalse(session.open());
        } finally {
            scheduler.getAsyncExecutor().shutdownNow();
        }
    }

    @Test
    void retirementAfterSchedulingReleasesOnceAndCannotReopen() throws Exception {
        FriendlyScheduler scheduler = scheduler(true);
        try {
            VaultSession session = new VaultSession(UUID.randomUUID(), "one");
            session.ready();
            AtomicInteger releases = new AtomicInteger();
            AtomicReference<Runnable> retirement = new AtomicReference<>();
            ScheduledTask task = proxy(ScheduledTask.class, (object, method, args) -> null);
            EntityScheduler scheduled = proxy(EntityScheduler.class, (object, method, args) -> {
                retirement.set((Runnable) args[2]);
                return task;
            });
            assertSame(task, scheduler.runEntity(entity(scheduled), () -> fail("Player retired"), () -> {
                if (session.beginRelease()) releases.incrementAndGet();
            }));
            assertEquals(0, releases.get());
            retirement.get().run();
            retirement.get().run();
            assertEquals(1, releases.get());
            assertFalse(session.open());
        } finally {
            scheduler.getAsyncExecutor().shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulSchedulingOpensWithoutReleasing() throws Exception {
        FriendlyScheduler scheduler = scheduler(true);
        try {
            VaultSession session = new VaultSession(UUID.randomUUID(), "one");
            session.ready();
            AtomicReference<Consumer<ScheduledTask>> callback = new AtomicReference<>();
            ScheduledTask task = proxy(ScheduledTask.class, (object, method, args) -> null);
            EntityScheduler scheduled = proxy(EntityScheduler.class, (object, method, args) -> {
                callback.set((Consumer<ScheduledTask>) args[1]);
                return task;
            });
            scheduler.runEntity(entity(scheduled), () -> assertTrue(session.open()), () -> fail("Live player must not retire"));
            callback.get().accept(task);
            assertTrue(session.isOpen());
            assertFalse(session.beginRelease(), "An active vault must use the save path");
        } finally {
            scheduler.getAsyncExecutor().shutdownNow();
        }
    }

    @Test
    void disabledPluginRunsCleanupWithoutScheduling() throws Exception {
        FriendlyScheduler scheduler = scheduler(false);
        try {
            AtomicInteger releases = new AtomicInteger();
            assertNull(scheduler.runEntity(null, () -> fail("Plugin disabled"), releases::incrementAndGet));
            assertEquals(1, releases.get());
        } finally {
            scheduler.getAsyncExecutor().shutdownNow();
        }
    }
}
