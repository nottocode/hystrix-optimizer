package io.phonepe.hystrixoptimizer;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hystrix.configurator.config.HystrixConfig;
import com.hystrix.configurator.core.HystrixConfigurationFactory;
import com.netflix.hystrix.contrib.codahalemetricspublisher.HystrixCodaHaleMetricsPublisher;
import com.netflix.hystrix.strategy.HystrixPlugins;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.phonepe.hystrixoptimizer.config.actions.Actions;
import io.phonepe.hystrixoptimizer.config.OptimizerConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerConfigUpdaterConfig;
import io.phonepe.hystrixoptimizer.config.OptimizerMetricsCollectorConfig;
import io.phonepe.hystrixoptimizer.core.HystrixConfigUpdater;
import io.phonepe.hystrixoptimizer.core.OptimizerMetricsCache;
import io.phonepe.hystrixoptimizer.core.OptimizerMetricsCollector;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
public abstract class HystrixOptimizerBundle<T extends Configuration> implements ConfiguredBundle<T> {

    public abstract HystrixConfig getHystrixConfig(T configuration);

    public abstract OptimizerConfig getOptimizerConfig(T configuration);

    public abstract Actions getActions(T configuration);

    @Override
    public void initialize(Bootstrap<?> bootstrap) {
        HystrixPlugins.reset();
    }

    @Override
    public void run(T configuration, Environment environment) throws IOException {
        //Add metrics publisher
        HystrixCodaHaleMetricsPublisher metricsPublisher = new HystrixCodaHaleMetricsPublisher(
                environment.metrics());
        val metrics = environment.metrics();
        ScheduledExecutorService metricsBuilderExecutorService = environment.lifecycle()
                .scheduledExecutorService("optimizer-metrics-builder").build();
        ScheduledExecutorService configUpdaterExecutorService = environment.lifecycle()
                .scheduledExecutorService("hystrix-config-updater").build();

        HystrixPlugins.getInstance().registerMetricsPublisher(metricsPublisher);

        HystrixConfig hystrixConfig = getHystrixConfig(configuration);

        HystrixConfigurationFactory.init(hystrixConfig);

        Actions allowedActions = getActions(configuration);

        setupOptimizer(getOptimizerConfig(configuration), hystrixConfig, metrics, metricsBuilderExecutorService,
                configUpdaterExecutorService, environment.getObjectMapper(), allowedActions);
    }

    /**
     * Setup optimizer jobs for collecting metrics and updating hystrix config
     *
     * @param optimizerConfig OptimizerConfig
     * @param metrics MetricRegistry
     * @param metricsBuilderExecutorService Scheduled executor service to run metrics builder
     * @param configUpdaterExecutorService Scheduled executor service to run config updater
     */
    private void setupOptimizer(final OptimizerConfig optimizerConfig,
                                final HystrixConfig hystrixConfig,
                                final MetricRegistry metrics,
                                final ScheduledExecutorService metricsBuilderExecutorService,
                                final ScheduledExecutorService configUpdaterExecutorService,
                                final ObjectMapper objectMapper,
                                final Actions allowedActions) throws IOException {

        if (optimizerConfig != null && optimizerConfig.isEnabled()) {
            log.info("Optimizer config enabled");
            OptimizerMetricsCollectorConfig optimizerMetricsCollectorConfig = optimizerConfig
                    .getMetricsCollectorConfig();

            OptimizerMetricsCache optimizerMetricsCache = OptimizerMetricsCache.builder()
                    .optimizerMetricsCollectorConfig(optimizerMetricsCollectorConfig)
                    .build();
            OptimizerMetricsCollector optimizerMetricsCollector = OptimizerMetricsCollector.builder()
                    .metrics(metrics)
                    .optimizerMetricsCache(optimizerMetricsCache)
                    .optimizerConfig(optimizerConfig)
                    .build();

            metricsBuilderExecutorService.scheduleAtFixedRate(optimizerMetricsCollector,
                    optimizerConfig.getInitialDelay(),
                    optimizerMetricsCollectorConfig.getRepeatAfter(),
                    optimizerMetricsCollectorConfig.getTimeUnit());

            HystrixConfigUpdater hystrixConfigUpdater = new HystrixConfigUpdater(hystrixConfig,
                    objectMapper.readValue(objectMapper.writeValueAsString(hystrixConfig), HystrixConfig.class),
                    optimizerConfig, optimizerMetricsCache, allowedActions);

            OptimizerConfigUpdaterConfig configUpdaterConfig = optimizerConfig
                    .getConfigUpdaterConfig();

            configUpdaterExecutorService.scheduleAtFixedRate(hystrixConfigUpdater,
                    optimizerConfig.getInitialDelay(),
                    configUpdaterConfig.getRepeatAfter(),
                    configUpdaterConfig.getTimeUnit());

        }
    }
}
