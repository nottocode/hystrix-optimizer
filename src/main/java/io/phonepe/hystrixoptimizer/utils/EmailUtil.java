package io.phonepe.hystrixoptimizer.utils;


import io.phonepe.hystrixoptimizer.config.actions.Actions;
import io.phonepe.hystrixoptimizer.config.actions.impl.EmailConfig;
import io.phonepe.hystrixoptimizer.models.ActionType;

import java.util.List;

public class EmailUtil {

    public static String emailAddresses(final List<String> emailIds) {
        return String.join(",", emailIds);
    }

    public static EmailConfig getEmailConfig(final Actions actions) {
        return (EmailConfig) actions.getActionConfigs()
                .stream()
                .filter(actionConfig -> actionConfig.getActionType() == ActionType.SEND_EMAIL_ALERT)
                .findFirst()
                .orElse(null);
    }
}
