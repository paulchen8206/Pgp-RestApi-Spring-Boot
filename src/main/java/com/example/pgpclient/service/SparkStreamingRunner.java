package com.example.pgpclient.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SparkStreamingRunner implements ApplicationRunner {

    private final SparkEmployeeStreamingService sparkEmployeeStreamingService;

    public SparkStreamingRunner(SparkEmployeeStreamingService sparkEmployeeStreamingService) {
        this.sparkEmployeeStreamingService = sparkEmployeeStreamingService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        sparkEmployeeStreamingService.start();
    }
}