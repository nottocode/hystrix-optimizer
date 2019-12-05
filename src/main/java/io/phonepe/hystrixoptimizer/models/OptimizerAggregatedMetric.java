package io.phonepe.hystrixoptimizer.models;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/***
 Created by nitish.goyal on 30/03/19
 ***/
@Data
@Builder
@AllArgsConstructor
public class OptimizerAggregatedMetric {

    private Long sum;
    private Long count;
}
