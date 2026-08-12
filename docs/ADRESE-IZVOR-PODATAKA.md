# Adresni izvori — koja shema za koju razinu

Analiza nad **CDU** okolinom (11.08.2026.), povod: provjera da se za postojeći objekt iz eTurizma
ne smiju mijenjati adresni podaci. Provjera je uspoređivala nazive iz dva različita registra, pa se
postavilo pitanje može li se adresna hijerarhija svesti na jedan izvor.

Svi brojevi niže su izmjereni na CDU-u, ne pretpostavljeni.

## 1. Zatečeno stanje: tri paralelne hijerarhije

Adresna kaskada u formi zahtjeva čita iz **tri** sheme:

| Razina | Entitet | Shema |
| :--- | :--- | :--- |
| Država | `CountryEntity` | `str.country` |
| Županija | `CountyEntity` | `rpj_dgu.zupanije` |
| Grad / općina | `MunicipalityEntity` | `rpj_dgu.gradovi_i_opcine` |
| Naselje | `SettlementEntity` | `rpj_dgu.naselja` |
| Ulica | `StreetEntity` | `eturizam_test.ar_ulice` |
| Kućni broj | `HouseNumberEntity` | `eturizam_test.ar_address` |

Istovremeno `str.*` ima **potpunu vlastitu hijerarhiju** istih razina, koju čita popis objekata
(`StrFacilityRepository`), a `eturizam_test` ima i svoje `zupanije` / `opcine` / `naselja`. Dakle
ista hijerarhija postoji u tri kopije.

## 2. Pokrivenost i popunjenost

| Razina | `str` | `rpj_dgu` | `eturizam_test` |
| :--- | ---: | ---: | ---: |
| država | 249 | **1** (`drzava`) | — |
| županija | 21 | 21 | ima |
| grad/općina | 556 | 556 | ima |
| naselje | 6 757 | 6 759 | ima |
| ulica | 53 801 | **ne postoji** | 54 420 (`ar_ulice`) |
| kućni broj | 1 644 781 | **ne postoji** | 1 679 225 (`ar_address`) |

**`rpj_dgu` ne može biti jedini izvor** — nema tablice `ulice` ni `kucni_brojevi`, a `drzava` ima
jedan redak (zato je `CountryEntity` i vraćen na `str.country`, v. changeset 104).

## 3. `str.*` je DGU-sinkronizirano zrcalo, ne odvojena kopija

Svaka `str` adresna tablica nosi `external_code`, `dgu_id` i `dgu_last_modified_date`, **popunjene
100 % na svakoj razini** — uključujući `street` (53 801) i `house_number` (1 644 781).

| Stupac | Značenje | Poklapanje s `rpj_dgu` |
| :--- | :--- | :--- |
| `external_code` | nacionalna šifra | županija ↔ `zu_rb` (2 zn.); grad ↔ `jls_mb` **556/556**; naselje ↔ `na_mb` **6752/6757** |
| `dgu_id` | numerički rep INSPIRE oznake (`7000007` ↔ `HR.DGU.RPJ:ZU.0007000007`) | — |

> **`dgu_id` se NE spaja na `rpj_dgu.id`.** Join po `rpj_dgu.id` daje 0 / 0 / 5022 redaka.
> `rpj_dgu.id` je nekonzistentan surogat: naselje „Ada" ima `id = 5000573`, a susjedni „Adamovec"
> `id = 2175711557` uz uredan INSPIRE. Ključ je `external_code` (ili INSPIRE), nikako `id`.

## 4. Zašto naziv nije upotrebljiv kao ključ

- **313 naziva naselja se ponavlja.** „Novo Selo" ×6, „Poljica" ×5, pa ×4 za Brezje, Gorica,
  Korita, Lipovac, Luka, Markovac, Novaki, Otok, Podgorje, Podgrađe, Poljana, Rastovac, Soline.
  Razrješavanje tuStart stringa „Novo Selo" u autocomplete ima 1/6 šanse pogoditi pravo naselje.
- **Gradovi su u `str` pisani verzalom** („ANDRIJAŠEVCI"), u `rpj_dgu` naslovno („Andrijaševci").
  `FacilityClaimVerifier.normalize()` spušta na mala slova, pa provjera prolazi; bez toga bi
  **svaki** zahtjev za postojeći objekt padao na gradu.

Nazivi se, uz normalizaciju, poklapaju (županije 21/21, gradovi i naselja bez nepoklapanja), pa
usporedba po nazivu ne ruši ispravne zahtjeve — ali ni ne štiti od pogrešno razriješenog istoimenog
naselja.

## 5. Koliko su adrese objekata uopće strukturirane

`str.address`, 285 874 redaka:

| county_id | municipality_id | settlement_id | street_id | house_number_id |
| ---: | ---: | ---: | ---: | ---: |
| 284 613 (99,6 %) | 283 372 (99,1 %) | 277 947 (97,2 %) | **5 415 (1,9 %)** | **4 966 (1,7 %)** |

Tri gornje razine su popunjene; ulica i kućni broj gotovo nisu. Zaključavanje ulice/kbr za postojeći
objekt je time uglavnom bespredmetno — provjera ih preskoči jer izvor nema podatak.

## 6. Preporuka

**Izbaciti `rpj_dgu` iz forme; `eturizam_test` zadržati.** Tri sheme → dvije:

| Razina | Sad | Predloženo |
| :--- | :--- | :--- |
| Država | `str.country` | bez promjene |
| Županija / grad / naselje | `rpj_dgu` | **`str.county` / `municipality` / `settlement`** |
| Ulica / kućni broj | `eturizam_test` | bez promjene |

Razlozi:

1. **Tri razine koje su stvarno popunjene dolaze iz istog registra kao i objekt**, pa usporedba
   zahtjeva i objekta postaje usporedba po `id`-u umjesto po nazivu — dvosmislenost iz §4 nestaje.
2. **Poštanski broj postaje točan.** `str.settlement.postal_code` je kolona; sada se izvlači
   imenskim LEFT JOIN-om na `rpj_dgu.postanski_brojevi` s ≈98,6 % pogodaka.
3. **Veza prema DGU-u se ne gubi** — `external_code` ostaje i poklapa se 556/556 i 6752/6757.

**Zašto ne i `eturizam_test` → `str`:** `eturizam_test.ar_address` nosi `kc_broj` i `kat_opcina_id`
(katastarska čestica i općina), koje `str.house_number` nema — ondje su samo `geo_x`/`geo_y`. STR
katastar izvodi iz adrese (`HouseNumberResponse.kcBroj`), pa bi potpuni prelazak taj podatak izgubio.

## 7. Što treba prije izvedbe

- **Lokalni mock ne odražava produkcijsku shemu.** Changesetovi 100 i 123 nemaju `external_code`,
  `dgu_id` ni `dgu_last_modified_date`. Dok se ne dopune, logika vezana na te stupce se lokalno ne
  može ni razviti ni testirati. (Ovo je i razlog zašto je prva analiza pogrešno zaključila da mosta
  nema.)
- **Promjena je ugovorna.** `GET /api/address/*` sada vraća `rpj_dgu` ID-eve; prelaskom na `str`
  mijenjaju se ID-evi koje frontend šalje u `countyId` / `cityId` / `settlementId`.
- **`str_rn.accommodation` čuva samo nazive** (`county`, `city`, `settlement` su `VARCHAR`, bez
  ijednog ID-a), a `RegistrationService.resolveEntityName` na nerazrješiv ID upiše **sam ID kao
  naziv** (`.orElse(id)`). Zato `StatisticsRepository` ima obrambeni
  `LEFT JOIN rpj_dgu.gradovi_i_opcine ON m.id::text = a.city`. Uz prelazak bi trebalo perzistirati i
  ID/šifru, a nerazrješiv ID tretirati kao grešku, ne kao vrijednost.

## 7a. Naziv objekta u eTurizmu

Mjereno istom prilikom, jer `FacilityClaimVerifier` zaključava i naziv. `str.facility.name`,
242.468 aktivnih objekata:

| Sadržaj | Objekata | Udio |
| :--- | ---: | ---: |
| Stvarni naziv objekta | 209.148 | 86,3 % |
| **Ime iznajmljivača** (5.470 = `subject_version.name`, 22.442 = „ime prezime") | **27.912** | **11,5 %** |
| Popunjivač (`-`, `--`, `.`, `x`) ili prazno | 5.408 | 2,2 % |

Unutar „stvarnih naziva" velik dio je zapravo vrsta smještaja: `Apartman` 4.395, `Bučanje` 3.716,
`Soba - Depadansa` 1.326, `Apartmani` 983, `APARTMAN` 953, `Studio apartman` 675, `APARTMANI` 568.

Zbog toga verifier kao „nema naziva" tretira i ime vlasnika — inače tih 11,5 % vlasnika ne bi moglo
upisati stvarni naziv objekta. Naziv koji je vrsta smještaja ostaje zaključan; je li to poželjno,
otvoreno je pitanje za naručitelja (STR registar time nasljeđuje nazive tipa „Apartman").

## 8. Utjecaj na postojeću provjeru

`FacilityClaimVerifier` (v. `docs/TUSTART-INTEGRACIJA.md` §6a) ostaje kakav jest — podaci pokazuju da
usporedba po nazivu uz normalizaciju ne ruši ispravne zahtjeve. Kad se odradi §6, usporedbu adrese
treba prevesti s naziva na `id`, čime prestaje ovisiti o pisanju imena.

Skripte kojima su ovi brojevi izmjereni: `adrese-provjera.sql` i `adrese-provjera-2.sql`
(samo `SELECT`; nisu u repou — v. povijest razgovora ili ponovno generirati po §2–§5).
