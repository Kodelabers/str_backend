# eGOP integracija — tehnička dokumentacija

> Izvor: *eGOP10 platforma — Specifikacija integracijskih web servisa, v1.11, listopad 2025., MINT.*
> Verzija ovog dokumenta: 0.1 (prvi nacrt, namijenjen senior backend developerima koji rade integraciju iz STR-a).
> Sve nejasnoće prema spec-u nalaze se u `docs/eGOP-otvorena-pitanja.md`.

---

## 1. Što je eGOP i zašto nas zanima

eGOP10 je MINT-ova platforma za uredsko poslovanje. Modelira **predmete** (klasifikacijski označeni spisi), **pismena** (akti/podnesci unutar predmeta), **subjekte** (pravne i fizičke osobe — stranke), **dokumente** (binarni sadržaj uz pismeno), te **priloge** (pripadajući sadržaj/dokumente uz pismeno).

STR backend integrira se s eGOP-om kako bi *za svaki uspješno popunjen zahtjev za registraciju objekta* otvorio predmet, kreirao pismeno tipa "zahtjev za registraciju" i uz pismeno priložio generirani PDF. Sve daljnje upravne radnje (rješavanje, izlazno pismeno, klasifikacijska oznaka) ostaju u eGOP-u, ali STR mora trajno čuvati identifikatore kako bi se kasnije mogao referencirati na predmet/pismeno.

> **Vrlo bitno:** STR *ne* poziva eGOP dok forma nije validirana i submitana. eGOP poziv je nuspojava završetka submita, ne dio interaktivnog flowa. Iz toga slijedi: poziv je asinkron, otporan na retry, i mora preživjeti pad eGOP-a bez gubitka korisničkog zahtjeva.

---

## 2. Pristupne adrese

| Servis    | Test okruženje                                          | Produkcija                                          |
| :-------- | :------------------------------------------------------ | :-------------------------------------------------- |
| Pismeno   | `https://egopeaitest.mint.hr/ServicePismeno.asmx`       | `https://egopeai.mint.hr/ServicePismeno.asmx`       |
| Predmet   | `https://egopeaitest.mint.hr/ServicePredmet.asmx`       | `https://egopeai.mint.hr/ServicePredmet.asmx`       |
| Subjekt   | `https://egopeaitest.mint.hr/ServiceSubjekt.asmx`       | `https://egopeai.mint.hr/ServiceSubjekt.asmx`       |
| MDM       | `https://egopeaitest.mint.hr/ServiceMDM.asmx`           | `https://egopeai.mint.hr/ServiceMDM.asmx`           |

- WSDL je dostupan dodavanjem `?WSDL` na bilo koji od endpointa (npr. `ServicePredmet.asmx?WSDL`).
- Fizički server (MINT interno): `MINTS-SHAPP1 (192.168.30.80)` — relevantno samo ako se s naše strane gleda IP firewallom.
- Transport je SOAP 1.1/1.2 preko HTTPS-a (ASMX). HTTP status je u pravilu **200 OK i kad je biznis-greška**; greška se signalizira u tijelu odgovora.

---

## 3. Sigurnost

### 3.1 Autentifikacija

Autentifikacija je IIS-na Windows ili Basic Authentication. eGOP ne radi vlastiti login layer — oslanja se na IIS preko AD-a.

- Korisnički račun se predaje u svakoj metodi i kroz HTTP autentifikacijsko zaglavlje.
- Format korisničkog imena u svim metodama je `DOMENA\username` (npr. `MINT\str-svc`). Predaje se kroz `username` (ili `userNameZaposlenika` u metodama gdje se razlikuje "operator koji zove" od "zaposlenik za kojeg se zove").

> **Implementacijska napomena:** Apache HttpClient + Spring `WebServiceTemplate` ili Spring-WS s `BasicAuthInterceptor`-om / `HttpComponentsMessageSender`. Za Windows (NTLM) auth treba `JCIFS` ili `httpclient-ntlm` modul; ako MINT zaista koristi NTLM, Basic neće raditi. Pretpostavljamo Basic dok ne potvrde drugačije (→ pitanje #1).

### 3.2 Autorizacija

eGOP ima vlastiti role-based model. Svakom AD računu administrator eGOP-a dodjeljuje prava na pojedinu metodu pojedinog servisa. Manjak prava → `OperationSucceeded=false` + `ErrorCode = -700` u `ErrorStatus` listi.

Praktično: tehničkom računu kojim STR poziva eGOP moraju biti odobrene barem ove metode (vidi sekciju 6 za točan tok):

- `Subjekt.KreirajSubjektaProsireno`, `Subjekt.DohvatiPodatkeSubjekta`, `Subjekt.DohvatiSubjektIdZaUsername`
- `Predmet.KreirajPredmet2`, `Predmet.PostaviSubjektaNaPredmetu`, `Predmet.DohvatiPodatkePredmeta`
- `Pismeno.KreirajPismenoPoUredbi`, `Pismeno.KreirajDokumentZaPismenoPoUredbi`, `Pismeno.DohvatiPodatkePismenaPoUredbi`, `Pismeno.KreirajPrilogPoUredbi` (po potrebi)

### 3.3 Zatvorenost dokumenta

Spec eksplicitno kaže: *"Metode koje su dostupne unutar integracijskih servisa a nisu pokrivene dokumentom specifikacije ne smiju se koristiti u procesu integracije od strane drugih sustava."* Sve dodatne operacije (npr. WSDL nudi nešto što nije u spec-u) tretiramo kao neslužbene i ne koristimo ih.

---

## 4. Osnovne strukture odgovora

### 4.1 BaseInfo

Sve metode (osim `DohvatiSubjektIdZaUsername` — vidi 5.1) vraćaju skup koji *nasljeđuje* `BaseInfo`:

```
BaseInfo
├── operationSucceeded : boolean
└── ErrorStatus        : List<ErrorStatusItem>
    ├── ErrorCode    : int
    └── ErrorMessage : string
```

Konvencija:

- `operationSucceeded=true` → operacija je uspjela. `ErrorStatus` može biti prazan ili sadržavati warningse (ali u praksi je prazan).
- `operationSucceeded=false` → operacija nije izvršena. `ErrorStatus` sadrži najmanje jedan red s razlogom.
- **HTTP 200 ne znači uspjeh.** Naš kod *uvijek* mora čitati `operationSucceeded` prije nego konzumira ostatak payloada.

### 4.2 Generičke greške

| ErrorCode | Značenje                                                                                  |
| :-------- | :---------------------------------------------------------------------------------------- |
| -1        | Neočekivana pogreška (kontaktirati MINT). U pravilu tehnička, ne business.                |
| -100      | Korisnik (AD) ne postoji.                                                                 |
| -240      | Vrsta predmeta ne postoji (kriva `vrstaPredmeta` šifra).                                  |
| -300      | Poslovni subjekt ne postoji.                                                              |
| -450      | Rješavatelj ne postoji.                                                                   |
| -700      | Narušeno sigurnosno pravilo (nedostaje ovlast) — dinamička poruka.                        |
| -701      | Narušeno poslovno pravilo (npr. predmet je zatvoren a pokušavamo dodati pismeno) — dinamička poruka. |

`-700`/`-701` su generički "kontejneri" — semantika sjedi u `ErrorMessage`. Kod njih je *poruka* dokumentacija; **ne smije se parsirati**, ali smije se logirati i prikazati u alertu.

`DohvatiSubjektIdZaUsername` je iznimka — ne vraća `BaseInfo`, već skalar. Negativne vrijednosti su greške, vidi 5.1.

---

## 5. Servisi i metode (samo one relevantne za STR)

### 5.1 ServiceSubjekt

#### `DohvatiSubjektIdZaUsername(userName, userNameKorisnikaAplikacije) → int`

Iznimka: vraća `int`, **ne** `BaseInfo`.

- `>0` → `oznakaSubjekta` u eGOP registru subjekata za tog službenika.
- `-1` korisnik (operater) ne postoji.
- `-2` korisnik aplikacije ne postoji.
- `-3` nema prava na dohvat.
- `-4` korisnik nema definiranu podrazumijevajuću oznakuSubjekta.
- `-5` interna greška servisa.

Koristi se ako STR ikad poziva u ulozi službenika (npr. kad pisanje pismena radi konkretan AD korisnik). Za STR-ov tehnički račun vjerojatno nije nužno; ostavljamo za buduće (→ pitanje #2 oko username modela).

#### `KreirajSubjektaProsireno(username, mbJmbg, tipOsobe, naziv, OIB, telefon, ziroracun, email, ulica, kucniBroj, naselje, postanskiBroj, isoKodDrzave, urBroj) → SubjektBasicInfo`

Kreira eksterni poslovni subjekt (iznajmljivač) ako još ne postoji u eGOP registru. Razlika spram `KreirajSubjekta`: dozvoljava dva subjekta s istim OIB-om ako jedan od njih nije eksterni (npr. službenik MINT-a koji *kao fizička osoba* podnosi nešto MINT-u).

Vraća `SubjektBasicInfo { oznakaSubjekta: int }`. **`oznakaSubjekta` je primarni identifikator** prema eGOP-u i čuvamo ga u STR snapshotu iznajmljivača kao FK.

`tipOsobe` je eGOP šifrarnik (vrijednosti nisu u spec-u — → pitanje #3).

#### `DohvatiPodatkeSubjekta(userName, [subjektOznaka | MbJmbg | OIB]) → SubjektInfo`

Idempotentni lookup. Korisno *prije* `KreirajSubjektaProsireno` da provjerimo postoji li već iznajmljivač u eGOP-u; sprečava bespotrebni "duplicate insert" pokušaj. Vraća pun set podataka subjekta uključujući `urBroj` (brojčana oznaka — ulazi u urudžbeni broj kasnije).

#### `AzurirajPodatkeSubjekta(...)` — *ne koristimo na STR-u*

STR nije izvor istine za subjekt; ne ažuriramo eGOP iznajmljivačke podatke. Ako iznajmljivač promijeni adresu, novi snapshot u STR-u i nova registracija → eventualno potrebno (→ pitanje #4 oko strategije za promjenu adrese postojećeg iznajmljivača).

### 5.2 ServicePredmet

#### `KreirajPredmet2(username, upisnaKnjiga, vrstaPredmeta, [nadleznaOrgJedinica | rjesavatelj], [subjektOznaka | subjektMbJmbg | subjektOIB], nazivPredmeta, datumOtvaranja) → PredmetBasicInfo2`

Otvara novi predmet u eGOP-u. Preferiramo `KreirajPredmet2` (ne `KreirajPredmet`) zato što vraća **klasifikacijsku oznaku** u istom pozivu — bez nje moramo poslije zvati `DohvatiPodatkePredmeta`.

Obavezna polja:

- `username` — `DOMENA\racun`.
- `upisnaKnjiga` — STR koristi `UP/I` (prvostupanjski upravni postupak) za zahtjev za registraciju. Ovo je *naša pretpostavka*, → pitanje #5.
- `vrstaPredmeta` — eGOP šifra. Mora biti **predefinirana u eGOP-u**; ne smijemo izmišljati. → pitanje #6 (točan kod za STR registraciju).
- Jedan od `subjektOznaka | subjektMbJmbg | subjektOIB` — glavni subjekt predmeta = iznajmljivač.

Opcionalna ali važna:

- `nadleznaOrgJedinica` ili `rjesavatelj` — barem jedno. → pitanje #7 (otvara li STR predmet "u zrak" za pisarnicu da rasporedi, ili odmah dodjeljujemo org. jedinici nadležnoj za turistički nadzor po županiji).
- `nazivPredmeta` — slobodan tekst, npr. *"Zahtjev za registracijski broj — {naziv objekta}, {naselje}"*. Treba ostati ispod 200 znakova.
- `datumOtvaranja` — ako ne pošaljemo, eGOP postavlja `now()` na serveru. Pošaljemo da se ne oslanjamo na server time.

Odgovor:

```
PredmetBasicInfo2 {
  uredskaGodina         : int       // npr. 2026
  rbrPredmeta           : int       // npr. 1245
  klasifikacijskaOznaka : string    // npr. "UP/I-334-05/26-01/45"
}
```

**Trajni identifikator predmeta = `(uredskaGodina, rbrPredmeta)`.** Klasifikacijska oznaka je human-readable derivat i može se s vremenom dohvatiti opet ako je trebamo.

#### `PostaviSubjektaNaPredmetu(username, uredskaGodina, rbrPredmeta, [subjektOznaka|MbJmbg|OIB], idUlogePartnera, isGlavni) → BaseInfo`

Glavni subjekt je već postavljen kroz `KreirajPredmet2`. Ovu metodu koristimo *iznimno* — npr. ako dodatno trebamo zabilježiti nadležno tijelo kao "ostali subjekt". `idUlogePartnera` je eGOP šifra uloge u predmetu (→ pitanje #8 za točan kod uloge iznajmljivača).

#### `DohvatiPodatkePredmeta(userName, uredskaGodina, rbrPredmeta) → PredmetInfo`

Read-only dohvat. Korisno za:

- Verifikaciju da je naš predmet stvarno otvoren (sanity poslije `KreirajPredmet2`).
- Buduće preglede statusa (`statusPredmeta`).

#### `ZatvoriPredmet` / `StornirajPredmet` / `PonovoOtvori` / `OdrediRjesavatelja`

*Ne koristimo iz STR-a u prvoj fazi.* Zatvaranje i sprega s rješavanjem ostaju u rukama MINT-ovih službenika u eGOP-u.

### 5.3 ServicePismeno — "PoUredbi" varijanta

Spec ima dvije generacije metoda za pismena: **legacy** (`KreirajPismeno`, `KreirajPismeno2`, `KreirajDokumentZaPismeno`, …) i **PoUredbi** (`KreirajPismenoPoUredbi`, `KreirajDokumentZaPismenoPoUredbi`, …).

> **STR koristi isključivo "PoUredbi" varijantu.** Razlog: te metode vraćaju i `jedinstveniIdentifikatorPismena` (GUID), koji je po Uredbi službeni cross-system identifikator akta. Time imamo dva neovisna identifikatora (`jop` i GUID) i ne osuđujemo se na legacy schemu koja izlazi iz upotrebe.

#### `KreirajPismenoPoUredbi(username, rbrSpisa, uredskaGodina, vrstaPismena, [subjektOznaka|MbJmbg|OIB], nazivPismena, datumNastanka) → PismenoBasicInfoUredba`

Stvara *ulazno* pismeno u predmetu (po pravilu — ulazno se kreira automatski jer je stvaratelj akta vanjski subjekt, tj. iznajmljivač, ne MINT službenik).

- `rbrSpisa` = `rbrPredmeta` iz `KreirajPredmet2`. Spec ga ovdje označava kao "NE" obavezan, ali bez njega pismeno nije vezano za naš predmet — uvijek šaljemo.
- `vrstaPismena` — eGOP šifrarnik. Za STR "zahtjev za registraciju" → pitanje #9.
- `[subjektOznaka|MbJmbg|OIB]` — stvaratelj akta = iznajmljivač. Pošto smo ga već imali u `KreirajPredmet2` i tamo dobili `oznakaSubjekta` (iz `DohvatiPodatkeSubjekta` / `KreirajSubjektaProsireno`), preferiramo proslijediti `subjektOznaka` (najjeftinije za eGOP).
- `nazivPismena` — *"Zahtjev za registraciju — {naziv objekta}"* (slobodan tekst, ali kratak).

Odgovor:

```
PismenoBasicInfoUredba {
  jedinstvenaOznakaPismena       : string  // "brojčana oznaka tijela - klasa - rbrPismena"
  jedinstveniIdentifikatorPismena: string  // GUID
  jop                            : int     // eGOP interni id
  brojcanaOznaka                 : string  // pomoćni
}
```

> **Što čuvamo:** sva četiri polja. `jop` je najkraći ključ; GUID je formalni identifikator po Uredbi i poželjan je za bilo kakvu kasniju razmjenu s drugim sustavima.

#### `KreirajDokumentZaPismenoPoUredbi(jedinstveniIdentifikatorPismena | jop, username, extension, attachment) → DokumentInfoUredba`

Učitava PDF (Base64 encoded byte stream) uz pismeno. **Pošalji GUID** umjesto `jop` (perena praksa za novije sustave).

- `extension` = `"pdf"` (bez točke; → pitanje #10 da potvrde format).
- `attachment` = Base64 string PDF-a.

#### `KreirajPrilogPoUredbi(...)` / `KreirajDokumentZaPrilogPoUredbi(...)`

Prilozi su odvojeni dokumenti unutar pismena (npr. dokaz vlasništva). Ako STR forma ikad podržava upload privitaka, ovo je put. Prva faza: **ne koristimo**, samo glavni PDF.

#### `DohvatiPodatkePismenaPoUredbi(userName, jedinstveniIdentifikatorPismena | jop) → PismenoInfoUredba`

Read-only dohvat. Vraća status pismena (`status`, `UI`), urudžbeni broj (`urBroj`), datume. Koristit ćemo ga za nightly job koji sinkronizira eGOP status u STR (ako se odlučimo na taj job — → pitanje #11).

#### `KreirajIzlaznoPismenoPoUredbi`

*Iz STR-a se ne poziva.* MINT službenik radi rješenje (izlazno pismeno) ručno u eGOP-u; ako uopće trebamo dohvatiti izlazno pismeno → koristimo `DohvatiPodatkePismenaPoUredbi`.

---

## 6. Integracijski tok za STR registraciju objekta

Polazni uvjet: validan i submitan zahtjev (sve GO-1…GO-5 provjere prošle, `sso.status = AKTIVAN`, generiran PDF).

```
                ┌────────────────────────────────────────────────────┐
                │ 1. Lookup ili create subjekta (iznajmljivač)        │
                │                                                      │
                │   a) DohvatiPodatkeSubjekta(OIB)                    │
                │      └── ako vrati subjekt → koristi oznakaSubjekta │
                │   b) inače KreirajSubjektaProsireno(...)            │
                │      └── dobijemo oznakaSubjekta                    │
                └────────────────────────┬─────────────────────────────┘
                                         │
                                         ▼
                ┌────────────────────────────────────────────────────┐
                │ 2. KreirajPredmet2(...)                              │
                │      vrstaPredmeta = <šifra za STR registraciju>    │
                │      upisnaKnjiga  = UP/I                            │
                │      subjektOznaka = <iz koraka 1>                   │
                │      nadleznaOrgJedinica = <po županiji>             │
                │   → uredskaGodina, rbrPredmeta, klasifikacijskaOznaka│
                └────────────────────────┬─────────────────────────────┘
                                         │
                                         ▼
                ┌────────────────────────────────────────────────────┐
                │ 3. KreirajPismenoPoUredbi(...)                       │
                │      rbrSpisa     = rbrPredmeta                      │
                │      uredskaGodina= uredskaGodina                    │
                │      vrstaPismena = <šifra "zahtjev za registraciju">│
                │      subjektOznaka= <iz koraka 1>                    │
                │   → jop, jedinstveniIdentifikatorPismena, jedOznaka  │
                └────────────────────────┬─────────────────────────────┘
                                         │
                                         ▼
                ┌────────────────────────────────────────────────────┐
                │ 4. KreirajDokumentZaPismenoPoUredbi(...)             │
                │      jedinstveniIdentifikatorPismena = <iz koraka 3> │
                │      extension = "pdf"                                │
                │      attachment = Base64(pdfContent)                 │
                │   → BaseInfo (operationSucceeded)                    │
                └────────────────────────┬─────────────────────────────┘
                                         │
                                         ▼
                ┌────────────────────────────────────────────────────┐
                │ 5. Persist u STR str_rn.submission                   │
                │      filing_number = jedinstvenaOznakaPismena        │
                │      egop_uredska_godina, egop_rbr_predmeta          │
                │      egop_klasifikacijska_oznaka                     │
                │      egop_jop, egop_pismeno_guid                     │
                │      egop_sync_status = SYNCED                       │
                └────────────────────────────────────────────────────┘
```

### 6.1 Atomarnost

eGOP nema XA transakcije. Tok 1→4 je sekvenca neovisnih SOAP poziva. Posljedice:

- Ako padne između koraka 2 i 3 — imamo predmet bez pismena. Nije kraj svijeta (predmet je prazna ljuska), ali treba "izlječnik": retry job koji u tom slučaju pokušava ponovo kreirati pismeno koristeći već dobivenu `(uredskaGodina, rbrPredmeta)`.
- Ako padne između koraka 3 i 4 — imamo pismeno bez PDF-a. Retry job poziva `KreirajDokumentZaPismenoPoUredbi` koristeći već dobiveni GUID.

### 6.2 Predloženi state machine za eGOP sinkronizaciju

Dodamo u `str_rn.submission` polje `egop_sync_status`:

```
NEW → SUBJEKT_OK → PREDMET_OK → PISMENO_OK → DOKUMENT_OK (= SYNCED)
                                                       ↘ FAILED (zahtijeva ručnu intervenciju)
```

Svaki uspješan korak commitamo u zasebnoj transakciji + audit row. Job radi reconciliation za sve `submission` zapise čiji `egop_sync_status != SYNCED && updated_at < now() - 5min`.

### 6.3 Asinkrona priroda

Frontend ne čeka eGOP. Submit forme:

1. Validira (GO-1…GO-5, sinkrono).
2. Generira PDF (sinkrono).
3. Persistira `submission` u stanju `egop_sync_status = NEW`.
4. Odgovori frontendu **bez čekanja eGOP-a** s STR-ovim `filingNumber`-om (interni `HR12345678`, *ne* eGOP klasifikacijska oznaka).
5. Asinkrono enqueue eGOP sync (Spring `@Async`, ili scheduled `egopSyncJob`).

Kad eGOP sinkronizacija završi, dopune se eGOP polja u već postojećem `submission` zapisu. Korisnik može kroz "moji zahtjevi" vidjeti eGOP klasifikacijsku oznaku kad postane dostupna.

---

## 7. Mapiranje na STR shemu

Trenutno `str_rn.submission` ima:

| Stupac                  | Trenutni semantički sadržaj                                          |
| :---------------------- | :------------------------------------------------------------------- |
| `submission_id` (UUID)  | STR-ov primarni ključ                                                |
| `filing_number`         | Trenutno STR-ov interni `HR + 8 znamenki`                            |
| `document_link`         | *"URI dokumenta pohranjenoga u eGOP sustavu"* — **kriva semantika** (eGOP ne vraća URL) |
| `pdf_content`           | byte[] PDF                                                            |
| `lessor_id`             | FK na STR iznajmljivača                                              |
| `authority_id`          | STR nadležno tijelo                                                  |
| `status`                | `IN_PROCESSING` / …                                                  |
| `filing_date`           | Trenutak submita                                                     |

Predložene izmjene (novi Liquibase changeset, **bez** brisanja postojećih stupaca dok ne migriramo podatke):

```
ALTER TABLE str_rn.submission
  ADD COLUMN egop_uredska_godina         INT,
  ADD COLUMN egop_rbr_predmeta           INT,
  ADD COLUMN egop_klasifikacijska_oznaka VARCHAR(64),
  ADD COLUMN egop_pismeno_jop            INT,
  ADD COLUMN egop_pismeno_guid           UUID,
  ADD COLUMN egop_pismeno_oznaka         VARCHAR(64),
  ADD COLUMN egop_subjekt_oznaka         INT,
  ADD COLUMN egop_sync_status            VARCHAR(32) NOT NULL DEFAULT 'NEW',
  ADD COLUMN egop_synced_at              TIMESTAMP,
  ADD COLUMN egop_last_error             TEXT;

CREATE UNIQUE INDEX uk_submission_egop_predmet
  ON str_rn.submission(egop_uredska_godina, egop_rbr_predmeta)
  WHERE egop_uredska_godina IS NOT NULL;

CREATE UNIQUE INDEX uk_submission_egop_guid
  ON str_rn.submission(egop_pismeno_guid)
  WHERE egop_pismeno_guid IS NOT NULL;
```

`document_link` se postupno odbacuje (poslije ćemo dropati u zasebnom changesetu kad budemo sigurni da nigdje ne piše).

`filing_number` ostaje **STR-ov** broj (HR-prefix), ne eGOP-ov. eGOP klasifikacijsku oznaku držimo u `egop_klasifikacijska_oznaka`, jer su to dvije neovisne stvari (STR ima vlastiti reg.broj koji pokazujemo korisniku odmah; eGOP klasifikacijska oznaka dolazi tek nakon uspješne sinkronizacije).

`lessor` snapshot je već immutable po pravilima projekta. Polje `egop_subjekt_oznaka` pripada **submissionu, ne iznajmljivaču**, jer iznajmljivač može imati više predmeta u eGOP-u, a oznakaSubjekta je per-iznajmljivač globalna — možemo je cachirati i na `iznajmljivac` snapshotu kao optimizaciju za 2. fazu.

---

## 8. Operativni aspekti

### 8.1 Idempotencija

eGOP **nema** request idempotency keys. Posljedice:

- `KreirajSubjektaProsireno` s istim OIB-om dva puta:
  - Spec implicira da se isti OIB ne smije ponoviti kao eksterni subjekt → drugi poziv vrati grešku (vjerojatno `-701`). To je *de facto* zaštita od duplikata, ali se moramo osloniti na `DohvatiPodatkeSubjekta` *prije* kreacije.
- `KreirajPredmet2` dva puta s istim parametrima — **otvori dva predmeta**. Mora postojati STR-ov idempotency guard prije poziva: `submission.egop_sync_status != NEW` znači "predmet je već otvoren, ne zovi ponovo".
- `KreirajPismenoPoUredbi` dva puta — **kreira dva pismena** unutar istog predmeta. Ista zaštita kao gore.
- `KreirajDokumentZaPismenoPoUredbi` dva puta na istom pismenu — → pitanje #12 (briše li drugi poziv prvi dokument, ili pada).

**Konkluzija:** state machine `egop_sync_status` *jest* naš idempotency mehanizam. Svaki korak gleda trenutni status, preskače već gotovo, izvršava sljedeće, commita status.

### 8.2 Retry i timeout

- **Connect timeout** 5s, **read timeout** 30s (default predlog; eGOP nije real-time, ali ne smije visiti).
- **Retry policy**: 3 pokušaja s exponential backoff (1s, 4s, 16s) **samo na tehničke greške** (HTTP 5xx, SocketTimeoutException, javax.xml.ws.WebServiceException network root). Biznis greške (`operationSucceeded=false`) se ne retry-aju automatski.
- **Circuit breaker**: Resilience4j s `slidingWindow=10`, `failureRateThreshold=50`, `waitDurationInOpenState=60s`. Kad je open, `egopSyncJob` parkira poslove u stanju `RETRY_LATER` i pokuša poslije.
- **Dead-letter**: nakon 5 neuspjelih sinkronizacija isti submission, `egop_sync_status = FAILED`, alert ide na ops i traži ručnu intervenciju.

### 8.3 Logiranje

eGOP zahtjevi i odgovori se logiraju **bez PDF sadržaja** (samo veličina i hash). Logirati:

- Trenutak, URL, metoda, request body bez attachment polja.
- Response: `operationSucceeded`, sve `ErrorStatus` redove.
- Korelacijski ID (STR `submission_id`) na svakom retku.

PDF Base64 stream NIKAD u log (i veličina i potencijal PII).

### 8.4 Tlow MDM servisa

Spec spominje `ServiceMDM` u listi adresa ali ne opisuje metode u dokumentu. Vjerojatno je to "master data management" za šifrarnike (`vrstaPredmeta`, `vrstaPismena`, `idUlogePartnera`, `tipOsobe`, …). → pitanje #13.

---

## 9. Konstrukcija Java klijenta

Predloženi paketni izgled (zasebno od ostatka koda, fora "anti-corruption layer"):

```
com.str.backend.egop
├── EgopProperties.java           // @ConfigurationProperties("egop") — URLs, credentials, timeouts
├── EgopClientConfig.java          // beans: SoapMessageFactory, WebServiceTemplate per-servis
├── EgopSyncJob.java               // @Scheduled reconciliation worker
├── EgopSyncService.java           // orchestrator: state machine over submission.egop_sync_status
├── client
│   ├── SubjektClient.java        // wrapper za ServiceSubjekt metode
│   ├── PredmetClient.java
│   └── PismenoClient.java
├── dto
│   └── ...                       // generirani iz WSDL-a kroz wsdl2java (Apache CXF) u target/generated-sources
└── exception
    ├── EgopBusinessException.java // operationSucceeded=false (poslovna greška)
    ├── EgopAuthorizationException.java // -700
    └── EgopTechnicalException.java // network/parse — retry candidate
```

> Generiranje klijenta: WSDL-i su public — `cxf-codegen-plugin` u Maven build phase generira JAXB tipove i `*PortType` interfejs. Klijent ne treba pisati ručno.

Authoritativni endpoints u `application-{profile}.properties`:

```
egop.test.subjekt-url=https://egopeaitest.mint.hr/ServiceSubjekt.asmx
egop.test.predmet-url=https://egopeaitest.mint.hr/ServicePredmet.asmx
egop.test.pismeno-url=https://egopeaitest.mint.hr/ServicePismeno.asmx
egop.test.mdm-url=https://egopeaitest.mint.hr/ServiceMDM.asmx
egop.username=${EGOP_USERNAME}    # u .env / Railway secrets, ne u repo
egop.password=${EGOP_PASSWORD}
egop.ad-username=${EGOP_AD_USERNAME}  # DOMENA\racun za sve "username" parametre
egop.vrsta-predmeta=??   # → pitanje #6
egop.vrsta-pismena=??    # → pitanje #9
egop.org-jedinica-mapping=??  # → pitanje #7
```

---

## 10. Pitfalls i smjernice

1. **HTTP 200 ≠ success.** Uvijek prvi citiraj `operationSucceeded`.
2. **Username uvijek `DOMENA\racun`.** Bez prefiksa → `-100`.
3. **Predmet stvori jednom.** Bez idempotency guarda u STR-u ćemo umnožavati predmete na svaki retry.
4. **Datumi su lokalni server time ako ne pošaljemo eksplicitno.** Šaljemo eksplicitno (ISO 8601 ili XML xsd:dateTime).
5. **PDF prelazi 4MB?** SOAP encoding s Base64 napuhuje ~33%. Provjeriti server limit (→ pitanje #14).
6. **Ne dirati legacy metode** (`KreirajPismeno`, `KreirajPismeno2`) — koristiti samo "PoUredbi".
7. **MDM servis nije dokumentiran.** Ne pozivati dok ne dobijemo spec ili usmenu suglasnost.
8. **Spec verzija 1.11 (10/2025).** Pratiti revizije; svaka nova kolona ulaza je mogući breaking change.

---

## 11. Otvorena pitanja

Sva otvorena pitanja, organizirana po prioritetu, vidi u `docs/eGOP-otvorena-pitanja.md`.
