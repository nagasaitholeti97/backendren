package com.sams.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Sends email over SMTP. If sams.mail.enabled is false the message is only written to the log. */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;

    public MailService(JavaMailSender sender,
                       @Value("${sams.mail.enabled:false}") boolean enabled,
                       @Value("${sams.mail.from:}") String from) {
        this.sender = sender;
        this.enabled = enabled;
        this.from = from;
    }

    public void send(String to, String subject, String body) {
        if (!enabled) {
            log.info("[mail disabled] To: {} | {} | {}", to, subject, body);
            return;
        }
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            if (!from.isBlank()) m.setFrom(from);
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            sender.send(m);
            log.info("Email sent to {}", to);
        } catch (Exception e) {
            // Never reveal mail problems to the caller (the forgot endpoint must answer the same way every time).
            log.error("Could not send email to {}: {}", to, e.getMessage());
        }
    }
}
