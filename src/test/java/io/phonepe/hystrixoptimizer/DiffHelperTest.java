package io.phonepe.hystrixoptimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.hystrix.configurator.config.CommandThreadPoolConfig;
import com.hystrix.configurator.config.HystrixCommandConfig;
import com.hystrix.configurator.config.HystrixConfig;
import com.hystrix.configurator.config.ThreadPoolConfig;
import io.phonepe.hystrixoptimizer.utils.DiffHelper;
import org.junit.Test;

import java.util.Collections;

public class DiffHelperTest {

    @Test
    public void testEmailConfig() {
        ObjectMapper mapper = new ObjectMapper();
        HystrixConfig base = HystrixConfig.builder()
                .pools(ImmutableMap.of("a", ThreadPoolConfig.builder()
                        .concurrency(10)
                        .dynamicRequestQueueSize(12)
                        .build()))
                .commands(Collections.singletonList(HystrixCommandConfig.builder()
                        .threadPool(CommandThreadPoolConfig.builder()
                                .concurrency(12)
                                .timeout(0)
                                .build())
                        .build()))
                .build();

        HystrixConfig current = HystrixConfig.builder()
                .pools(ImmutableMap.of("a", ThreadPoolConfig.builder()
                        .concurrency(5)
                        .dynamicRequestQueueSize(1)
                        .build(), "b", ThreadPoolConfig.builder()
                        .concurrency(100)
                        .build()))
                .commands(Collections.singletonList(HystrixCommandConfig.builder()
                        .threadPool(CommandThreadPoolConfig.builder()
                                .concurrency(12)
                                .timeout(10)
                                .build())
                        .build()))
                .build();

        DiffHelper<HystrixConfig> diffHelper = new DiffHelper<>(mapper);
        System.out.println(diffHelper.getObjectDiff(base, current));
    }
}
