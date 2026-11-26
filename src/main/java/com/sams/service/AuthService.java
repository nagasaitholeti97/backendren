package com.sams.service;

import com.sams.dto.Dtos.*;
import com.sams.entity.*;
import com.sams.exception.ApiException;
import com.sams.repository.*;
import com.sams.security.JwtService;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String DEPARTMENT = "Electronics & Communication Engineering";

    private final UserAccountRepository accounts;
    private final StudentRepository students;
    private final FacultyRepository faculty;
    private final SectionRepository sections;
    private final SubjectRepository subjects;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final MailService mail;
    private final String frontendUrl;

    public AuthService(UserAccountRepository accounts, StudentRepository students, FacultyRepository faculty,
                       SectionRepository sections, SubjectRepository subjects, PasswordEncoder encoder, JwtService jwt,
                       MailService mail, @Value("${sams.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.accounts = accounts;
        this.students = students;
        this.faculty = faculty;
        this.sections = sections;
        this.subjects = subjects;
        this.encoder = encoder;
        this.jwt = jwt;
        this.mail = mail;
        this.frontendUrl = frontendUrl.replaceAll("/$", "");
    }

    /** Finds an account from an ID or an email address. IDs are not case sensitive. */
    private Optional<UserAccount> find(String identifier) {
        String q = identifier.trim();
        Optional<UserAccount> byId = accounts.findById(q);
        if (byId.isEmpty()) byId = accounts.findById(q.toUpperCase());
        if (byId.isPresent()) return byId;
        Optional<String> byStudentEmail = students.findByEmailIgnoreCase(q).map(Student::getId);
        if (byStudentEmail.isPresent()) return accounts.findById(byStudentEmail.get());
        return faculty.findByEmailIgnoreCase(q).map(Faculty::getId).flatMap(accounts::findById);
    }

    private String nameOf(UserAccount acc) {
        return acc.getRole() == Role.STUDENT
            ? students.findById(acc.getUserId()).map(Student::getName).orElse(acc.getUserId())
            : faculty.findById(acc.getUserId()).map(Faculty::getName).orElse(acc.getUserId());
    }

    private LoginResponse session(UserAccount acc) {
        return new LoginResponse(jwt.issue(acc.getUserId(), acc.getRole()), acc.getRole(), acc.getUserId(), nameOf(acc));
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        UserAccount acc = find(req.userId())
            .filter(a -> encoder.matches(req.password(), a.getPasswordHash()))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ID or password is incorrect."));
        return session(acc);
    }

    private void requireFreeEmail(String email) {
        if (students.existsByEmailIgnoreCase(email.trim()) || faculty.existsByEmailIgnoreCase(email.trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "That email is already registered.");
        }
    }

    /** Creates a student. The ID is the class prefix plus the next number, for example 24ECE3009. */
    public LoginResponse register(RegisterRequest r) {
        requireFreeEmail(r.email());
        Section section = sections.findById(r.sectionId()).orElseThrow(() -> ApiException.badRequest("Unknown class."));
        long n = students.countBySectionId(section.getId()) + 1;
        String id = section.getIdPrefix() + String.format("%03d", n);
        while (accounts.existsById(id)) id = section.getIdPrefix() + String.format("%03d", ++n);
        Student s = new Student();
        s.setId(id);
        s.setName(r.name().trim());
        s.setGender(r.gender());
        s.setPhone(r.phone());
        s.setEmail(r.email().trim());
        s.setFatherName("");
        s.setMotherName("");
        s.setAddress("");
        s.setCity("");
        s.setPincode("");
        s.setDepartment(section.getDepartment());
        s.setCourse(section.getCourse());
        s.setSection(section);
        students.save(s);
        UserAccount acc = accounts.save(new UserAccount(id, Role.STUDENT, encoder.encode(r.password())));
        return session(acc);
    }

    /** Creates a faculty member with the next ID (F106, F107, ...). */
    public LoginResponse registerFaculty(RegisterFacultyRequest r) {
        requireFreeEmail(r.email());
        Subject subject = subjects.findById(r.subjectId()).orElseThrow(() -> ApiException.badRequest("Unknown subject."));
        long n = 101 + faculty.count();
        while (accounts.existsById("F" + n)) n++;
        Faculty f = new Faculty();
        f.setId("F" + n);
        f.setName(r.name().trim());
        f.setGender(r.gender());
        f.setDesignation(r.designation() == null || r.designation().isBlank() ? "Assistant Professor" : r.designation());
        f.setQualification(r.qualification() == null ? "" : r.qualification().trim());
        f.setExperience(0);
        f.setDepartment(DEPARTMENT);
        f.setPhone(r.phone());
        f.setEmail(r.email().trim());
        f.setSubject(subject);
        faculty.save(f);
        UserAccount acc = accounts.save(new UserAccount(f.getId(), Role.FACULTY, encoder.encode(r.password())));
        return session(acc);
    }

    private record Otp(String hash, Instant expires, int[] tries) {}

    private final Map<String, Otp> otps = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private static String sha(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Always answers the same way so the endpoint cannot be used to find out which IDs exist. */
    @Transactional(readOnly = true)
    public Message forgot(ForgotRequest r) {
        find(r.identifier()).ifPresent(a -> {
            log.info("Password reset code requested for {}", a.getUserId());
            String email = emailOf(a);
            if (email == null || email.isBlank()) return;
            String code = String.format("%06d", random.nextInt(1_000_000));
            otps.put(a.getUserId(), new Otp(sha(a.getUserId() + ":" + code), Instant.now().plusSeconds(600), new int[] {0}));
            mail.send(email, "SAMS password reset code",
                "Hello,\n\nYour SAMS verification code is: " + code
                + "\n\nIt is valid for 10 minutes. If you did not ask for this, ignore this email; your password has not changed.");
        });
        return new Message("If an account matches, a 6-digit code has been sent to its email.");
    }

    private String emailOf(UserAccount a) {
        return students.findById(a.getUserId()).map(Student::getEmail)
            .or(() -> faculty.findById(a.getUserId()).map(Faculty::getEmail)).orElse(null);
    }

    @Transactional
    public Message reset(ResetRequest r) {
        String bad = "The code is wrong or has expired. Ask for a new code.";
        UserAccount acc = find(r.identifier()).orElseThrow(() -> ApiException.badRequest(bad));
        Otp o = otps.get(acc.getUserId());
        if (o == null || Instant.now().isAfter(o.expires()) || o.tries()[0] >= 5) {
            otps.remove(acc.getUserId());
            throw ApiException.badRequest(bad);
        }
        if (!MessageDigest.isEqual(o.hash().getBytes(StandardCharsets.UTF_8),
                sha(acc.getUserId() + ":" + r.otp().trim()).getBytes(StandardCharsets.UTF_8))) {
            o.tries()[0]++;
            throw ApiException.badRequest(bad);
        }
        otps.remove(acc.getUserId());
        acc.setPasswordHash(encoder.encode(r.newPassword()));
        accounts.save(acc);
        return new Message("Password changed. You can sign in now.");
    }

    @Transactional(readOnly = true)
    public Message verifyPassword(String userId, VerifyPasswordRequest r) {
        UserAccount acc = accounts.findById(userId).orElseThrow(() -> ApiException.notFound("Account"));
        if (!encoder.matches(r.password(), acc.getPasswordHash())) {
            throw ApiException.badRequest("Current password is incorrect.");
        }
        return new Message("OK");
    }

    public Message changePassword(String userId, ChangePasswordRequest r) {
        UserAccount acc = accounts.findById(userId).orElseThrow(() -> ApiException.notFound("Account"));
        if (!encoder.matches(r.currentPassword(), acc.getPasswordHash())) {
            throw ApiException.badRequest("Current password is incorrect.");
        }
        if (r.currentPassword().equals(r.newPassword())) {
            throw ApiException.badRequest("Choose a password different from your current one.");
        }
        acc.setPasswordHash(encoder.encode(r.newPassword()));
        return new Message("Password changed.");
    }
}
