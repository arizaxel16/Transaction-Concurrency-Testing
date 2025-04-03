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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

@RestController
public class TransferController {

    private final AccountRepository accountRepository;
    private final TransferService transferService;
    private ExecutorService executorService;
    private static final String ACCOUNT_A_ID = "abc";
    private static final String ACCOUNT_B_ID = "cbd";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000.00");
    private static final BigDecimal TRANSFER_AMOUNT = new BigDecimal("1.00");
    private static final int NUMBER_OF_THREADS = 30;
    private static final int NUMBER_OF_TRANSFERS_PER_THREAD = 250;

    @Autowired
    public TransferController(AccountRepository accountRepository, TransferService transferService) {
        this.accountRepository = accountRepository;
        this.transferService = transferService;
    }

    @PostMapping("/run-transfer-test")
    public ResponseEntity<String> runTransferTest() {
        // Initialize accounts
        Account accountA = new Account();
        accountA.setAccountId(ACCOUNT_A_ID);
        accountA.setBalance(INITIAL_BALANCE);
        accountRepository.save(accountA);

        Account accountB = new Account();
        accountB.setAccountId(ACCOUNT_B_ID);
        accountB.setBalance(INITIAL_BALANCE);
        accountRepository.save(accountB);

        executorService = Executors.newFixedThreadPool(NUMBER_OF_THREADS);
        AtomicInteger completedTransfers = new AtomicInteger(0);

        // Submit tasks to the executor
        IntStream.range(0, NUMBER_OF_THREADS).forEach(i -> executorService.submit(() -> {
            for (int j = 0; j < NUMBER_OF_TRANSFERS_PER_THREAD; j++) {
                try {
                    transferService.transfer(ACCOUNT_A_ID, ACCOUNT_B_ID, TRANSFER_AMOUNT);
                    completedTransfers.incrementAndGet();
                } catch (RuntimeException e) {
                    System.err.println("Thread " + Thread.currentThread().getId() + " error: " + e.getMessage());
                    // Optionally break the inner loop if a critical error occurs
                    // break;
                }
                // Optional: Add a small delay if needed for local testing
                // try {
                //     TimeUnit.MILLISECONDS.sleep(10);
                // } catch (InterruptedException e) {
                //     Thread.currentThread().interrupt();
                //     break;
                // }
            }
        }));

        executorService.shutdown();

        try {
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ResponseEntity<>("Error waiting for transfers to complete.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        Account finalA = accountRepository.findById(ACCOUNT_A_ID).orElse(null);
        Account finalB = accountRepository.findById(ACCOUNT_B_ID).orElse(null);

        String balances = "Test Completed.\n" +
                "Total Transfers Attempted: " + (NUMBER_OF_THREADS * NUMBER_OF_TRANSFERS_PER_THREAD) + "\n" +
                "Total Transfers (Successfully Recorded): " + completedTransfers.get() + "\n" +
                "Final Balance of Account " + ACCOUNT_A_ID + ": " + (finalA != null ? finalA.getBalance() : "N/A") + "\n" +
                "Final Balance of Account " + ACCOUNT_B_ID + ": " + (finalB != null ? finalB.getBalance() : "N/A");

        return new ResponseEntity<>(balances, HttpStatus.OK);
    }
}