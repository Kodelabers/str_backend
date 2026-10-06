-- =====================================================================================================
-- M-1 · dijagnostika, 6. krug (CDU test): kopije ili jedinice?
--
-- 5. krug (W1/W3): zapisi s istim system_uuid u istom predmetu razlikuju se SAMO u id, address_id,
-- created_date i last_modified_date — sve ostale kolone str.facility su iste. Ostaje provjeriti
-- povezane tablice (adresa, kapaciteti, sadržaji, usluge) i usporediti broj zapisa s nazivom
-- (npr. „ROTUS - GAJAC, Novalja A 11 - 51, 52” ima 2 zapisa → dvije jedinice, 51 i 52?).
--
-- SAMO ČITANJE, ništa ne stvara. Jedna sesija, cijela skripta (Alt+X).
-- =====================================================================================================


-- ---------------------------------------------------------------------------------------------------
-- X1 · po grupi (uuid, predmet): razlikuju li se sadržaj adrese, skup kapaciteta, sadržaji, usluge
--      kapaciteti/sadržaji/usluge uspoređuju se kao cijeli skup (tip + količina) po zapisu
-- ---------------------------------------------------------------------------------------------------
WITH grupe AS (
    SELECT f.system_uuid, d.business_case_id
      FROM str.facility f
      JOIN str.document d       ON d.id  = f.document_id AND d.active
      JOIN str.business_case bc ON bc.id = d.business_case_id AND bc.active
      LEFT JOIN str.facility_type ft
             ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                          WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
      LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
     WHERE f.active
       AND f.created_by = 'optimit'
       AND coalesce(f.historical, false) = false
       AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')
     GROUP BY f.system_uuid, d.business_case_id
    HAVING count(*) > 1
     ORDER BY f.system_uuid
     LIMIT 500),
zapisi AS (
    SELECT g.system_uuid, f.id,
           (SELECT to_jsonb(a) - 'id' - 'created_date' - 'last_modified_date'
                               - 'created_by' - 'last_modified_by'
              FROM str.address a WHERE a.id = f.address_id)                         AS adresa,
           (SELECT jsonb_agg(jsonb_build_array(fc.type_id, fc.quantity) ORDER BY fc.type_id, fc.quantity)
              FROM str.facility_capacity fc
             WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true)      AS kapaciteti,
           (SELECT jsonb_agg(jsonb_build_array(c.type_id, c.quantity) ORDER BY c.type_id, c.quantity)
              FROM str.facility_content c WHERE c.facility_id = f.id)               AS sadrzaji,
           (SELECT jsonb_agg(s.type_id ORDER BY s.type_id)
              FROM str.facility_service s WHERE s.facility_id = f.id)               AS usluge
      FROM grupe g
      JOIN str.document d ON d.business_case_id = g.business_case_id
      JOIN str.facility f ON f.document_id = d.id AND f.system_uuid = g.system_uuid
     WHERE f.active AND coalesce(f.historical, false) = false),
po_grupi AS (
    SELECT system_uuid,
           count(*)                                         AS zapisa,
           count(DISTINCT coalesce(adresa, '{}'))           AS adresa,
           count(DISTINCT coalesce(kapaciteti, '[]'))       AS kapaciteti,
           count(DISTINCT coalesce(sadrzaji, '[]'))         AS sadrzaji,
           count(DISTINCT coalesce(usluge, '[]'))           AS usluge
      FROM zapisi
     GROUP BY system_uuid)
SELECT count(*)                                   AS grupa,
       sum(zapisa)                                AS zapisa,
       count(*) FILTER (WHERE adresa > 1)         AS razlicit_sadrzaj_adrese,
       count(*) FILTER (WHERE kapaciteti > 1)     AS razliciti_kapaciteti,
       count(*) FILTER (WHERE sadrzaji > 1)       AS razliciti_sadrzaji,
       count(*) FILTER (WHERE usluge > 1)         AS razlicite_usluge,
       count(*) FILTER (WHERE adresa = 1 AND kapaciteti = 1 AND sadrzaji = 1 AND usluge = 1)
                                                  AS potpuno_iste_grupe
  FROM po_grupi;


-- ---------------------------------------------------------------------------------------------------
-- X2 · 15 grupa: broj zapisa uz naziv objekta, kapacitet jednog zapisa i napomenu migracije
--      (traži se odgovara li broj zapisa broju jedinica navedenih u nazivu)
-- ---------------------------------------------------------------------------------------------------
WITH grupe AS (
    SELECT f.system_uuid, d.business_case_id, count(*) AS zapisa, min(f.id) AS prvi_id
      FROM str.facility f
      JOIN str.document d       ON d.id  = f.document_id AND d.active
      JOIN str.business_case bc ON bc.id = d.business_case_id AND bc.active
      LEFT JOIN str.facility_type ft
             ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                          WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
      LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
     WHERE f.active
       AND f.created_by = 'optimit'
       AND coalesce(f.historical, false) = false
       AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')
     GROUP BY f.system_uuid, d.business_case_id
    HAVING count(*) > 1
     ORDER BY f.system_uuid
     LIMIT 15)
SELECT g.system_uuid, g.zapisa, f.name AS naziv, c_sub.code AS podvrsta,
       (SELECT jsonb_object_agg(ce.code, fc.quantity)
          FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true)   AS kapacitet_jednog_zapisa,
       f.migration_notice,
       left(f.migration_notice_fulltext::text, 300)                                AS napomena_migracije
  FROM grupe g
  JOIN str.facility f ON f.id = g.prvi_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
 ORDER BY g.system_uuid;


-- ---------------------------------------------------------------------------------------------------
-- X3 · kontrola iz NOVOG sustava: verificirani (view) uuid-ovi s više zapisa (3. krug: 24)
--      Ako novi eTurizam sam stvara više zapisa po uuid-u u istom predmetu, to je njegov model
--      (jedinice); ako ih nema ili su u različitim predmetima, višestruki zapisi su ostatak migracije.
--      stvorio: optimit / (OIB) / ostalo — bez osobnih podataka.
-- ---------------------------------------------------------------------------------------------------
WITH v AS (
    SELECT DISTINCT f_id AS id, f_system_uuid AS system_uuid
      FROM str.vw_src_facility_actual),
visestruki AS (
    SELECT system_uuid FROM v GROUP BY system_uuid HAVING count(*) > 1),
zapisi AS (
    SELECT v.system_uuid, f.id, f.name, f.created_by, f.created_date,
           d.business_case_id, d.subtype_code::text AS dok_podvrsta,
           (SELECT jsonb_agg(jsonb_build_array(fc.type_id, fc.quantity) ORDER BY fc.type_id, fc.quantity)
              FROM str.facility_capacity fc
             WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true)::text AS kapaciteti
      FROM v
      JOIN visestruki x   ON x.system_uuid = v.system_uuid
      JOIN str.facility f ON f.id = v.id
      JOIN str.document d ON d.id = f.document_id)
SELECT system_uuid,
       count(*)                                                        AS zapisa,
       count(DISTINCT business_case_id)                                AS predmeta,
       string_agg(DISTINCT CASE WHEN created_by = 'optimit' THEN 'optimit'
                                WHEN created_by ~ '^[0-9]{11}$' THEN '(OIB)'
                                ELSE created_by END, ',')              AS stvorio,
       string_agg(DISTINCT dok_podvrsta, ',')                          AS dok_podvrsta,
       count(DISTINCT coalesce(name, ''))                              AS naziva,
       count(DISTINCT coalesce(kapaciteti, '[]'))                      AS razlicitih_kapaciteta,
       min(created_date)                                               AS prvi_stvoren,
       max(created_date)                                               AS zadnji_stvoren
  FROM zapisi
 GROUP BY system_uuid
 ORDER BY system_uuid;

-- kraj
