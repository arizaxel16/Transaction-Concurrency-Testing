package com.transactionapp.controller;

import com.transactionapp.model.Account;
import com.transactionapp.repository.AccountRepository;
import com.transactionapp.service.TransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private static final int NUMBER_OF_THREADS = 40;
    private static final int NUMBER_OF_TRANSFERS_PER_THREAD = 250;
    private boolean testRunning = false;

    @Autowired
    public TransferController(AccountRepository accountRepository, TransferService transferService) {
        this.accountRepository = accountRepository;
        this.transferService = transferService;
    }

    @PostMapping("/create-accounts")
    public ResponseEntity<String> createAccounts() {
        if (accountRepository.findById(ACCOUNT_A_ID).isEmpty()) {
            Account accountA = new Account();
            accountA.setAccountId(ACCOUNT_A_ID);
            accountA.setBalance(BigDecimal.ZERO);
            accountRepository.save(accountA);
        }
        if (accountRepository.findById(ACCOUNT_B_ID).isEmpty()) {
            Account accountB = new Account();
            accountB.setAccountId(ACCOUNT_B_ID);
            accountB.setBalance(BigDecimal.ZERO);
            accountRepository.save(accountB);
        }
        return new ResponseEntity<>("Accounts 'abc' and 'cbd' created (if they didn't exist).", HttpStatus.CREATED);
    }

    @PostMapping("/set-initial-conditions")
    public ResponseEntity<String> setInitialConditions(@RequestParam(defaultValue = "10000.00") BigDecimal initialBalance) {
        Account accountA = accountRepository.findById(ACCOUNT_A_ID).orElse(null);
        Account accountB = accountRepository.findById(ACCOUNT_B_ID).orElse(null);

        if (accountA != null) {
            accountA.setBalance(initialBalance);
            accountRepository.save(accountA);
        } else {
            return new ResponseEntity<>("Account 'abc' not found. Please create accounts first.", HttpStatus.NOT_FOUND);
        }

        if (accountB != null) {
            accountB.setBalance(initialBalance);
            accountRepository.save(accountB);
        } else {
            return new ResponseEntity<>("Account 'cbd' not found. Please create accounts first.", HttpStatus.NOT_FOUND);
        }

        return new ResponseEntity<>("Accounts 'abc' and 'cbd' balances set to " + initialBalance, HttpStatus.OK);
    }

    @GetMapping("/check-balance")
    public ResponseEntity<String> checkBalance() {
        Account accountA = accountRepository.findById(ACCOUNT_A_ID).orElse(null);
        Account accountB = accountRepository.findById(ACCOUNT_B_ID).orElse(null);

        if (accountA == null || accountB == null) {
            return new ResponseEntity<>("One or both accounts not found. Please create accounts first.", HttpStatus.NOT_FOUND);
        }

        String balances = "Balance of Account " + ACCOUNT_A_ID + ": " + accountA.getBalance() + "\n" +
                "Balance of Account " + ACCOUNT_B_ID + ": " + accountB.getBalance();
        return new ResponseEntity<>(balances, HttpStatus.OK);
    }

    private ResponseEntity<String> runConcurrentTransfers(Runnable transferTask, String testName) {
        if (testRunning) {
            return new ResponseEntity<>("Test is already running.", HttpStatus.CONFLICT);
        }
        testRunning = true;

        executorService = Executors.newFixedThreadPool(NUMBER_OF_THREADS);
        AtomicInteger completedTransfers = new AtomicInteger(0);

        IntStream.range(0, NUMBER_OF_THREADS).forEach(i -> executorService.submit(() -> {
            for (int j = 0; j < NUMBER_OF_TRANSFERS_PER_THREAD; j++) {
                try {
                    transferTask.run();
                    completedTransfers.incrementAndGet();
                } catch (RuntimeException e) {
                    System.err.println("Thread " + Thread.currentThread().getId() + " error in " + testName + ": " + e.getMessage());
                    // Optionally break;
                }
                // Optional: TimeUnit.MILLISECONDS.sleep(1);
            }
        }));

        executorService.shutdown();

        try {
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            testRunning = false;
            return new ResponseEntity<>("Error waiting for transfers to complete.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        Account finalA = accountRepository.findById(ACCOUNT_A_ID).orElse(null);
        Account finalB = accountRepository.findById(ACCOUNT_B_ID).orElse(null);

        String balances = testName + " Completed.\n" +
                "Total Transfers Attempted: " + (NUMBER_OF_THREADS * NUMBER_OF_TRANSFERS_PER_THREAD) + "\n" +
                "Total Transfers (Successfully Recorded): " + completedTransfers.get() + "\n" +
                "Final Balance of Account " + ACCOUNT_A_ID + ": " + (finalA != null ? finalA.getBalance() : "N/A") + "\n" +
                "Final Balance of Account " + ACCOUNT_B_ID + ": " + (finalB != null ? finalB.getBalance() : "N/A");

        testRunning = false;
        return new ResponseEntity<>(balances, HttpStatus.OK);
    }

    @PostMapping("/default-transaction")
    public ResponseEntity<String> defaultTransaction() {
        Runnable transferTask = () -> {
            transferService.transfer(ACCOUNT_A_ID, ACCOUNT_B_ID, TRANSFER_AMOUNT);
        };
        return runConcurrentTransfers(transferTask, "Default Transaction Test");
    }

    @PostMapping("/synchronized-transaction")
    public ResponseEntity<String> synchronizedTransaction() {
        Runnable transferTask = () -> {
            transferService.transferSynchronized(ACCOUNT_A_ID, ACCOUNT_B_ID, TRANSFER_AMOUNT);
        };
        return runConcurrentTransfers(transferTask, "Synchronized Transaction Test");
    }

    @PostMapping("/pessimistic-transaction")
    public ResponseEntity<String> pessimisticTransaction() {
        Runnable transferTask = () -> {
            transferService.transferPessimistic(ACCOUNT_A_ID, ACCOUNT_B_ID, TRANSFER_AMOUNT);
        };
        return runConcurrentTransfers(transferTask, "Pessimistic Lock Transaction Test");
    }

    @PostMapping("/optimistic-transaction")
    public ResponseEntity<String> optimisticTransaction() {
        Runnable transferTask = () -> {
            transferService.transferOptimistic(ACCOUNT_A_ID, ACCOUNT_B_ID, TRANSFER_AMOUNT);
        };
        return runConcurrentTransfers(transferTask, "Optimistic Lock Transaction Test");
    }

    @PostMapping("/reentrant-transaction")
    public ResponseEntity<String> reentrantLockTransaction() {
        Runnable transferTask = () -> {
            transferService.transferReentrantLock(ACCOUNT_A_ID, ACCOUNT_B_ID, TRANSFER_AMOUNT);
        };
        return runConcurrentTransfers(transferTask, "Reentrant Lock Transaction Test");
    }
}