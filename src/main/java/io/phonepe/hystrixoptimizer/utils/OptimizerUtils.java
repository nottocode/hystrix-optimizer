package io.phonepe.hystrixoptimizer.utils;

import static io.phonepe.hystrixoptimizer.metrics.LatencyMetric.LATENCY_PERCENTILE_75;
import static io.phonepe.hystrixoptimizer.metrics.LatencyMetric.LATENCY_PERCENTILE_99;
import static io.phonepe.hystrixoptimizer.metrics.LatencyMetric.LATENCY_PERCENTILE_995;

import com.google.common.collect.Lists;
import io.phonepe.hystrixoptimizer.config.OptimizerConcurrencyConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerConfigUpdaterConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerMetricsCollectorConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerTimeConfig;
import io.phonepe.hystrixoptimizer.metrics.LatencyMetric;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/***
 Created by nitish.goyal on 29/03/19
 ***/
public class OptimizerUtils {

    public static OptimizerConfig getDefaultOptimizerConfig() {
        return OptimizerConfig.builder()
                .initialDelay(1)
                .timeUnit(TimeUnit.MINUTES)
                .concurrencyConfig(
                        OptimizerConcurrencyConfig.builder()
                                .bandwidth(1.4)
                                .minThreshold(0.5)
                                .maxThreshold(0.85)
                                .enabled(true)
                                .maxThreadsMultiplier(1.8)
                                .build())
                .configUpdaterConfig(OptimizerConfigUpdaterConfig.builder()
                        .repeatAfter(2)
                        .timeUnit(TimeUnit.MINUTES).build())
                .metricsCollectorConfig(
                        OptimizerMetricsCollectorConfig.builder()
                                .repeatAfter(30)
                                .timeUnit(TimeUnit.SECONDS)
                                .cachingWindowInMinutes(15)
                                .concurrency(3)
                                .build())
                .timeConfig(
                        OptimizerTimeConfig.builder()
                                .allMethodTimeoutBuffer(1.5)
                                .latencyMetrics(EnumSet.allOf(LatencyMetric.class))
                                .timeoutMetric(LATENCY_PERCENTILE_99)
                                .apiLatencyMetric(LATENCY_PERCENTILE_75)
                                .appLatencyMetric(LATENCY_PERCENTILE_995)
                                .build())
                .enabled(true)
                .build();
    }


}
