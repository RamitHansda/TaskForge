package com.taskforge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TaskForgeApplication

fun main(args: Array<String>) {
    runApplication<TaskForgeApplication>(*args)
}
