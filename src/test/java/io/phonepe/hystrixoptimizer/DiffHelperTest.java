package io.phonepe.hystrixoptimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.hystrix.configurator.config.CommandThreadPoolConfig;
import com.hystrix.configurator.config.HystrixCommandConfig;
import com.hystrix.configurator.config.HystrixConfig;
import com.hystrix.configurator.config.ThreadPoolConfig;
import io.phonepe.hystrixoptimizer.config.actions.ActionConfig;
import io.phonepe.hystrixoptimizer.config.actions.impl.EmailConfig;
import io.phonepe.hystrixoptimizer.utils.DiffHelper;
import io.phonepe.hystrixoptimizer.utils.EmailUtil;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    @Test
    public void testConfig() throws IOException {
        String a = "{" +
                "                \"actionType\": \"SEND_EMAIL_ALERT\"," +
                "                \"host\": \"smtp.phonepe.com\"," +
                "                \"port\": 25," +
                "                \"from\": \"noreply@phonepe.com\"," +
                "                \"receivers\": [" +
                "                        \"deepak.kumarsingh@phonepe.com\"," +
                "                        \"kanika.khetawat@phonepe.com\"" +
                "                    ]" +
                "            }";
        EmailConfig emailConfig = (EmailConfig) new ObjectMapper().readValue(a, ActionConfig.class);
        System.out.println(emailConfig);
    }

    @Test
    public void testEmailIds() {
        List<String> emailIds = Arrays.asList("a@gmail.com", "b.@gmail.com");
        System.out.println(EmailUtil.emailAddresses(emailIds));
    }
}
