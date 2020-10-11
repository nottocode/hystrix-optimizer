package io.phonepe.hystrixoptimizer.config.actions.impl;

import io.phonepe.hystrixoptimizer.config.actions.ActionConfig;
import io.phonepe.hystrixoptimizer.models.ActionType;
import io.phonepe.hystrixoptimizer.utils.ActionTypeVisitor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import org.hibernate.validator.constraints.NotEmpty;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class EmailConfig extends ActionConfig {

    @NonNull
    private String host;

    @NonNull
    private Integer port;

    @NonNull
    private String from;

    @NotEmpty
    private List<String> receivers;

    public EmailConfig() {
        super(ActionType.SEND_EMAIL_ALERT);
    }

    @Builder
    public EmailConfig(final String host,
                       final Integer port,
                       final String from,
                       final List<String> receivers) {
        super(ActionType.SEND_EMAIL_ALERT);
        this.host = host;
        this.port = port;
        this.from = from;
        this.receivers = receivers;
    }

    @Override
    public <T> T accept(ActionTypeVisitor<T> actionTypeVisitor) {
        return actionTypeVisitor.visitSendEmailAlert(this);
    }
}
