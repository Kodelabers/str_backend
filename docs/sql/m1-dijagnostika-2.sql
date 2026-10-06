-- =====================================================================================================
-- M-1 · dijagnostika, 2. krug: migrirani (optimit) objekti, vlasnik i brzina upita vođenog po OIB-u
--
-- SAMO ČITANJE. Ne stvara ništa (ni TEMP tablice) — svaki blok je samostalan.
-- Pokrenuti na ISTOJ okolini kao 1. krug, a zatim i na okolini na kojoj je bila prezentacija.
-- Kao aplikacijski korisnik (na preprod / CDU preprod prvo: SET ROLE str_owner;).
-- Rezultat svakog bloka (R0 … R6) poslati uz naziv okoline. Ako blok padne, poslati grešku.
--
-- Zašto: u 1. krugu nijedan optimit objekt nije prošao uvjete „predmet gotov” iz viewa
-- (status predmeta BCST_RJES_IZVRSNO, datum izvršnosti), pa su obje varijante neverificiranih
-- (Simonova i alt) vratile 0 objekata, a testni iznajmljivač 06756460531 (38 migriranih objekata)
-- ostao bi s praznim popisom. Treba vidjeti kakvi su migrirani zapisi da bi eTurizam rekao koji
-- uvjeti za njih vrijede.
-- =====================================================================================================


-- R0 · okolina
SELECT current_database() AS baza, current_user AS korisnik, inet_server_addr() AS server,
       version() AS pg, now() AS vrijeme;


-- ---------------------------------------------------------------------------------------------------
-- R1 · aktivni migrirani objekti: koliko ih pada na kojem uvjetu iz viewa
-- ---------------------------------------------------------------------------------------------------
SELECT count(*)                                                                     AS optimit_aktivnih,
       count(*) FILTER (WHERE c_st.code = 'FBS_ACTIVE')                             AS poslovni_status_aktivan,
       count(*) FILTER (WHERE coalesce(f.historical, false))                        AS historical,
       count(*) FILTER (WHERE f.system_uuid IS NULL)                                AS bez_system_uuid,
       count(*) FILTER (WHERE d.id IS NULL)                                         AS bez_dokumenta,
       count(*) FILTER (WHERE d.id IS NOT NULL AND NOT coalesce(d.active, false))   AS dokument_neaktivan,
       count(*) FILTER (WHERE d.id IS NOT NULL AND dt.code IS NULL)                 AS vrsta_dok_nije_rjesenje_ni_potvrda,
       count(*) FILTER (WHERE bc.id IS NULL)                                        AS bez_predmeta,
       count(*) FILTER (WHERE bc.id IS NOT NULL AND NOT coalesce(bc.active, false)) AS predmet_neaktivan,
       count(*) FILTER (WHERE bc.id IS NOT NULL AND bc.status_type_id IS NULL)      AS predmet_bez_statusa,
       count(*) FILTER (WHERE bcs.code = 'BCST_RJES_IZVRSNO')                       AS predmet_izvrsan,
       count(*) FILTER (WHERE d.execution_date IS NULL)                             AS bez_datuma_izvrsnosti,
       count(*) FILTER (WHERE bc.id IS NOT NULL AND NOT EXISTS (
                            SELECT 1 FROM str.organizational_unit ou
                             WHERE ou.id = bc.jurisdiction_organizational_unit_id)) AS predmet_bez_org_jedinice,
       count(*) FILTER (WHERE f.subject_version_id IS NULL)                         AS objekt_bez_subjekta,
       count(*) FILTER (WHERE bc.id IS NOT NULL AND bc.subject_version_id IS NULL)  AS predmet_bez_subjekta,
       count(*) FILTER (WHERE f.subject_version_id IS NOT NULL AND bc.subject_version_id IS NOT NULL
                          AND s1.jips IS DISTINCT FROM s2.jips)                     AS razlicit_oib_objekt_predmet
  FROM str.facility f
  LEFT JOIN str.document d               ON d.id   = f.document_id
  LEFT JOIN str.sif_podvrsta_dokumenta ds ON ds.code::text = d.subtype_code::text
  LEFT JOIN str.sif_vrsta_dokumenata dt  ON dt.code::text = ds.vrsta_dokumenata_code::text
                                        AND dt.code::text IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU')
  LEFT JOIN str.business_case bc         ON bc.id  = d.business_case_id
  LEFT JOIN str.codebook_element bcs     ON bcs.id = bc.status_type_id
  LEFT JOIN str.codebook_element c_st    ON c_st.id = f.business_status_id
  LEFT JOIN str.subject_version sv1      ON sv1.id = f.subject_version_id
  LEFT JOIN str.subject s1               ON s1.id  = sv1.subject_id
  LEFT JOIN str.subject_version sv2      ON sv2.id = bc.subject_version_id
  LEFT JOIN str.subject s2               ON s2.id  = sv2.subject_id
 WHERE f.active
   AND f.created_by = 'optimit';


-- ---------------------------------------------------------------------------------------------------
-- R2 · migrirani objekti po statusu predmeta × vrsti/podvrsti dokumenta × datumu izvršnosti
-- ---------------------------------------------------------------------------------------------------
SELECT coalesce(bcs.code::text, 'NULL')            AS status_predmeta,
       coalesce(ds.vrsta_dokumenata_code::text, '?') AS vrsta_dokumenta,
       d.subtype_code                               AS podvrsta_dokumenta,
       d.execution_date IS NULL                     AS bez_datuma_izvrsnosti,
       coalesce(f.historical, false)                AS historical,
       count(*)                                     AS objekata
  FROM str.facility f
  LEFT JOIN str.document d               ON d.id   = f.document_id
  LEFT JOIN str.sif_podvrsta_dokumenta ds ON ds.code::text = d.subtype_code::text
  LEFT JOIN str.business_case bc         ON bc.id  = d.business_case_id
  LEFT JOIN str.codebook_element bcs     ON bcs.id = bc.status_type_id
 WHERE f.active
   AND f.created_by = 'optimit'
 GROUP BY 1, 2, 3, 4, 5
 ORDER BY count(*) DESC
 LIMIT 40;


-- ---------------------------------------------------------------------------------------------------
-- R3 · tko je u verifikaciji izvor, a tko cilj (je li migrirani predmet SOURCE, kako pretpostavljamo)
--      kategorija predmeta po objektima na njemu: optimit / ostali / bez objekta
-- ---------------------------------------------------------------------------------------------------
SELECT ce.code     AS status_verifikacije,
       src.kat     AS izvor_unverified,
       tgt.kat     AS cilj_verified,
       count(*)    AS verifikacija
  FROM str.business_case_verification v
  LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
  LEFT JOIN LATERAL (
       SELECT CASE WHEN count(f.id) = 0                   THEN 'bez objekta'
                   WHEN bool_or(f.created_by = 'optimit') THEN 'optimit'
                   ELSE 'ostali' END AS kat
         FROM str.document d
         JOIN str.facility f ON f.document_id = d.id
        WHERE d.business_case_id = v.unverified_business_case_id) src ON true
  LEFT JOIN LATERAL (
       SELECT CASE WHEN count(f.id) = 0                   THEN 'bez objekta'
                   WHEN bool_or(f.created_by = 'optimit') THEN 'optimit'
                   ELSE 'ostali' END AS kat
         FROM str.document d
         JOIN str.facility f ON f.document_id = d.id
        WHERE d.business_case_id = v.verified_business_case_id) tgt ON true
 GROUP BY 1, 2, 3
 ORDER BY 1, 4 DESC;


-- ---------------------------------------------------------------------------------------------------
-- R4 · što se dogodi s migriranim objektom kad je verifikacija ZAVRŠENA
--      (ostaje li aktivan / nepovijesni — tj. bi li se prikazao uz novu, verificiranu verziju)
--      i dijele li stari i novi objekt system_uuid
-- ---------------------------------------------------------------------------------------------------
SELECT ce.code                                              AS status_verifikacije,
       fs.active                                            AS stari_active,
       coalesce(fs.historical, false)                       AS stari_historical,
       bcs_src.active                                       AS stari_predmet_active,
       count(*)                                             AS starih_objekata,
       count(*) FILTER (WHERE EXISTS (
           SELECT 1 FROM str.document dt
             JOIN str.facility ft ON ft.document_id = dt.id
            WHERE dt.business_case_id = v.verified_business_case_id
              AND ft.system_uuid = fs.system_uuid))         AS isti_uuid_u_cilju
  FROM str.business_case_verification v
  LEFT JOIN str.codebook_element ce   ON ce.id = v.status_id
  JOIN str.business_case bcs_src      ON bcs_src.id = v.unverified_business_case_id
  JOIN str.document ds                ON ds.business_case_id = bcs_src.id
  JOIN str.facility fs                ON fs.document_id = ds.id
 WHERE fs.created_by = 'optimit'
 GROUP BY 1, 2, 3, 4
 ORDER BY 1, 5 DESC;


-- ---------------------------------------------------------------------------------------------------
-- R5 · testni iznajmljivač 06756460531: njegovi zapisi po izvoru vlasništva
--      (preko objekta = danas; preko predmeta = kako view uzima subjekt)
-- ---------------------------------------------------------------------------------------------------
SELECT 'preko objekta (f.subject_version_id)' AS put, count(DISTINCT f.id) AS zapisa,
       count(DISTINCT f.id) FILTER (WHERE f.created_by = 'optimit') AS optimit
  FROM str.subject s
  JOIN str.subject_version sv ON sv.subject_id = s.id
  JOIN str.facility f         ON f.subject_version_id = sv.id
 WHERE s.jips = '06756460531' AND f.active
UNION ALL
SELECT 'preko predmeta (bc.subject_version_id)', count(DISTINCT f.id),
       count(DISTINCT f.id) FILTER (WHERE f.created_by = 'optimit')
  FROM str.subject s
  JOIN str.subject_version sv ON sv.subject_id = s.id
  JOIN str.business_case bc   ON bc.subject_version_id = sv.id
  JOIN str.document d         ON d.business_case_id = bc.id
  JOIN str.facility f         ON f.document_id = d.id
 WHERE s.jips = '06756460531' AND f.active;


-- ---------------------------------------------------------------------------------------------------
-- R6 · brzina: ista logika kao Q7b iz 1. kruga, ali vođena kandidatima OIB-a (preko predmeta).
--      OFFSET 0 sprječava da planer „razmota” podupit i krene od svih dokumenata registra.
-- ---------------------------------------------------------------------------------------------------
EXPLAIN (ANALYZE, BUFFERS)
SELECT facility.id,
       bool_and(facility.created_by::text <> 'optimit') AS verificiran
  FROM (SELECT DISTINCT f.id
          FROM str.subject s
          JOIN str.subject_version sv ON sv.subject_id = s.id
          JOIN str.business_case bc   ON bc.subject_version_id = sv.id
          JOIN str.document d         ON d.business_case_id = bc.id
          JOIN str.facility f         ON f.document_id = d.id
         WHERE s.jips = '12312312316'
        OFFSET 0) kandidati
  JOIN str.facility facility
    ON facility.id = kandidati.id
  JOIN str.document document
    ON facility.document_id = document.id AND document.active
  JOIN str.sif_podvrsta_dokumenta document_subtype
    ON document_subtype.code::text = document.subtype_code::text
  JOIN str.sif_vrsta_dokumenata document_type
    ON document_type.code::text = document_subtype.vrsta_dokumenata_code::text
   AND document_type.code::text IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU')
  JOIN str.business_case business_case
    ON document.business_case_id = business_case.id AND business_case.active
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
 WHERE facility.active
   AND facility.created_by IS NOT NULL
   AND (verification_source.id IS NULL OR verification_source_status.code::text = 'BCVS_U_IZRADI')
   AND (verification_target.id IS NULL OR verification_target_status.code::text = 'BCVS_ZAVRSENA')
   AND (facility.historical IS NULL OR facility.historical = false)
   AND bc_status_ce.code::text = 'BCST_RJES_IZVRSNO'
   AND document.execution_date IS NOT NULL
   AND document.execution_date < now()
   AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
 GROUP BY facility.id
HAVING count(facility.system_uuid) = 1;

-- kraj
