package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.store.OfflineStore;
import com.github.lechandonga.featurestorejava.store.OnlineStore;
import com.github.lechandonga.featurestorejava.store.RegistryStore;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Holds the three stores and a single read/write lock that serializes commits
 * against concurrent reads. Readers always observe a consistent snapshot.
 */
public class StoreState {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final RegistryStore registry = new RegistryStore();
    private final OfflineStore offline = new OfflineStore();
    private final OnlineStore online = new OnlineStore();

    public ReentrantReadWriteLock lock() {
        return lock;
    }

    public RegistryStore registry() {
        return registry;
    }

    public OfflineStore offline() {
        return offline;
    }

    public OnlineStore online() {
        return online;
    }
}
