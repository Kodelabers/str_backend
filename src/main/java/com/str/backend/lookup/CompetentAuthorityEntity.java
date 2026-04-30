package com.str.backend.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "str_rn", name = "competent_authority")
public class CompetentAuthorityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "authority_id")
    private Long authorityId;

    @Column(name = "name", nullable = false)
    private String name;

    protected CompetentAuthorityEntity() {
    }

    public CompetentAuthorityEntity(String name) {
        this.name = name;
    }

    public Long getAuthorityId() { return authorityId; }
    public String getName() { return name; }
}
