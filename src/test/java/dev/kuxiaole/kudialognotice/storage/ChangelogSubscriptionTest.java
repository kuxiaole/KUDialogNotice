package dev.kuxiaole.kudialognotice.storage;

import dev.kuxiaole.kudialognotice.config.MainConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogSubscriptionTest {
    @Test
    void ownerCloseIsIdempotentAndInvokesItsStopperOnce() {
        AtomicInteger stops = new AtomicInteger();
        AtomicReference<ChangelogSubscription> reference = new AtomicReference<>();
        ChangelogSubscription owner = new ChangelogSubscription(closedOwner -> {
            assertSame(reference.get(), closedOwner);
            stops.incrementAndGet();
        });
        reference.set(owner);

        assertTrue(owner.isOpen());
        assertFalse(owner.isClosed());
        owner.close();
        owner.close();

        assertTrue(owner.isClosed());
        assertFalse(owner.isOpen());
        assertEquals(1, stops.get());
    }

    @Test
    void oldOwnerCannotStopAReplacementAndLegacyNoArgStopRemainsUsable() {
        RedisStateCache cache = new RedisStateCache(redisConfig(), "test-namespace");
        try {
            ChangelogSubscription first = cache.startChangelogSubscriptionOwned(
                    "test-channel", ignored -> { });
            ChangelogSubscription second = cache.startChangelogSubscriptionOwned(
                    "test-channel", ignored -> { });

            assertTrue(first.isClosed(), "replacing a subscription closes its old owner");
            assertTrue(second.isOpen());

            first.close();
            cache.stopChangelogSubscription(first);
            assertTrue(second.isOpen(), "an old owner must not stop the replacement");

            cache.stopChangelogSubscription(second);
            assertTrue(second.isClosed());

            // Source-compatible API used by older callers.
            cache.startChangelogSubscription("test-channel", ignored -> { });
            cache.stopChangelogSubscription();
        } finally {
            cache.close();
        }
    }

    @Test
    void closedCacheRejectsNewOwnedSubscriptions() {
        RedisStateCache cache = new RedisStateCache(redisConfig(), "test-namespace");
        cache.close();

        assertThrows(IllegalStateException.class, () -> cache.startChangelogSubscriptionOwned(
                "test-channel", ignored -> { }));
        assertThrows(IllegalStateException.class, () -> cache.publishChangelogEvent(
                "test-channel", "message"));
    }

    private static MainConfig.RedisConfig redisConfig() {
        return new MainConfig.RedisConfig(true, "redis://127.0.0.1:1/0", "kudn:", 5);
    }
}
