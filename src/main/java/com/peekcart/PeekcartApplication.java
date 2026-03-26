package com.peekcart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PeekcartApplication {

	public static void main(String[] args) {
		SpringApplication.run(PeekcartApplication.class, args);
	}

}
