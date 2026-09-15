import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Jednokratna provjera CDU baze — sve u jednom prolazu, jer se do nje dolazi preko državnog VPN-a
 * koji gasi vezu prema ostatku mreže, pa je svako spajanje skupo.
 *
 * <p>Provjerava da svaki native upit koji je mijenjan 11.09.2026. radi nad CDU shemom, i mjeri
 * koliko su relevantni stupci popunjeni. <b>Samo SELECT</b> — konekcija je read-only, a upiti nad
 * osobnim podacima idu s nepostojećim OIB-om ili kao agregati, pa ništa osobno ne izlazi.
 *
 * <p>Pokretanje iz korijena repoa (PowerShell). Lozinka se cita iz varijable okruzenja, ne iz
 * argumenta — argument ostaje u povijesti ljuske i vidljiv je u popisu procesa:
 * <pre>
 * $env:CDU_DB_PASSWORD = (Get-Content "$env:USERPROFILE\.str-cdu-db" -Raw).Trim()
 * java -cp "$env:USERPROFILE\.m2\repository\org\postgresql\postgresql\42.7.8\postgresql-42.7.8.jar" `
 *   tools\CduProvjera.java 172.20.8.196 5432 eturizam shorttermrental
 * Remove-Item Env:CDU_DB_PASSWORD
 * </pre>
 *
 * <p>Ishod odlučuje smije li se na CDU upaliti {@code APP_CADASTRAL_ENABLED=true}.
 */
public class CduProvjera {

    static final String LISTING_FROM = """
             FROM (SELECT f.id AS fid,
                          row_number() OVER (
                              PARTITION BY coalesce(cast(f.system_uuid AS varchar),
                                                    cast(d.business_case_id AS varchar),
                                                    'facility-' || cast(f.id AS varchar))
                              ORDER BY f.id DESC) AS rnk
                     FROM str.facility f
                     LEFT JOIN str.document d       ON d.id  = f.document_id
                     JOIN str.subject_version sv    ON sv.id = f.subject_version_id
                     JOIN str.subject s             ON s.id  = sv.subject_id
                    WHERE s.jips = ?) r
             JOIN str.facility f ON f.id = r.fid
             LEFT JOIN str.facility_type ft
                    ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                                 WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
             LEFT JOIN str.codebook_element c_type ON c_type.id = ft.type_id
             LEFT JOIN str.codebook_element c_sub  ON c_sub.id  = ft.sub_type_id
             LEFT JOIN str.codebook_element c_cat  ON c_cat.id  = f.category_id
             LEFT JOIN str.codebook_element c_st   ON c_st.id   = f.business_status_id
             LEFT JOIN str.address a
                    ON a.id = CASE WHEN f.same_address_subject = true
                                   THEN (SELECT max(x.address_id) FROM str.subject_address x
                                          WHERE x.subject_version_id = f.subject_version_id
                                            AND coalesce(x.active, true) = true)
                                   ELSE f.address_id END
             LEFT JOIN str.county co        ON co.id  = a.county_id
             LEFT JOIN str.municipality mu  ON mu.id  = a.municipality_id
             LEFT JOIN str.settlement se    ON se.id  = a.settlement_id
             LEFT JOIN str.street stt       ON stt.id = a.street_id
             LEFT JOIN str.house_number hn  ON hn.id  = a.house_number_id
             WHERE r.rnk = 1
               AND f.active = true
               AND c_sub.code IN (?, ?, ?)
               AND NOT EXISTS (SELECT 1 FROM str.facility f2
                                WHERE f2.system_uuid = f.system_uuid AND f2.id > f.id)
            """;

    static final String OIB_NEPOSTOJECI = "00000000001";

    static int pao = 0;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.out.println("Uporaba: CduProvjera <host> <port> <baza> <user>");
            System.out.println("Lozinka se cita iz varijable okruzenja CDU_DB_PASSWORD, ne iz argumenta.");
            System.exit(2);
        }
        String lozinka = System.getenv("CDU_DB_PASSWORD");
        if (lozinka == null || lozinka.isBlank()) {
            System.out.println("Nije postavljena varijabla okruzenja CDU_DB_PASSWORD.");
            System.exit(2);
        }
        String url = "jdbc:postgresql://" + args[0] + ":" + args[1] + "/" + args[2];
        System.out.println("Spajam se na " + url);

        try (Connection c = DriverManager.getConnection(url, args[3], lozinka)) {
            c.setReadOnly(true);
            Statement st = c.createStatement();
            st.setQueryTimeout(90);

            naslov("1. Postoje li tablice i stupci na koje se oslanjamo");
            provjera("eturizam_test.ka_katastar_ko", () -> postojiTablica(st, "eturizam_test", "ka_katastar_ko"));
            provjera("ar_address.kat_opcina_id", () -> postojiStupac(st, "eturizam_test", "ar_address", "kat_opcina_id"));
            provjera("ar_address.kc_broj", () -> postojiStupac(st, "eturizam_test", "ar_address", "kc_broj"));
            provjera("str.facility.email", () -> postojiStupac(st, "str", "facility", "email"));
            provjera("str.facility.phone", () -> postojiStupac(st, "str", "facility", "phone"));
            provjera("str.facility.external_uid", () -> postojiStupac(st, "str", "facility", "external_uid"));
            provjera("str.address.postal_code", () -> postojiStupac(st, "str", "address", "postal_code"));
            provjera("str.settlement.postal_code", () -> postojiStupac(st, "str", "settlement", "postal_code"));

            naslov("2. Izmijenjeni native upiti se izvrsavaju");
            provjera("findListingByOib (+ email, phone)", () -> {
                String sql = "SELECT f.id, f.name, c_type.code, c_sub.code, c_sub.name, c_cat.name,"
                        + " c_st.name, f.registration_number, coalesce(co.name, a.county),"
                        + " coalesce(mu.name, a.municipality), coalesce(se.name, a.settlement),"
                        + " coalesce(stt.name, a.street), coalesce(hn.name, a.house_number),"
                        + " coalesce(a.postal_code, se.postal_code), a.full_address, f.email, f.phone"
                        + LISTING_FROM + " ORDER BY f.id LIMIT 1";
                try (PreparedStatement p = c.prepareStatement(sql)) {
                    p.setString(1, OIB_NEPOSTOJECI);
                    p.setString(2, "FS_SOBA");
                    p.setString(3, "FS_APARTMAN");
                    p.setString(4, "FS_KUCA_ZA_ODMOR");
                    try (ResultSet r = p.executeQuery()) {
                        return "izvrsen (redaka " + (r.next() ? 1 : 0) + ")";
                    }
                }
            });
            provjera("findOwnership (+ postal_code, email, phone)", () -> {
                String sql = "SELECT s.jips, c_sub.code, f.active, f.name, sv.name,"
                        + " coalesce(co.name, a.county), coalesce(mu.name, a.municipality),"
                        + " coalesce(se.name, a.settlement), coalesce(stt.name, a.street),"
                        + " coalesce(hn.name, a.house_number), coalesce(a.postal_code, se.postal_code),"
                        + " f.email, f.phone"
                        + " FROM str.facility f"
                        + " JOIN str.subject_version sv ON sv.id = f.subject_version_id"
                        + " JOIN str.subject s ON s.id = sv.subject_id"
                        + " LEFT JOIN str.facility_type ft ON ft.id = (SELECT max(x.id) FROM str.facility_type x"
                        + "   WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)"
                        + " LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id"
                        + " LEFT JOIN str.address a ON a.id = f.address_id"
                        + " LEFT JOIN str.county co ON co.id = a.county_id"
                        + " LEFT JOIN str.municipality mu ON mu.id = a.municipality_id"
                        + " LEFT JOIN str.settlement se ON se.id = a.settlement_id"
                        + " LEFT JOIN str.street stt ON stt.id = a.street_id"
                        + " LEFT JOIN str.house_number hn ON hn.id = a.house_number_id"
                        + " WHERE f.id = -1";
                try (ResultSet r = st.executeQuery(sql)) {
                    return "izvrsen (redaka " + (r.next() ? 1 : 0) + ")";
                }
            });
            provjera("findKatOpcinaByStreetId (novi join)", () -> {
                String sql = "SELECT ad.id, ko.naziv FROM eturizam_test.ar_address ad"
                        + " LEFT JOIN eturizam_test.ka_katastar_ko ko ON ko.id = ad.kat_opcina_id"
                        + " WHERE ad.ulica_id = (SELECT ulica_id FROM eturizam_test.ar_address"
                        + "                      WHERE kat_opcina_id IS NOT NULL LIMIT 1) LIMIT 3";
                try (ResultSet r = st.executeQuery(sql)) {
                    StringBuilder sb = new StringBuilder("izvrsen: ");
                    while (r.next()) sb.append(r.getString(1)).append("=").append(r.getString(2)).append("  ");
                    return sb.toString();
                }
            });

            naslov("3. Popunjenost (agregati, uzorak 100k)");
            pokrivenost(st, "eturizam_test.ar_address", "kat_opcina_id", null);
            pokrivenost(st, "eturizam_test.ar_address", "kc_broj", null);
            pokrivenost(st, "str.facility", "email", "active = true");
            pokrivenost(st, "str.facility", "phone", "active = true");
            pokrivenost(st, "str.facility", "external_uid", "active = true");
            pokrivenost(st, "str.settlement", "postal_code", null);

            naslov("4. Oblik kc_broj (ocekivano: <MB opcine>|<cestica>)");
            provjera("udio s '|'", () -> {
                try (ResultSet r = st.executeQuery(
                        "SELECT count(*), count(*) FILTER (WHERE kc_broj LIKE '%|%')"
                      + " FROM (SELECT kc_broj FROM eturizam_test.ar_address"
                      + "        WHERE kc_broj IS NOT NULL LIMIT 100000) t")) {
                    r.next();
                    return r.getLong(2) + " / " + r.getLong(1);
                }
            });

            naslov("5. Sanity: str_rn (nasa shema) postoji?");
            provjera("str_rn.registration_number", () -> postojiTablica(st, "str_rn", "registration_number"));
            provjera("str_rn.databasechangelog", () -> postojiTablica(st, "str_rn", "databasechangelog"));
        }

        System.out.println();
        System.out.println(pao == 0
                ? ">>> SVE PROLAZI. Smije se upaliti APP_CADASTRAL_ENABLED=true na CDU."
                : ">>> PADA " + pao + " provjera — NE paliti cadastral dok se ne rijesi.");
        System.exit(pao == 0 ? 0 : 1);
    }

    // ---- pomocno ----

    interface Provjera {
        String run() throws Exception;
    }

    static void naslov(String s) {
        System.out.println();
        System.out.println("=== " + s + " ===");
    }

    static void provjera(String naziv, Provjera p) {
        try {
            System.out.printf("  [OK]   %-42s %s%n", naziv, p.run());
        } catch (Exception e) {
            pao++;
            System.out.printf("  [PADA] %-42s %s%n", naziv, e.getMessage());
        }
    }

    static String postojiTablica(Statement st, String shema, String tablica) throws SQLException {
        try (ResultSet r = st.executeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='" + shema
              + "' AND table_name='" + tablica + "'")) {
            r.next();
            if (r.getLong(1) == 0) throw new SQLException("NE POSTOJI");
            return "postoji";
        }
    }

    static String postojiStupac(Statement st, String shema, String tablica, String stupac) throws SQLException {
        try (ResultSet r = st.executeQuery(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema='" + shema
              + "' AND table_name='" + tablica + "' AND column_name='" + stupac + "'")) {
            r.next();
            if (r.getLong(1) == 0) throw new SQLException("NE POSTOJI");
            return "postoji";
        }
    }

    static void pokrivenost(Statement st, String tablica, String stupac, String uvjet) {
        String naziv = tablica + "." + stupac;
        try {
            String where = uvjet == null ? "" : " WHERE " + uvjet;
            try (ResultSet r = st.executeQuery(
                    "SELECT count(*), count(nullif(btrim(cast(" + stupac + " AS varchar)), ''))"
                  + " FROM (SELECT " + stupac + " FROM " + tablica + where + " LIMIT 100000) t")) {
                r.next();
                long uk = r.getLong(1), n = r.getLong(2);
                System.out.printf("  %-42s %7d / %-7d (%.1f%%)%n",
                        naziv, n, uk, uk == 0 ? 0.0 : 100.0 * n / uk);
            }
        } catch (Exception e) {
            pao++;
            System.out.printf("  [PADA] %-42s %s%n", naziv, e.getMessage());
        }
    }
}
