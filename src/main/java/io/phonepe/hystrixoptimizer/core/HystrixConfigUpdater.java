package io.phonepe.hystrixoptimizer.core;

import static io.phonepe.hystrixoptimizer.metrics.ThreadPoolMetric.ROLLING_MAX_ACTIVE_THREADS;

import com.google.common.collect.Maps;
import com.hystrix.configurator.config.CommandThreadPoolConfig;
import com.hystrix.configurator.config.HystrixCommandConfig;
import com.hystrix.configurator.config.HystrixConfig;
import com.hystrix.configurator.config.ThreadPoolConfig;
import com.hystrix.configurator.core.HystrixConfigurationFactory;
import io.phonepe.hystrixoptimizer.config.OptimizerConcurrencyConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerTimeConfig;
import io.phonepe.hystrixoptimizer.models.OptimalThreadPoolAttributes;
import io.phonepe.hystrixoptimizer.models.OptimalThreadPoolAttributes.OptimalThreadPoolAttributesBuilder;
import io.phonepe.hystrixoptimizer.models.OptimalTimeoutAttributes;
import io.phonepe.hystrixoptimizer.models.OptimalTimeoutAttributes.OptimalTimeoutAttributesBuilder;
import io.phonepe.hystrixoptimizer.metrics.AggregationAlgo;
import io.phonepe.hystrixoptimizer.metrics.LatencyMetric;
import io.phonepe.hystrixoptimizer.models.OptimizerAggregatedMetric;
import io.phonepe.hystrixoptimizer.models.OptimizerMetrics;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/***
 Created by nitish.goyal on 29/03/19
 ***/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class HystrixConfigUpdater implements Runnable {

    private HystrixConfig hystrixConfig;
    private OptimizerConfig optimizerConfig;
    private OptimizerMetricsCache optimizerMetricsCache;

    private static final int DEFAULT_CONCURRENCY = 3;
    public static final String GLOBAL_THREAD_POOL_PREFIX = "global_";

    @Override
    public void run() {
        try {
            log.info("Running revolver config updater job with exception catching enabled");
            Map<OptimizerCacheKey, OptimizerMetrics> metricsCache = optimizerMetricsCache.getCache();
            if (metricsCache.isEmpty()) {
                log.info("Metrics cache is empty");
                return;
            }

            // Map to compute sum and keep count of all latency metrics :
            //       latencyExecute_percentile_995, latencyExecute_percentile_90,
            //       latencyExecute_percentile_75, latencyExecute_percentile_50
            Map<String, OptimizerAggregatedMetric> aggregatedAppLatencyMetrics = Maps.newHashMap();

            // Map to keep max values of thread pool metrics at api level
            Map<String, OptimizerMetrics> apiLevelThreadPoolMetrics = Maps.newHashMap();

            // Map to keep latency metrics' sum and count at api level
            Map<String, Map<String, OptimizerAggregatedMetric>> aggregateApiLevelLatencyMetrics = Maps.newHashMap();

            metricsCache.forEach((key, optimizerMetrics) -> {
                optimizerMetrics.getMetrics().forEach((metric, value) -> {
                    aggregateAppLevelLatencyMetrics(aggregatedAppLatencyMetrics, metric, value);
                    aggregateApiLevelMetrics(apiLevelThreadPoolMetrics,
                            aggregateApiLevelLatencyMetrics, metric, value, key);
                });

            });

            Map<String, Number> appLevelLatencyMetrics = avgAppLevelLatencyMetrics(aggregatedAppLatencyMetrics);
            Map<String, OptimizerMetrics> apiLevelLatencyMetrics = avgApiLevelLatencyMetrics(
                    aggregateApiLevelLatencyMetrics);

            updateHystrixConfig(apiLevelThreadPoolMetrics, apiLevelLatencyMetrics);
            updateLatencyThreshold(appLevelLatencyMetrics);
        } catch (Exception e) {
            log.error("Hystrix config couldn't be updated : " + e);
        }

    }

    private Map<String, OptimizerMetrics> avgApiLevelLatencyMetrics(
            Map<String, Map<String, OptimizerAggregatedMetric>> aggregatedLatencyMetrics) {
        Map<String, OptimizerMetrics> aggregateApiLevelLatencyMetrics = Maps.newHashMap();
        aggregatedLatencyMetrics.forEach((keyName, latencyMetricMap) -> {
            if (!aggregateApiLevelLatencyMetrics.containsKey(keyName)) {
                aggregateApiLevelLatencyMetrics.put(keyName, OptimizerMetrics.builder()
                        .metrics(Maps.newHashMap())
                        .build());
            }
            latencyMetricMap.forEach((metric, aggregateMetric) -> {
                aggregateApiLevelLatencyMetrics
                        .get(keyName)
                        .getMetrics()
                        .put(metric, aggregateMetric.getSum() / aggregateMetric.getCount());
            });
        });
        return aggregateApiLevelLatencyMetrics;
    }

    private Map<String, Number> avgAppLevelLatencyMetrics(
            Map<String, OptimizerAggregatedMetric> overallAppLatencyMetrics) {
        Map<String, Number> aggregatedAppLevelLatencyMetrics = Maps.newHashMap();
        overallAppLatencyMetrics.forEach((metricName, aggregatedAppMetrics) -> {
            aggregatedAppLevelLatencyMetrics
                    .put(metricName, aggregatedAppMetrics.getSum() / aggregatedAppMetrics.getCount());
        });
        return aggregatedAppLevelLatencyMetrics;
    }


    private void updateLatencyThreshold(Map<String, Number> appLevelLatencyMetrics) {

        OptimizerTimeConfig optimizerTimeConfig = optimizerConfig.getTimeConfig();
        if (optimizerTimeConfig == null || !optimizerTimeConfig.isEnabled()
                || !appLevelLatencyMetrics.containsKey(optimizerTimeConfig.getAppLatencyMetric().getMetricName())) {
            return;
        }
        int latencyThresholdValue = appLevelLatencyMetrics
                .get(optimizerTimeConfig.getAppLatencyMetric().getMetricName()).intValue();
        optimizerTimeConfig.setAppLatencyThresholdValue(latencyThresholdValue);
    }

    private void aggregateAppLevelLatencyMetrics(
            Map<String, OptimizerAggregatedMetric> aggregatedAppLatencyMetrics,
            String metric, Number value) {

        OptimizerTimeConfig optimizerTimeConfig = optimizerConfig.getTimeConfig();
        if (optimizerTimeConfig == null || !optimizerTimeConfig.isEnabled()
                || !optimizerTimeConfig.getLatencyMetrics().contains(LatencyMetric.valueOf(metric))
                || value.intValue() == 0) {
            return;
        }

        if (!aggregatedAppLatencyMetrics.containsKey(metric)) {
            aggregatedAppLatencyMetrics.put(metric, OptimizerAggregatedMetric.builder()
                    .sum(0L)
                    .count(0L)
                    .build());
        }

        // aggregate metric value into sum and update count of metric
        OptimizerAggregatedMetric optimizerAggregatedMetrics = aggregatedAppLatencyMetrics.get(metric);
        optimizerAggregatedMetrics.setSum(optimizerAggregatedMetrics.getSum()
                + value.longValue());
        optimizerAggregatedMetrics.setCount(optimizerAggregatedMetrics.getCount() + 1L);

        aggregatedAppLatencyMetrics.forEach((metricName, aggregatedAppMetrics) -> {
            log.info("Aggregated " + metricName + " for app: "
                    + aggregatedAppMetrics.getSum() / aggregatedAppMetrics.getCount());
        });
    }

    private void aggregateApiLevelMetrics(
            Map<String, OptimizerMetrics> apiLevelThreadPoolMetrics,
            Map<String, Map<String, OptimizerAggregatedMetric>> aggregateApiLevelLatencyMetrics,
            String metric, Number value, OptimizerCacheKey key) {
        AggregationAlgo aggregationAlgo = key.getMetricType().getAggregationAlgo();
        switch (aggregationAlgo) {
            case MAX:
                Map<String, Number> threadPoolMetricsMap = getThreadPoolMetricsMap(apiLevelThreadPoolMetrics, key);
                if (!threadPoolMetricsMap.containsKey(metric)
                        || threadPoolMetricsMap.get(metric).intValue() < value.intValue()) {
                    threadPoolMetricsMap.put(metric, value);
                }
                break;
            case AVG:
                OptimizerAggregatedMetric optimizerAggregatedMetric =
                        getAggregateMetricsMap(aggregateApiLevelLatencyMetrics, key, metric);
                optimizerAggregatedMetric.setSum(optimizerAggregatedMetric.getSum() + value.longValue());
                optimizerAggregatedMetric.setCount(optimizerAggregatedMetric.getCount() + 1);
                break;
        }
    }

    private Map<String, Number> getThreadPoolMetricsMap(
            Map<String, OptimizerMetrics> maxedThreadPoolMetrics, OptimizerCacheKey key) {
        if (!maxedThreadPoolMetrics.containsKey(key.getName())) {
            maxedThreadPoolMetrics.put(key.getName(),
                    OptimizerMetrics.builder().metrics(Maps.newHashMap())
                            .build());
        }

        OptimizerMetrics optimizerAggregatedMetrics = maxedThreadPoolMetrics
                .get(key.getName());

        return optimizerAggregatedMetrics.getMetrics();
    }

    private OptimizerAggregatedMetric getAggregateMetricsMap(
            Map<String, Map<String, OptimizerAggregatedMetric>> aggregatedMetrics, OptimizerCacheKey key,
            String metric) {
        if (!aggregatedMetrics.containsKey(key.getName())) {
            aggregatedMetrics.put(key.getName(), Maps.newHashMap());
        }

        if (!aggregatedMetrics.get(key.getName()).containsKey(metric)) {
            aggregatedMetrics.get(key.getName()).put(metric,
                    OptimizerAggregatedMetric.builder()
                            .sum(0L)
                            .count(0L)
                            .build());
        }

        return aggregatedMetrics.get(key.getName()).get(metric);
    }

    private void updateHystrixConfig(Map<String, OptimizerMetrics> apiLevelThreadPoolMetrics,
            Map<String, OptimizerMetrics> apiLevelLatencyMetrics) {
        AtomicBoolean configUpdated = new AtomicBoolean();

        hystrixConfig.getPools().forEach((poolName, threadPoolConfig) -> {
            OptimizerMetrics optimizerThreadPoolMetrics = apiLevelThreadPoolMetrics
                    .get(GLOBAL_THREAD_POOL_PREFIX + poolName);
            updateConcurrencySettingForPool(threadPoolConfig, optimizerThreadPoolMetrics,
                    configUpdated, poolName);
        });

        Set<String> existingCommandConfigKeys = hystrixConfig.getCommands().stream()
                .map(HystrixCommandConfig::getName)
                .collect(Collectors.toSet());

        hystrixConfig.getCommands().forEach(hystrixCommandConfig -> {
            updateApiSettings(hystrixCommandConfig, apiLevelThreadPoolMetrics, apiLevelLatencyMetrics,
                    configUpdated);
        });

        List<HystrixCommandConfig> hystrixCommandConfigs = hystrixConfig.getCommands();

        apiLevelLatencyMetrics.keySet().stream()
                .filter(keyName -> !existingCommandConfigKeys.contains(keyName))
                .forEach(commandName -> {
                    OptimizerMetrics optimizerLatencyMetrics = apiLevelLatencyMetrics.get(commandName);
                    OptimizerMetrics optimizerThreadPoolMetrics = apiLevelThreadPoolMetrics.get(commandName);
                    if (optimizerLatencyMetrics == null || optimizerThreadPoolMetrics == null) {
                        return;
                    }

                    OptimalThreadPoolAttributes optimalThreadPoolAttributes = calculateOptimalThreadPoolSize(
                            hystrixConfig.getDefaultConfig().getThreadPool().getConcurrency(),
                            commandName, optimizerThreadPoolMetrics);
                    OptimalTimeoutAttributes optimalTimeoutAttributes = calculateOptimalTimeout(
                            hystrixConfig.getDefaultConfig().getThreadPool().getTimeout(),
                            optimizerLatencyMetrics);

                    log.info("Setting concurrency for : " + commandName + " from : " + hystrixConfig
                            .getDefaultConfig().getThreadPool().getConcurrency()
                            + " to : " + optimalThreadPoolAttributes.getOptimalConcurrency() +
                            ", maxRollingActiveThreads : " + optimalThreadPoolAttributes.getMaxRollingActiveThreads());
                    log.info("Setting timeout for : " + commandName + " from : "
                            + hystrixConfig.getDefaultConfig().getThreadPool().getTimeout()
                            + " to : " + optimalTimeoutAttributes.getOptimalTimeout() + ", " + "meanTimeoutValue : " +
                            optimalTimeoutAttributes.getMeanTimeout()
                            + ", with timeout buffer : " + optimalTimeoutAttributes.getTimeoutBuffer());
                    HystrixCommandConfig hystrixCommandConfig = HystrixCommandConfig.builder()
                            .name(commandName)
                            .threadPool(CommandThreadPoolConfig.builder()
                                    .concurrency(optimalThreadPoolAttributes.getOptimalConcurrency())
                                    .timeout(optimalTimeoutAttributes.getOptimalTimeout())
                                    .build())
                            .build();
                    hystrixCommandConfigs.add(hystrixCommandConfig);
                    configUpdated.set(true);
                });

        if (configUpdated.get()) {
            hystrixConfig.setCommands(hystrixCommandConfigs);
            HystrixConfigurationFactory.init(hystrixConfig);
        }
    }

    private void updateApiSettings(HystrixCommandConfig hystrixCommandConfig,
            Map<String, OptimizerMetrics> apiLevelThreadPoolMetrics,
            Map<String, OptimizerMetrics> apiLevelLatencyMetrics, AtomicBoolean configUpdated) {

        String commandName = hystrixCommandConfig.getName();
        OptimizerMetrics optimizerLatencyMetrics = apiLevelLatencyMetrics.get(commandName);
        OptimizerMetrics optimizerThreadPoolMetrics = apiLevelThreadPoolMetrics.get(commandName);

        if (optimizerLatencyMetrics == null || optimizerThreadPoolMetrics == null) {
            return;
        }
        updateConcurrencySettingForCommand(hystrixCommandConfig.getThreadPool(), optimizerThreadPoolMetrics,
                configUpdated, commandName);
        updateTimeoutSettingForCommand(hystrixCommandConfig.getThreadPool(), optimizerLatencyMetrics,
                configUpdated, commandName);
    }

    private void updateConcurrencySettingForCommand(CommandThreadPoolConfig threadPoolConfig,
            OptimizerMetrics optimizerThreadPoolMetrics, AtomicBoolean configUpdated,
            String poolName) {

        OptimalThreadPoolAttributes optimalThreadPoolAttributes = calculateOptimalThreadPoolSize(
                threadPoolConfig.getConcurrency(), poolName,
                optimizerThreadPoolMetrics);
        if (optimalThreadPoolAttributes.getOptimalConcurrency() != threadPoolConfig.getConcurrency()) {
            log.info("Setting concurrency for command : " + poolName + " from : " + threadPoolConfig.getConcurrency()
                    + " to : "
                    + optimalThreadPoolAttributes.getOptimalConcurrency() + ", maxRollingActiveThreads : "
                    + optimalThreadPoolAttributes.getMaxRollingActiveThreads());
            threadPoolConfig.setConcurrency(optimalThreadPoolAttributes.getOptimalConcurrency());
            configUpdated.set(true);
        }

    }

    private void updateConcurrencySettingForPool(ThreadPoolConfig threadPoolConfig,
            OptimizerMetrics optimizerThreadPoolMetrics, AtomicBoolean configUpdated,
            String poolName) {

        OptimalThreadPoolAttributes optimalThreadPoolAttributes = calculateOptimalThreadPoolSize(
                threadPoolConfig.getConcurrency(), poolName,
                optimizerThreadPoolMetrics);
        if (optimalThreadPoolAttributes.getOptimalConcurrency() != threadPoolConfig.getConcurrency()) {
            log.info("Setting concurrency for pool : " + poolName + " from : " + threadPoolConfig.getConcurrency()
                    + " to : "
                    + optimalThreadPoolAttributes.getOptimalConcurrency() + ", maxRollingActiveThreads : "
                    + optimalThreadPoolAttributes.getMaxRollingActiveThreads());
            threadPoolConfig.setConcurrency(optimalThreadPoolAttributes.getOptimalConcurrency());
            configUpdated.set(true);
        }

    }

    private void updateTimeoutSettingForCommand(CommandThreadPoolConfig threadPoolConfig,
            OptimizerMetrics optimizerLatencyMetrics, AtomicBoolean configUpdated,
            String commandName) {

        OptimalTimeoutAttributes optimalTimeoutAttributes = calculateOptimalTimeout(threadPoolConfig.getTimeout(),
                optimizerLatencyMetrics);
        if (optimalTimeoutAttributes.getOptimalTimeout() != threadPoolConfig.getTimeout()) {
            threadPoolConfig.setTimeout(optimalTimeoutAttributes.getOptimalTimeout());
            configUpdated.set(true);
            log.info("Setting timeout for : " + commandName + " from : " + threadPoolConfig.getTimeout()
                    + " to : " + optimalTimeoutAttributes.getOptimalTimeout() + ", " + "meanTimeoutValue : " +
                    optimalTimeoutAttributes.getMeanTimeout()
                    + ", with timeout buffer : " + optimalTimeoutAttributes.getTimeoutBuffer());
        }

    }

    private OptimalThreadPoolAttributes calculateOptimalThreadPoolSize(int initialConcurrency, String poolName,
            OptimizerMetrics optimizerThreadPoolMetrics) {
        OptimalThreadPoolAttributesBuilder initialConcurrencyAttrBuilder = OptimalThreadPoolAttributes.builder()
                .optimalConcurrency(initialConcurrency);

        OptimizerConcurrencyConfig concurrencyConfig = optimizerConfig.getConcurrencyConfig();
        if (concurrencyConfig == null || !concurrencyConfig.isEnabled()
                || !optimizerThreadPoolMetrics.getMetrics().containsKey(ROLLING_MAX_ACTIVE_THREADS.getMetricName())) {
            return initialConcurrencyAttrBuilder.build();
        }

        int maxRollingActiveThreads = optimizerThreadPoolMetrics.getMetrics()
                .getOrDefault(ROLLING_MAX_ACTIVE_THREADS.getMetricName(), 0).intValue();

        log.info("Optimizer Concurrency Settings Enabled : {}, "
                        + "Max Threads Multiplier : {}, Max Threshold : {}, Initial Concurrency : {}, Pool Name: {}",
                concurrencyConfig.isEnabled(), concurrencyConfig.getMaxThreadsMultiplier(),
                concurrencyConfig.getMaxThreshold(), initialConcurrency, poolName);

        if (maxRollingActiveThreads == 0) {
            return OptimalThreadPoolAttributes.builder()
                    .optimalConcurrency(DEFAULT_CONCURRENCY)
                    .maxRollingActiveThreads(maxRollingActiveThreads)
                    .build();
        } else if ((maxRollingActiveThreads > initialConcurrency * concurrencyConfig.getMaxThreshold()
                || maxRollingActiveThreads < initialConcurrency * concurrencyConfig.getMinThreshold())
                && maxRollingActiveThreads
                < initialConcurrency * concurrencyConfig.getMaxThreadsMultiplier()) {
            int optimalConcurrency = (int) Math
                    .ceil(maxRollingActiveThreads * concurrencyConfig.getBandwidth());
            return OptimalThreadPoolAttributes.builder()
                    .optimalConcurrency(optimalConcurrency)
                    .maxRollingActiveThreads(maxRollingActiveThreads)
                    .build();
        } else {
            return initialConcurrencyAttrBuilder
                    .maxRollingActiveThreads(maxRollingActiveThreads)
                    .build();
        }
    }


    private OptimalTimeoutAttributes calculateOptimalTimeout(int currentTimeout,
            OptimizerMetrics optimizerLatencyMetrics) {
        OptimalTimeoutAttributesBuilder initialTimeoutAttributesBuilder = OptimalTimeoutAttributes
                .builder()
                .optimalTimeout(currentTimeout);

        OptimizerTimeConfig timeoutConfig = optimizerConfig.getTimeConfig();
        if (timeoutConfig == null || !timeoutConfig.isEnabled()
                || !optimizerLatencyMetrics.getMetrics()
                .containsKey(timeoutConfig.getTimeoutMetric().getMetricName())) {
            return initialTimeoutAttributesBuilder.build();
        }

        int meanTimeoutValue = optimizerLatencyMetrics.getMetrics()
                .get(timeoutConfig.getTimeoutMetric().getMetricName()).intValue();

        if (meanTimeoutValue <= 0) {
            return initialTimeoutAttributesBuilder
                    .meanTimeout(meanTimeoutValue)
                    .build();
        }

        double timeoutBuffer = timeoutConfig.getAllMethodTimeoutBuffer();

        if (currentTimeout < meanTimeoutValue || currentTimeout > (meanTimeoutValue * timeoutBuffer)) {
            return OptimalTimeoutAttributes.builder()
                    .meanTimeout(meanTimeoutValue)
                    .timeoutBuffer(timeoutBuffer)
                    .optimalTimeout((int) (meanTimeoutValue * timeoutBuffer))
                    .build();
        } else {
            return initialTimeoutAttributesBuilder
                    .meanTimeout(meanTimeoutValue)
                    .timeoutBuffer(timeoutBuffer)
                    .build();
        }

    }

}
