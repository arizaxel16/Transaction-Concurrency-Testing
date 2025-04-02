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

}
