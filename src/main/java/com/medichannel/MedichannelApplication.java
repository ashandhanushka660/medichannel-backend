package com.medichannel;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MedichannelApplication {

	public static void main(String[] args) {
		// Business time is always resolved through the Asia/Colombo Clock bean.
		// Pinning the JVM default to UTC keeps java.sql.Time conversion (and
		// therefore LocalTime columns) independent of the host's timezone.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(MedichannelApplication.class, args);
	}

}
