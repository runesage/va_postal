package com.vodhanel.minecraft.va_postal.store;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

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

    /** Wraps {@code store} so every call is timed into {@code stats}; {@code main_thread} says where a call runs. */
    public static MailStore timed(MailStore store, StoreStats stats, BooleanSupplier main_thread) {
        InvocationHandler h = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(store, args);
            }
            long t0 = System.nanoTime();
            try {
                return method.invoke(store, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof StoreException) {
                    stats.failures.incrementAndGet();
                    stats.last_failure = method.getName() + ": " + e.getCause().getMessage()
                            + (e.getCause().getCause() != null ? " (" + e.getCause().getCause().getMessage() + ")" : "");
                }
                throw e.getCause();
            } finally {
                stats.record(method, System.nanoTime() - t0, main_thread.getAsBoolean());
            }
        };
        return (MailStore) Proxy.newProxyInstance(MailStore.class.getClassLoader(), new Class<?>[]{MailStore.class}, h);
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
