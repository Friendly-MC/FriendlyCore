package dev.lyy.friendlycore.common;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Cross-platform task scheduler providing seamless compatibility between Folia
 * (RegionScheduler, EntityScheduler, AsyncScheduler, GlobalRegionScheduler)
 * and standard Paper/Spigot schedulers.
 */
public class FriendlyScheduler {

    private final Plugin plugin;
    private final boolean isFolia;
    private final ExecutorService asyncExecutor;
    private final ExecutorService migrationExecutor;
    private final AtomicLong lastSaturationWarning = new AtomicLong(0L);

    public FriendlyScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.isFolia = checkFolia();
        // Keep background work bounded. Spawning a thread per rejected task turns an
        // external outage (database/Redis) into an unrecoverable JVM thread storm.
        // Migration coordinators join database futures. Give them a separate, serial
        // lane so they cannot occupy every database worker while waiting for children.
        this.migrationExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(8), r -> {
                    Thread t = new Thread(r, "FriendlyCore-Migration");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.asyncExecutor = new ThreadPoolExecutor(
                4, 32, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2048),
                r -> {
                    Thread t = new Thread(r, "FriendlyCore-AsyncPool");
                    t.setDaemon(true);
                    return t;
                },
                (runnable, executor) -> {
                    long now = System.currentTimeMillis();
                    long previous = lastSaturationWarning.get();
                    if (now - previous >= 5000L && lastSaturationWarning.compareAndSet(previous, now)) {
                        plugin.getLogger().severe("[FriendlyScheduler] Async task queue saturated; rejecting background work to protect server memory and tick threads.");
                    }
                    throw new RejectedExecutionException("FriendlyCore async task queue is saturated");
                });
    }

    private static boolean checkFolia() {
        try {
            java.lang.reflect.Method isRegionMethod = Bukkit.class.getMethod("isRegionServer");
            return (boolean) isRegionMethod.invoke(null);
        } catch (Throwable ignored) {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
    }

    public boolean isFolia() {
        return isFolia;
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public <T> java.util.concurrent.CompletableFuture<T> submitMigration(java.util.function.Supplier<T> task) {
        try {
            return java.util.concurrent.CompletableFuture.supplyAsync(task, migrationExecutor);
        } catch (RejectedExecutionException rejected) {
            return java.util.concurrent.CompletableFuture.failedFuture(rejected);
        }
    }

    /**
     * Run a task asynchronously.
     */
    public Object runAsync(Runnable runnable) {
        if (!plugin.isEnabled()) return null;
        try {
            if (isFolia) {
                return Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run());
            } else {
                return Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            return null;
        }
    }

    /**
     * Run a task asynchronously after a delay in ticks.
     */
    public Object runAsyncLater(Runnable runnable, long delayTicks) {
        if (!plugin.isEnabled()) return null;
        try {
            if (isFolia) {
                return Bukkit.getAsyncScheduler().runDelayed(plugin, task -> runnable.run(), delayTicks * 50L, TimeUnit.MILLISECONDS);
            } else {
                return Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delayTicks);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            return null;
        }
    }

    /**
     * Run a repeating asynchronous task.
     */
    public Object runAsyncTimer(Runnable runnable, long delayTicks, long periodTicks) {
        if (!plugin.isEnabled()) return null;
        try {
            if (isFolia) {
                return Bukkit.getAsyncScheduler().runAtFixedRate(
                        plugin,
                        task -> runnable.run(),
                        delayTicks * 50L,
                        periodTicks * 50L,
                        TimeUnit.MILLISECONDS
                );
            } else {
                return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            return null;
        }
    }

    /**
     * Run a task on the global region / main thread.
     */
    public Object runGlobal(Runnable runnable) {
        if (!plugin.isEnabled()) return null;
        try {
            if (isFolia) {
                return Bukkit.getGlobalRegionScheduler().run(plugin, task -> runnable.run());
            } else {
                return Bukkit.getScheduler().runTask(plugin, runnable);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            return null;
        }
    }

    /**
     * Run a task on the global region after delay.
     */
    public Object runGlobalLater(Runnable runnable, long delayTicks) {
        if (!plugin.isEnabled()) return null;
        try {
            if (isFolia) {
                return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> runnable.run(), Math.max(1, delayTicks));
            } else {
                return Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            return null;
        }
    }

    /**
     * Run a repeating task on the global region.
     */
    public Object runGlobalTimer(Runnable runnable, long delayTicks, long periodTicks) {
        if (!plugin.isEnabled()) return null;
        try {
            if (isFolia) {
                return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> runnable.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
            } else {
                return Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            return null;
        }
    }

    /**
     * Run a task synchronized with an entity's region.
     */
    public Object runEntity(Entity entity, Runnable runnable) {
        return runEntity(entity, runnable, null);
    }

    /**
     * Run a task synchronized with an entity's region and notify the caller if
     * Folia retires the entity before the task can execute.
     */
    public Object runEntity(Entity entity, Runnable runnable, Runnable retired) {
        if (!plugin.isEnabled() || entity == null) {
            if (retired != null) retired.run();
            return null;
        }
        try {
            if (isFolia) {
                ScheduledTask scheduled = entity.getScheduler().run(plugin, task -> runnable.run(), retired);
                // An already-retired scheduler returns null without invoking either callback.
                if (scheduled == null && retired != null) retired.run();
                return scheduled;
            } else {
                if (!entity.isValid()) {
                    if (retired != null) retired.run();
                    return null;
                }
                if (Bukkit.isPrimaryThread()) {
                    runnable.run();
                    return null;
                }
                return Bukkit.getScheduler().runTask(plugin, runnable);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            if (retired != null) retired.run();
            return null;
        }
    }

    /**
     * Run a task synchronized with an entity's region after delay.
     */
    public Object runEntityLater(Entity entity, Runnable runnable, long delayTicks) {
        return runEntityLater(entity, runnable, null, delayTicks);
    }

    /** Run a delayed entity task and invoke {@code retired} if its Folia region retires first. */
    public Object runEntityLater(Entity entity, Runnable runnable, Runnable retired, long delayTicks) {
        if (!plugin.isEnabled() || entity == null) {
            if (retired != null) retired.run();
            return null;
        }
        try {
            if (isFolia) {
                return entity.getScheduler().runDelayed(plugin, task -> runnable.run(), retired, Math.max(1, delayTicks));
            } else {
                if (!entity.isValid()) {
                    if (retired != null) retired.run();
                    return null;
                }
                return Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            if (retired != null) retired.run();
            return null;
        }
    }

    /**
     * Run a repeating task bound to an entity.
     */
    public Object runEntityTimer(Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
        if (!plugin.isEnabled() || entity == null) {
            return null;
        }
        try {
            if (isFolia) {
                return entity.getScheduler().runAtFixedRate(plugin, task -> runnable.run(), null, Math.max(1, delayTicks), Math.max(1, periodTicks));
            } else {
                if (!entity.isValid()) return null;
                return Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            return null;
        }
    }

    /**
     * Run a task synchronized with a specific location's chunk region.
     */
    public Object runLocation(Location location, Runnable runnable) {
        if (!plugin.isEnabled()) return null;
        if (location == null || location.getWorld() == null) {
            return runGlobal(runnable);
        }
        try {
            if (isFolia) {
                return Bukkit.getRegionScheduler().run(plugin, location, task -> runnable.run());
            } else {
                return Bukkit.getScheduler().runTask(plugin, runnable);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            return null;
        }
    }

    /**
     * Run a delayed task on a specific location's region.
     */
    public Object runLocationLater(Location location, Runnable runnable, long delayTicks) {
        if (!plugin.isEnabled()) return null;
        if (location == null || location.getWorld() == null) {
            return runGlobalLater(runnable, delayTicks);
        }
        try {
            if (isFolia) {
                return Bukkit.getRegionScheduler().runDelayed(plugin, location, task -> runnable.run(), Math.max(1, delayTicks));
            } else {
                return Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
            }
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            return null;
        }
    }

    /**
     * Cancel a running task object.
     */
    public void cancel(Object task) {
        if (task == null) return;
        if (task instanceof ScheduledTask scheduledTask) {
            scheduledTask.cancel();
        } else if (task instanceof BukkitTask bukkitTask) {
            bukkitTask.cancel();
        }
    }

    /**
     * Shutdown scheduler and executors gracefully.
     */
    public void shutdown() {
        if (isFolia) {
            try {
                Bukkit.getAsyncScheduler().cancelTasks(plugin);
                Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            } catch (Throwable ignored) {}
        } else {
            try {
                Bukkit.getScheduler().cancelTasks(plugin);
            } catch (Throwable ignored) {}
        }
        migrationExecutor.shutdown();
        try {
            if (!migrationExecutor.awaitTermination(3, TimeUnit.SECONDS)) migrationExecutor.shutdownNow();
        } catch (InterruptedException e) {
            migrationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
