package com.transactionapp;

import com.transactionapp.model.Account;
import com.transactionapp.repository.AccountRepository;
import com.transactionapp.service.TransferService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@SpringBootApplication
public class TransactionAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionAppApplication.class, args);
    }

    @Bean
    CommandLineRunner run(AccountRepository accountRepository, TransferService transferService) {
        return args -> {
            // Initialize accounts
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

            ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);

            // Submit tasks to the executor
            IntStream.range(0, numberOfThreads).forEach(i -> executorService.submit(() -> {
                while (true) {
                    try {
                        transferService.transfer(accountAId, accountBId, transferAmount);
                    } catch (RuntimeException e) {
                        // Stop the thread if there's an error (e.g., insufficient balance)
                        System.err.println("Thread " + Thread.currentThread().getId() + " stopped: " + e.getMessage());
                        break;
                    }
                    // Optional: Add a small delay to avoid overwhelming the system in local testing
                    // try {
                    //     TimeUnit.MILLISECONDS.sleep(10);
                    // } catch (InterruptedException e) {
                    //     Thread.currentThread().interrupt();
                    //     break;
                    // }
                    Account currentA = accountRepository.findById(accountAId).orElse(null);
                    Account currentB = accountRepository.findById(accountBId).orElse(null);
                    if (currentA != null && currentB != null && (currentA.getBalance().compareTo(BigDecimal.ZERO) == 0 || currentB.getBalance().compareTo(BigDecimal.ZERO) == 0)) {
                        break; // Stop the thread if one of the accounts reaches zero
                    }
                }
            }));

            executorService.shutdown();

            try {
                executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Final balance check
            Account finalA = accountRepository.findById(accountAId).orElse(null);
            Account finalB = accountRepository.findById(accountBId).orElse(null);
            System.out.println("Final Balance of Account abc: " + (finalA != null ? finalA.getBalance() : "N/A"));
            System.out.println("Final Balance of Account cbd: " + (finalB != null ? finalB.getBalance() : "N/A"));
        };
    }
}
