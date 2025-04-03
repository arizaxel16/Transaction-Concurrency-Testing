package com.transactionapp.service;

import com.transactionapp.lock.AccountLockManager;
import com.transactionapp.model.Account;
import com.transactionapp.repository.AccountRepository;
import org.multiverse.api.StmUtils;
import org.multiverse.api.Txn;
import org.multiverse.api.TxnExecutor;
import org.multiverse.api.callables.TxnVoidCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class TransferService {
    private final AccountRepository accountRepository;
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    @Autowired
    public TransferService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public void transfer(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        if (originAccount.getBalance().compareTo(amount) < 0) {
            log.warn("Thread {}: Insufficient balance in account {}. Required: {}, Available: {}",
                    Thread.currentThread().getId(), originAccountId, amount, originAccount.getBalance());
            throw new RuntimeException("Insufficient balance in origin account: " + originAccountId + " (Balance: " + originAccount.getBalance() + ")");
        }

        originAccount.setBalance(originAccount.getBalance().subtract(amount));
        targetAccount.setBalance(targetAccount.getBalance().add(amount));

        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }

    @Transactional
    public synchronized void transferSynchronized(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (Sync) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        if (originAccount.getBalance().compareTo(amount) < 0) {
            log.warn("Thread {}: (Sync) Insufficient balance in account {}. Required: {}, Available: {}",
                    Thread.currentThread().getId(), originAccountId, amount, originAccount.getBalance());
            throw new RuntimeException("Insufficient balance in origin account: " + originAccountId + " (Balance: " + originAccount.getBalance() + ")");
        }

        originAccount.setBalance(originAccount.getBalance().subtract(amount));
        targetAccount.setBalance(targetAccount.getBalance().add(amount));

        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: (Sync) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }

    @Transactional
    public void transferPessimistic(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (Pessimistic) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        Account originAccount = accountRepository.findByIdForUpdate(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findByIdForUpdate(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        if (originAccount.getBalance().compareTo(amount) < 0) {
            log.warn("Thread {}: (Pessimistic) Insufficient balance in account {}. Required: {}, Available: {}",
                    Thread.currentThread().getId(), originAccountId, amount, originAccount.getBalance());
            throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
        }

        // Perform the transfer
        originAccount.setBalance(originAccount.getBalance().subtract(amount));
        targetAccount.setBalance(targetAccount.getBalance().add(amount));

        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: (Pessimistic) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }

    @Retryable(
            retryFor = { OptimisticLockingFailureException.class },
            maxAttempts = 15,
            backoff = @Backoff(
                    delay = 100,
                    multiplier = 2,
                    maxDelay = 2000
            )
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transferOptimistic(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (Optimistic) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        if (originAccount.getBalance().compareTo(amount) < 0) {
            log.warn("Thread {}: (Optimistic) Insufficient balance in account {}. Required: {}, Available: {}",
                    Thread.currentThread().getId(), originAccountId, amount, originAccount.getBalance());
            throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
        }

        originAccount.setBalance(originAccount.getBalance().subtract(amount));
        targetAccount.setBalance(targetAccount.getBalance().add(amount));

        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: (Optimistic) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }

    @Transactional
    public void transferReentrantLock(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (Reentrant) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        String firstLockId = originAccountId.compareTo(targetAccountId) < 0 ? originAccountId : targetAccountId;
        String secondLockId = originAccountId.compareTo(targetAccountId) < 0 ? targetAccountId : originAccountId;

        ReentrantLock firstLock = AccountLockManager.getLock(firstLockId);
        ReentrantLock secondLock = AccountLockManager.getLock(secondLockId);

        firstLock.lock();
        try {
            secondLock.lock();
            try {
                Account originAccount = accountRepository.findById(originAccountId)
                        .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
                Account targetAccount = accountRepository.findById(targetAccountId)
                        .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

                if (originAccount.getBalance().compareTo(amount) < 0) {
                    log.warn("Thread {}: (Reentrant) Insufficient balance in account {}. Required: {}, Available: {}",
                            Thread.currentThread().getId(), originAccountId, amount, originAccount.getBalance());
                    throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
                }

                originAccount.setBalance(originAccount.getBalance().subtract(amount));
                targetAccount.setBalance(targetAccount.getBalance().add(amount));

                accountRepository.save(originAccount);
                accountRepository.save(targetAccount);

                log.info("Thread {}: (Reentrant) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                        Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());

            } finally {
                secondLock.unlock();
            }
        } finally {
            firstLock.unlock();
        }
    }

    @Transactional
    public void transferAtomic(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (Atomic) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        AtomicReference<BigDecimal> originBalance = new AtomicReference<>(originAccount.getBalance());
        AtomicReference<BigDecimal> targetBalance = new AtomicReference<>(targetAccount.getBalance());

        boolean updated = false;
        while (!updated) {
            BigDecimal currentOriginBalance = originBalance.get();
            BigDecimal currentTargetBalance = targetBalance.get();

            if (currentOriginBalance.compareTo(amount) < 0) {
                log.warn("Thread {}: (Atomic) Insufficient balance in account {}. Required: {}, Available: {}",
                        Thread.currentThread().getId(), originAccountId, amount, currentOriginBalance);
                throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
            }

            BigDecimal newOriginBalance = currentOriginBalance.subtract(amount);
            BigDecimal newTargetBalance = currentTargetBalance.add(amount);

            if (originBalance.compareAndSet(currentOriginBalance, newOriginBalance) &&
                    targetBalance.compareAndSet(currentTargetBalance, newTargetBalance)) {
                updated = true;
            }
        }

        originAccount.setBalance(originBalance.get());
        targetAccount.setBalance(targetBalance.get());

        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: (Atomic) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }

    @Transactional
    public void transferSTM(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (STM) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        if (originAccount.getStmBalance() == null) {
            originAccount.setStmBalance(originAccount.getBalance());
        }
        if (targetAccount.getStmBalance() == null) {
            targetAccount.setStmBalance(targetAccount.getBalance());
        }

        StmUtils.atomic(() -> {
            if (originAccount.getStmBalance().compareTo(amount) < 0) {
                log.warn("Thread {}: (STM) Insufficient balance in account {}. Required: {}, Available: {}",
                        Thread.currentThread().getId(), originAccountId, amount, originAccount.getStmBalance());
                throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
            }

            originAccount.setStmBalance(originAccount.getStmBalance().subtract(amount));
            targetAccount.setStmBalance(targetAccount.getStmBalance().add(amount));
        });

        originAccount.setBalance(originAccount.getStmBalance());
        targetAccount.setBalance(targetAccount.getStmBalance());
        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: (STM) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }
}