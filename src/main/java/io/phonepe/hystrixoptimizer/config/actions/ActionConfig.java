package io.phonepe.hystrixoptimizer.config.actions;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.phonepe.hystrixoptimizer.config.actions.impl.EmailConfig;
import io.phonepe.hystrixoptimizer.config.actions.impl.UpdateHystrixConfig;
import io.phonepe.hystrixoptimizer.models.ActionType;
import io.phonepe.hystrixoptimizer.utils.ActionTypeVisitor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "actionType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UpdateHystrixConfig.class, name = "UPDATE_HYSTRIX_CONFIG"),
        @JsonSubTypes.Type(value = EmailConfig.class, name = "SEND_EMAIL_ALERT")
})
public abstract class ActionConfig {

    private ActionType actionType;

    public ActionConfig(final ActionType actionType) {
        this.actionType = actionType;
    }

    public abstract <T> T accept(ActionTypeVisitor<T> actionTypeVisitor);
}
