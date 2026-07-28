# STR ↔ eTurizam (tuStart) — integracijski URL-ovi

> Model: tuStart radi **redirect preglednika izravno na STR frontend** (HREF), s podacima o objektu
> u query stringu. STR frontend pročita parametre i predpopuni formu; identitet uzima iz NIAS sesije;
> korisnik uredi i frontend pošalje zahtjev. **tuStart ne zove STR backend.**

## 1. Osnovne adrese (frontend)

| Okruženje | Osnovna adresa |
| :--- | :--- |
| **Test (CDU)** | `https://str-test-eturizam.gov.hr` |
| Dev | `http://s-str-02.infodom.hr:8085` |
| Produkcija | TBD |

Puni URL = osnovna adresa + ruta iz §2.

## 2. Ulazne točke (rute)

| Forma | Ruta |
| :--- | :--- |
| Zahtjev za RB — postojeći **i** novi objekt | `/registration-number` (+ query, §3) |
| Pregled prijava non-EU iznajmljivača | `/registration-approval` |
| Moji registracijski brojevi | `/moji-registracijski-brojevi-nias` |
| Registar registracijskih brojeva (interni) | `/registar` |
| Pregled aktivnosti na platformama | `/statistics/platform-activities` |

Postojeći i novi objekt dijele istu rutu: ako u URL-u stignu podaci o objektu → postojeći; ako ne
stignu → prazna forma (novi).

## 3. Query parametri za `/registration-number`

Svi su **opcionalni**. Identitet (ime, prezime, OIB) se **ne šalje** — STR ga čita iz NIAS sesije.

| Parametar | Opis | Primjer |
| :--- | :--- | :--- |
| `facilityId` | ID objekta u eTurizmu — `str.facility.id`. Šalje se samo za postojeći objekt; ključ po kojem STR nakon dodjele vraća RB (v. §6). | `1448035` |
| `name` | Naziv objekta | `kuća test 55` |
| `type` | Vrsta objekta — šifra iz `GET /api/lookups/accommodation-types` (`code`). Backend je prima izravno u `typeId` (v. §5), pa je frontend ne mora prevoditi. | `FS_KUCA_ZA_ODMOR` |
| `maxBedCount` | Broj kreveta | `3` |
| `maxGuestCount` | Maksimalni broj gostiju | `6` |
| `county` | Županija | `Grad Zagreb` |
| `municipality` | Grad / općina | `Zagreb` |
| `settlement` | Naselje | `Zagreb` |
| `street` | Ulica | `Ilica` |
| `streetNumber` | Kućni broj | `1` |
| `postalCode` | Poštanski broj | `10000` |
| `place` | Mjesto | `Zagreb` |

Adresni nazivi (`county`, `municipality`, `settlement`, `street`) dolaze kao **stringovi** i STR
frontend ih razrješava u svoj autocomplete. Katastarska općina i čestica se **ne šalju** — STR ih
izvodi iz adrese.

> Točni nazivi parametara su prijedlog ugovora — potvrditi između tuStarta i STR frontenda prije
> zaključavanja.

## 4. Primjer (CDU) — postojeći objekt

```
https://str-test-eturizam.gov.hr/registration-number?facilityId=1448035&name=ku%C4%87a%20test%2055&type=FS_KUCA_ZA_ODMOR&maxBedCount=0&maxGuestCount=0&streetNumber&street&settlement&postalCode&municipality&county
```

Novi objekt = ista ruta bez parametara:
```
https://str-test-eturizam.gov.hr/registration-number
```

## 5. Vrsta objekta — `typeId` prima i šifru

Polje `typeId` u tijelu `POST /api/generateRegistrationNumber` prihvaća **dvije** vrijednosti:

| Vrijednost | Primjer | Kada |
| :--- | :--- | :--- |
| Stabilna šifra (`FS_*`) | `FS_KUCA_ZA_ODMOR` | tuStart handoff — `type` iz URL-a se proslijedi kakav jest |
| Numerički `type_id` | `4` | STR forma, gdje korisnik bira iz dropdowna |

Šifra se uspoređuje bez obzira na velika/mala slova i okolni razmak. Preporuka je slati šifru:
`type_id` se razlikuje među okolinama jer changeset `020-reseed` briše i ponovno umeće retke.

Nepoznata vrijednost (ni postojeća šifra ni postojeći `type_id`) vraća **400**
`error.accommodation.type.unknown`. Namjerno se ne ignorira tiho — bez vrste otpada provjera iz
`RnService.issue()` koja hotelu i kampu brani dodjelu RB-a.

## 6. Povrat registracijskog broja

Frontend pri spremanju šalje `facilityId` u tijelu `POST /api/generateRegistrationNumber`; backend ga
zapamti na registraciji (`str_rn.accommodation.facility_id`). Nakon dodjele RB-a STR **upiše RB u
`str.facility.registration_number`** za redak čiji `id` odgovara tom `facilityId`.

Izvedba: `FacilityRegistrationNumberWriteBack`, okinut iz `RnIssuedListener` nakon commita
registracijske transakcije (`TransactionPhase.AFTER_COMMIT`).

Ponašanje u rubnim slučajevima:

| Situacija | Ishod |
| :--- | :--- |
| Registracija bez `facilityId` (nije iz tuStarta) | Preskače se, ništa se ne piše |
| Objekt već ima RB u `str.facility` | Ne prepisuje se (`WHERE registration_number IS NULL`), logira se `facility_writeback_no_row` |
| `id` ne postoji u `str.facility` | Isto kao gore — logira se, RB ostaje valjan |
| Upis padne (npr. nema `UPDATE` prava) | Logira se `facility_writeback_failed`, **RB ostaje valjan i izdan** |

Retryja nema — eTurizam nema idempotentni endpoint za ovo, pa neuspjeli upis ide na ručnu
intervenciju preko logova.

> **Preduvjet za okoline:** ovo je jedini put pisanja u shemu `str`, koja je inače read-only za STR.
> DB korisnik treba `UPDATE` pravo na `str.facility.registration_number`. Bez toga registracija i
> dalje prolazi, ali se RB ne vraća u eTurizam.

## 7. Napomene za STR frontend

- **Identitet** (ime/prezime/OIB) iz NIAS sesije (`GET /api/nias/me`), nikad iz URL-a.
- **Param survival:** kod hladnog ulaska (nema STR sesije) ide redirect na NIAS i query parametri se
  gube. Frontend mora spremiti parametre u `sessionStorage` **prije** skoka na NIAS i vratiti ih nakon
  povratka.
- **Adresni autocomplete:** primljene nazive (`county`/`municipality`/`settlement`) razriješiti u
  stavke kaskade (da se uhvate ID-evi koje submit traži); ako string ne pogodi, ostaviti korisniku.

## 8. Što je u swaggeru, a što nije

tuStart ulazne točke su **frontend rute** — **nisu** u swaggeru (swagger dokumentira samo backend REST
API). U swaggeru je backend koji frontend zove tijekom toka: `POST /api/generateRegistrationNumber`
(tijelo sadrži `facilityId`), `GET /api/nias/me`, te adresni lookup endpointi.
