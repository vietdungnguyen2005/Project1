package dev.vcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(VCoreApplication.class, args);
    }
}
