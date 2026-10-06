# eTurizam objekti (`str.facility`) — popis objekata iznajmljivača

Podloga za `GET /api/nias/facilities` i za provjeru objekta pri zahtjevu za RB
(`GET /api/nias/facilities/{id}`, `FacilityClaimVerifier`). Shema `str` **nema deklarirane FK-ove**, pa
su joinovi i pravila provjereni upitima nad bazom — skripte su u `docs/sql/m1-dijagnostika-*.sql`, a
nalazi niže su s **CDU test okoline** (`172.20.8.196/eturizam`, PostgreSQL 13), 6. 10. 2026., osim gdje
piše drukčije.

## Mapiranje

| Podatak | Izvor |
| :--- | :--- |
| Naziv | `str.facility.name` — često `-` ili ime iznajmljivača; kod novog eTurizma oznaka jedinice |
| Vrsta / podvrsta | `str.facility_type` (`facility_id`, `active`) → `str.codebook_element` po `type_id` i `sub_type_id` |
| Kategorija | `facility.category_id` → `codebook_element` (`C_3_ZVJEZDICE`) |
| Poslovni status | `facility.business_status_id` → `codebook_element` (`FBS_ACTIVE` / `FBS_INACTIVE`) |
| Adresa | `facility.address_id` → `str.address`, imena preko `str.settlement` / `street` / `house_number` / `municipality` / `county`; `facility.same_address_subject = true` → adresa subjekta predmeta (`business_case.subject_version_id` → `subject_address` → `address`) |
| Broj kreveta | `str.facility_capacity` (`active`) → `codebook_element.code = 'CAT_BROJ_KREVETA'` (pomoćni: `CAT_BROJ_POM_KREVETA`) |
| Broj kreveta (hoteli i sl.) | `facility_unit` → `facility_unit_capacity` → `CAT_BROJ_KREVETA` |
| Objekt | `facility.system_uuid` |
| Predmet i OIB | `facility.document_id → document.business_case_id → business_case.subject_version_id → subject.jips` |
| RB | `facility.registration_number` |

**`str.codebook_element` je zajednički šifrarnik** (id → code, name) za vrstu, podvrstu, kategoriju,
poslovni status, statuse predmeta i verifikacije te tipove kapaciteta. Kodovi su stabilni, ID-evi se
razlikuju među okolinama — vezati se isključivo na `code`.

## Model: jedinica i objekt

- **Zapis `str.facility` je smještajna jedinica** (soba, apartman, studio, kuća za odmor).
- **`system_uuid` je objekt.** Više jedinica istog objekta stoji u istom predmetu: na CDU ~5.900
  migriranih objekata ima 2–217 jedinica (ukupno ~43.700 zapisa). Novi eTurizam radi isto — dvije
  jedinice u istom predmetu s različitim nazivima i kapacitetima.
- **Registracijski broj ide po jedinici**: `accommodation.facility_id` i write-back
  (`writeBackRegistrationNumber`) su po `facility.id`.
- Migrirane jedinice nemaju vlastitu oznaku — svi stupci osim `id`, `address_id` i datuma su im isti
  (provjereno na 500 objekata), a kapacitet je po jedinici („Vila Tamaris" = 10 soba po 2 kreveta).
  Frontend ih zato prikazuje ispod objekta s rednim brojem; novi eTurizam jedinicama daje naziv
  (`facility.name`), pa se tada prikazuje naziv.

Ranije se deduplikiralo po `system_uuid` (vidio se jedan zapis po objektu). To je skrivalo jedinice:
iznajmljivač s najviše migriranih jedinica na CDU vidio je 51 redak od 3.741 jedinice. Vjerojatni
uzrok primjedbi Z-16 („nije se prikazao iako ima isti UUID") i W-8 (prikazana jedinica „2" umjesto „1").

## Koje jedinice su aktualne — pravila eTurizma

Pravila su prepisana **1:1 iz `f_active` CTE-a eTurizmova viewa `str.vw_src_facility_actual`** (DDL
koji je poslao Simon, eTurizam, 4. 10. 2026.). Sam view se ne čita:

1. agregira cijeli registar — **1,2 s po pozivu** bez obzira na OIB (filtar po `f_id` se ne spušta);
2. nema OIB vlasnika;
3. ne zna za migrirane objekte (v. niže).

Vjernost kopije provjerena je na CDU: 1.122 verificirana zapisa, **0 razlika u oba smjera**
(`m1-dijagnostika.sql`, Q3).

Zajednički uvjeti (kao u viewu): `facility.active`; `historical` NULL ili `false`; aktivan dokument
čija je vrsta (`sif_podvrsta_dokumenta → sif_vrsta_dokumenata`) `DOT_RJESENJE` ili
`DOT_POTVRDA_O_UPISU`; aktivan predmet s organizacijskom jedinicom; ako je predmet izvor verifikacije,
ona je `BCVS_U_IZRADI`; ako je cilj verifikacije, ona je `BCVS_ZAVRSENA`;
`HAVING count(system_uuid) = 1` (bez `system_uuid` ili s više redaka verifikacije zapis ispada).

| Skup | Uvjet | CDU |
| :--- | :--- | ---: |
| **Verificiran** | `created_by <> 'optimit'` + zajednički uvjeti + „predmet gotov": status predmeta `BCST_RJES_IZVRSNO` i `execution_date` u prošlosti | 1.122 |
| **Neverificiran** | `created_by = 'optimit'` (migracija iz starog sustava, siječanj 2023.) + zajednički uvjeti, **bez** „predmet gotov" | 129.350 |

**Zašto neverificirani nemaju uvjet „predmet gotov".** Migrirani predmeti nemaju ni status (237.140
od 237.140) ni datum izvršnosti (238.682 od 238.682). Doslovna inverzija viewa koju je predložio
eTurizam (`created_by = 'optimit'` + target verifikacije nije završen) zato daje **0 neverificiranih**
— testni iznajmljivač 06756460531 ostao bi bez ijednog od svojih 38 objekata. Na CDU podacima obje
varijante uvjeta verifikacije daju isti skup (migrirani predmet je u `business_case_verification`
uvijek izvor, nikad cilj; nakon završene verifikacije stari predmet je neaktivan).
**Čeka potvrdu eTurizma.**

`created_by IS NULL` ne ulazi ni u jedan skup (kao u viewu: i `<>` i `=` daju NULL).

## Najnoviji predmet po objektu

Objekt može biti aktualan u više predmeta:

- rješenje pa promjena podataka / ukidanje — view oba vodi kao aktualna (22 objekta na CDU);
- migracija je isti objekt upisala u više predmeta, a verifikacija ugasi samo jedan — kopije u
  ostalim predmetima ostanu aktivne (26 zapisa na CDU).

Vrijede **samo jedinice najnovijeg predmeta**: predmet s najkasnijim `facility.created_date`, kod
jednakosti veći `business_case.id`. Rang se računa nad **svim** aktualnim zapisima objekta, i tuđima,
**prije** filtara vlasnika, statusa i vrste — noviji odjavljeni ili na drugog vlasnika preneseni
predmet skriva stariji. Na CDU pravilo skriva 7.486 zapisa (1.719 objekata), a **nijedan verificirani
zapis nije skriven iza migriranog** (verificirani iz 2022. bi po datumu mogli biti stariji od migracije).

## Vlasnik, filtri i adresa

- **Vlasnik je subjekt predmeta**: `business_case.subject_version_id → subject_version → subject.jips`,
  kao u viewu. Preko `facility.subject_version_id` 43 od 1.129 aktualnih zapisa na CDU nemaju
  vlasnika. **Čeka potvrdu eTurizma.**
- `subject.active` se **ne** filtrira — jedan OIB ima više `subject` redaka, identitet nosi `jips`.
- **Prikazuju se samo jedinice s poslovnim statusom `FBS_ACTIVE`** (W-5). `facility.active` je
  zastavica verzije zapisa, ne podatak o tome posluje li objekt — na CDU je 103.033 zapisa smještaja s
  `active = true` „Odjavljeno". Jedinica bez statusa se ne prikazuje.
- **Vrsta**: samo `FS_SOBA`, `FS_APARTMAN`, `FS_STUDIO_APARTMAN`, `FS_KUCA_ZA_ODMOR` — po šifrarniku
  `str_rn.accommodation_type.code`.
- **Adresa subjekta** (`same_address_subject = true`) čita se preko subjekta predmeta, kao u viewu.
- `coalesce(active, true)` na `facility_type`, kapacitetima i `subject_address` — eTurizam
  `facility_type.active` ne filtrira, pa NULL znači „aktivno".

Ista pravila vrijede za **claim i `FacilityClaimVerifier`**: `findOwnership` vraća `current`, a
`FacilityClaimVerifier.isActive` traži `active`, `FBS_ACTIVE` **i** `current`. Jedinica koja se ne vidi
na popisu (stara verzija, predmet u obradi, migrirana kopija koju je zamijenio noviji predmet) ne može
proći ni kroz tuStart handoff (400 `error.facility.inactive`).

## Redoslijed i paginacija

Redoslijed: **verificirani objekti, neverificirani, pa privremena rješenja** (`str_rn.categorization_decision`
bez RB-a i bez `facility_id`). Paginacija broji **objekte**: stranica nosi najviše `size` objekata sa
svim njihovim jedinicama (najveći objekt na CDU ima 217). `total` je broj objekata, `totalUnits` broj
jedinica. Svaki redak eTurizma nosi ukupno; kad je stranica prazna, ukupno daje `countListingByOib`.

## Oblik upita i brzina

Upit je **ugniježđen u podupite, bez `WITH`**:

- „najnoviji predmet" je prozorska funkcija nad jednim skupom — oblik sa samospajanjem CTE-ova
  planer je zbog procjene od 1 retka slagao ugniježđenom petljom: **55,9 s** za 3.741 jedinicu;
- H2 (testovi) krivo izvršava parametar u CTE-u na koji se nastavlja drugi CTE — vrati prazno.

Izmjereno na CDU (oblik s CTE-ovima i istim prozorskim funkcijama, `m1-dijagnostika-8.sql`): 3.741
jedinica **75 ms**, 215 jedinica **25 ms**, isto s generičkim planom (pgjdbc nakon 5. izvršavanja).
**Konačni oblik iz aplikacije ponovo izmjeriti** skriptom `m1-dijagnostika-9.sql` (tekst upita je
izvučen iz kompiliranih `@Query` anotacija).

## Što u podacima ne postoji

- **Broj gostiju za domaćinstva.** `CAT_BROJ_GOSTIJU` postoji samo u `facility_unit_capacity`, a sobe
  i apartmani u domaćinstvu nemaju `facility_unit` redaka. Iz eTurizma se dobije samo broj kreveta.
- **Legacy registracijski brojevi.** `facility.registration_number` je na CDU popunjen u 4 zapisa —
  kolona je odredište write-backa iz STR-a (v. `docs/TUSTART-INTEGRACIJA.md` §6).
- **Strukturirana adresa u `str.address`.** Upotrebljivi su `full_address`, `settlement` i ID-evi prema
  hijerarhiji — zato se imena razrješavaju joinovima, a denormalizirane kolone su samo fallback.
- **Oznaka migrirane jedinice.** Ne postoji ni u jednoj koloni zapisa ni u povezanim tablicama.

## Preduvjeti na okolini (provjeriti prije testiranja na CDU / preprod)

Popis čita tablice u shemi `str` koje DB korisnik mora smjeti čitati. Ako fali `SELECT` na bilo
kojoj, endpoint vraća 500 (namjerno se ne guta — prazna lista bi sakrila konfiguracijski problem).
Na CDU (`shorttermrental`) sva prava postoje (`m1-dijagnostika.sql`, Q0).

```sql
-- sve mora vratiti red; greška = nema GRANT-a
SELECT 'facility' t, count(*) FROM str.facility WHERE false
UNION ALL SELECT 'facility_type',              count(*) FROM str.facility_type WHERE false
UNION ALL SELECT 'facility_capacity',          count(*) FROM str.facility_capacity WHERE false
UNION ALL SELECT 'facility_unit',              count(*) FROM str.facility_unit WHERE false
UNION ALL SELECT 'facility_unit_capacity',     count(*) FROM str.facility_unit_capacity WHERE false
UNION ALL SELECT 'codebook_element',           count(*) FROM str.codebook_element WHERE false
UNION ALL SELECT 'document',                   count(*) FROM str.document WHERE false
UNION ALL SELECT 'business_case',              count(*) FROM str.business_case WHERE false
UNION ALL SELECT 'business_case_verification', count(*) FROM str.business_case_verification WHERE false
UNION ALL SELECT 'sif_podvrsta_dokumenta',     count(*) FROM str.sif_podvrsta_dokumenta WHERE false
UNION ALL SELECT 'sif_vrsta_dokumenata',       count(*) FROM str.sif_vrsta_dokumenata WHERE false
UNION ALL SELECT 'organizational_unit',        count(*) FROM str.organizational_unit WHERE false
UNION ALL SELECT 'address',                    count(*) FROM str.address WHERE false
UNION ALL SELECT 'subject_address',            count(*) FROM str.subject_address WHERE false
UNION ALL SELECT 'county',                     count(*) FROM str.county WHERE false
UNION ALL SELECT 'municipality',               count(*) FROM str.municipality WHERE false
UNION ALL SELECT 'settlement',                 count(*) FROM str.settlement WHERE false
UNION ALL SELECT 'street',                     count(*) FROM str.street WHERE false
UNION ALL SELECT 'house_number',               count(*) FROM str.house_number WHERE false;
```

Shema mora biti **str2** (`document.subtype_code` + `sif_podvrsta_dokumenta`); stara shema (str1,
`document.document_subtype_id`) nije podržana — `m1-dijagnostika.sql` Q0 to provjerava.

Drugi preduvjet je **naš** šifrarnik: filtar podvrsta radi na `str_rn.accommodation_type.code`, koji
changeset 060 popunjava **po nazivu vrste** (`LOWER(name) = 'soba'` itd.). Ako se nazivi na okolini
razlikuju, `code` ostane NULL i dashboard je prazan za sve korisnike. Provjera:

```sql
SELECT type_id, name, code FROM str_rn.accommodation_type ORDER BY type_id;
-- FS_SOBA, FS_APARTMAN, FS_STUDIO_APARTMAN i FS_KUCA_ZA_ODMOR moraju biti popunjeni
```

Aplikacija taj slučaj logira kao WARN (`accommodation_type nema ni jednu FS_* šifru`), pa se u
logovima prepoznaje bez pogađanja.

## Testni podaci (CDU)

| OIB | Objekata / jedinica | Napomena |
| :--- | ---: | :--- |
| `06756460531` | 38 / 38 | svi migrirani (neverificirani) |
| `12312312316` | 213 / 215 | svi verificirani; ranije 207 redaka |
| `98765432106` | 23 / 23 | svi verificirani; ranije 20 redaka |
| (najveći migrirani) | 51 / 3.741 | do 217 jedinica u objektu; ranije 51 redak |

Objekt iz W-8 (`12bcff39-74e9-4696-b5f0-4444216ee8e9`) na CDU ne postoji — tražiti ga na ostalim
okolinama (`m1-dijagnostika-3.sql`, U1).

## Lokalni mock

Changeset `123-str-facility-lookup-mock-local.xml` stvara šifrarnik, dokumente, vrste, kapacitete,
adrese i objekte mock OIB-a `99999999990` (`nias.mock.fixed-oib`). Changeset
`131-str-facility-actual-rules-local.xml` (`context="local"`) dodaje predmete, verifikaciju i
šifrarnik vrsta dokumenta te slučajeve prikaza:

| Zapisi | Slučaj | Prikaz |
| :--- | :--- | :--- |
| 200–203 | verificirani, svaki svoj predmet (201, 203 s RB-om) | 4 objekta |
| 204 | pizzeria | izbačen filtrom vrste |
| 205 | odjavljen | izbačen |
| 206 / 207 | isti objekt u dva predmeta | samo 207 (noviji predmet) |
| 210–213 | migrirani objekt „Vila Mare" s 4 jedinice | 1 objekt, 4 jedinice, „Nije verificiran" |
| 214 | migrirana soba | 1 objekt, „Nije verificiran" |
| 215 | migrirana kopija objekta 203, starija | skrivena |

Mock objekti ostalih lokalnih subjekata (changeseti 112/115/116) nemaju predmet, pa se na popisu više
ne prikazuju.
