package com.transactionapp.service;

import com.transactionapp.model.Account;
import com.transactionapp.model.Transaction;
import com.transactionapp.repository.AccountRepository;
import com.transactionapp.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class TransferService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Autowired
    public TransferService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public void transfer(String originAccountId, String targetAccountId, BigDecimal amount) {
        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        if (originAccount.getBalance().compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
        }

        originAccount.setBalance(originAccount.getBalance().subtract(amount));
        targetAccount.setBalance(targetAccount.getBalance().add(amount));

        Transaction transaction = new Transaction();
        transaction.setOriginAccount(originAccount);
        transaction.setTargetAccount(targetAccount);
        transaction.setAmount(amount);

        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);
        transactionRepository.save(transaction);
    }
}