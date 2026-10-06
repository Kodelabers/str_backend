package com.str.backend.str;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface StrFacilityRepository extends JpaRepository<StrFacilityEntity, Long> {

    long countByActiveTrue();

    /**
     * Šifra poslovnog statusa ({@code facility.business_status_id → codebook_element}) objekta koji
     * posluje. Drugi status u registru je {@code FBS_INACTIVE} („Odjavljen"). Isti literal stoji u
     * {@link #PRIKAZ_ZA_OIB} — native query ga ne može referencirati.
     */
    String ACTIVE_BUSINESS_STATUS = "FBS_ACTIVE";

    /*
     * ---------------------------------------------------------------------------------------------
     * Koje su smještajne jedinice „aktualne" — pravila eTurizma
     *
     * Model (provjeren na CDU testu 6. 10. 2026., docs/sql/m1-dijagnostika-*.sql):
     *   - zapis str.facility je SMJEŠTAJNA JEDINICA (soba, apartman…), a system_uuid je OBJEKT.
     *     Jedan objekt ima više jedinica u istom predmetu (do 217 na CDU); registracijski broj
     *     ide po jedinici. Zato se ne deduplicira po system_uuid.
     *   - uvjeti su prepisani 1:1 iz f_active CTE-a eTurizmova viewa str.vw_src_facility_actual
     *     (DDL od Simona, eTurizam, 4. 10. 2026.). Sam view se ne čita: agregira cijeli registar
     *     (1,2 s po pozivu bez obzira na OIB) i nema OIB. Vjernost kopije provjerena je na CDU:
     *     1.122 verificirana zapisa, 0 razlika u oba smjera.
     *
     * VERIFICIRAN = created_by <> 'optimit' i svi uvjeti viewa, uključujući „predmet gotov"
     *   (status predmeta BCST_RJES_IZVRSNO i execution_date u prošlosti).
     * NEVERIFICIRAN = created_by = 'optimit' (migracija iz starog sustava, siječanj 2023.) i isti
     *   uvjeti BEZ „predmet gotov": migrirani predmeti nemaju ni status ni datum izvršnosti
     *   (237.140 od 237.140 na CDU), pa bi ih doslovna inverzija viewa sve izbacila.
     *   ČEKA POTVRDU eTurizma (Simon) — ako kaže drukčije, mijenja se samo zadnji uvjet u
     *   RANGIRANE_JEDINICE_DO.
     *   Migrirani predmet je u business_case_verification uvijek izvor (unverified), pa ga uvjeti
     *   verifikacije iz viewa skrivaju tek kad je verifikacija završena.
     * created_by IS NULL ne ulazi ni u jedan skup (kao u viewu: <> i = daju NULL).
     *
     * NAJNOVIJI PREDMET: objekt može biti aktualan u više predmeta (rješenje pa promjena podataka;
     *   migracija je isti objekt upisala u više predmeta, a verifikacija gasi samo jedan). Vrijede
     *   samo jedinice predmeta s najkasnijim facility.created_date (kod jednakosti veći
     *   business_case.id; predmet bez datuma je najstariji — NULLS LAST, jer Postgres kod DESC
     *   NULL inače stavlja prvi). Računa se PRIJE filtara vlasnika, statusa i vrste: noviji odjavljeni ili
     *   na drugog vlasnika preneseni predmet skriva stariji. Na CDU nijedan verificirani zapis nije
     *   skriven iza migriranog.
     *
     * VLASNIK = business_case.subject_version_id → subject.jips, kao u viewu. Preko
     *   facility.subject_version_id 43 od 1.129 aktualnih zapisa na CDU nemaju vlasnika.
     *
     * OBLIK UPITA: ugniježđeni podupiti, bez WITH. „Najnoviji predmet" je prozorska funkcija nad
     *   jednim skupom — samospajanje CTE-ova bez statistike planer je slagao ugniježđenom petljom
     *   (55,9 s na CDU). WITH se ne koristi i zato što H2 (testovi) krivo izvršava parametar u CTE-u
     *   na koji se nastavlja drugi CTE (vrati prazno). Brzina na CDU (PostgreSQL 13), oblik s
     *   CTE-ovima i istim prozorskim funkcijama: 3.741 jedinica 75 ms, 215 jedinica 25 ms, i s
     *   generičkim planom; ovaj oblik ponovo izmjeriti skriptom docs/sql/m1-dijagnostika-9.sql.
     * ---------------------------------------------------------------------------------------------
     */

    /**
     * Početak podupita aktualnih jedinica s rangom predmeta unutar objekta (1 = najnoviji).
     * Pozivatelj nastavlja podupitom koji daje stupac {@code system_uuid} (objekti koje treba
     * razmotriti), pa {@link #RANGIRANE_JEDINICE_DO} i alias.
     *
     * <p>Stupci: {@code id, su, verificiran, created_date, bc_id, bc_sv, predmet_zadnji,
     * predmet_rang, predmet_jedinica}. Svi zapisi objekta ulaze u rang (i tuđi), pa noviji predmet
     * drugog vlasnika skriva stariji. {@code predmet_jedinica} je broj aktualnih jedinica objekta u
     * istom predmetu (v. {@link FacilityListingRow#getObjectLevelCapacity()}).
     */
    String RANGIRANE_JEDINICE_OD = """
            (SELECT a.*,
                    dense_rank() OVER (PARTITION BY a.su
                                       ORDER BY a.predmet_zadnji DESC NULLS LAST, a.bc_id DESC) AS predmet_rang,
                    count(*) OVER (PARTITION BY a.su, a.bc_id) AS predmet_jedinica
               FROM (SELECT x.*,
                            max(x.created_date) OVER (PARTITION BY x.su, x.bc_id) AS predmet_zadnji
                       FROM (SELECT id,
                                    max(su)               AS su,
                                    bool_and(verificiran) AS verificiran,
                                    max(created_date)     AS created_date,
                                    max(bc_id)            AS bc_id,
                                    max(bc_sv)            AS bc_sv
                               FROM (SELECT facility.id,
                                            cast(facility.system_uuid AS varchar(64)) AS su,
                                            facility.created_date,
                                            facility.created_by <> 'optimit'          AS verificiran,
                                            business_case.id                           AS bc_id,
                                            business_case.subject_version_id           AS bc_sv
                                       FROM """;

    /** Kraj podupita iz {@link #RANGIRANE_JEDINICE_OD} — uvjeti eTurizmova viewa. */
    String RANGIRANE_JEDINICE_DO = """
             o
                                       JOIN str.facility facility
                                         ON facility.system_uuid = o.system_uuid
                                       JOIN str.document document
                                         ON facility.document_id = document.id AND document.active = true
                                       JOIN str.sif_podvrsta_dokumenta document_subtype
                                         ON document_subtype.code = document.subtype_code
                                       JOIN str.sif_vrsta_dokumenata document_type
                                         ON document_type.code = document_subtype.vrsta_dokumenata_code
                                        AND document_type.code IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU')
                                       JOIN str.business_case business_case
                                         ON document.business_case_id = business_case.id
                                        AND business_case.active = true
                                       LEFT JOIN str.business_case_verification verification_source
                                         ON business_case.id = verification_source.unverified_business_case_id
                                       LEFT JOIN str.codebook_element verification_source_status
                                         ON verification_source.status_id = verification_source_status.id
                                       LEFT JOIN str.business_case_verification verification_target
                                         ON business_case.id = verification_target.verified_business_case_id
                                       LEFT JOIN str.codebook_element verification_target_status
                                         ON verification_target.status_id = verification_target_status.id
                                       LEFT JOIN str.codebook_element bc_status_ce
                                         ON bc_status_ce.id = business_case.status_type_id
                                      WHERE facility.active = true
                                        AND facility.created_by IS NOT NULL
                                        AND (facility.historical IS NULL OR facility.historical = false)
                                        AND (verification_source.id IS NULL
                                             OR verification_source_status.code = 'BCVS_U_IZRADI')
                                        AND (verification_target.id IS NULL
                                             OR verification_target_status.code = 'BCVS_ZAVRSENA')
                                        AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                                                     WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
                                        AND (facility.created_by = 'optimit'
                                             OR (bc_status_ce.code = 'BCST_RJES_IZVRSNO'
                                                 AND document.execution_date IS NOT NULL
                                                 AND document.execution_date < now()))
                                    ) redovi
                              GROUP BY id
                             HAVING count(su) = 1) x) a)
            """;

    /**
     * Jedinice koje iznajmljivač {@code :oib} vidi na popisu: aktualne jedinice najnovijeg predmeta
     * svojih objekata, kojima je predmet njegov, koje posluju ({@code FBS_ACTIVE}, W-5) i koje su
     * privatni smještaj ({@code :codes}). Stupci: {@code id, su, verificiran, bc_sv, predmet_jedinica}.
     */
    String PRIKAZ_ZA_OIB = """
            SELECT r.id, r.su, r.verificiran, r.bc_sv, r.predmet_jedinica
              FROM """ + RANGIRANE_JEDINICE_OD + """
                   (SELECT DISTINCT f.system_uuid
                      FROM str.subject s
                      JOIN str.subject_version sv ON sv.subject_id = s.id
                      JOIN str.business_case bc   ON bc.subject_version_id = sv.id
                      JOIN str.document d         ON d.business_case_id = bc.id
                      JOIN str.facility f         ON f.document_id = d.id
                     WHERE s.jips = :oib
                       AND f.system_uuid IS NOT NULL)""" + RANGIRANE_JEDINICE_DO + """
               r
              JOIN str.facility f ON f.id = r.id
              LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
              LEFT JOIN str.facility_type ft
                     ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                                  WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
              LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
             WHERE r.predmet_rang = 1
               AND r.bc_sv IN (SELECT sv.id
                                 FROM str.subject s
                                 JOIN str.subject_version sv ON sv.subject_id = s.id
                                WHERE s.jips = :oib)
               AND c_st.code = 'FBS_ACTIVE'
               AND c_sub.code IN (:codes)
            """;

    /*
     * ---------------------------------------------------------------------------------------------
     * Kapacitet jedinice — jedno pravilo za popis i za claim (B-3, docs/ETURIZAM-OBJEKTI.md)
     *
     * Provjereno na CDU testu i CDU preprodu 6. 10. 2026. (docs/sql/b3-dijagnostika-*.sql) i
     * usporedbom s TuRegistrom (W-8 „4 + 2”, „Vila Lucija” 30 kreveta, „KZO pristojba” 2 kreveta):
     *   - kreveti su ILI u facility_capacity (sobe, studio apartmani) ILI u smještajnim sadržajima
     *     (apartmani, kuće za odmor: facility_content → facility_content_capacity), nikad u oba;
     *   - sadržaj: facility_content.quantity je „broj jednakih smještajnih sadržaja", a kapacitet
     *     sadržaja broj kreveta jednog sadržaja — ukupno je umnožak (TuRegistar: 3 × 10 = 30);
     *   - pomoćni kreveti su uvijek u facility_capacity;
     *   - samo active = true: neaktivni retci su stare verzije koje eTurizam ostavi kod svake
     *     izmjene (jedinica 243335: 11 neaktivnih + 1 aktivan redak pomoćnih kreveta).
     * facility_unit_capacity ostaje zadnja rezerva (hoteli i sl.; na popisu privatnog smještaja
     * nema nijednog retka).
     * ---------------------------------------------------------------------------------------------
     */

    /** Broj kreveta jedinice {@code f}; {@code NULL} kad ga eTurizam ne zna. */
    String KREVETI_JEDINICE = """
            coalesce(
                (SELECT sum(fc.quantity) FROM str.facility_capacity fc
                   JOIN str.codebook_element ce ON ce.id = fc.type_id
                  WHERE fc.facility_id = f.id AND fc.active = true
                    AND ce.code = 'CAT_BROJ_KREVETA'),
                (SELECT sum(fcc.quantity * c.quantity) FROM str.facility_content c
                   JOIN str.facility_content_capacity fcc
                     ON fcc.facility_content_id = c.id AND fcc.active = true
                   JOIN str.codebook_element ce ON ce.id = fcc.type_id
                  WHERE c.facility_id = f.id AND c.active = true
                    AND ce.code = 'CAT_BROJ_KREVETA'),
                (SELECT sum(fuc.quantity) FROM str.facility_unit fu
                   JOIN str.facility_unit_capacity fuc
                     ON fuc.facility_unit_id = fu.id AND fuc.active = true
                   JOIN str.codebook_element ce ON ce.id = fuc.type_id
                  WHERE fu.facility_id = f.id AND fu.active = true
                    AND ce.code = 'CAT_BROJ_KREVETA'))
            """;

    /** Broj pomoćnih kreveta jedinice {@code f}; {@code NULL} kad ga eTurizam ne zna. */
    String POMOCNI_KREVETI_JEDINICE = """
            coalesce(
                (SELECT sum(fc.quantity) FROM str.facility_capacity fc
                   JOIN str.codebook_element ce ON ce.id = fc.type_id
                  WHERE fc.facility_id = f.id AND fc.active = true
                    AND ce.code = 'CAT_BROJ_POM_KREVETA'),
                (SELECT sum(fuc.quantity) FROM str.facility_unit fu
                   JOIN str.facility_unit_capacity fuc
                     ON fuc.facility_unit_id = fu.id AND fuc.active = true
                   JOIN str.codebook_element ce ON ce.id = fuc.type_id
                  WHERE fu.facility_id = f.id AND fu.active = true
                    AND ce.code = 'CAT_BROJ_POM_KREVETA'))
            """;

    interface FacilityListingRow {
        Long getFacilityId();
        /** {@code system_uuid} — oznaka objekta kojem jedinica pripada; frontend po njoj grupira. */
        String getSystemUuid();
        /** {@code true} verificiran (novi eTurizam), {@code false} migriran iz starog sustava. */
        Boolean getVerified();
        /** Ukupno objekata iznajmljivača (ne samo na ovoj stranici). */
        Long getTotalObjects();
        /** Ukupno jedinica iznajmljivača (ne samo na ovoj stranici). */
        Long getTotalUnits();
        String getName();
        String getTypeCode();
        String getSubtypeCode();
        String getSubtypeName();
        String getCategoryName();
        String getStatusName();
        String getRegistrationNumber();
        String getCountyName();
        String getMunicipalityName();
        String getSettlementName();
        String getStreetName();
        String getHouseNumber();
        String getPostalCode();
        String getFullAddress();
        /**
         * Kreveti po {@link StrFacilityRepository#KREVETI_JEDINICE}; kad je
         * {@link #getObjectLevelCapacity()}, kreveti cijelog objekta.
         */
        Integer getBeds();
        Integer getAuxiliaryBeds();
        /**
         * {@code true} kad {@link #getBeds()} i {@link #getAuxiliaryBeds()} nisu kapacitet jedinice
         * nego cijelog objekta: migrirani objekt s više jedinica u istom predmetu. Migracija je na
         * <b>svaku</b> jedinicu upisala kapacitet cijelog objekta (N redaka {@code facility_capacity}
         * ili isti sadržaj na svakoj jedinici — 5.887 od 5.887 takvih objekata na CDU), pa se
         * kapacitet pojedine jedinice iz podataka ne može saznati (P-22).
         */
        Boolean getObjectLevelCapacity();
        /** Kontakt objekta iz eTurizma — za predpopunu forme (vidi {@link FacilityOwnershipRow}). */
        String getContactEmail();
        String getContactPhone();
    }

    /**
     * Jedinice objekata s rednim brojem objekta u {@code (offset, offset + limit]} — paginacija je
     * po OBJEKTIMA, verificirani prvi. Svaki redak nosi i ukupan broj objekata i jedinica
     * iznajmljivača; kad je stranica prazna, ukupno daje {@link #countListingByOib}.
     *
     * <p>Redni broj objekta je {@code dense_rank} po (ima li objekt verificiranu jedinicu, najmanji
     * id jedinice), pa je {@code max(redni)} broj objekata.
     *
     * <p>Adresa subjekta ({@code same_address_subject}) čita se preko subjekta predmeta, kao u viewu.
     */
    @Query(value = """
            SELECT f.id                                    AS facilityId,
                   s.su                                    AS systemUuid,
                   s.verificiran                           AS verified,
                   s.ukupno_objekata                       AS totalObjects,
                   s.ukupno_jedinica                       AS totalUnits,
                   f.name                                  AS name,
                   c_type.code                             AS typeCode,
                   c_sub.code                              AS subtypeCode,
                   c_sub.name                              AS subtypeName,
                   c_cat.name                              AS categoryName,
                   c_st.name                               AS statusName,
                   f.registration_number                   AS registrationNumber,
                   coalesce(co.name, a.county)             AS countyName,
                   coalesce(mu.name, a.municipality)       AS municipalityName,
                   coalesce(se.name, a.settlement)         AS settlementName,
                   coalesce(stt.name, a.street)            AS streetName,
                   coalesce(hn.name, a.house_number)       AS houseNumber,
                   coalesce(a.postal_code, se.postal_code) AS postalCode,
                   a.full_address                          AS fullAddress,
                   f.email                                 AS contactEmail,
                   f.phone                                 AS contactPhone,
                   """ + KREVETI_JEDINICE + """
                                                           AS beds,
                   """ + POMOCNI_KREVETI_JEDINICE + """
                                                           AS auxiliaryBeds,
                   (NOT s.verificiran AND s.predmet_jedinica > 1) AS objectLevelCapacity
              FROM (SELECT u.*,
                           max(u.redni) OVER () AS ukupno_objekata,
                           count(*) OVER ()     AS ukupno_jedinica
                      FROM (SELECT p.*,
                                   dense_rank() OVER (ORDER BY p.obj_verificiran DESC, p.obj_prvi_id) AS redni
                              FROM (SELECT q.*,
                                           bool_or(q.verificiran) OVER (PARTITION BY q.su) AS obj_verificiran,
                                           min(q.id) OVER (PARTITION BY q.su)              AS obj_prvi_id
                                      FROM (""" + PRIKAZ_ZA_OIB + """
                                           ) q) p) u) s
              JOIN str.facility f ON f.id = s.id
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
                                           WHERE x.subject_version_id = s.bc_sv
                                             AND coalesce(x.active, true) = true)
                                    ELSE f.address_id END
              LEFT JOIN str.county co        ON co.id  = a.county_id
              LEFT JOIN str.municipality mu  ON mu.id  = a.municipality_id
              LEFT JOIN str.settlement se    ON se.id  = a.settlement_id
              LEFT JOIN str.street stt       ON stt.id = a.street_id
              LEFT JOIN str.house_number hn  ON hn.id  = a.house_number_id
             WHERE s.redni > :offset
               AND s.redni <= :offset + :limit
             ORDER BY s.redni, f.id
            """, nativeQuery = true)
    List<FacilityListingRow> findListingByOib(@Param("oib") String oib,
                                              @Param("codes") Collection<String> codes,
                                              @Param("limit") int limit,
                                              @Param("offset") long offset);

    interface ListingTotals {
        Long getObjects();
        Long getUnits();
    }

    /** Ukupno objekata i jedinica iznajmljivača — za praznu stranicu, kad ga redci ne nose. */
    @Query(value = """
            SELECT count(DISTINCT q.su) AS objects,
                   count(*)             AS units
              FROM (""" + PRIKAZ_ZA_OIB + """
                   ) q
            """, nativeQuery = true)
    ListingTotals countListingByOib(@Param("oib") String oib, @Param("codes") Collection<String> codes);

    interface FacilityOwnershipRow {
        String getOib();
        String getSubtypeCode();
        Integer getBeds();
        /** Pomoćni kreveti ({@code CAT_BROJ_POM_KREVETA}); ulaze u maksimalan broj gostiju. */
        Integer getAuxiliaryBeds();
        /**
         * {@code true} kad su kreveti kapacitet cijelog objekta, ne jedinice — isto značenje kao
         * {@link FacilityListingRow#getObjectLevelCapacity()}. Tada broj gostiju jedinice nije
         * poznat (v. {@link FacilityClaimVerifier#maxGuests}).
         */
        Boolean getObjectLevelCapacity();
        Boolean getActive();
        /**
         * Šifra poslovnog statusa ({@code FBS_ACTIVE} / {@code FBS_INACTIVE}), {@code null} kad je
         * objekt nema. {@link #getActive()} je zastavica verzije zapisa i ne kaže posluje li objekt.
         */
        String getBusinessStatusCode();
        /**
         * Je li zapis aktualna jedinica po pravilima eTurizma (v. {@link #RANGIRANE_JEDINICE_OD}): ne
         * stara verzija, ne predmet u obradi, ne migrirana kopija koju je zamijenio noviji predmet.
         * Isto pravilo kao popis — jedinica koja se na popisu ne vidi ne smije dobiti RB.
         */
        Boolean getCurrent();
        /**
         * {@code true} verificiran (zapis novog eTurizma), {@code false} migriran iz starog sustava
         * ({@code created_by = 'optimit'}), {@code null} kad zapis nema autora. Čita se iz samog
         * zapisa, ne iz {@link #RANGIRANE_JEDINICE_OD}: tamo je NULL čim jedinica nije aktualna, a
         * predaja rješenja uz već izdan RB pita i za takvu.
         */
        Boolean getVerified();
        String getName();
        /** Naziv/ime iznajmljivača — služi samo da se prepozna kad je `facility.name` zapravo on. */
        String getOwnerName();
        String getOwnerFullName();
        String getCountyName();
        String getMunicipalityName();
        String getSettlementName();
        String getStreetName();
        String getHouseNumber();
        String getPostalCode();
        String getCategoryName();

        /**
         * Kontakt objekta iz eTurizma — služi <b>samo</b> za predpopunu forme, ne i za provjeru.
         * Kontakt je promjenjiv podatak, ne identitet objekta; zaključavanje bi značilo da
         * korisnik ne može ispraviti zastarjeli e-mail, a naručitelj traži suprotno.
         *
         * <p><b>Mjereno 11.09.2026. na obje okoline, uzorak 100 000 aktivnih objekata:</b>
         * predprodukcija {@code email} 0,2 % / {@code phone} 0,1 %; <b>CDU (test, ondje se
         * prezentira) {@code email} 0 od 100 000, {@code phone} 28 od 100 000.</b> Predpopuna je
         * dakle prazna — na test okolini u svih 100 % slučajeva — i kontakt uvijek upisuje
         * korisnik. To je i razlog zašto su kontakt polja u zahtjevu obvezna. Ne graditi ništa
         * na pretpostavci da je ovdje podatak.
         *
         * <p>Provjereno je i alternativno vrelo: {@code str.document_contact} jest popunjen
         * (31 % e-mail, 53 % mobitel), ali preko {@code facility.document_id} doseže samo 0,2 %
         * objekata, pa kao izvor ne valja. Pitanje gdje eTurizam stvarno drži kontakt objekta
         * otvoreno je prema naručitelju.
         */
        String getContactEmail();
        String getContactPhone();

        /**
         * Dokument eTurizma uz koji je vezana ova verzija objekta ({@code facility.document_id}) —
         * prvi segment URL-a eTurizmova zahtjeva za promjenu podataka. {@code null} kad ga zapis nema.
         */
        Long getDocumentId();
    }

    /**
     * Podaci potrebni da se za zahtjev koji nosi {@code facilityId} provjeri da jedinica
     * pripada podnositelju, da je aktualna i da poslana vrsta / kapacitet odgovaraju eTurizmu.
     *
     * <p>Vlasnik je subjekt predmeta ({@code business_case.subject_version_id → subject.jips}),
     * isto kao na popisu. {@code subject.active} se namjerno <strong>ne</strong> filtrira: jedan
     * OIB ima više {@code subject} redaka, a identitet nosi {@code jips}, ne zastavica.
     *
     * <p>{@code current} računa ista pravila kao popis ({@link #RANGIRANE_JEDINICE_OD}) nad svim
     * zapisima objekta, pa jedinica koju je zamijenio noviji predmet nije aktualna.
     *
     * <p>{@code coalesce(active, true)} jer {@code facility_type.active} u eTurizmu smije biti
     * NULL (njihov vlastiti view ga uopće ne filtrira); {@code active = true} bi za takve zapise
     * izgubio vrstu i provjera bi se tiho preskočila.
     *
     * <p>Kapacitet se računa istim fragmentima kao popis ({@link #KREVETI_JEDINICE},
     * {@link #POMOCNI_KREVETI_JEDINICE}), strogo nad aktivnim retcima, i nosi istu oznaku
     * {@code objectLevelCapacity} — popis i provjera ne smiju vidjeti različit kapacitet (B-3).
     *
     * <p>Adresa i naziv se čitaju istom join-mapom kao popis (isti {@code CASE} za
     * {@code same_address_subject}), jer se uspoređuju s onim što je korisnik vidio u formi.
     */
    @Query(value = """
            SELECT s.jips      AS oib,
                   c_sub.code  AS subtypeCode,
                   f.active    AS active,
                   c_st.code   AS businessStatusCode,
                   coalesce(r.predmet_rang = 1, false) AS current,
                   f.created_by <> 'optimit' AS verified,
                   f.name      AS name,
                   sv.name     AS ownerName,
                   btrim(coalesce(sv.first_name,'') || ' ' || coalesce(sv.last_name,'')) AS ownerFullName,
                   coalesce(co.name, a.county)       AS countyName,
                   coalesce(mu.name, a.municipality) AS municipalityName,
                   coalesce(se.name, a.settlement)   AS settlementName,
                   coalesce(stt.name, a.street)      AS streetName,
                   coalesce(hn.name, a.house_number) AS houseNumber,
                   coalesce(a.postal_code, se.postal_code) AS postalCode,
                   f.email     AS contactEmail,
                   f.phone     AS contactPhone,
                   f.document_id AS documentId,
                   c_cat.name  AS categoryName,
                   """ + KREVETI_JEDINICE + """
                               AS beds,
                   """ + POMOCNI_KREVETI_JEDINICE + """
                               AS auxiliaryBeds,
                   coalesce(f.created_by = 'optimit' AND r.predmet_jedinica > 1, false) AS objectLevelCapacity
            FROM str.facility f
            JOIN str.document d         ON d.id  = f.document_id
            JOIN str.business_case bc   ON bc.id = d.business_case_id
            JOIN str.subject_version sv ON sv.id = bc.subject_version_id
            JOIN str.subject s          ON s.id  = sv.subject_id
            LEFT JOIN """ + RANGIRANE_JEDINICE_OD + """
                   (SELECT fx.system_uuid FROM str.facility fx
                     WHERE fx.id = :facilityId AND fx.system_uuid IS NOT NULL)""" + RANGIRANE_JEDINICE_DO + """
                   r ON r.id = f.id
            LEFT JOIN str.facility_type ft
                   ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                                WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
            LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
            LEFT JOIN str.codebook_element c_st  ON c_st.id  = f.business_status_id
            LEFT JOIN str.codebook_element c_cat ON c_cat.id = f.category_id
            LEFT JOIN str.address a
                   ON a.id = CASE WHEN f.same_address_subject = true
                                  THEN (SELECT max(x.address_id) FROM str.subject_address x
                                         WHERE x.subject_version_id = bc.subject_version_id
                                           AND coalesce(x.active, true) = true)
                                  ELSE f.address_id END
            LEFT JOIN str.county co        ON co.id  = a.county_id
            LEFT JOIN str.municipality mu  ON mu.id  = a.municipality_id
            LEFT JOIN str.settlement se    ON se.id  = a.settlement_id
            LEFT JOIN str.street stt       ON stt.id = a.street_id
            LEFT JOIN str.house_number hn  ON hn.id  = a.house_number_id
            WHERE f.id = :facilityId
            ORDER BY s.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<FacilityOwnershipRow> findOwnership(@Param("facilityId") long facilityId);


    /**
     * Id elementa eTurizmova šifrarnika po šifri. Id se među okolinama može razlikovati, šifra ne —
     * zato se traži po šifri (npr. {@code DST_Z_PROMJ_POD} je na CDU testu id 454).
     */
    @Query(value = """
            SELECT ce.id
              FROM str.codebook_element ce
             WHERE ce.code = :code
               AND coalesce(ce.active, true) = true
             ORDER BY ce.id DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<Long> findCodebookElementId(@Param("code") String code);

    /**
     * Upisuje dodijeljeni RB natrag u eTurizam registar, po dogovoru s tuStartom.
     *
     * <p>Ovo je <strong>jedini</strong> put pisanja u shemu {@code str}, koja je inače
     * read-only za ovaj servis. Namjerno je izveden kao uski native UPDATE nad jednom
     * kolonom umjesto kroz entitet: {@link StrFacilityEntity} ostaje {@code @Immutable},
     * pa nijedan drugi tok ne može slučajno perzistirati promjenu u tuđu tablicu.
     *
     * <p>{@code WHERE registration_number IS NULL} sprječava prepisivanje RB-a koji je
     * objekt već dobio (ponovni pokušaj, ručni upis u eTurizmu) — write-back je time
     * idempotentan i ne može tiho pregaziti tuđi podatak.
     *
     * <p>{@code REQUIRES_NEW}: poziva se iz {@code RnIssuedListener} u fazi {@code AFTER_COMMIT},
     * kad registracijska transakcija više ne može ništa upisati. S običnim {@code REQUIRED} upis
     * se priključi toj završenoj transakciji i padne s {@code TransactionRequiredException} —
     * pogreška se proguta i RB nikad ne stigne u eTurizam. Isti obrazac kao {@code EgopFilingStore}.
     * Na repozitoriju, a ne na pozivatelju, da neuspjeh ostane iznimka koju pozivatelj guta, a ne
     * {@code UnexpectedRollbackException} koja bi preskočila i eGOP dostavu.
     *
     * @return broj ažuriranih redaka: 1 kad je upis prošao, 0 kad objekt ne postoji ili
     * već ima RB
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            UPDATE str.facility
               SET registration_number = :rn
             WHERE id = :facilityId
               AND registration_number IS NULL
            """, nativeQuery = true)
    int writeBackRegistrationNumber(@Param("facilityId") long facilityId, @Param("rn") String rn);
}
