package io.phonepe.hystrixoptimizer.utils;

import io.phonepe.hystrixoptimizer.config.actions.impl.EmailConfig;
import io.phonepe.hystrixoptimizer.config.actions.impl.UpdateHystrixConfig;

public interface ActionTypeVisitor<T> {

    T visitUpdateHystrixConfig(UpdateHystrixConfig updateHystrixConfig);

    T visitSendEmailAlert(EmailConfig emailConfig);
}
