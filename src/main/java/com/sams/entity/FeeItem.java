package com.sams.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A fee that students must pay, for one class (sectionId) or for everyone (sectionId is null). Amount is in rupees. */
@Entity
@Getter @Setter @NoArgsConstructor
public class FeeItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private long amount;
    private LocalDate dueDate;
    private String sectionId;
    private String createdBy;
    private Instant createdAt = Instant.now();
}
