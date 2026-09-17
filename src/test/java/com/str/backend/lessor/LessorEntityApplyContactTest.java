package com.str.backend.lessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code applyContact} upisuje e-mail, a {@code email} je {@code updatable = false}. Na već
 * spremljenom entitetu JPA ga ne bi upisao, ali bi ga objekt u memoriji do kraja transakcije
 * prikazivao — pa bi PDF i obavijest pročitali vrijednost koje u bazi nema. Zato se takav poziv
 * odbija.
 */
class LessorEntityApplyContactTest {

    @Test
    void newEntity_acceptsContact() {
        LessorEntity lessor = LessorEntity.create("ANA", "ANIĆ", "Marulićeva", "5", "Split", "Splitsko-dalmatinska", null);

        lessor.applyContact("ana@example.com", "Ana Anić", "021555666", "0991234567");

        assertThat(lessor.getEmail()).isEqualTo("ana@example.com");
        assertThat(lessor.getMobileNumber()).isEqualTo("0991234567");
    }

    /** {@code markManaged} zove JPA na {@code @PostLoad} i {@code @PostPersist}. */
    @Test
    void managedEntity_rejectsContact_andKeepsOriginalEmail() {
        LessorEntity lessor = LessorEntity.create("ANA", "ANIĆ", "Marulićeva", "5", "Split", "Splitsko-dalmatinska",
                "racun@example.com");
        lessor.markManaged();

        assertThatThrownBy(() -> lessor.applyContact("drugi@example.com", null, null, "0991234567"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lessor.getEmail()).isEqualTo("racun@example.com");
    }

    /** Za spremljenog iznajmljivača ispravan je {@code setContact} — on e-mail ne dira. */
    @Test
    void managedEntity_setContactStillWorks() {
        LessorEntity lessor = LessorEntity.create("ANA", "ANIĆ", "Marulićeva", "5", "Split", "Splitsko-dalmatinska",
                "racun@example.com");
        lessor.markManaged();

        lessor.setContact("Ana Anić", "021555666", "0991234567", null);

        assertThat(lessor.getMobileNumber()).isEqualTo("0991234567");
        assertThat(lessor.getEmail()).isEqualTo("racun@example.com");
    }
}
