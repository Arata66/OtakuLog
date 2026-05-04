package com.otakulog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class OtakuLogApplication {

    public static void main(String[] args) {
        SpringApplication.run(OtakuLogApplication.class, args);
    }

}
