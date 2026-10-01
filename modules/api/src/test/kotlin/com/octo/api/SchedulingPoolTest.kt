package com.octo.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * #493: every @Scheduled job shares Boot's scheduler, which defaults to one thread, so one slow job
 * (a long EVM scan) stalled the rest. application.yml must give each job its own thread.
 */
class SchedulingPoolTest {
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class Scheduling

    @Test
    fun `the scheduler pool has a thread for every scheduled job`() {
        val metadata = CachingMetadataReaderFactory()
        val jobs =
            PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/octo/**/*.class")
                .sumOf {
                    metadata
                        .getMetadataReader(it)
                        .annotationMetadata
                        .getAnnotatedMethods(Scheduled::class.java.name)
                        .size
                }
        assertThat(jobs).`as`("the classpath scan found the scheduled jobs").isGreaterThanOrEqualTo(4)

        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration::class.java))
            .withUserConfiguration(Scheduling::class.java)
            .run { context ->
                val scheduler = context.getBean(ThreadPoolTaskScheduler::class.java)
                assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isGreaterThanOrEqualTo(jobs)
            }
    }
}
