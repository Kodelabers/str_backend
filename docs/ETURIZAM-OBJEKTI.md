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
| Broj kreveta | `str.facility_capacity` (`active`) → `CAT_BROJ_KREVETA` (sobe, studio apartmani); inače Σ `facility_content_capacity.CAT_BROJ_KREVETA` × `facility_content.quantity`, oba `active` (apartmani, kuće za odmor); v. § B-3 i `StrFacilityRepository.KREVETI_JEDINICE` |
| Pomoćni kreveti | `str.facility_capacity` (`active`) → `CAT_BROJ_POM_KREVETA` |
| Broj kreveta (hoteli i sl.) | `facility_unit` → `facility_unit_capacity` → `CAT_BROJ_KREVETA` (zadnja rezerva) |
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
  (provjereno na 500 objekata). **Kapacitet migrirane jedinice je kapacitet cijelog objekta** —
  migracija ga je kopirala na svaku jedinicu (B-3), pa se prikazuje samo na objektu. Frontend
  migrirane jedinice prikazuje ispod objekta s rednim brojem; novi eTurizam jedinicama daje naziv
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
- **Strukturirana adresa migriranih objekata.** Migrirani zapisi nemaju ulicu ni kućni broj, nego
  samo `full_address` u obliku „Ulica 12" (bez naselja). Verificirani imaju ulicu i kućni broj preko
  ID-eva hijerarhije, a `full_address` im je često prazna (B-3). Imena se razrješavaju joinovima, a
  denormalizirane kolone su samo fallback.
- **Kapacitet pojedine migrirane jedinice** u objektu s više jedinica (B-3, P-22).
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

Changeset `132-str-facility-capacity-b3-local.xml` (`context="local"`, B-3) dodaje tablice
`facility_content` i `facility_content_capacity` te:

| Zapisi | Slučaj | Prikaz |
| :--- | :--- | :--- |
| 220 | „1" — objekt iz W-8: apartman, 2 dvokrevetne sobe + 2 pomoćna, Ulica Vile Velebita 4, Nin | „Ulica Vile Velebita 4, 23232 Nin", „4 kreveta + 2 pomoćna", broj gostiju 6 (zaključan) |
| 221 | „Vila Lucija": kuća za odmor, 10 trokrevetnih soba (+ jedna neaktivna verzija sadržaja), prazna `full_address`, ulica i kućni broj popunjeni | „Blato 3, 21420 Bol", „30 kreveta + 2 pomoćna", broj gostiju 32 |
| 210–213 | „Vila Mare": svaka jedinica dobiva retke cijelog objekta (2, 2, 2, 3) | kapacitet 9 samo na objektu; jedinice bez kapaciteta, broj gostiju nije zaključan |

Mock objekti ostalih lokalnih subjekata (changeseti 112/115/116) nemaju predmet, pa se na popisu više
ne prikazuju.

## B-3 · Adresa i kapacitet u popisu objekata

Povod: Z-18, Z-19 i W-8. Ministarstvo javlja da adresa i kapacitet u tablici „Vaši smještajni objekti
u sustavu” ne odgovaraju TuRegistru. Iste vrijednosti predpopunjavaju i zaključavaju formu (claim) i
dopunjuju se uz izdani registracijski broj (`completeFrom`).

Skripte su `docs/sql/b3-dijagnostika.sql`, `-2`, `-3`, `-4` i `-w8`. Rezultati s **CDU testa**
(6. 10. 2026.) su u `docs/sql/results/sql_1`, `sql_2`, `sql_3` i `sql_w8`. Skup je ono što popis može
prikazati, bez filtra OIB-a: 423 verificirane jedinice (421 objekt) i 119.252 neverificirane jedinice
(81.441 objekt).

**Objekt iz W-8 na CDU ne postoji** (`sql_w8`, B1-0 = 0). Kućni broj 6 i oznaka „2” zato još nisu
dokazani; nalaz niže vrijedi za CDU.

### Hipoteze

| | Hipoteza | Nalaz na CDU | Odluka |
| :--- | :--- | :--- | :--- |
| H1 | `same_address_subject = true` → prikazuje se adresa vlasnika | Nijedna jedinica na popisu nema `true`. Verificirane: 408 NULL, 15 `false`. Neverificirane: sve `false`. | **Odbačeno** |
| H2 | Više adresa subjekta, `max(address_id)` bira krivu | Bez H1 se pravilo ne primjenjuje | **Odbačeno** |
| H3 | Jedinice istog objekta imaju različite adrese | 5.879 objekata s više jedinica: svaka jedinica ima svoj `address_id`, ali je sadržaj adrese (puna adresa, kućni broj, naselje) u **svih** jednak | **Odbačeno** |
| H4 | Kapacitet: `active` NULL/false, više redaka, dva izvora, popis ≠ claim | Popis i claim na CDU se ne razlikuju (nema `active` NULL). Nađena su **dva druga problema**, v. „Kapacitet” | **Potvrđeno u drugom obliku** |
| H5 | Oznaka jedinice nije `facility.name` | Verificirani objekti s više jedinica (2) imaju jedinstvene nazive (`facility.name`). Migrirani nemaju oznaku. `facility_unit` je prazan, a `parent_facility_id` nema nijedna jedinica s popisa (u registru 1.732 zapisa) | **Odbačeno**: oznaka je `facility.name` |
| H6 | `full_address` nije usklađen s ostalim stupcima | `full_address` nije puna adresa ni kod verificiranih ni kod migriranih, v. „Adresa” | **Potvrđeno** (uzrok je u prikazu) |
| H7 | Očekuje se kapacitet po jedinici i maksimalan broj gostiju | Nije podatkovno pitanje | **Otvoreno** (pitanje ministarstvu) |

### Adresa: uzrok je prikaz, ne upit

`PostojeciObjektiTable` prikazuje `full_address`, a kad je ona prazna, „naselje, općina”. Ulicu i
kućni broj nikad ne slaže, jer polazi od toga da su ta polja gotovo uvijek prazna. To vrijedi za
migrirane, ali ne i za verificirane objekte (`sql_2`, A1a, A1b, A2, A2b):

| Skupina | Jedinica | Prikaz danas | Što postoji u bazi |
| :--- | ---: | :--- | :--- |
| Verificirane, `full_address` popunjena | 137 | puna adresa („Bol, Blato 3, 21420 BOL”) | i ulica (135) i kućni broj (133) |
| **Verificirane, `full_address` prazna** | **247** | **„Split, SPLIT”** | ulica 234, kućni broj 231, poštanski broj 246. Naselje = općina u 211 |
| Verificirane, prazan redak adrese | 24 | „-” | ništa. Subjekt predmeta ima adresu u 21 |
| Verificirane bez `address_id` (`same_address_subject` false/NULL) | 15 | „-” | ništa. Subjekt ima adresu u 12 |
| **Migrirane** | **119.252** | `full_address` + županija | `full_address` je samo „ulica kbr” („Kurmanova 12”, „RADINI 9”): 102.471 ne sadrži naselje, 128 sadrži poštanski broj. Strukturirane ulice i kućnog broja nema ni u jednoj; naselje, poštanski broj i županija postoje odvojeno |

Usporedba s viewom (`sql_1`, S1/S2): kad view ima adresu, naša `full_address` i kućni broj su jednaki
u 100 % jedinica. Upit, dakle, čita ispravan zapis; pogrešno je samo ono što se od njega prikazuje.

Ista ograničenja vrijede i za formu: migrirani objekt nema strukturiranu ulicu ni kućni broj. Ta polja
zato nisu zaključana i ne upisuju se uz registracijski broj, osim ako ih korisnik upiše sam.

### Kapacitet: dva odvojena problema

**1. Migrirani objekt s više jedinica: svaka jedinica nosi kapacitet cijelog objekta** (`sql_3`, R1,
R1b, R1c).

- Kod svih 22.121 jedinice objekata s više jedinica koje imaju retke kreveta broj aktivnih redaka
  `CAT_BROJ_KREVETA` jednak je broju jedinica objekta. Isto vrijedi i za pomoćne krevete.
- U svih 5.877 takvih objekata **sve jedinice imaju isti skup redaka**. Primjer: objekt s 3 jedinice
  ima na svakoj jedinici retke 2, 3 i 4 kreveta.
- Objekti s jednom jedinicom imaju 0 ili 1 redak (0 ih ima više).

Aplikacija retke zbraja. Jedinica zato prikazuje kapacitet **cijelog objekta** (2 + 3 + 4 = 9). Redak
objekta u tablici zbraja jedinice, pa prikazuje N puta više (27). Isti zbroj ide u claim i u
`maxGuests`, pa se broj gostiju cijelog objekta zaključava na svakoj jedinici i upisuje uz njezin
registracijski broj.

Koji redak pripada kojoj jedinici, iz podataka se ne vidi. Svi redovi su stvoreni istog dana, a
redoslijed `facility_capacity.id` unutar jedinice ne mora odgovarati redoslijedu `facility.id`
jedinica. To je pitanje za eTurizam.

**2. Verificirani apartmani i kuće za odmor drže krevete u kapacitetu sadržaja** (`sql_3`, R2, R2b,
R2c).

- `facility_capacity.CAT_BROJ_KREVETA` imaju samo sobe i studio apartmani.
- Nijedan verificirani `FS_APARTMAN` ni `FS_KUCA_ZA_ODMOR` (252 jedinice na CDU) nema retka kreveta u
  `facility_capacity`.
- Njihovi kreveti su u `facility_content` (spavaće sobe: `CT_DVO_SOBA`, `CT_JEDNO_SOBA`,
  `CT_TRO_SOBA`) → `facility_content_capacity.CAT_BROJ_KREVETA`: 252 jedinice, 384 retka, 754 kreveta.

Popis i claim taj izvor ne čitaju, pa je kapacitet „-”, a polje broja gostiju nije zaključano.
Pomoćni kreveti su i za apartmane u `facility_capacity`. Pravila `active` i zbrajanja za kapacitet
sadržaja provjerava `b3-dijagnostika-4.sql`.

### View nije referenca za kapacitet

Živa definicija `vw_src_facility_actual` na CDU (`sql_2`, C0a) odgovara DDL-u. Za kapacitet se ipak ne
može koristiti kao mjerilo:

- `facility_capacity` spaja bez filtra `active`. Svaka izmjena u eTurizmu ostavlja neaktivni redak, pa
  view zbraja povijest. Jedinica 243335 ima 11 neaktivnih redaka i 1 aktivan redak pomoćnih kreveta, a
  view daje 12 (`sql_2`, K3).
- Tip jediničnog kapaciteta spaja na `fu.type_id` umjesto na `fuc.type_id`.
- `facility_content_capacity` uopće ne čita.

Pravilo `active = true` koje aplikacija koristi je ispravno.

### Otvoreno

- W-8: `b3-dijagnostika-w8.sql` na dev, preprodu i CDU preprodu.
- Kapacitet sadržaja verificiranih objekata (`active`, verzije, zbroj): `b3-dijagnostika-4.sql`.
- eTurizam: koji redak kapaciteta migriranog objekta pripada kojoj jedinici; prikazuje li TuRegistar
  adresu subjekta kad je `same_address_subject` NULL, a adresa objekta prazna (39 verificiranih
  jedinica na CDU).
- Ministarstvo (H7, W-7, W-10): prikazuje li se kapacitet po jedinici i kao maksimalan broj gostiju.

**Odluka 6. 10. 2026. (do odgovora eTurizma, P-22):**
- Kapacitet migriranog objekta s više jedinica prikazuje se samo u retku objekta, kao zbroj jednog
  skupa redaka.
- Jedinice kapacitet ne prikazuju.
- Broj gostiju jedinice se ne zaključava i ne dopunjuje iz eTurizma, nego ga upisuje korisnik
  (obavezan je po V-2).

### Kapacitet sadržaja: kako ga računa TuRegistar (4. krug, `sql_4`)

Primjer iz TuRegistra (CDU), kuća za odmor „KZO pristojba” (`facility.id` 240986):

| U TuRegistru | Vrijednost |
| :--- | :--- |
| Ulica | „Biokovska” |
| Kućni broj | prazno |
| Županija, grad/općina, naselje | prazno u obrascu (u bazi postoje: naselje Split, općina SPLIT, poštanski broj 21000) |
| „Smještajni sadržaji objekta” | broj kreveta 2, broj jednakih smještajnih sadržaja 1, **UKUPNO 2** |

STR je za taj objekt prikazao adresu „Split, SPLIT” bez ulice, a kapacitet „-”.

Model sadržaja:

- `facility_content.quantity` je **broj jednakih smještajnih sadržaja** (npr. 10 trokrevetnih soba).
- `facility_content_capacity` (`CAT_BROJ_KREVETA`) je **broj kreveta jednog sadržaja**. U svim
  primjerima `CT_JEDNO_SOBA` = 1, `CT_DVO_SOBA` = 2, `CT_TRO_SOBA` = 3.
- **Ukupno kreveta = Σ (kreveti sadržaja × broj jednakih sadržaja)**, samo za aktivne retke
  (`facility_content.active` i `facility_content_capacity.active`). Neaktivni retci su stare verzije
  (T1: 26 neaktivnih redaka `CT_DVO_SOBA`). Zbroj bi mijenjali kod 19 jedinica (T2), a `active` nije
  NULL ni u jednom retku.

Umnožak je **potvrđen u TuRegistru** na objektu „Vila Lucija” (`facility.id` 73, predmet
UP/I-100/22-1): broj kreveta 3, broj jednakih smještajnih sadržaja 10, **UKUPNO 30**. Adresa u
TuRegistru je ulica „Blato”, kućni broj „3”. STR za taj objekt prikazuje kapacitet „-”.

Izvor kreveta po podvrsti verificiranih jedinica (T2), dvije skupine se nigdje ne preklapaju:

| Podvrsta | Kreveti u `facility_capacity` | Kreveti u sadržaju | Bez kreveta |
| :--- | ---: | ---: | ---: |
| `FS_SOBA` | 95 | 0 | 8 |
| `FS_STUDIO_APARTMAN` | 39 | 0 | 0 |
| `FS_APARTMAN` | 0 | 180 | 19 |
| `FS_KUCA_ZA_ODMOR` | 0 | 71 | 17 |

Pomoćni kreveti su za sve podvrste u `facility_capacity` (`CAT_BROJ_POM_KREVETA`).

**Migrirani zapisi** (T4): svaki od 125.559 migriranih zapisa sa sadržajem ima točno jedan sadržaj i
jedan kapacitet sadržaja. Je li to kapacitet same jedinice (odgovor na P-22 a) provjerava
`b3-dijagnostika-5.sql`.

### Kapacitet migriranih jedinica (5. krug, `sql_5`)

Migrirani zapisi drže krevete u **jednom od dva izvora, nikad u oba**:

- u `facility_capacity`;
- ili u jednom sadržaju `CT_SOBA` s kapacitetom `CAT_BROJ_KREVETA` i količinom 1 (U1: 67.998
  jedinica; 61.387 jedinica nema sadržaj).

Kod objekata s jednom jedinicom 46.433 jedinice imaju krevete u sadržaju, a 29.563 u
`facility_capacity`. Nijedna nema oba izvora (U2).

**Objekti s više jedinica:** 5.887 objekata, 43.712 jedinica (U3). Podaci su kopija kapaciteta
objekta na svakoj jedinici:

- 3.225 objekata ima kapacitet u sadržaju. **U svim je vrijednost na svakoj jedinici ista** (npr. obje
  jedinice imaju po 7 kreveta). To je ili ukupan kapacitet objekta ili kapacitet svake jedinice.
- Ostali imaju N redaka `facility_capacity`, kao u 3. krugu: isti skup redaka na svakoj jedinici, a
  zbroj jednog skupa je kapacitet objekta.

Ni u jednom izvoru se ne vidi kapacitet pojedine migrirane jedinice. Odluka od 6. 10. (kapacitet
samo na objektu, P-22) zato vrijedi za oba izvora.

**Pravilo za broj kreveta jedinice:**

```
kreveti = Σ facility_capacity.CAT_BROJ_KREVETA (active)
          inače Σ (facility_content_capacity.CAT_BROJ_KREVETA × facility_content.quantity) (oba active)
pomoćni = Σ facility_capacity.CAT_BROJ_POM_KREVETA (active)
```

Iznimka je migrirani objekt s više jedinica u istom predmetu. Ondje se isto pravilo primijenjeno na
**jednu** jedinicu daje kapacitet objekta, a kapacitet jedinice je nepoznat.

Za objekte čiji je kapacitet u sadržaju ostaje otvoreno je li vrijednost ukupna za objekt ili po
jedinici. Treba je usporediti s TuRegistrom, npr. objekt `00182b7e-319f-41a3-bd9f-ec88fbb90478`
(jedinice 54734 i 54735, svaka po 7 kreveta).

### W-8 (CDU preprod, `sql_w8_preprod`)

Objekt `12bcff39-74e9-4696-b5f0-4444216ee8e9` postoji na **CDU preprodu** (`172.20.8.212`) i ima 5
zapisa:

| `facility.id` | Naziv | Stanje | Adresa objekta |
| ---: | :--- | :--- | :--- |
| 1100263 | (naziv objekta) | migriran, `active = false`, predmet neaktivan | „Nin, 23232 NIN” (bez ulice) |
| 1440696 | (naziv objekta) | zahtjev za promjenu podataka (`DST_Z_PROMJ_POD`), bez predmeta i poslovnog statusa | Ulica Vile Velebita 4 |
| 1444993 | 1 | `historical = true` | Ulica Vile Velebita 4 |
| **1444999** | **1** | **jedina aktualna jedinica** (rang 1, verificiran, `FBS_ACTIVE`, jedina u viewu) | **Ulica Vile Velebita 4** |
| 1445081 | 1 | `historical = true`, rješenje o ukidanju, `FBS_INACTIVE` | Ulica Vile Velebita 4 |

**Kućni broj 6** postoji **samo u adresi prebivališta vlasnika** (`subject_address` tipa
`PREBIVALISTE` subjekta predmeta). Nijedan zapis objekta nema kućni broj 6. Adresa vlasnika se ovdje
namjerno ne navodi: osobni je podatak, a nalazi se u izvozu rezultata koji se ne commita.

Svi zapisi imaju `same_address_subject` NULL ili `false`, pa popis i claim tu adresu za objekt ne
uzimaju. Danas i staro pravilo prikazuju „Nin, Ulica Vile Velebita 4, 23232 NIN” (B1e). STR adresu
prebivališta prikazuje u odjeljku **podnositelja** (izvor `STR_SUBJEKT`). Najvjerojatnije je
kućni broj 6 viđen ondje, a ne kao adresa objekta. Snimke onoga što je ministarstvo vidjelo nemamo
(P-5).

**Oznaka „2”:** nijedan zapis nema naziv „2"; aktualna jedinica ima naziv „1”, kao u TuRegistru.
Jedini podatak „2” su pomoćni kreveti (`CAT_BROJ_POM_KREVETA` = 2). Odakle je „2” na snimci, ne može
se utvrditi bez snimke (P-5).

**Kapacitet** (`sql_w8_preprod_2`, W1) je **potvrđen**:

- Jedinica 1444999 je `FS_APARTMAN`. Ima aktivan sadržaj `CT_DVO_SOBA` s 2 jednaka sadržaja po 2
  kreveta, što daje **4 kreveta**.
- U `facility_capacity` ima **2 pomoćna kreveta**.
- Zajedno to je **4 + 2, isto kao u TuRegistru**.

STR krevete ne prikazuje jer ne čita sadržaj. Migrirani zapis (1100263, neaktivan) imao je jedan
sadržaj `CT_SOBA` s 4 kreveta.

STR za ove zapise na CDU preprodu nema ni zahtjev ni izdan registracijski broj (W2, W3 prazni).
Primjedba W-8 dakle dolazi s popisa ili iz forme, ne iz izdanog broja.

### Ispravak (B-3, grana `feature/b-3-adresa-kapacitet`)

**Kapacitet — backend.** Jedno pravilo za popis i claim (`StrFacilityRepository`):

- `KREVETI_JEDINICE` i `POMOCNI_KREVETI_JEDINICE` koriste i `findListingByOib` i `findOwnership`.
- Kreveti dolaze iz `facility_capacity`; kad ondje nema redaka, uzima se umnožak iz smještajnih
  sadržaja, a zadnja rezerva je `facility_unit_capacity`.
- Uzimaju se samo aktivni retci. Claim je ranije redak s `active` NULL brojao kao aktivan, a popis
  nije.
- `objectLevelCapacity` je `true` za migriranu jedinicu iz objekta s više jedinica u istom predmetu
  (`predmet_jedinica` iz `RANGIRANE_JEDINICE_OD`).
  - Popis tada kapacitet vraća u `objektBrKreveta` / `objektBrPomocnihKreveta`, a jedinica nema
    svoj.
  - `FacilityClaimVerifier.maxGuests` vraća `null`: broj gostiju se ne zaključava, ne dopunjuje
    (`completeFrom`) i ne uspoređuje.
  - Korisnik ga upisuje sam, a obavezan je po V-2.

**Adresa i kapacitet — frontend.**

- `formatAdresaZaPopis` (`src/utils/address.ts`) slaže „Ulica kbr, poštanski broj naselje" kad
  je ulica poznata.
- Inače uzima `full_address` i dodaje naselje kad ga ona ne sadrži. Općina se prikazuje samo kad
  naselja nema.
- `objectCapacity` (`src/utils/facilityGroups.ts`) kapacitet objekta uzima jednom, a ne zbraja ga
  po jedinicama.
- Prikaz ostaje „N kreveta" i sivo „N pomoćnih" (odluka 6. 10.; P-23 otvoren).

**Već izdani brojevi.** Podaci se ne mijenjaju. Izvještaj `docs/sql/b3-izdani-brojevi.sql` (I1, I2)
broji i navodi brojeve izdane na migriranoj jedinici objekta s više jedinica, uz koje je mogao biti
upisan kapacitet cijelog objekta.

**CDU, 6. 10. 2026.:** STR je izdao 7 brojeva (2 `ACTIVE`, 5 `WITHDRAWN`). Nijedan nije na migriranoj
jedinici objekta s više jedinica (I1 = 0, I2 prazan), pa na CDU nema pogođenih brojeva. Na preprodu
i produkciji izvještaj treba pokrenuti prije deploya.

**Indeksi (`b3-indeksi.sql`, CDU):** `facility_content.facility_id`,
`facility_content_capacity.facility_content_id`, `facility_capacity.facility_id`,
`facility_unit.facility_id` i `facility_unit_capacity.facility_unit_id` imaju btree indeks. Novi
podupit za kapacitet sadržaja zato ide po indeksu.
