package com.str.backend.address;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(schema = "eturizam_test", name = "ar_address")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class HouseNumberEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "broj", nullable = false, updatable = false)
    private String name;

    @Column(name = "ulica_id", nullable = false, updatable = false)
    private Long ulicaId;

    /**
     * Sirova vrijednost iz registra. Na pravoj bazi je to <b>složena</b> oznaka oblika
     * {@code <MB katastarske općine>|<broj čestice>}, npr. {@code 300071|666/3} — na CDU bazi
     * 15.09.2026. taj oblik ima 100 % nepraznih vrijednosti, a popunjena je u 71,3 % adresa.
     * Za prikaz koristiti {@link #getKcCestica()}.
     */
    @Column(name = "kc_broj", updatable = false)
    private String kcBroj;

    /**
     * Broj katastarske čestice bez prefiksa katastarske općine — v. {@link #cestica(String)}.
     *
     * <p>Bez {@code @Transient}: entitet koristi pristup preko polja ({@code @Id} je na polju), pa
     * Hibernate getter metode ionako ne mapira.
     */
    public String getKcCestica() {
        return cestica(kcBroj);
    }

    /**
     * Broj katastarske čestice iz sirove registarske oznake: sve iza {@code |}, ili cijela
     * vrijednost kad {@code |} nema.
     *
     * <p>Dijeljenje je u Javi, a ne u SQL-u, zato što lokalni mock (changeset 109) drži čestice
     * bez prefiksa: {@code split_part(kc_broj,'|',2)} bi ondje vratio prazan niz, dok ovdje
     * vrijednost bez {@code |} prolazi netaknuta.
     */
    public static String cestica(String kcBroj) {
        if (kcBroj == null) {
            return null;
        }
        int sep = kcBroj.indexOf('|');
        String cestica = sep < 0 ? kcBroj : kcBroj.substring(sep + 1);
        return cestica.isBlank() ? null : cestica.trim();
    }
}
