package com.str.backend.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(schema = "str", name = "submission_type")
public class SubmissionTypeEntity {

    @Id
    @Column(name = "type_code", length = 32, nullable = false, updatable = false)
    private String typeId;

    @Column(name = "code", length = 16, nullable = false)
    private String codeLabel;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    protected SubmissionTypeEntity() {
    }

    public SubmissionTypeEntity(String typeId, String codeLabel, String name,
                                LocalDate validFrom, LocalDate validTo, String status) {
        this.typeId = typeId;
        this.codeLabel = codeLabel;
        this.name = name;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.status = status;
    }

    public String getTypeId() { return typeId; }
    public String getCodeLabel() { return codeLabel; }
    public String getName() { return name; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public String getStatus() { return status; }
}
