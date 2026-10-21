package com.str.backend.auth.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Role internih korisnika iz sheme {@code str}, koju vodi eTurizam — samo čitanje.
 *
 * <p>OIB internog korisnika stoji u {@code application_user.username}. Uspoređuje se točnom
 * jednakošću: {@code LIKE} ili normalizacija u SQL-u mogli bi pogoditi tuđi zapis. Rola vrijedi
 * samo za aktivnog korisnika s {@code internal = true}.
 *
 * <p>Upiti idu <b>izvan</b> okolne transakcije ({@link Propagation#NOT_SUPPORTED}). Pozivaju se
 * i iz transakcijskih servisa ({@code /api/auth/me}, popis rješenja), a neuspio upit (npr.
 * {@code permission denied} na shemi eTurizma) u PostgreSQL-u prekida cijelu transakciju —
 * pozivatelj grešku hvata i nastavlja, ali bi commit tada pao („database returned ROLLBACK")
 * i srušio zahtjev koji sa službenicima nema veze.
 */
@Repository
public class InternalUserRepository {

    private static final String ROLES_BY_OIB = """
            SELECT ur.roles_sys_name
              FROM str.application_user u
              JOIN str.application_user_roles ur ON ur.users_id = u.id
             WHERE u.username = ?
               AND u.internal = TRUE
               AND u.active = TRUE
            """;

    private final JdbcTemplate jdbc;

    public InternalUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Set<String> findRoles(String oib) {
        return Set.copyOf(jdbc.queryForList(ROLES_BY_OIB, String.class, oib));
    }

    /**
     * eGOP korisničko ime internog korisnika ({@code external_username}, bez domene) — akt koji je
     * službenik napravio u STR-u urudžbira se pod njegovim eGOP korisnikom. Prazno ako korisnik
     * nije aktivan interni korisnik ili nema upisano ime.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<String> findExternalUsername(String oib) {
        return jdbc.queryForList("""
                        SELECT u.external_username
                          FROM str.application_user u
                         WHERE u.username = ?
                           AND u.internal = TRUE
                           AND u.active = TRUE
                        """, String.class, oib).stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::strip)
                .findFirst();
    }

    /** Ime i prezime internih korisnika po OIB-u — za prikaz aktera; nepoznat OIB izostaje. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, String> findNames(Collection<String> oibs) {
        Map<String, String> names = new HashMap<>();
        if (oibs.isEmpty()) {
            return names;
        }
        String placeholders = String.join(", ", Collections.nCopies(oibs.size(), "?"));
        jdbc.query("""
                SELECT u.username, u.first_name, u.last_name
                  FROM str.application_user u
                 WHERE u.internal = TRUE
                   AND u.username IN (%s)
                """.formatted(placeholders),
                rs -> {
                    String name = (nonNull(rs.getString("first_name")) + " "
                            + nonNull(rs.getString("last_name"))).trim();
                    if (!name.isEmpty()) {
                        names.put(rs.getString("username"), name);
                    }
                },
                oibs.toArray());
        return names;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
