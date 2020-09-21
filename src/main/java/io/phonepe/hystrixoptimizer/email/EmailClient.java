package io.phonepe.hystrixoptimizer.email;

import io.phonepe.hystrixoptimizer.config.actions.impl.EmailConfig;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Slf4j
@Singleton
public class EmailClient {

    private final Session mailSession;
    private final EmailConfig mailConfig;

    public EmailClient(final EmailConfig emailConfig) {
        this.mailConfig = emailConfig;

        Properties mailProps = new Properties();
        mailProps.put("mail.transport.protocol", "smtp");
        mailProps.put("mail.smtp.host", mailConfig.getHost());
        mailProps.put("mail.smtp.port", mailConfig.getPort());
        mailProps.put("mail.smtp.auth", false);
        mailProps.put("mail.smtp.startttls.enable", false);
        mailProps.put("mail.smtp.timeout", 10000);
        mailProps.put("mail.smtp.connectiontimeout", 10000);
        this.mailSession = Session.getDefaultInstance(mailProps);
    }

    public void sendEmail(String emailIds, String subject, String messageBody) {
        try {
            MimeMessage message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(mailConfig.getFrom()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailIds));
            message.setSubject(subject);
            InternetHeaders headers = new InternetHeaders();
            headers.addHeader("Content-type", "text/html; charset=UTF-8");
            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(new MimeBodyPart(headers, messageBody.getBytes(StandardCharsets.UTF_8)));
            message.setContent(multipart);
            Transport.send(message);
        } catch (Exception e) {
            log.error("Error while sending email alert.", e);
        }
    }
}
