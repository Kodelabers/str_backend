SELECT 'X1' AS blok, q.* FROM (
SELECT tablename, indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'str'
   AND tablename IN ('facility_capacity', 'facility_content', 'facility_content_capacity', 'facility_unit', 'facility_unit_capacity')
 ORDER BY tablename, indexname
) q;
