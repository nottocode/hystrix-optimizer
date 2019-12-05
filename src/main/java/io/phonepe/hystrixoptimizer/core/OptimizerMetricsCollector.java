package io.phonepe.hystrixoptimizer.core;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Maps;
import io.phonepe.hystrixoptimizer.config.OptimizerConfig;
import io.phonepe.hystrixoptimizer.metrics.LatencyMetric;
import io.phonepe.hystrixoptimizer.metrics.OptimizerMetricType;
import io.phonepe.hystrixoptimizer.metrics.ThreadPoolMetric;
import io.phonepe.hystrixoptimizer.models.OptimizerMetrics;
import java.util.Set;
import java.util.SortedMap;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

/***
 Created by nitish.goyal on 29/03/19
 ***/
@Slf4j
@Data
@Builder
@AllArgsConstructor
public class OptimizerMetricsCollector implements Runnable {

    private MetricRegistry metrics;
    private OptimizerMetricsCache optimizerMetricsCache;
    private OptimizerConfig optimizerConfig;

    @Override
    public void run() {

        log.info("Running optimizer metrics collection job");
        SortedMap<String, Gauge> gauges = metrics.getGauges();
        Long time = new DateTime().getMillis();

        try {
            captureThreadPoolMetrics(gauges, time);
            captureLatencyMetrics(gauges, time);
        } catch (Exception e) {
            log.error("Error occurred while executing metrics collector : ", e);
        }
    }


    /**
     * Captures thread pool size allocated for threadpool group/ command (propertyValue_maximumSize)
     *
     * and Rolling max number of active threads during rolling statistical window (rollingMaxActiveThreads)
     *
     * example gauge key names :
     *
     * HystrixThreadPool.global_{threadpoolName}.rollingMaxActiveThreads,
     * HystrixThreadPool.{threadpoolGroupName}.rollingMaxActiveThreads,
     * HystrixThreadPool.{serviceName}.{commandName}.rollingMaxActiveThreads
     */
    private void captureThreadPoolMetrics(SortedMap<String, Gauge> gauges, Long time) {
        gauges.forEach((key, gauge) -> {
            updateOptimizerMetricsCache(key, gauge, time,1, OptimizerMetricType.THREAD_POOL,
                    ThreadPoolMetric.metrics());
        });
    }

    /**
     * Captures latency metrics for hystrix commands
     *
     * example gauge key names :
     *
     * revolver : {serviceName}.{serviceName}.{commandName}.latencyExecute_percentile_(995|90|75|50)
     * hystrix : {serviceName}.{commandName}.{serviceName}.{commandName}.latencyExecute_percentile_(995|90|75|50)
     */
    private void captureLatencyMetrics(SortedMap<String, Gauge> gauges, Long time) {
        gauges.forEach((key, gauge) -> {
            updateOptimizerMetricsCache(key, gauge, time, 2,OptimizerMetricType.LATENCY,
                    LatencyMetric.metrics());
        });

    }

    /**
     *
     * OptimizerMetricsCache: Map<OptimizerCacheKey, OptimizerMetrics>
     *
     * OptimizerCacheKey : {"time":1574682861000,
     *                      "name": "serviceName.commandName,
     *                      "metricType": {LATENCY/THREAD_POOL}"
     *                      }
     *
     * OptimizerMetrics :
     *   (LATENCY)   {
     *                  "metrics": {
     *                      "latencyExecute_percentile_995" : 100,
     *                      "latencyExecute_percentile_90" : 80,
     *                      "latencyExecute_percentile_75" : 50,
     *                      "latencyExecute_percentile_50" : 10
     *                  },
     *              "aggregationAlgo" : "AVG"
     *              }
     *
     * (THREAD_POOL) {
     *              "metrics": {
     *                     "propertyValue_maximumSize" : 5,
     *                     "rollingMaxActiveThreads" : 2,
     *                  },
     *              "aggregationAlgo" : "MAX"
     *              }
     *
     */
    private void updateOptimizerMetricsCache(String key, Gauge gauge, long time,
            int keyStartIndex, OptimizerMetricType metricType, Set<String> metricsToCapture) {
        String[] splits = key.split("\\.");
        int length = splits.length;
        if (length < metricType.getMinValidLength()) {
            return;
        }

        String metricName = splits[length - 1];
        if (!(metricsToCapture.contains(metricName))
                || !((gauge.getValue() instanceof Number))) {
            return;
        }

        OptimizerCacheKey cacheKey = getOptimizerCacheKey(time, splits, keyStartIndex, length, metricType);
        if (optimizerMetricsCache.get(cacheKey) == null) {
            optimizerMetricsCache.put(cacheKey, OptimizerMetrics.builder()
                    .metrics(Maps.newHashMap())
                    .build());
        }
        OptimizerMetrics optimizerMetrics = optimizerMetricsCache.get(cacheKey);
        if (optimizerMetrics == null) {
            return;
        }
        optimizerMetrics.getMetrics().put(metricName, (Number) gauge.getValue());
    }

    /**
     * Build optimizerCacheKey with current timestamp and name
     *
     * e.g. name format : {serviceName}.{commandName}
     */
    private OptimizerCacheKey getOptimizerCacheKey(Long time, String[] splits, int keyStartIndex, int length,
            OptimizerMetricType metricType) {
        StringBuilder sb = new StringBuilder();
        String delimiter = "";
        for (int i = keyStartIndex; i < length - 1; i++) {
            sb.append(delimiter);
            sb.append(splits[i]);
            delimiter = ".";
        }

        return new OptimizerCacheKey(time, sb.toString(), metricType);
    }

}
