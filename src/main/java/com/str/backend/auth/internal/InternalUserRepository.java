package com.str.backend.auth.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

    /**
     * Interna organizacija korisnika i županije koje pokriva nadležnost njezinih organizacijskih
     * jedinica: {@code organizational_unit → settlement_jurisdiction → settlement → municipality →
     * county}. Gleda se nadležnost cijele organizacije, ne samo korisnikove jedinice — jedinica
     * može biti bez upisane nadležnosti, a organizacija županije pokriva točno svoju županiju.
     */
    private static final String ORGANIZATION_BY_OIB = """
            SELECT io.name AS organization,
                   ce.code AS org_type,
                   count(DISTINCT c.id) AS counties,
                   min(c.external_code) AS county_code,
                   min(c.name) AS county_name
              FROM str.application_user u
              JOIN str.internal_organization io ON io.id = u.internal_organization_id AND io.active = TRUE
              LEFT JOIN str.codebook_element ce ON ce.id = io.type_id
              LEFT JOIN str.organizational_unit ou ON ou.internal_organization_id = io.id AND ou.active = TRUE
              LEFT JOIN str.settlement_jurisdiction sj ON sj.organizational_unit_id = ou.id AND sj.active = TRUE
              LEFT JOIN str.settlement s ON s.id = sj.settlement_id
              LEFT JOIN str.municipality m ON m.id = s.municipality_id
              LEFT JOIN str.county c ON c.id = m.county_id
             WHERE u.username = ?
               AND u.internal = TRUE
               AND u.active = TRUE
             GROUP BY io.id, io.name, ce.code
             ORDER BY io.id
            """;

    /**
     * Interna organizacija korisnika i nadležnost po županijama.
     *
     * @param orgType    šifra vrste organizacije ({@code IOT_MINTS}, {@code IOT_ZUPANIJA}, {@code IOT_OSTALO})
     * @param counties   broj različitih županija u nadležnosti organizacije
     * @param countyCode {@code str.county.external_code} (= {@code rpj_dgu.zupanije.zu_rb}) kad je
     *                   županija jedna; inače jedna od njih, bez značenja
     */
    public record Organization(String name, String orgType, long counties, String countyCode, String countyName) {}

    private final JdbcTemplate jdbc;

    public InternalUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Organizacije aktivnog internog korisnika, po jedna po organizaciji; prazno kad je nema (ili
     * nije aktivna). Više redaka dolazi samo kad isti OIB ima više aktivnih internih zapisa s
     * različitim organizacijama — o tome odlučuje pozivatelj ({@link OfficialCountyResolver}).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<Organization> findOrganizations(String oib) {
        return jdbc.query(ORGANIZATION_BY_OIB, (rs, row) -> new Organization(
                rs.getString("organization"),
                rs.getString("org_type"),
                rs.getLong("counties"),
                rs.getString("county_code"),
                rs.getString("county_name")), oib);
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
