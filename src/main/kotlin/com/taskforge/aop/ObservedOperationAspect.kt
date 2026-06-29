package com.taskforge.aop

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Aspect
@Component
class ObservedOperationAspect(
    meterRegistryProvider: ObjectProvider<MeterRegistry>,
) {
    private val logger = LoggerFactory.getLogger(ObservedOperationAspect::class.java)
    private val meterRegistry = meterRegistryProvider.ifAvailable

    @Around("@annotation(observed)")
    fun around(joinPoint: ProceedingJoinPoint, observed: ObservedOperation): Any? {
        val operation = observed.name.ifBlank {
            val signature = joinPoint.signature
            "${signature.declaringTypeName}.${signature.name}"
        }
        val startedAt = System.nanoTime()
        logger.info("operation_started operation={}", operation)

        return try {
            val result = joinPoint.proceed()
            val durationNanos = System.nanoTime() - startedAt
            recordMetrics(operation, "success", durationNanos)
            logger.info(
                "operation_completed operation={} status=success duration_ms={}",
                operation,
                TimeUnit.NANOSECONDS.toMillis(durationNanos),
            )
            result
        } catch (ex: Throwable) {
            val durationNanos = System.nanoTime() - startedAt
            val errorType = ex::class.simpleName ?: "unknown_error"
            recordMetrics(operation, "failure", durationNanos, errorType)
            logger.warn(
                "operation_completed operation={} status=failure error_type={} duration_ms={} message={}",
                operation,
                errorType,
                TimeUnit.NANOSECONDS.toMillis(durationNanos),
                ex.message,
            )
            throw ex
        }
    }

    private fun recordMetrics(
        operation: String,
        status: String,
        durationNanos: Long,
        errorType: String? = null,
    ) {
        val tags = mutableListOf("operation", operation, "status", status)
        if (errorType != null) {
            tags.add("error_type")
            tags.add(errorType)
        }

        val registry = meterRegistry ?: return
        registry.counter("taskforge.operation.calls", *tags.toTypedArray()).increment()
        Timer.builder("taskforge.operation.duration")
            .tags(*tags.toTypedArray())
            .register(registry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }
}
