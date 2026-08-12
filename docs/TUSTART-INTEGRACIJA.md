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
| ~~`maxGuestCount`~~ | Broj gostiju je izbačen (v. §6c) — parametar se ignorira | — |
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

## 6a. Zaključani podaci postojećeg objekta

Kad tijelo `POST /api/generateRegistrationNumber` nosi `facilityId`, backend prije svega ostalog
provjerava (`FacilityClaimVerifier`):

| Provjera | Ishod na neslaganje |
| :--- | :--- |
| Objekt pripada OIB-u iz NIAS sesije (`facility → subject_version → subject.jips`) | 400 `error.facility.notOwned` |
| Objekt postoji i aktivan je | 400 `error.facility.unknown` / `error.facility.inactive` |
| Objekt već nema stojeći RB (ACTIVE / SUSPENSION_PROPOSED / SUSPENDED) | 400 `error.facility.alreadyRegistered` |
| `typeId` odgovara podvrsti u eTurizmu (`FS_*`) | 400 `error.facility.type.mismatch` |
| `maxBeds` odgovara `CAT_BROJ_KREVETA` u eTurizmu | 400 `error.facility.beds.mismatch` |
| `name` odgovara `facility.name` | 400 `error.facility.name.mismatch` |
| Županija / grad-općina / naselje / ulica / kućni broj odgovaraju adresi objekta | 400 `error.facility.address.mismatch` |

Vlasništvo nije kozmetika: write-back iz §6 piše RB u `str.facility` za poslani `facilityId`, pa bi
bez provjere tuđi ID upisao RB u tuđi zapis u eTurizmu.

**Usporedba se preskače kad eTurizam podatak ne zna.** Ulica i kućni broj su u `str.address`
razriješeni u ispod 2 % objekata (v. `docs/ADRESE-IZVOR-PODATAKA.md` §5), pa se ondje najčešće i ne
uspoređuju. Stroga jednakost nad praznim izvorom davala bi 400 na ispravnim zahtjevima, pa vrijedi
pravilo: **usporedi samo ono što izvor stvarno zna**.

Usporedba zanemaruje velika/mala slova te rubne i višestruke razmake. To nije kozmetika: gradovi su
u `str.municipality` pisani verzalom („ANDRIJAŠEVCI"), a u registru iz kojeg forma čita naslovno
(„Andrijaševci") — bez normalizacije bi **svaki** zahtjev za postojeći objekt padao na gradu.

Kao „nema naziva" broje se tri slučaja, pa ih korisnik smije upisati sam:

| Slučaj | Na CDU (od 242.468 aktivnih) |
| :--- | ---: |
| Popunjivač — vrijednost bez ijednog slova ili znamenke (`-`, `--`, `.`) ili prazno | 5.408 (2,2 %) |
| `facility.name` je **ime samog iznajmljivača** | 27.912 (11,5 %) |
| Stvarni naziv objekta → **zaključan** | 209.148 (86,3 %) |

Naziv koji je vrsta smještaja („Apartman", „Studio apartman" — preko 12 tisuća zapisa) ostaje
zaključan: to jest vrijednost koju eTurizam vodi kao naziv. Smije li se i to mijenjati, poslovna je
odluka, ne tehnička.

Broj gostiju se **ne** provjerava — v. §6c, više se ni ne šalje.

## 6b. Koja su polja stvarno zaključana — `GET /api/nias/facilities/{id}`

tuStart podatke šalje kroz query string, koji je korisniku vidljiv i izmjenjiv prije submita.
Frontend zato **ne smije** zaključavati polja po tome što je stiglo u URL-u, nego po ovom
endpointu:

```
GET /api/nias/facilities/1448035
```
```json
{
  "id": "1448035",
  "naziv": "-",
  "vrstaSifra": "FS_SOBA",
  "brKreveta": 2,
  "zupanijaNaziv": "Splitsko-dalmatinska",
  "opcinaNaziv": "Makarska",
  "naseljeNaziv": "Makarska",
  "ulicaNaziv": null,
  "kucniBrojNaziv": null,
  "zakljucanaPolja": ["typeId", "maxBeds", "countyId", "cityId", "settlementId"]
}
```

`zakljucanaPolja` nosi **nazive polja iz tijela** `POST /api/generateRegistrationNumber`, pa se
preslikavaju izravno na inpute forme. Popis računa ista metoda (`FacilityClaimVerifier.lockedFields`)
koju provjera koristi pri submitu, pa se prikaz i provjera ne mogu razići. Polje kojeg nema na
popisu (u primjeru: `name`, `street`, `streetNumber`) eTurizam ne zna — korisnik ga smije i treba
popuniti.

Tuđi i nepostojeći objekt daju isti **404** — postojanje tuđeg zapisa nije podatak koji ovaj
endpoint smije otkriti.

## 6c. Broj gostiju je izbačen

Primjedba s UAT-a: broj gostiju je isti kao broj kreveta, pa je maknut iz forme i iz API-ja.
`maxGuests` više **nije** polje tijela `POST /api/generateRegistrationNumber` (ni internog ni
vanjskog), ne vraća se u `GET /api/rn/{rn}/detail` i nema ga u statističkim izvozima. Backend na
registraciji upisuje `max_guests = max_beds`; kolona je zadržana radi već izdanih RB-ova.

eTurizam ga za objekte u domaćinstvu ionako ne vodi — `CAT_BROJ_GOSTIJU` postoji samo na razini
jedinica hotela i sličnih objekata (v. `docs/ETURIZAM-OBJEKTI.md`).

## 6d. NIAS dashboard — postojeći objekti i skenirano rješenje

| Endpoint | Namjena |
| :--- | :--- |
| `GET /api/nias/facilities?page=&size=` | Popis objekata prijavljenog iznajmljivača (eTurizam + uploadana skenirana rješenja). Paginirano, default 20, max 100. |
| `GET /api/nias/facilities/{id}` | Mjerodavni podaci jednog objekta + `zakljucanaPolja` (v. §6b). |
| `POST /api/nias/categorization-decisions` | Upload skeniranog papirnatog rješenja (multipart, `datoteka` + opcionalni metapodaci). |

Item popisa nosi `registracijskiBroj` (null → frontend nudi „Zatraži RB", inače „Prikaži") i `izvor`
(`ETURIZAM` / `PRIVREMENO_RJESENJE`). Prijenos objekta iz drugog izvora u eTurizam radi nadležno
tijelo; do tada zapis živi u `str_rn.categorization_decision` i prikazuje se bez RB-a.

Upload prihvaća PDF/JPEG/PNG do 10 MB, a tip određuje iz sadržaja (magic bytes), ne iz
`Content-Type` headera. Prevelika datoteka daje **413** `error.upload.file.tooLarge`; neispravan
sadržaj 400 `error.categorization.file.type`.

> Preduvjet za CDU: reverse proxy pred aplikacijom mora dopustiti tijelo od 10 MB
> (`client_max_body_size`), inače zahtjev nikad ne dođe do Springa i korisnik dobije 413 od proxyja
> bez našeg tijela greške.

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
