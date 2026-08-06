# eTurizam objekti (`str.facility`) — mapiranje za popis objekata iznajmljivača

Podloga za `GET /api/nias/facilities`. Sve niže je provjereno queryjima nad dev bazom
(`s-str-02.infodom.hr:5431/str2`), jer shema `str` **nema deklarirane FK-ove** pa se joinovi ne mogu
izvesti iz metapodataka.

## Mapiranje

| Podatak | Izvor |
| :--- | :--- |
| Naziv | `str.facility.name` — često `-` ili ime iznajmljivača, ne naziv objekta |
| Vrsta / podvrsta | `str.facility_type` (`facility_id`, `active`) → `str.codebook_element` po `type_id` i `sub_type_id` |
| Kategorija | `facility.category_id` → `codebook_element` (`C_3_ZVJEZDICE`) |
| Poslovni status | `facility.business_status_id` → `codebook_element` (`FBS_ACTIVE` / `FBS_INACTIVE`) |
| Adresa | `facility.address_id` → `str.address`, imena preko `str.settlement` / `street` / `house_number` / `municipality` / `county`; `facility.same_address_subject = true` → adresa iznajmljivača (`subject_address` → `address`) |
| Broj kreveta | `str.facility_capacity` (`active`) → `codebook_element.code = 'CAT_BROJ_KREVETA'` (pomoćni: `CAT_BROJ_POM_KREVETA`) |
| Broj kreveta (hoteli i sl.) | `facility_unit` → `facility_unit_capacity` → `CAT_BROJ_KREVETA` |
| OIB | `facility.subject_version_id` → `subject_version` → `subject.jips` |
| RB | `facility.registration_number` |

**`str.codebook_element` je zajednički šifrarnik** (id → code, name) za vrstu, podvrstu, kategoriju,
poslovni status i tipove kapaciteta. Kodovi su stabilni, ID-evi se razlikuju među okolinama — vezati
se isključivo na `code`.

Podvrste privatnog smještaja su **`FS_SOBA`, `FS_APARTMAN`, `FS_STUDIO_APARTMAN`, `FS_KUCA_ZA_ODMOR`**
— identične `str_rn.accommodation_type.code` (changeset 060), pa je mapiranje vrste 1:1 i usporedba
direktna. Iznajmljivač u eTurizmu može imati i `FT_RESTORAN`, `FT_TUR_AGENCIJA`, `FT_TUR_VODIC` i
slično; popis filtrira po šifrarniku iz `accommodation_type`.

## Što u podacima ne postoji

- **Broj gostiju za domaćinstva.** `CAT_BROJ_GOSTIJU` postoji samo u `facility_unit_capacity`, a sobe
  i apartmani u domaćinstvu nemaju `facility_unit` redaka. Iz eTurizma se dobije samo broj kreveta.
- **Legacy registracijski brojevi.** `facility.registration_number` je neprazan u **0 od 245.044**
  redaka, `facility_unit.registration_number` u 0 od 43.984. Kolona je isključivo odredište
  write-backa iz STR-a (v. `docs/TUSTART-INTEGRACIJA.md` §6), pa dok STR ne izda prvi RB nijedan
  objekt na dev-u nije „s RB-om".
- **Strukturirana adresa u `str.address`.** Od 285.874 adresa samo **1** ima ispunjenu `county`, 1544
  `municipality`, 217 `street`, 27 `postal_code`. Upotrebljivi su `full_address` (98,3 %),
  `settlement` (97,7 %) i ID-evi prema hijerarhiji — zato se imena razrješavaju joinovima, a
  denormalizirane kolone su samo fallback.

## Zašto se `str.vw_src_facility_actual` ne koristi

View izgleda kao gotovo rješenje (nosi `f_subtype_code`, razriješenu adresu, kapacitete), ali:

1. Njegov `f_active` CTE ima `facility.created_by <> 'optimit'` — **izbacuje sve migrirane objekte**.
   Pokriva 1124 od 245.044 objekta; za stvarnog iznajmljivača (38 objekata) join na view vraća **0
   redaka**.
2. Izvršava se **1,2 s**: `GROUP BY` + `string_agg` nad cijelim registrom, s external merge sortom od
   22 MB na disk. Predikat po OIB-u se ne može propagirati u agregaciju.
3. Ima bug: `f_unit_capacity_type` joina `codebook_element` po `fu.type_id` (tip jedinice) umjesto
   `fuc.type_id`, pa vraća `FU_*` kodove tamo gdje bi trebali biti `CAT_*`.

Koristi se **samo njegova join-mapa**; query je vlastiti (`StrFacilityRepository.findListingByOib`).

## Dedup i filtriranje

Dedup je po `coalesce(system_uuid, document.business_case_id)`, kako je eTurizam predložio — jedan
objekt ima više `facility` redaka kroz povijest, a `system_uuid` je stabilan identitet (predlošci u
radu ga nemaju, pa se grupiraju po predmetu).

- **Dedup ide unutar redaka samog OIB-a.** Globalni dedup (join po izračunatom bucketu) tjera parallel
  seq scan cijelog `facility` + `document` → 102 ms po pozivu neovisno o iznajmljivaču. Ovako:
  **20 ms za 38 objekata, 33 ms za 1530** (→ 1150 nakon dedupa), sve indeksnim putem.
- **`NOT EXISTS` na `system_uuid`** vraća korektnost koju bi globalni dedup dao: objekt prenesen na
  drugog vlasnika ne visi na starom. Kod OIB-a 12312312316 izbaci 16 nadjačanih zapisa.
- **`historical` se ne filtrira.** 74.177 redaka s `historical = true` preživi dedup, a najveća
  skupina (85.596) ima `historical` NULL — filtriranje bi izbrisalo pola registra. Što točno
  `historical` znači, otvoreno je pitanje za eTurizam.
- **`facility.active` filtrira se nakon dedupa.** Unutar dedupa bi objekt čiji je najnoviji zapis
  neaktivan „oživio" kroz stariji aktivni red.
- **`subject.active` se NE filtrira.** Objekt vodi na točno jednu verziju subjekta, pa filtar ne može
  spriječiti multiplikaciju — može samo sakriti objekt čiji je zapis subjekta nadjačan novijim
  (jedan OIB ima više `subject` redaka; unique indeks je `(jips, jips_source_id, subtype_id) WHERE
  active`). Identitet nosi `jips`. U `findOwnership` bi taj filtar bio gori od skrivanja: legitiman
  handoff iz tuStarta bio bi odbijen kao „objekt ne postoji".
- **Bucket ima treću granu: `'facility-' || f.id`.** Objekt bez `system_uuid` **i** bez dokumenta
  inače pada u `PARTITION BY NULL`, gdje se svi takvi objekti jednog iznajmljivača skupe u jednu
  grupu i prikaže se samo najnoviji.
- **`coalesce(active, true)` na child tablicama** (`facility_type`, `facility_capacity`,
  `facility_unit(_capacity)`, `subject_address`). eTurizam u svom viewu `facility_type.active` uopće
  ne filtrira, dakle NULL je moguć; uz `active = true` objekt bi ostao bez vrste, a bez vrste ga
  izbaci filtar podvrsta — objekt bi nestao s dashboarda.
- Skalarni podqueryji u `JOIN` uvjetima (`facility_type`, `subject_address`) drže rezultat na jednom
  redu po objektu, pa su `LIMIT`/`OFFSET` i `count` točni.

Nedostatak koji ostaje: objekti bez `system_uuid` grupiraju se po `document.business_case_id`, a
jedan predmet može pokrivati **više** objekata (u stvarnim podacima sedam `facility` redaka dijeli
`document_id = 99148`). Za takve bi se prikazao samo najnoviji. Pravilo je eTurizamovo i odnosi se na
objekte „u radu / predloške", pa je vezano na otvoreno pitanje prikazuju li se oni uopće.

## Preduvjeti na okolini (provjeriti prije testiranja na CDU)

Popis čita **dvanaest** tablica u shemi `str` koje ovaj servis dosad nije dirao. Ako DB korisniku
fali `SELECT` na bilo kojoj, endpoint vraća 500 (namjerno se ne guta — prazna lista bi sakrila
konfiguracijski problem):

```sql
-- sve mora vratiti red; greška = nema GRANT-a
SELECT 'facility' t, count(*) FROM str.facility WHERE false
UNION ALL SELECT 'facility_type',          count(*) FROM str.facility_type WHERE false
UNION ALL SELECT 'facility_capacity',      count(*) FROM str.facility_capacity WHERE false
UNION ALL SELECT 'facility_unit',          count(*) FROM str.facility_unit WHERE false
UNION ALL SELECT 'facility_unit_capacity', count(*) FROM str.facility_unit_capacity WHERE false
UNION ALL SELECT 'codebook_element',       count(*) FROM str.codebook_element WHERE false
UNION ALL SELECT 'document',               count(*) FROM str.document WHERE false
UNION ALL SELECT 'address',                count(*) FROM str.address WHERE false
UNION ALL SELECT 'county',                 count(*) FROM str.county WHERE false
UNION ALL SELECT 'municipality',           count(*) FROM str.municipality WHERE false
UNION ALL SELECT 'settlement',             count(*) FROM str.settlement WHERE false
UNION ALL SELECT 'street',                 count(*) FROM str.street WHERE false
UNION ALL SELECT 'house_number',           count(*) FROM str.house_number WHERE false;
```

Drugi preduvjet je **naš** šifrarnik: filtar podvrsta radi na `str_rn.accommodation_type.code`, koji
changeset 060 popunjava **po nazivu vrste** (`LOWER(name) = 'soba'` itd.). Ako se nazivi na okolini
razlikuju, `code` ostane NULL i dashboard je prazan za sve korisnike. Provjera:

```sql
SELECT type_id, name, code FROM str_rn.accommodation_type ORDER BY type_id;
-- FS_SOBA, FS_APARTMAN, FS_STUDIO_APARTMAN i FS_KUCA_ZA_ODMOR moraju biti popunjeni
```

Aplikacija taj slučaj logira kao WARN (`accommodation_type nema ni jednu FS_* šifru`), pa se u
logovima prepoznaje bez pogađanja.

**Paginacija je obavezna** — testni iznajmljivač ima 1530 objekata.

## Testni podaci (dev)

| OIB | Objekata | Napomena |
| :--- | ---: | :--- |
| `06756460531` | 38 | Tonći Beroš, sve `FS_SOBA` u Makarskoj, 2–3 kreveta |
| `12312312316` | 1530 | Pero Perić — za provjeru paginacije; ima dva `subject` reda |
| `98765432106` | 262 | Marko Markić |

Nijedan nema RB, pa se scenarij „ima RB → Prikaži" na dev-u može testirati samo nakon što STR izda
prvi RB za taj objekt.

## Lokalni mock

Changeset `123-str-facility-lookup-mock-local.xml` (`context="local"`) stvara `codebook_element`,
`document`, `facility_type`, `facility_capacity`, `facility_unit(_capacity)`, `address` i adresnu
hijerarhiju, te seeda iznajmljivača za mock OIB `99999999990` (`nias.mock.fixed-oib`) s objektima
koji pokrivaju sve slučajeve prikaza: s RB-om i bez, neaktivan, restoran koji filter izbacuje i par
zapisa s istim `system_uuid` za dedup.
