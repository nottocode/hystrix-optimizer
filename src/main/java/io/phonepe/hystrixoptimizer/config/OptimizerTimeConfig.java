package io.phonepe.hystrixoptimizer.config;

import io.phonepe.hystrixoptimizer.metrics.LatencyMetric;
import java.util.EnumSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/***
 Created by nitish.goyal on 05/04/19
 ***/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OptimizerTimeConfig {

    private boolean enabled;

    private Set<LatencyMetric> latencyMetrics = EnumSet.allOf(LatencyMetric.class);

    private LatencyMetric timeoutMetric;

    private double getMethodTimeoutBuffer;
    private double allMethodTimeoutBuffer;

    private LatencyMetric appLatencyMetric;
    private LatencyMetric apiLatencyMetric;
    private int appLatencyThresholdValue;


}
