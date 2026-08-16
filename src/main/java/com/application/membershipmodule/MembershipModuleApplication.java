package com.application.membershipmodule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MembershipModuleApplication {

	public static void main(String[] args) {
		SpringApplication.run(MembershipModuleApplication.class, args);
	}

}
