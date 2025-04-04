package com.transactionapp.runner;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@Service
public class LoadTestService implements CommandLineRunner {

    private static final int NUM_THREADS = 40;
    private static final int NUM_REQUESTS = 250;
    private static final String ENDPOINT_URL = "http://localhost:8080/transfer-single-pessimistic";
    private final RestTemplate restTemplate;
    private final ExecutorService executorService = Executors.newFixedThreadPool(NUM_THREADS);

    public LoadTestService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    @Override
    public void run(String... args) {
        // Uncomment the next line to run the load test at startup.
        // startLoadTest();
    }

    public void startLoadTest() {
        System.out.println("Starting load test...");

        int tasksPerThread = 250;
        int totalTasks = NUM_THREADS * tasksPerThread;

        IntStream.range(0, totalTasks).forEach(i -> executorService.submit(() -> {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(ENDPOINT_URL, null, String.class);
                System.out.println("Response: " + response.getBody());
            } catch (Exception e) {
                System.err.println("Request failed: " + e.getMessage());
            }
        }));

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(1, TimeUnit.HOURS)) {
                executorService.shutdownNow();
            }
            System.out.println("Load test completed.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Load test interrupted.");
        }
    }
}
