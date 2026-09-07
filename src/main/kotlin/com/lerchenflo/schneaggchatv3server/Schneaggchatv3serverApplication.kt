package com.lerchenflo.schneaggchatv3server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
//@ComponentScan(basePackages = ["com.lerchenflo.schneaggchatv3server"])
@EnableMongoRepositories(basePackages = ["com.lerchenflo.schneaggchatv3server.repository"])
@EnableScheduling
// Backs @Async in EmailService: login-alert mails go out on the task executor so SMTP latency
// never sits inside the /auth/login response.
@EnableAsync
class Schneaggchatv3serverApplication

fun main(args: Array<String>) {
	runApplication<Schneaggchatv3serverApplication>(*args)
}