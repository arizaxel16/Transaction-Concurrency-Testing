package com.transactionapp.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class AccountLockManager {
    private static final ConcurrentHashMap<String, ReentrantLock> lockMap = new ConcurrentHashMap<>();

    public static ReentrantLock getLock(String accountId) {
        return lockMap.computeIfAbsent(accountId, id -> new ReentrantLock());
    }
}

