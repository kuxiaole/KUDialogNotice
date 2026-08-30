package dev.kuxiaole.kudialognotice.storage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owner token for one Redis changelog subscription.
 *
 * <p>The token is deliberately separate from the Redis connection. A
 * synchronizer can close its own token after a reload without accidentally
 * stopping a newer subscription that replaced it.</p>
 */
public final class ChangelogSubscription implements AutoCloseable {
    private final Consumer<ChangelogSubscription> stopper;
    private final AtomicBoolean open = new AtomicBoolean(true);

    ChangelogSubscription(Consumer<ChangelogSubscription> stopper) {
        this.stopper = Objects.requireNonNull(stopper, "stopper");
    }

    /** Return whether this owner is still allowed to receive messages. */
    public boolean isOpen() {
        return open.get();
    }

    /** Return whether this owner has been closed. */
    public boolean isClosed() {
        return !isOpen();
    }

    /**
     * Close this owner exactly once. Closing an already-replaced owner is a
     * no-op for the active subscription.
     */
    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            stopper.accept(this);
        }
    }

    boolean closeFromCache() {
        return open.compareAndSet(true, false);
    }
}
