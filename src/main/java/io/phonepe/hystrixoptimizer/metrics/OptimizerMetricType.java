package io.phonepe.hystrixoptimizer.metrics;

import lombok.Getter;

@Getter
public enum OptimizerMetricType {
    LATENCY("latency", 4, AggregationAlgo.AVG),
    THREAD_POOL("threadPool", 3, AggregationAlgo.MAX);


    //
    /**
     * Minimum number of tokens for hystrix metrics
     *
     * LATENCY metrics pattern : {serviceName}.{serviceName}.{commandName}.latencyExecute_percentile_{percentileType}
     * so minValidLength = 4
     *
     * THREADPOOL metrics pattern : HystrixThreadPool.{serviceName}.{commandName}.propertyValue_maximumSize
     *                              HystrixThreadPool.{threadpoolGroupName}.rollingMaxActiveThreads
     *                              HystrixThreadPool.{serviceName}.{commandName}.rollingMaxActiveThreads
     * so minValidLength = 3
     */
    int minValidLength;

    String metricType;

    AggregationAlgo aggregationAlgo;

    OptimizerMetricType(String metricType, int minValidLength,
            AggregationAlgo aggregationAlgo) {
        this.metricType = metricType;
        this.minValidLength = minValidLength;
        this.aggregationAlgo = aggregationAlgo;
    }
}
