package io.phonepe.hystrixoptimizer.utils;


import com.google.common.collect.MapDifference;
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

    public static String tableRowForEmail(final String key,
                                          final MapDifference.ValueDifference<Object> value) {
        return String.format("<tr style=\"border: 1px solid black;\">" +
                "<td style=\"border: 1px solid black; text-align: center\">%s </td>" +
                "<td style=\"border: 1px solid black; text-align: center\">%s </td>" +
                "<td style=\"border: 1px solid black; text-align: center\">%s </td>" +
                "</tr>", key, value.leftValue(), value.rightValue());
    }

    public static String emailBody(final String body) {
        final String template = "<html>"
                + "<body>"
                + "<p><b>Please find the differences in hystrix configuration.</b></p>"
                + "<table style=\"border: 1px solid black; border-collapse: collapse\">"
                + "<tr>"
                + "<th style=\"border: 1px solid black; padding: 4px;\"> Field Name </th>"
                + "<th style=\"border: 1px solid black; padding: 4px;\"> Current Value </th>"
                + "<th style=\"border: 1px solid black; padding: 4px;\"> Suggested Value </th>"
                + "%s"
                + "</table>"
                + "</body>"
                + "</html>";
        return String.format(template, body);
    }
}
