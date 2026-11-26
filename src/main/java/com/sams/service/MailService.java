package com.sams.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends email. Order of choice:
 * 1) BREVO_API_KEY set  -> Brevo HTTPS API (works on Railway/Render, where SMTP ports are blocked)
 * 2) MAIL_ENABLED=true  -> SMTP (for example Gmail on your own PC)
 * 3) otherwise          -> the message is only written to the log.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;
    private final String brevoKey;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public MailService(JavaMailSender sender,
                       @Value("${sams.mail.enabled:false}") boolean enabled,
                       @Value("${sams.mail.from:}") String from,
                       @Value("${sams.mail.brevo-api-key:}") String brevoKey) {
        this.sender = sender;
        this.enabled = enabled;
        this.from = from;
        this.brevoKey = brevoKey == null ? "" : brevoKey.trim();
    }

    public void send(String to, String subject, String body) {
        try {
            if (!brevoKey.isBlank()) {
                sendBrevo(to, subject, body);
            } else if (enabled) {
                SimpleMailMessage m = new SimpleMailMessage();
                if (!from.isBlank()) m.setFrom(from);
                m.setTo(to);
                m.setSubject(subject);
                m.setText(body);
                sender.send(m);
                log.info("Email sent to {} (SMTP)", to);
            } else {
                log.info("[mail disabled] To: {} | {} | {}", to, subject, body);
            }
        } catch (Exception e) {
            // Never reveal mail problems to the caller (the forgot endpoint must answer the same way every time).
            log.error("Could not send email to {}: {}", to, e.getMessage());
        }
    }

    private void sendBrevo(String to, String subject, String body) throws Exception {
        if (from.isBlank()) throw new IllegalStateException("MAIL_FROM (your verified Brevo sender email) is not set");
        String payload = json.writeValueAsString(Map.of(
            "sender", Map.of("name", "SAMS", "email", from),
            "to", List.of(Map.of("email", to)),
            "subject", subject,
            "textContent", body));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
            .timeout(Duration.ofSeconds(10))
            .header("api-key", brevoKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 == 2) log.info("Email sent to {} (Brevo)", to);
        else log.error("Brevo refused the email ({}): {}", res.statusCode(), res.body());
    }
}
