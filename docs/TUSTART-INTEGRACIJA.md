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
| `facilityUnitId` | ID smještajnog objekta (unita) u eTurizmu — `str.facility_unit.id`. Šalje se samo za postojeći objekt. | `42` |
| `name` | Naziv objekta | `Apartman Ana` |
| `type` | Vrsta objekta | `Apartman` |
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
https://str-test-eturizam.gov.hr/registration-number?facilityUnitId=42&name=Apartman%20Ana&type=Apartman&maxBedCount=3&maxGuestCount=6&county=Grad%20Zagreb&municipality=Zagreb&settlement=Zagreb&street=Ilica&streetNumber=1&postalCode=10000&place=Zagreb
```

Novi objekt = ista ruta bez parametara:
```
https://str-test-eturizam.gov.hr/registration-number
```

## 5. Povrat registracijskog broja

Frontend pri spremanju šalje `facilityUnitId` u tijelu `POST /api/generateRegistrationNumber`; backend
ga zapamti na registraciji (`str_rn.accommodation.facility_unit_id`). Dogovoreni povrat prema eTurizmu:
STR nakon dodjele RB-a **upiše RB u `str.facility_unit`** pod tim `id`-em unita. **Blokirano** dok ne
stignu prava na upis + naziv/tip RB kolone.

## 6. Napomene za STR frontend

- **Identitet** (ime/prezime/OIB) iz NIAS sesije (`GET /api/nias/me`), nikad iz URL-a.
- **Param survival:** kod hladnog ulaska (nema STR sesije) ide redirect na NIAS i query parametri se
  gube. Frontend mora spremiti parametre u `sessionStorage` **prije** skoka na NIAS i vratiti ih nakon
  povratka.
- **Adresni autocomplete:** primljene nazive (`county`/`municipality`/`settlement`) razriješiti u
  stavke kaskade (da se uhvate ID-evi koje submit traži); ako string ne pogodi, ostaviti korisniku.

## 7. Što je u swaggeru, a što nije

tuStart ulazne točke su **frontend rute** — **nisu** u swaggeru (swagger dokumentira samo backend REST
API). U swaggeru je backend koji frontend zove tijekom toka: `POST /api/generateRegistrationNumber`
(tijelo sadrži `facilityUnitId`), `GET /api/nias/me`, te adresni lookup endpointi.
