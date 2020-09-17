package io.phonepe.hystrixoptimizer.core;

import static io.phonepe.hystrixoptimizer.metrics.ThreadPoolMetric.ROLLING_MAX_ACTIVE_THREADS;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.hystrix.configurator.config.CommandThreadPoolConfig;
import com.hystrix.configurator.config.HystrixCommandConfig;
import com.hystrix.configurator.config.HystrixConfig;
import com.hystrix.configurator.config.ThreadPoolConfig;
import com.hystrix.configurator.core.HystrixConfigurationFactory;
import io.phonepe.hystrixoptimizer.config.actions.Actions;
import io.phonepe.hystrixoptimizer.config.actions.impl.EmailConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerConcurrencyConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerTimeConfig;
import io.phonepe.hystrixoptimizer.config.actions.impl.UpdateHystrixConfig;
import io.phonepe.hystrixoptimizer.email.EmailClient;
import io.phonepe.hystrixoptimizer.models.ActionType;
import io.phonepe.hystrixoptimizer.utils.ActionTypeVisitor;
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

import io.phonepe.hystrixoptimizer.utils.DiffHelper;
import io.phonepe.hystrixoptimizer.utils.EmailUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/***
 Created by nitish.goyal on 29/03/19
 ***/
@Data
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class HystrixConfigUpdater implements Runnable {

    private HystrixConfig hystrixConfig;
    private HystrixConfig initialHystrixConfig;
    private OptimizerConfig optimizerConfig;
    private OptimizerMetricsCache optimizerMetricsCache;
    private Map<String, HystrixCommandConfig> initialHystrixCommandConfigMap;
    private Actions allowedActions;
    private EmailClient emailClient;
    private DiffHelper<HystrixConfig> diffHelper;

    public HystrixConfigUpdater(final HystrixConfig hystrixConfig,
                                final HystrixConfig initialHystrixConfig,
                                final OptimizerConfig optimizerConfig,
                                final OptimizerMetricsCache optimizerMetricsCache,
                                final Actions allowedActions) {
        this.hystrixConfig = hystrixConfig;
        this.initialHystrixConfig = initialHystrixConfig;
        this.optimizerConfig = optimizerConfig;
        this.optimizerMetricsCache = optimizerMetricsCache;
        this.initialHystrixCommandConfigMap = Maps.newHashMap();
        this.initialHystrixConfig.getCommands()
                .forEach(hystrixCommandConfig -> initialHystrixCommandConfigMap
                        .putIfAbsent(hystrixCommandConfig.getName(), hystrixCommandConfig));
        this.diffHelper = new DiffHelper<>(new ObjectMapper());

        if (allowedActions.getActionConfigs().stream().anyMatch(actionConfig ->
                actionConfig.getActionType() == ActionType.SEND_EMAIL_ALERT)) {
            EmailConfig emailConfig = EmailUtil.getEmailConfig(allowedActions);
            Preconditions.checkNotNull(emailConfig, "Email Config cannot be null");
            this.emailClient = new EmailClient(emailConfig);
        }
    }

    public static final String GLOBAL_THREAD_POOL_PREFIX = "global_";

    @Override
    public void run() {
        try {
            log.info("Running hystrix config updater job with exception catching enabled");
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
            log.debug("Aggregated API Level Latency Metrics: {}", aggregateApiLevelLatencyMetrics);
            Map<String, OptimizerMetrics> apiLevelLatencyMetrics = avgApiLevelLatencyMetrics(
                    aggregateApiLevelLatencyMetrics);
            log.debug("API Level Latency Metrics: {}", apiLevelLatencyMetrics);
            updateHystrixConfig(apiLevelThreadPoolMetrics, apiLevelLatencyMetrics);
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

        updateHystrixConfigForThreadPoolGroups(apiLevelThreadPoolMetrics, configUpdated);
        log.debug("Updated Config for thread pool groups.");

        updateHystrixConfigForCommands(apiLevelThreadPoolMetrics, apiLevelLatencyMetrics, configUpdated);
        log.debug("Updated Config for commands.");

        List<HystrixCommandConfig> hystrixCommandConfigs = updateHystrixConfigForCommandsWithDefaultConfig(
                apiLevelThreadPoolMetrics, apiLevelLatencyMetrics, configUpdated);
        log.debug("Updated Config for default cases.");

        if (configUpdated.get()) {
            takeAction(hystrixCommandConfigs);
        }
    }

    /**
     * Updates thread pool config of commands which are not explicitly specified in commands
     * <p>
     * (to which default config is applied at first)
     * <p>
     * This will be executed only once at the start, thereafter all commands configs will be added to the list
     */
    private List<HystrixCommandConfig> updateHystrixConfigForCommandsWithDefaultConfig(
            Map<String, OptimizerMetrics> apiLevelThreadPoolMetrics,
            Map<String, OptimizerMetrics> apiLevelLatencyMetrics, AtomicBoolean configUpdated) {
        List<HystrixCommandConfig> hystrixCommandConfigs = hystrixConfig.getCommands();

        Set<String> existingCommandConfigKeys = hystrixConfig.getCommands().stream()
                .map(HystrixCommandConfig::getName)
                .collect(Collectors.toSet());

        apiLevelLatencyMetrics.keySet().stream()
                .filter(keyName -> !existingCommandConfigKeys.contains(keyName))
                .forEach(commandName -> {
                    OptimizerMetrics optimizerLatencyMetrics = apiLevelLatencyMetrics.get(commandName);
                    OptimizerMetrics optimizerThreadPoolMetrics = apiLevelThreadPoolMetrics.get(commandName);
                    if (optimizerLatencyMetrics == null || optimizerThreadPoolMetrics == null) {
                        return;
                    }
                    int concurrency = hystrixConfig.getDefaultConfig().getThreadPool().getConcurrency();
                    OptimalThreadPoolAttributes optimalThreadPoolAttributes = calculateOptimalThreadPoolSize(
                            concurrency, concurrency, commandName, optimizerThreadPoolMetrics);
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
        return hystrixCommandConfigs;
    }

    private void updateHystrixConfigForCommands(Map<String, OptimizerMetrics> apiLevelThreadPoolMetrics,
                                                Map<String, OptimizerMetrics> apiLevelLatencyMetrics, AtomicBoolean configUpdated) {
        hystrixConfig.getCommands().forEach(hystrixCommandConfig -> {
            HystrixCommandConfig initialHystrixCommandConfig = initialHystrixCommandConfigMap
                    .get(hystrixCommandConfig.getName());
            int initialConcurrency = initialHystrixCommandConfig != null
                    ? initialHystrixCommandConfig.getThreadPool().getConcurrency()
                    : initialHystrixConfig.getDefaultConfig().getThreadPool().getConcurrency();
            updateApiSettings(hystrixCommandConfig, initialConcurrency, apiLevelThreadPoolMetrics,
                    apiLevelLatencyMetrics,
                    configUpdated);
        });
    }

    private void updateHystrixConfigForThreadPoolGroups(Map<String, OptimizerMetrics> apiLevelThreadPoolMetrics,
                                                        AtomicBoolean configUpdated) {
        // Update hystrix config for thread pool groups
        hystrixConfig.getPools().forEach((poolName, currentThreadPoolConfig) -> {
            OptimizerMetrics optimizerThreadPoolMetrics = apiLevelThreadPoolMetrics
                    .get(GLOBAL_THREAD_POOL_PREFIX + poolName);
            ThreadPoolConfig initialThreadPoolConfig = initialHystrixConfig.getPools().get(poolName);
            Preconditions.checkArgument(initialThreadPoolConfig != null,
                    "Initial thread pool config for pool name : " + poolName + " is null");

            log.debug("PoolName: {}, Current ThreadPool: {}", poolName, currentThreadPoolConfig);
            log.debug("PoolName: {}, Initial ThreadPool: {}", poolName, initialThreadPoolConfig);

            updateConcurrencySettingForPool(currentThreadPoolConfig, initialThreadPoolConfig,
                    optimizerThreadPoolMetrics, poolName, configUpdated);
        });
    }

    private void updateApiSettings(HystrixCommandConfig hystrixCommandConfig, int initialConcurrency,
                                   Map<String, OptimizerMetrics> apiLevelThreadPoolMetrics,
                                   Map<String, OptimizerMetrics> apiLevelLatencyMetrics, AtomicBoolean configUpdated) {

        String commandName = hystrixCommandConfig.getName();
        OptimizerMetrics optimizerLatencyMetrics = apiLevelLatencyMetrics.get(commandName);
        OptimizerMetrics optimizerThreadPoolMetrics = apiLevelThreadPoolMetrics.get(commandName);

        if (optimizerLatencyMetrics == null || optimizerThreadPoolMetrics == null) {
            return;
        }
        updateConcurrencySettingForCommand(hystrixCommandConfig.getThreadPool(), initialConcurrency,
                optimizerThreadPoolMetrics, commandName, configUpdated);
        updateTimeoutSettingForCommand(hystrixCommandConfig.getThreadPool(), optimizerLatencyMetrics, commandName,
                configUpdated);
    }

    private void updateConcurrencySettingForCommand(CommandThreadPoolConfig threadPoolConfig,
                                                    int initialConcurrency, OptimizerMetrics optimizerThreadPoolMetrics, String poolName,
                                                    AtomicBoolean configUpdated) {

        OptimalThreadPoolAttributes optimalThreadPoolAttributes = calculateOptimalThreadPoolSize(
                threadPoolConfig.getConcurrency(), initialConcurrency, poolName,
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

    private void updateConcurrencySettingForPool(ThreadPoolConfig currentThreadPoolConfig,
                                                 ThreadPoolConfig initialThreadPoolConfig,
                                                 OptimizerMetrics optimizerThreadPoolMetrics, String poolName, AtomicBoolean configUpdated) {

        OptimalThreadPoolAttributes optimalThreadPoolAttributes = calculateOptimalThreadPoolSize(
                currentThreadPoolConfig.getConcurrency(), initialThreadPoolConfig.getConcurrency(), poolName,
                optimizerThreadPoolMetrics);
        if (optimalThreadPoolAttributes.getOptimalConcurrency() != currentThreadPoolConfig.getConcurrency()) {
            log.info(
                    "Setting concurrency for pool : " + poolName + " from : " + currentThreadPoolConfig.getConcurrency()
                            + " to : "
                            + optimalThreadPoolAttributes.getOptimalConcurrency() + ", maxRollingActiveThreads : "
                            + optimalThreadPoolAttributes.getMaxRollingActiveThreads());
            currentThreadPoolConfig.setConcurrency(optimalThreadPoolAttributes.getOptimalConcurrency());
            configUpdated.set(true);
        }

    }

    private void updateTimeoutSettingForCommand(CommandThreadPoolConfig threadPoolConfig,
                                                OptimizerMetrics optimizerLatencyMetrics, String commandName, AtomicBoolean configUpdated) {

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

    private OptimalThreadPoolAttributes calculateOptimalThreadPoolSize(int currentConcurrency, int initialConcurrency,
                                                                       String poolName,
                                                                       OptimizerMetrics optimizerThreadPoolMetrics) {
        OptimalThreadPoolAttributesBuilder initialConcurrencyAttrBuilder = OptimalThreadPoolAttributes.builder()
                .optimalConcurrency(currentConcurrency);

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
                concurrencyConfig.getMaxThreshold(), currentConcurrency, poolName);

        if (maxRollingActiveThreads == 0) {
            return OptimalThreadPoolAttributes.builder()
                    .optimalConcurrency(optimizerConfig.getConcurrencyConfig().getDefaultConcurrency())
                    .maxRollingActiveThreads(maxRollingActiveThreads)
                    .build();
        } else if ((maxRollingActiveThreads > currentConcurrency * concurrencyConfig.getMaxThreshold()
                || maxRollingActiveThreads < currentConcurrency * concurrencyConfig.getMinThreshold())
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

    private void takeAction(final List<HystrixCommandConfig> hystrixCommandConfigs) {
        allowedActions.getActionConfigs().forEach(
                actionConfig -> actionConfig.accept(new ActionTypeVisitor<Void>() {

                    @Override
                    public Void visitUpdateHystrixConfig(UpdateHystrixConfig updateHystrixConfig) {
                        hystrixConfig.setCommands(hystrixCommandConfigs);
                        HystrixConfigurationFactory.init(hystrixConfig);

                        log.debug("Updated Hystrix config with command cache : {}, pool cache: {}",
                                HystrixConfigurationFactory.getCommandCache(), HystrixConfigurationFactory.getPoolCache());
                        return null;
                    }

                    @Override
                    public Void visitSendEmailAlert(EmailConfig emailConfig) {
                        final String jsonDiff = diffHelper.getObjectDiff(initialHystrixConfig, hystrixConfig);
                        if (!Strings.isNullOrEmpty(jsonDiff)) {
                            log.info("Sending Email Alert for Config Update");
                            emailClient.sendEmail(EmailUtil.emailAddresses(emailConfig.getReceivers()),
                                    "Hystrix Config Updated",
                                    jsonDiff);
                        }
                        return null;
                    }
                }));
    }
}
