-- =====================================================================================================
-- M-1 · dijagnostika, 4. krug (CDU test): preklapanja, više zapisa po system_uuid, izdani RB-ovi
--
-- SAMO ČITANJE nad str i str_rn; stvara samo TEMP tablice. Jedna sesija, cijela skripta (Alt+X).
-- Priprema je ista kao u 3. krugu (ponovljena, da skripta radi i u novoj sesiji).
-- Osobni podaci se ne ispisuju: created_by koji je OIB prikazuje se kao „(OIB)”, adresa samo kao
-- naselje + kućni broj.
-- =====================================================================================================

-- pg_temp. eksplicitno: DROP smije dirati samo TEMP tablice ove sesije
DROP TABLE IF EXISTS pg_temp.t3_redovi, pg_temp.t3_verif, pg_temp.t3_neverif, pg_temp.t3_neverif_simon;

-- osnova za migrirane: f_active iz viewa s created_by = 'optimit', BEZ uvjeta „predmet gotov”
CREATE TEMP TABLE t3_redovi AS
SELECT facility.id,
       facility.system_uuid::text               AS system_uuid,
       business_case.subject_version_id         AS bc_subject_version_id,
       document.subtype_code::text              AS dok_podvrsta,
       verification_source.id                   AS vs_id,
       verification_source_status.code::text    AS vs_code,
       verification_target.id                   AS vt_id,
       verification_target_status.code::text    AS vt_code
  FROM str.facility facility
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
 WHERE facility.active
   AND facility.created_by::text = 'optimit'
   AND (facility.historical IS NULL OR facility.historical = false)
   AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                WHERE ou.id = business_case.jurisdiction_organizational_unit_id);

CREATE INDEX ON t3_redovi (id);
ANALYZE t3_redovi;

-- prijedlog: oba uvjeta verifikacije kao u viewu
CREATE TEMP TABLE t3_neverif AS
SELECT id, max(system_uuid) AS system_uuid, max(dok_podvrsta) AS dok_podvrsta
  FROM t3_redovi
 WHERE (vs_id IS NULL OR vs_code = 'BCVS_U_IZRADI')
   AND (vt_id IS NULL OR vt_code = 'BCVS_ZAVRSENA')
 GROUP BY id
HAVING count(system_uuid) = 1;

-- Simonova varijanta nad istom osnovom
CREATE TEMP TABLE t3_neverif_simon AS
SELECT id, max(system_uuid) AS system_uuid
  FROM t3_redovi
 WHERE (vt_id IS NULL OR vt_code <> 'BCVS_ZAVRSENA')
 GROUP BY id
HAVING count(system_uuid) = 1;

-- verificirani = view, kako je dan
CREATE TEMP TABLE t3_verif AS
SELECT DISTINCT f_id AS id, f_system_uuid::text AS system_uuid
  FROM str.vw_src_facility_actual;

CREATE INDEX ON t3_neverif (id);        CREATE INDEX ON t3_neverif (system_uuid);
CREATE INDEX ON t3_neverif_simon (id);  CREATE INDEX ON t3_neverif_simon (system_uuid);
CREATE INDEX ON t3_verif (id);          CREATE INDEX ON t3_verif (system_uuid);
ANALYZE t3_neverif; ANALYZE t3_neverif_simon; ANALYZE t3_verif;


-- ---------------------------------------------------------------------------------------------------
-- V1 · 26 migriranih objekata koji dijele system_uuid s verificiranim (U2): odakle verificirana verzija?
--      verif_izvorni_predmet = predmet koji je verifikacijom zamijenjen (ako je nastala verifikacijom)
-- ---------------------------------------------------------------------------------------------------
SELECT n.system_uuid,
       n.id                                    AS neverif_id,
       dn.subtype_code                         AS neverif_podvrsta,
       fn.created_date                         AS neverif_stvoren,
       dn.business_case_id                     AS neverif_predmet,
       (SELECT string_agg(coalesce(ce.code::text, '?'), ',')
          FROM str.business_case_verification x
          LEFT JOIN str.codebook_element ce ON ce.id = x.status_id
         WHERE x.unverified_business_case_id = dn.business_case_id)   AS neverif_verif_kao_izvor,
       v.id                                    AS verif_id,
       CASE WHEN fv.created_by ~ '^[0-9]{11}$' THEN '(OIB)' ELSE fv.created_by END AS verif_stvorio,
       fv.created_date                         AS verif_stvoren,
       dv.subtype_code                         AS verif_podvrsta,
       dv.business_case_id                     AS verif_predmet,
       (SELECT string_agg(coalesce(ce.code::text, '?'), ',')
          FROM str.business_case_verification x
          LEFT JOIN str.codebook_element ce ON ce.id = x.status_id
         WHERE x.verified_business_case_id = dv.business_case_id)     AS verif_kao_cilj,
       (SELECT string_agg(x.unverified_business_case_id::text, ',')
          FROM str.business_case_verification x
         WHERE x.verified_business_case_id = dv.business_case_id)     AS verif_izvorni_predmet
  FROM t3_neverif n
  JOIN t3_verif v          ON v.system_uuid = n.system_uuid
  JOIN str.facility fn     ON fn.id = n.id
  JOIN str.document dn     ON dn.id = fn.document_id
  JOIN str.facility fv     ON fv.id = v.id
  JOIN str.document dv     ON dv.id = fv.document_id
 ORDER BY n.system_uuid, n.id, v.id;


-- ---------------------------------------------------------------------------------------------------
-- V2 · system_uuid s više zapisa među neverificiranima (U2: 7576): koliko zapisa, isti predmet?
--      ista adresa? isti naziv? smještaj (FS_*)?
-- ---------------------------------------------------------------------------------------------------
WITH g AS (
    SELECT n.system_uuid,
           count(*)                                    AS zapisa,
           count(DISTINCT d.business_case_id)          AS predmeta,
           count(DISTINCT f.address_id)                AS adresa,
           count(DISTINCT coalesce(f.name, ''))        AS naziva,
           bool_or(c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')) AS ima_fs
      FROM t3_neverif n
      JOIN str.facility f ON f.id = n.id
      JOIN str.document d ON d.id = f.document_id
      LEFT JOIN str.facility_type ft
             ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                          WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
      LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
     GROUP BY n.system_uuid
    HAVING count(*) > 1)
SELECT CASE WHEN zapisa <= 5 THEN zapisa::text WHEN zapisa <= 20 THEN '6-20' ELSE '>20' END AS zapisa_po_uuid,
       predmeta = 1     AS isti_predmet,
       adresa <= 1      AS ista_adresa,
       naziva = 1       AS isti_naziv,
       ima_fs,
       count(*)         AS uuid_ova,
       sum(zapisa)      AS zapisa_ukupno
  FROM g
 GROUP BY 1, 2, 3, 4, 5
 ORDER BY 7 DESC;


-- ---------------------------------------------------------------------------------------------------
-- V3 · tri primjera smještaja (FS_*) s 2–6 zapisa pod istim system_uuid — što se razlikuje?
-- ---------------------------------------------------------------------------------------------------
WITH u AS (
    SELECT n.system_uuid
      FROM t3_neverif n
      JOIN str.facility f ON f.id = n.id
      LEFT JOIN str.facility_type ft
             ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                          WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
      LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
     WHERE c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')
     GROUP BY n.system_uuid
    HAVING count(*) BETWEEN 2 AND 6
     ORDER BY n.system_uuid
     LIMIT 3)
SELECT n.system_uuid, f.id, f.name, d.business_case_id AS predmet, d.subtype_code AS dok_podvrsta,
       c_sub.code AS podvrsta_objekta, c_cat.code AS kategorija, f.same_address_subject,
       coalesce(se.name, a.settlement)   AS naselje,
       coalesce(hn.name, a.house_number) AS kucni_broj,
       (SELECT sum(fc.quantity) FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
           AND ce.code = 'CAT_BROJ_KREVETA')                        AS kreveti,
       (SELECT sum(fc.quantity) FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
           AND ce.code = 'CAT_BROJ_POM_KREVETA')                    AS pomocni,
       (SELECT count(*) FROM str.facility_unit fu WHERE fu.facility_id = f.id) AS jedinica
  FROM u
  JOIN t3_neverif n   ON n.system_uuid = u.system_uuid
  JOIN str.facility f ON f.id = n.id
  JOIN str.document d ON d.id = f.document_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
  LEFT JOIN str.codebook_element c_cat ON c_cat.id = f.category_id
  LEFT JOIN str.address a
         ON a.id = CASE WHEN f.same_address_subject = true
                        THEN (SELECT max(x.address_id) FROM str.subject_address x
                               WHERE x.subject_version_id = f.subject_version_id
                                 AND coalesce(x.active, true) = true)
                        ELSE f.address_id END
  LEFT JOIN str.settlement se    ON se.id = a.settlement_id
  LEFT JOIN str.house_number hn  ON hn.id = a.house_number_id
 ORDER BY n.system_uuid, f.id;


-- ---------------------------------------------------------------------------------------------------
-- V4 · RB-ovi izdani kroz STR na objektu koji po novim pravilima NIJE aktualan (U6: 10 ACTIVE)
-- ---------------------------------------------------------------------------------------------------
SELECT r.status, r.rn, f.id AS facility_id,
       CASE WHEN f.created_by = 'optimit' THEN 'optimit'
            WHEN f.created_by ~ '^[0-9]{11}$' THEN '(OIB)'
            ELSE f.created_by END                                  AS stvorio,
       f.active, f.historical, c_st.code AS poslovni_status,
       f.system_uuid IS NULL                                       AS bez_uuid,
       (SELECT count(*) FROM str.facility x WHERE x.system_uuid = f.system_uuid) AS zapisa_istog_uuid,
       d.subtype_code AS dok_podvrsta, d.active AS dok_aktivan, d.execution_date AS izvrsnost,
       bc.active AS predmet_aktivan, bcs.code AS status_predmeta,
       (SELECT string_agg(coalesce(ce.code::text, '?'), ',')
          FROM str.business_case_verification x
          LEFT JOIN str.codebook_element ce ON ce.id = x.status_id
         WHERE x.unverified_business_case_id = bc.id)              AS verif_kao_izvor,
       (SELECT string_agg(coalesce(ce.code::text, '?'), ',')
          FROM str.business_case_verification x
          LEFT JOIN str.codebook_element ce ON ce.id = x.status_id
         WHERE x.verified_business_case_id = bc.id)                AS verif_kao_cilj
  FROM str_rn.registration_number r
  JOIN str_rn.accommodation a         ON a.accommodation_id = r.accommodation_id
  JOIN str.facility f                 ON f.id::text = a.facility_id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.document d            ON d.id   = f.document_id
  LEFT JOIN str.business_case bc      ON bc.id  = d.business_case_id
  LEFT JOIN str.codebook_element bcs  ON bcs.id = bc.status_type_id
 WHERE f.id NOT IN (SELECT id FROM t3_verif)
   AND f.id NOT IN (SELECT id FROM t3_neverif)
 ORDER BY r.status, r.rn;

-- kraj · TEMP tablice nestaju zatvaranjem sesije
