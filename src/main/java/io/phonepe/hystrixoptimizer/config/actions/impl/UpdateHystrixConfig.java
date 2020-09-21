package io.phonepe.hystrixoptimizer.config.actions.impl;

import io.phonepe.hystrixoptimizer.config.actions.ActionConfig;
import io.phonepe.hystrixoptimizer.models.ActionType;
import io.phonepe.hystrixoptimizer.utils.ActionTypeVisitor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
public class UpdateHystrixConfig extends ActionConfig {

    public UpdateHystrixConfig() {
        super(ActionType.UPDATE_HYSTRIX_CONFIG);
    }

    @Override
    public <T> T accept(ActionTypeVisitor<T> actionTypeVisitor) {
        return actionTypeVisitor.visitUpdateHystrixConfig(this);
    }
}
