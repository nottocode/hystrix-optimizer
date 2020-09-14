package io.phonepe.hystrixoptimizer.models;

import lombok.Getter;

public enum ActionType {

    UPDATE_HYSTRIX_CONFIG(ActionType.UPDATE_HYSTRIX_CONFIG_VALUE),
    SEND_EMAIL_ALERT(ActionType.SEND_EMAIL_ALERT_VALUE);

    @Getter
    private final String value;

    ActionType(String value) {
        this.value = value;
    }

    public static final String UPDATE_HYSTRIX_CONFIG_VALUE = "UPDATE_HYSTRIX_CONFIG";
    public static final String SEND_EMAIL_ALERT_VALUE = "SEND_EMAIL_ALERT";
}