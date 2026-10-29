SELECT 'X1' AS blok, q.* FROM (
SELECT a.county AS accommodation_county, count(*) AS accommodations, count(r.rn) AS rbs
FROM str_rn.accommodation a
LEFT JOIN str_rn.registration_number r ON r.accommodation_id = a.accommodation_id
WHERE NOT EXISTS (
SELECT 1 FROM rpj_dgu.zupanije z
WHERE regexp_replace(lower(btrim(z.zu_ime)), ' ' || chr(382) || 'upanija$', '') = regexp_replace(regexp_replace(lower(btrim(coalesce(a.county, ''))), '\s+', ' ', 'g'), ' ' || chr(382) || 'upanija$', '')
)
GROUP BY a.county
ORDER BY count(*) DESC
) q;

SELECT 'X2' AS blok, q.* FROM (
SELECT count(*) AS accommodations_total, count(*) FILTER (WHERE a.county IS NULL OR btrim(a.county) = '') AS without_county
FROM str_rn.accommodation a
) q;
