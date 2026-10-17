package com.vodhanel.minecraft.va_postal.store;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One background thread for mail-store work, in order. Store writes that nothing in the world waits on (the end of
 * a route, committing a move once the book is in place, recording a newly posted letter) go to {@link #later} and
 * the main thread carries on; with a database on another machine each of those writes costs several network round
 * trips. A main-thread call that does need an answer (starting a move, which must be on record before the book
 * moves) still waits for it, but it runs on this thread too, after everything queued before it, so it never reads
 * a record a queued write hasn't reached yet ({@link #ordered}).
 */
public final class StoreQueue {
    private static volatile ExecutorService exec;
    private static volatile Thread thread;
    private static volatile Consumer<String> log = message -> { };

    private StoreQueue() {
    }

    public static synchronized void start(Consumer<String> logger) {
        if (exec != null) {
            return;
        }
        log = logger;
        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Postal-store");
            t.setDaemon(true);
            thread = t;
            return t;
        });
    }

    /** Finishes the queued work (up to 10 seconds), then stops the thread. */
    public static void stop() {
        ExecutorService e;
        synchronized (StoreQueue.class) {
            e = exec;
            exec = null;
        }
        if (e == null) {
            return;
        }
        e.shutdown();
        try {
            if (!e.awaitTermination(10L, TimeUnit.SECONDS)) {
                log.accept("[Postal] The mail store queue didn't finish within 10 s; reconciliation will settle what's left.");
                e.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    static boolean on_queue() {
        return Thread.currentThread() == thread;
    }

    /**
     * Runs {@code task} on the store thread after everything queued before it, and returns at once. Without a queue
     * (tests, or while stopping) it runs now. A failure is logged as "{@code what} failed: ...".
     */
    public static void later(String what, Runnable task) {
        ExecutorService e = exec;
        if (e == null || on_queue()) {
            run(what, task);
            return;
        }
        try {
            e.execute(() -> run(what, task));
        } catch (RejectedExecutionException stopped) {
            run(what, task);
        }
    }

    private static void run(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException ex) {
            log.accept("[Postal] " + what + " failed: " + ex.getMessage());
        }
    }

    /** Runs {@code call} on the store thread after everything queued before it, and waits for its result. */
    static <T> T await(Callable<T> call) throws Throwable {
        ExecutorService e = exec;
        if (e == null || on_queue()) {
            return call.call();
        }
        Future<T> f;
        try {
            f = e.submit(call);
        } catch (RejectedExecutionException stopped) {
            return call.call();
        }
        try {
            return f.get();
        } catch (ExecutionException ex) {
            throw ex.getCause();
        }
    }

    /**
     * {@code store}, with calls made on the main thread run on the store thread in order behind queued work (and
     * waited for). Calls from other threads (the network poller, the directory publisher) go straight through, as
     * before.
     */
    public static MailStore ordered(MailStore store, BooleanSupplier main_thread) {
        return (MailStore) Proxy.newProxyInstance(MailStore.class.getClassLoader(), new Class<?>[]{MailStore.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class || !main_thread.getAsBoolean()) {
                        return invoke(store, method, args);
                    }
                    return await(() -> invoke(store, method, args));
                });
    }

    private static Object invoke(MailStore store, Method method, Object[] args) throws Exception {
        try {
            return method.invoke(store, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
