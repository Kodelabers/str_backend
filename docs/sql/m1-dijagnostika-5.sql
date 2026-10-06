-- =====================================================================================================
-- M-1 · dijagnostika, 5. krug (CDU test): što razlikuje zapise objekta s istim system_uuid u istom predmetu
--
-- SAMO ČITANJE, ništa ne stvara. Jedna sesija, cijela skripta (Alt+X).
-- Uzorak: prvih 500 (uuid, predmet) grupa migriranog smještaja (FS_*) s više aktivnih, nepovijesnih
-- zapisa (4. krug, V2: ~5.900 takvih uuid-ova, ~43.700 zapisa).
-- Pitanje: jesu li to jedinice jednog objekta (svaka bi trebala svoj registracijski broj) ili kopije
-- istog objekta (treba ih svesti na jedan)?
-- =====================================================================================================


-- ---------------------------------------------------------------------------------------------------
-- W1 · koje se kolone str.facility razlikuju unutar grupe (broj grupa u kojima se kolona razlikuje)
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
    SELECT f.*
      FROM grupe g
      JOIN str.document d ON d.business_case_id = g.business_case_id
      JOIN str.facility f ON f.document_id = d.id AND f.system_uuid = g.system_uuid
     WHERE f.active AND coalesce(f.historical, false) = false),
kv AS (
    SELECT z.system_uuid, e.key, count(DISTINCT e.value) AS vrijednosti
      FROM zapisi z, jsonb_each(to_jsonb(z)) e
     GROUP BY z.system_uuid, e.key)
SELECT key                                              AS kolona,
       count(*) FILTER (WHERE vrijednosti > 1)          AS grupa_gdje_se_razlikuje,
       count(*)                                         AS grupa_ukupno
  FROM kv
 GROUP BY key
 ORDER BY 2 DESC, 1;


-- ---------------------------------------------------------------------------------------------------
-- W2 · razlikuju li se povezani podaci: puna adresa, kućni broj, kreveti, pomoćni kreveti, podvrsta,
--      kategorija, broj jedinica (facility_unit)
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
           a.full_address,
           coalesce(hn.name, a.house_number) AS kucni_broj,
           (SELECT sum(fc.quantity) FROM str.facility_capacity fc
              JOIN str.codebook_element ce ON ce.id = fc.type_id
             WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
               AND ce.code = 'CAT_BROJ_KREVETA')     AS kreveti,
           (SELECT sum(fc.quantity) FROM str.facility_capacity fc
              JOIN str.codebook_element ce ON ce.id = fc.type_id
             WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
               AND ce.code = 'CAT_BROJ_POM_KREVETA') AS pomocni,
           (SELECT max(x.sub_type_id) FROM str.facility_type x
             WHERE x.facility_id = f.id AND coalesce(x.active, true) = true) AS podvrsta,
           f.category_id                            AS kategorija,
           (SELECT count(*) FROM str.facility_unit fu WHERE fu.facility_id = f.id) AS jedinica
      FROM grupe g
      JOIN str.document d ON d.business_case_id = g.business_case_id
      JOIN str.facility f ON f.document_id = d.id AND f.system_uuid = g.system_uuid
      LEFT JOIN str.address a
             ON a.id = CASE WHEN f.same_address_subject = true
                            THEN (SELECT max(x.address_id) FROM str.subject_address x
                                   WHERE x.subject_version_id = f.subject_version_id
                                     AND coalesce(x.active, true) = true)
                            ELSE f.address_id END
      LEFT JOIN str.house_number hn ON hn.id = a.house_number_id
     WHERE f.active AND coalesce(f.historical, false) = false),
po_grupi AS (
    SELECT system_uuid,
           count(*)                                       AS zapisa,
           count(DISTINCT coalesce(full_address, ''))     AS adresa,
           count(DISTINCT coalesce(kucni_broj, ''))       AS kucnih_brojeva,
           count(DISTINCT coalesce(kreveti, -1))          AS kreveti,
           count(DISTINCT coalesce(pomocni, -1))          AS pomocni,
           count(DISTINCT podvrsta)                       AS podvrsta,
           count(DISTINCT kategorija)                     AS kategorija,
           sum(jedinica)                                  AS jedinica
      FROM zapisi
     GROUP BY system_uuid)
SELECT count(*)                                           AS grupa,
       sum(zapisa)                                        AS zapisa,
       count(*) FILTER (WHERE adresa > 1)                 AS razlicita_puna_adresa,
       count(*) FILTER (WHERE kucnih_brojeva > 1)         AS razlicit_kucni_broj,
       count(*) FILTER (WHERE kreveti > 1)                AS razliciti_kreveti,
       count(*) FILTER (WHERE pomocni > 1)                AS razliciti_pomocni,
       count(*) FILTER (WHERE podvrsta > 1)               AS razlicita_podvrsta,
       count(*) FILTER (WHERE kategorija > 1)             AS razlicita_kategorija,
       count(*) FILTER (WHERE jedinica > 0)               AS ima_facility_unit
  FROM po_grupi;


-- ---------------------------------------------------------------------------------------------------
-- W3 · dva primjera: samo kolone str.facility koje se unutar grupe razlikuju, po zapisu
--      (bez osobnih kolona: email, phone, pin, personal_document_number, web_address)
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
    HAVING count(*) BETWEEN 3 AND 8
     ORDER BY f.system_uuid
     LIMIT 2),
zapisi AS (
    SELECT f.*
      FROM grupe g
      JOIN str.document d ON d.business_case_id = g.business_case_id
      JOIN str.facility f ON f.document_id = d.id AND f.system_uuid = g.system_uuid
     WHERE f.active AND coalesce(f.historical, false) = false),
razlicite AS (
    SELECT z.system_uuid, e.key
      FROM zapisi z, jsonb_each(to_jsonb(z)) e
     WHERE e.key NOT IN ('email', 'phone', 'pin', 'personal_document_number', 'web_address')
     GROUP BY z.system_uuid, e.key
    HAVING count(DISTINCT e.value) > 1)
SELECT z.system_uuid, z.id, jsonb_object_agg(e.key, e.value) AS razlike
  FROM zapisi z
  CROSS JOIN LATERAL jsonb_each(to_jsonb(z)) e
  JOIN razlicite r ON r.system_uuid = z.system_uuid AND r.key = e.key
 GROUP BY z.system_uuid, z.id
 ORDER BY z.system_uuid, z.id;

-- kraj
