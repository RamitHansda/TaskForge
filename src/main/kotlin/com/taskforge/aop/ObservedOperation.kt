package com.taskforge.aop

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ObservedOperation(
    val name: String = "",
)
