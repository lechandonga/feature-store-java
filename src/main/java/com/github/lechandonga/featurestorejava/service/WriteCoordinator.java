package com.github.lechandonga.featurestorejava.service;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Serializes the validate-then-commit critical section of live writes, backfills and recomputes.
 * Reads never take this lock (they use immutable storage snapshots), so serving stays stable
 * and lock-free while a recompute is in progress.
 */
@Component
public class WriteCoordinator {

    private final ReentrantLock lock = new ReentrantLock();

    public void withWriteLock(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
