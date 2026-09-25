package com.edem.dedup4j.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Dedup4jDashboardApplication {

    public static final String DEFAULT_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 9090;

    public static void main(String[] args) {
        SpringApplication.run(Dedup4jDashboardApplication.class, args);
    }
}
