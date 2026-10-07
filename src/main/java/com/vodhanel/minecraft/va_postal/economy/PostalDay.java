package com.vodhanel.minecraft.va_postal.economy;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * The Postal economy day (docs/design/economy.md §11): a timer of configurable length (24 h by default)
 * on which upkeep, the server-owned sweep and the dividend run. The time of the last day is persisted, so
 * a restart neither skips nor repeats a day, and a server that was down for several days runs one
 * catch-up day, keeping the original time of day.
 */
public final class PostalDay {
    /** How often to check whether a day is due (ticks). */
    private static final long CHECK_TICKS = 1200L;

    private static final List<Runnable> actions = new ArrayList<>();
    private static int task = -1;
    private static LongSupplier length_seconds;
    private static LongSupplier last_day;
    private static LongConsumer save_last_day;

    private PostalDay() {
    }

    /** Registers something to run on each Postal day, in registration order. */
    public static synchronized void on_new_day(Runnable action) {
        actions.add(action);
    }

    /**
     * Starts the timer. {@code last} / {@code save} read and persist the epoch second the last day ran
     * (0: never); a first start begins counting from now rather than running a day immediately.
     */
    public static synchronized void start(Plugin plugin, LongSupplier length, LongSupplier last, LongConsumer save) {
        stop();
        length_seconds = length;
        last_day = last;
        save_last_day = save;
        if (last_day.getAsLong() <= 0L) {
            save_last_day.accept(now());
        }
        task = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, PostalDay::check, CHECK_TICKS, CHECK_TICKS);
    }

    public static synchronized void stop() {
        if (task >= 0) {
            Bukkit.getScheduler().cancelTask(task);
            task = -1;
        }
    }

    /** Runs a day now (admin command / tests) and restarts the count from now. */
    public static synchronized void run_now() {
        if (save_last_day != null) {
            save_last_day.accept(now());
        }
        run();
    }

    /** Seconds until the next day, or -1 if the timer isn't running. */
    public static synchronized long seconds_to_next_day() {
        if (task < 0) {
            return -1L;
        }
        long length = Math.max(1L, length_seconds.getAsLong());
        return Math.max(0L, last_day.getAsLong() + length - now());
    }

    private static synchronized void check() {
        long next = due(last_day.getAsLong(), now(), length_seconds.getAsLong());
        if (next < 0L) {
            return;
        }
        save_last_day.accept(next);
        run();
    }

    private static void run() {
        for (Runnable action : new ArrayList<>(actions)) {
            try {
                action.run();
            } catch (RuntimeException e) {
                Bukkit.getLogger().warning("[Postal] Postal day action failed: " + e);
            }
        }
    }

    /**
     * If a day is due, the new "last day" time to record, else -1. Missed days collapse into one, and the
     * result stays on the original schedule (last + n·length), so the time of day doesn't drift.
     */
    static long due(long last, long now, long length) {
        if (length <= 0L || now - last < length) {
            return -1L;
        }
        return now - ((now - last) % length);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }
}
