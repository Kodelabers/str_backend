package com.str.backend.auth.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Role internih korisnika iz sheme {@code str}, koju vodi eTurizam — samo čitanje.
 *
 * <p>OIB internog korisnika stoji u {@code application_user.username}. Uspoređuje se točnom
 * jednakošću: {@code LIKE} ili normalizacija u SQL-u mogli bi pogoditi tuđi zapis. Rola vrijedi
 * samo za aktivnog korisnika s {@code internal = true}.
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

    public Set<String> findRoles(String oib) {
        return Set.copyOf(jdbc.queryForList(ROLES_BY_OIB, String.class, oib));
    }

    /** Ime i prezime internih korisnika po OIB-u — za prikaz aktera; nepoznat OIB izostaje. */
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
