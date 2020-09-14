package io.phonepe.hystrixoptimizer.config.actions;

import io.phonepe.hystrixoptimizer.models.ActionType;
import io.phonepe.hystrixoptimizer.utils.ActionTypeVisitor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public abstract class ActionConfig {

    private ActionType actionType;

    public ActionConfig(final ActionType actionType) {
        this.actionType = actionType;
    }

    public abstract <T> T accept(ActionTypeVisitor<T> actionTypeVisitor);
}
