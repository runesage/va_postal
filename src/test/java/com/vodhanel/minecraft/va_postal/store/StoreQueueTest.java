package com.vodhanel.minecraft.va_postal.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StoreQueueTest {
    @AfterEach
    void stop() {
        StoreQueue.stop();
    }

    /** A store that records the name of each call and the thread it ran on. */
    private static MailStore recording(List<String> calls) {
        return (MailStore) Proxy.newProxyInstance(MailStore.class.getClassLoader(), new Class<?>[]{MailStore.class},
                (p, m, a) -> {
                    calls.add(m.getName() + "@" + Thread.currentThread().getName());
                    if (m.getName().equals("recent")) {
                        throw new StoreException("Could not read mail", null);
                    }
                    return m.getName().equals("schema_version") ? 4 : null;
                });
    }

    @Test
    void mainThreadCallsWaitBehindQueuedWrites() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        List<String> log = new CopyOnWriteArrayList<>();
        StoreQueue.start(log::add);
        MailStore store = StoreQueue.ordered(recording(calls), () -> true);
        CountDownLatch release = new CountDownLatch(1);

        StoreQueue.later("slow write", () -> {
            try {
                release.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            calls.add("write@" + Thread.currentThread().getName());
        });
        StoreQueue.later("failing write", () -> {
            throw new StoreException("Could not write mail", null);
        });
        release.countDown();
        // Queued before it, so the write is on record by the time the read runs.
        assertEquals(4, store.schema_version());
        assertEquals(List.of("write@Postal-store", "schema_version@Postal-store"), calls);
        assertEquals(List.of("[Postal] failing write failed: Could not write mail"), log);
        // A waited-for call's failure reaches the caller as the store's own exception.
        assertThrows(StoreException.class, () -> store.recent(5));
    }

    @Test
    void otherThreadsAndAStoppedQueueGoStraightThrough() {
        List<String> calls = new CopyOnWriteArrayList<>();
        StoreQueue.start(m -> { });
        StoreQueue.ordered(recording(calls), () -> false).schema_version();
        StoreQueue.stop();
        StoreQueue.ordered(recording(calls), () -> true).schema_version();
        StoreQueue.later("write", () -> calls.add("write@" + Thread.currentThread().getName()));
        String here = Thread.currentThread().getName();
        assertEquals(List.of("schema_version@" + here, "schema_version@" + here, "write@" + here), calls);
    }

    @Test
    void stopFinishesQueuedWork() {
        List<String> calls = new CopyOnWriteArrayList<>();
        StoreQueue.start(m -> { });
        for (int i = 0; i < 20; i++) {
            int n = i;
            StoreQueue.later("write", () -> calls.add("w" + n));
        }
        StoreQueue.stop();
        assertEquals(20, calls.size());
        assertEquals("w19", calls.get(19));
    }
}
