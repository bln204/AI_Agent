package com.aiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableAsync;

import com.aiagent.config.EnvLoader;

@SpringBootApplication
@EnableAsync
public class AiAgentApplication {

    public static void main(String[] args) {
        EnvLoader.load();
        System.out.println("DB_URL=" + System.getProperty("DB_URL"));
        SpringApplication.run(AiAgentApplication.class, args);
    }

}
