package com.transactionapp.service;

import com.transactionapp.model.Account;
import com.transactionapp.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

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
}