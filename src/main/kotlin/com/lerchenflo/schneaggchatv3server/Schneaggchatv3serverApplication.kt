package com.lerchenflo.schneaggchatv3server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories

@SpringBootApplication
//@ComponentScan(basePackages = ["com.lerchenflo.schneaggchatv3server"])
@EnableMongoRepositories(basePackages = ["com.lerchenflo.schneaggchatv3server.repository"])
class Schneaggchatv3serverApplication

fun main(args: Array<String>) {
	runApplication<Schneaggchatv3serverApplication>(*args)
}