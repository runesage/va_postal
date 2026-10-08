package com.vodhanel.minecraft.va_postal.store;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Times every call into the mail store, for {@code /postal store}: how many, how long on average and at worst,
 * how many on the main thread (where a slow database stalls the server), and how many failed. A remote MySQL
 * adds network latency to each call, so this is the number to watch.
 */
public final class StoreStats {
    public final AtomicLong calls = new AtomicLong();
    public final AtomicLong main_thread_calls = new AtomicLong();
    public final AtomicLong total_nanos = new AtomicLong();
    public final AtomicLong main_thread_nanos = new AtomicLong();
    public final AtomicLong max_nanos = new AtomicLong();
    public final AtomicLong failures = new AtomicLong();
    public volatile String slowest_call = "";
    public volatile String last_failure = "";
    public final long since = System.currentTimeMillis();
    /** Set while the database can't be reached: calls fail at once instead of each waiting for a connection. */
    public volatile String unavailable;
    public volatile long unavailable_since;

    /** Methods that never touch the database, so they work while it's unavailable. */
    private static final Set<String> LOCAL = Set.of("server_id", "dialect", "close");
    private static ScheduledExecutorService probe;
    /** How long between checks while the database is unavailable (shorter in tests). */
    static long probe_millis = 5000L;

    /** Wraps {@code store} so every call is timed into {@code stats}; {@code main_thread} says where a call runs. */
    public static MailStore timed(MailStore store, StoreStats stats, BooleanSupplier main_thread) {
        return timed(store, stats, main_thread, msg -> { });
    }

    /**
     * As above, with a circuit breaker: when a call fails because the database can't be reached, further calls
     * fail at once (the callers already cope with a failed store call: mail stays where it is) rather than each
     * blocking for the connection timeout, possibly on the main thread. A background check retries every 5
     * seconds and lets calls through again once the database answers. {@code log} hears when it goes and comes back.
     */
    public static MailStore timed(MailStore store, StoreStats stats, BooleanSupplier main_thread, Consumer<String> log) {
        InvocationHandler h = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(store, args);
            }
            if (stats.unavailable != null && !LOCAL.contains(method.getName())) {
                stats.failures.incrementAndGet();
                throw new StoreException("The mail store is unavailable: " + stats.unavailable, null);
            }
            long t0 = System.nanoTime();
            try {
                return method.invoke(store, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof StoreException) {
                    stats.failures.incrementAndGet();
                    stats.last_failure = method.getName() + ": " + e.getCause().getMessage()
                            + (e.getCause().getCause() != null ? " (" + e.getCause().getCause().getMessage() + ")" : "");
                    if (connection_lost(e.getCause())) {
                        stats.trip(store, log);
                    }
                }
                throw e.getCause();
            } finally {
                stats.record(method, System.nanoTime() - t0, main_thread.getAsBoolean());
            }
        };
        return (MailStore) Proxy.newProxyInstance(MailStore.class.getClassLoader(), new Class<?>[]{MailStore.class}, h);
    }

    /** True if a failure means the database couldn't be reached (rather than, say, a constraint). */
    static boolean connection_lost(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLTransientConnectionException || c instanceof SQLRecoverableException) {
                return true;
            }
            if (c instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
        }
        return false;
    }

    private synchronized void trip(MailStore store, Consumer<String> log) {
        if (unavailable != null) {
            return;
        }
        unavailable = last_failure;
        unavailable_since = System.currentTimeMillis();
        log.accept("The mail store can't be reached (" + last_failure + "). Mail moves wait until it's back; "
                + "retrying every 5 seconds.");
        if (probe == null) {
            probe = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Postal-store-probe");
                t.setDaemon(true);
                return t;
            });
        }
        probe.schedule(() -> retry(store, log), probe_millis, TimeUnit.MILLISECONDS);
    }

    private void retry(MailStore store, Consumer<String> log) {
        try {
            store.schema_version();
            long down = (System.currentTimeMillis() - unavailable_since) / 1000L;
            unavailable = null;
            log.accept("The mail store is back after " + down + " seconds.");
        } catch (RuntimeException e) {
            probe.schedule(() -> retry(store, log), probe_millis, TimeUnit.MILLISECONDS);
        }
    }

    void record(Method m, long nanos, boolean main) {
        calls.incrementAndGet();
        total_nanos.addAndGet(nanos);
        if (main) {
            main_thread_calls.incrementAndGet();
            main_thread_nanos.addAndGet(nanos);
        }
        long max = max_nanos.get();
        while (nanos > max) {
            if (max_nanos.compareAndSet(max, nanos)) {
                slowest_call = m.getName();
                break;
            }
            max = max_nanos.get();
        }
    }

    public double average_ms() {
        long n = calls.get();
        return n == 0 ? 0.0D : total_nanos.get() / 1e6D / n;
    }

    public double max_ms() {
        return max_nanos.get() / 1e6D;
    }
}
