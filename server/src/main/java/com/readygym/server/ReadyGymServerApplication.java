package com.readygym.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReadyGymServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReadyGymServerApplication.class, args);
    }
}
