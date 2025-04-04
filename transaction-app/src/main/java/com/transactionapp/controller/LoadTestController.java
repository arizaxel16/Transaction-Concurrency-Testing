package com.transactionapp.controller;

import com.transactionapp.runner.LoadTestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoadTestController {

    private final LoadTestService loadTestService;

    @Autowired
    public LoadTestController(LoadTestService loadTestService) {
        this.loadTestService = loadTestService;
    }

    @PostMapping("/start-load-test")
    public ResponseEntity<String> startLoadTest() {
        new Thread(loadTestService::startLoadTest).start();
        return ResponseEntity.ok("Load test started.");
    }
}
