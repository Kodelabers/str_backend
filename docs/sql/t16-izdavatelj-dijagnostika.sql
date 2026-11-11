SELECT 'I1' AS blok, c.table_name, c.ordinal_position, c.column_name, c.data_type FROM information_schema.columns c WHERE c.table_schema = 'str' AND c.table_name IN ('internal_organization', 'organizational_unit') ORDER BY c.table_name, c.ordinal_position;

SELECT 'I2' AS blok, row_to_json(io)::text AS organizacija FROM str.internal_organization io JOIN str.codebook_element ce ON ce.id = io.type_id WHERE ce.code = 'IOT_ZUPANIJA' ORDER BY io.id;

SELECT 'I3' AS blok, io.name AS organizacija, row_to_json(ou)::text AS jedinica FROM str.organizational_unit ou JOIN str.internal_organization io ON io.id = ou.internal_organization_id JOIN str.codebook_element ce ON ce.id = io.type_id WHERE ce.code = 'IOT_ZUPANIJA' AND ou.active = TRUE ORDER BY io.id, ou.id;
