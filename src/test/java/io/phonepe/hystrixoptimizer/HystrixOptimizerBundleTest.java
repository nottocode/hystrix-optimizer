package io.phonepe.hystrixoptimizer;

import static io.phonepe.hystrixoptimizer.core.HystrixConfigUpdater.GLOBAL_THREAD_POOL_PREFIX;
import static io.phonepe.hystrixoptimizer.metrics.LatencyMetric.LATENCY_PERCENTILE_50;
import static io.phonepe.hystrixoptimizer.metrics.LatencyMetric.LATENCY_PERCENTILE_75;
import static io.phonepe.hystrixoptimizer.metrics.LatencyMetric.LATENCY_PERCENTILE_99;
import static io.phonepe.hystrixoptimizer.metrics.LatencyMetric.LATENCY_PERCENTILE_995;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hystrix.configurator.config.CommandThreadPoolConfig;
import com.hystrix.configurator.config.HystrixCommandConfig;
import com.hystrix.configurator.config.HystrixConfig;
import com.hystrix.configurator.config.HystrixDefaultConfig;
import com.hystrix.configurator.config.ThreadPoolConfig;
import io.dropwizard.Configuration;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.phonepe.hystrixoptimizer.config.actions.Actions;
import io.phonepe.hystrixoptimizer.config.OptimizerConfig;
import io.phonepe.hystrixoptimizer.config.actions.impl.UpdateHystrixConfig;
import io.phonepe.hystrixoptimizer.core.HystrixConfigUpdater;
import io.phonepe.hystrixoptimizer.core.OptimizerMetricsCache;
import io.phonepe.hystrixoptimizer.core.OptimizerMetricsCollector;
import io.phonepe.hystrixoptimizer.metrics.LatencyMetric;
import io.phonepe.hystrixoptimizer.utils.OptimizerUtils;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;

public class HystrixOptimizerBundleTest {

    private MetricRegistry metrics;
    private OptimizerMetricsCollector optimizerMetricsCollector;
    private HystrixConfigUpdater hystrixConfigUpdater;
    private static final String THREAD_POOL_PREFIX = "HystrixThreadPool";
    private static final String ROLLING_MAX_ACTIVE_THREADS = "rollingMaxActiveThreads";

    @BeforeClass
    public void beforeClass() throws IOException {
        HystrixConfig hystrixConfig = HystrixConfig.builder()
                .defaultConfig(defaultHystrixConfig())
                .pools(threadPoolConfigs())
                .commands(hystrixCommandConfigs())
                .build();

        OptimizerConfig optimizerConfig = OptimizerUtils.getDefaultOptimizerConfig();
        metrics = new MetricRegistry();

        Actions actions = Actions.builder()
                .actionConfigs(Collections.singletonList(UpdateHystrixConfig.builder()
                        .build()))
                .build();

        LifecycleEnvironment lifecycleEnvironment = new LifecycleEnvironment();
        Environment environment = mock(Environment.class);
        when(environment.getObjectMapper()).thenReturn(new ObjectMapper());
        when(environment.metrics()).thenReturn(metrics);
        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        Bootstrap<?> bootstrap = mock(Bootstrap.class);

        HystrixOptimizerBundle<Configuration> hystrixOptimizerBundle = new HystrixOptimizerBundle<Configuration>() {
            @Override
            public HystrixConfig getHystrixConfig(Configuration configuration) {
                return hystrixConfig;
            }

            @Override
            public OptimizerConfig getOptimizerConfig(Configuration configuration) {
                return optimizerConfig;
            }


            @Override
            public Actions getActions(Configuration configuration) {
                return actions;
            }

            @Override
            public void run(Configuration configuration, Environment environment) throws IOException {
                super.run(configuration, environment);
            }
        };

        hystrixOptimizerBundle.initialize(bootstrap);

        hystrixOptimizerBundle.run(mock(Configuration.class), environment);

        OptimizerMetricsCache optimizerMetricsCache = OptimizerMetricsCache.builder()
                .optimizerMetricsCollectorConfig(optimizerConfig.getMetricsCollectorConfig())
                .build();
        optimizerMetricsCollector = OptimizerMetricsCollector.builder()
                .metrics(metrics)
                .optimizerMetricsCache(optimizerMetricsCache)
                .optimizerConfig(optimizerConfig)
                .build();

        hystrixConfigUpdater = new HystrixConfigUpdater(hystrixConfig,
                environment.getObjectMapper().readValue(environment.getObjectMapper().writeValueAsString(hystrixConfig),
                        HystrixConfig.class),
                optimizerConfig, optimizerMetricsCache, actions);

        rollingMaxActiveGauge("myService.myCommand", 10);
        rollingMaxActiveGauge(GLOBAL_THREAD_POOL_PREFIX + "threadPool1", 2);
        rollingMaxActiveGauge(GLOBAL_THREAD_POOL_PREFIX + "threadPool2", 2);

        latencyMetricGauge("myService.myCommand", LATENCY_PERCENTILE_995, 300);
        latencyMetricGauge("myService.myCommand", LATENCY_PERCENTILE_99, 200);
        latencyMetricGauge("myService.myCommand", LATENCY_PERCENTILE_75, 150);
        latencyMetricGauge("myService.myCommand", LATENCY_PERCENTILE_50, 100);
    }

    private void latencyMetricGauge(String commandName, LatencyMetric latencyMetric, int value) {
        metrics.gauge(commandName + "." + commandName + "." + latencyMetric.getMetricName(),
                new MetricRegistry.MetricSupplier<Gauge>() {
                    @Override
                    public Gauge newMetric() {
                        return new Gauge() {
                            @Override
                            public Object getValue() {
                                return value;
                            }
                        };
                    }
                });
    }

    private void rollingMaxActiveGauge(String commandName, int value) {
        metrics.gauge(THREAD_POOL_PREFIX + "." + commandName + "." + ROLLING_MAX_ACTIVE_THREADS,
                new MetricRegistry.MetricSupplier<Gauge>() {
                    @Override
                    public Gauge newMetric() {
                        return new Gauge() {
                            @Override
                            public Object getValue() {
                                return value;
                            }
                        };
                    }
                });
    }

    private static List<HystrixCommandConfig> hystrixCommandConfigs() {
        HystrixCommandConfig hystrixCommandConfig1 = HystrixCommandConfig.builder()
                .name("myService.myCommand")
                .threadPool(CommandThreadPoolConfig.builder()
                        .timeout(2000)
                        .concurrency(8)
                        .build())
                .build();
        return Collections.singletonList(hystrixCommandConfig1);
    }

    private static Map<String, ThreadPoolConfig> threadPoolConfigs() {
        ThreadPoolConfig threadPool1 = ThreadPoolConfig.builder()
                .concurrency(4)
                .build();
        ThreadPoolConfig threadPool2 = ThreadPoolConfig.builder()
                .concurrency(8)
                .build();

        Map<String, ThreadPoolConfig> threadPoolConfigs = new HashMap<>();
        threadPoolConfigs.put("threadPool1", threadPool1);
        threadPoolConfigs.put("threadPool2", threadPool2);
        return threadPoolConfigs;
    }

    private static HystrixDefaultConfig defaultHystrixConfig() {
        return HystrixDefaultConfig.builder()
                .threadPool(CommandThreadPoolConfig.builder()
                        .concurrency(16)
                        .timeout(1000)
                        .build())
                .build();
    }

}
