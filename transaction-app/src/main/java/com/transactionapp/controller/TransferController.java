package com.transactionapp.controller;

import com.transactionapp.model.Account;
import com.transactionapp.repository.AccountRepository;
import com.transactionapp.service.TransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@RestController
public class TransferController {

    private final AccountRepository accountRepository;
    private final TransferService transferService;
    private ExecutorService executorService;

    @Autowired
    public TransferController(AccountRepository accountRepository, TransferService transferService) {
        this.accountRepository = accountRepository;
        this.transferService = transferService;
    }

    @PostMapping("/start-transfers")
    public ResponseEntity<String> startConcurrentTransfers() {
        // Initialize accounts (moved here so it resets on each trigger if needed)
        Account accountA = new Account();
        accountA.setAccountId("abc");
        accountA.setBalance(new BigDecimal("10000.00"));
        accountRepository.save(accountA);

        Account accountB = new Account();
        accountB.setAccountId("cbd");
        accountB.setBalance(new BigDecimal("10000.00"));
        accountRepository.save(accountB);

        String accountAId = "abc";
        String accountBId = "cbd";
        BigDecimal transferAmount = new BigDecimal("5.00");
        int numberOfThreads = 30;

        executorService = Executors.newFixedThreadPool(numberOfThreads);

        // Submit tasks to the executor
        IntStream.range(0, numberOfThreads).forEach(i -> executorService.submit(() -> {
            while (true) {
                try {
                    transferService.transfer(accountAId, accountBId, transferAmount);
                } catch (RuntimeException e) {
                    // Stop the thread if there's an error
                    System.err.println("Thread " + Thread.currentThread().getId() + " stopped: " + e.getMessage());
                    break;
                }
                Account currentA = accountRepository.findById(accountAId).orElse(null);
                Account currentB = accountRepository.findById(accountBId).orElse(null);
                if (currentA != null && currentB != null && (currentA.getBalance().compareTo(BigDecimal.ZERO) == 0 || currentB.getBalance().compareTo(BigDecimal.ZERO) == 0)) {
                    break; // Stop if one account reaches zero
                }
                // Optional: Add a small delay if needed
                // try {
                //     TimeUnit.MILLISECONDS.sleep(10);
                // } catch (InterruptedException e) {
                //     Thread.currentThread().interrupt();
                //     break;
                // }
            }
        }));

        return new ResponseEntity<>("Concurrent transfers initiated.", HttpStatus.OK);
    }

    @PostMapping("/check-balances")
    public ResponseEntity<String> checkFinalBalances() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ResponseEntity<>("Error waiting for transfers to complete.", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        Account finalA = accountRepository.findById("abc").orElse(null);
        Account finalB = accountRepository.findById("cbd").orElse(null);
        String balances = "Final Balance of Account abc: " + (finalA != null ? finalA.getBalance() : "N/A") +
                "\nFinal Balance of Account cbd: " + (finalB != null ? finalB.getBalance() : "N/A");
        return new ResponseEntity<>(balances, HttpStatus.OK);
    }
}