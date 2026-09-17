# FE brief — primjedbe sa sastanka 10.09.2026.

**Repo:** `Kodelabers/str_frontend` · **Backend grana:** `feat/primjedbe-sastanak-10-09`
**Datum:** 11.09.2026.

Backend za sve niže je **gotov i testiran** (576 testova). Nijedna stavka ne traži novi endpoint
osim gdje je izričito napisano. Numeracija prati zapisnik sa sastanka.

> **Gdje se prezentira:** na **test okolini** (`https://str-test-eturizam.gov.hr`, profil `cdu`),
> ne na predprodukciji — ondje se ne smije zbog GDPR-a (radi s produkcijskim podacima).
> Sve backend postavke iz ovog briefa uključene su na `cdu` profilu.

---

## Sažetak

| # | Stavka | Tip |
|---|---|---|
| 3 | Zaključana polja + uputa za zahtjev za promjenom | FE polovica |
| 4 | Predpopuna postojećeg objekta | FE polovica |
| 5 | Kontakt blok, obavezan | FE polovica |
| 7+14 | Ne prikazivati zahtjev, samo obavijest | FE polovica |
| 10 | Maknuti gumb „Upiši novi smještajni objekt" | **čisto FE** |
| 11 | PDF u novom tabu | FE polovica |
| 12 | Blokada submita dok captcha nije označena | FE polovica |
| 15 | Zvjezdica na checkboxu izjave | **čisto FE** |
| 17 | Upload fizičkog rješenja + osnovna polja | FE polovica |
| 18 | Označiti uploadana rješenja u popisu | FE polovica |
| 19 | Maknuti „Novi objekt" iz lijevog menija | **čisto FE** |
| 20 | Crvena napomena o rješenju o kategorizaciji | **čisto FE** |

---

## 3. Zaključana polja + uputa za zahtjev za promjenom

Kad korisnik bira postojeći objekt, dio polja se **ne smije mijenjati** — podaci dolaze iz
kategorizacije (STR1) i mijenjaju se zasebnim zahtjevom za promjenom.

**Izvor istine je odgovor backenda, ne tuStart URL.** Query string korisnik može urediti prije
submita; `zakljucanaPolja` računa ista logika koja zahtjev i odbija, pa se to dvoje ne može
razići.

```
GET /api/nias/facilities/{facilityId}
→ { ..., "zakljucanaPolja": ["typeId", "maxBeds", "name", "countyId",
                             "cityId", "settlementId", "street", "streetNumber"] }
```

Popis nosi **imena polja iz tijela** `POST /api/generateRegistrationNumber`, pa se mapiraju
1:1 na inpute. Polje kojeg **nema** na popisu korisnik smije i treba popuniti — eTurizam za
njega nema podatak (prazan naziv, ulica `-` i slično).

- Onemogući svaki input čije je ime na popisu.
- Uz zaključano polje ispiši uputu da se promjena traži zasebnim zahtjevom. **Tekst dogovoriti
  s naručiteljem** — sam proces „zahtjev za promjenom" još ne postoji ni na jednoj strani.
- Ako korisnik ipak pošalje izmijenjeno zaključano polje, backend vraća **400** s ključem
  `error.facility.*.mismatch`.

## 4. Predpopuna postojećeg objekta

Isti endpoint kao gore. Puni oblik odgovora:

```json
{
  "id": "1448035",
  "naziv": "Apartman More",
  "vrstaSifra": "FS_APARTMAN",
  "brKreveta": 4,
  "zupanijaNaziv": "Splitsko-dalmatinska",
  "opcinaNaziv": "Split",
  "naseljeNaziv": "Split",
  "ulicaNaziv": "Marulićeva",
  "kucniBrojNaziv": "5",
  "postanskiBroj": "21000",
  "kontaktEmail": null,
  "kontaktTelefon": null,
  "zakljucanaPolja": ["typeId", "maxBeds", "name", "..."]
}
```

**Adresne razine dolaze kao nazivi, ne ID-evi** — treba ih razriješiti u autocompleteu preko
`GET /api/address/*`. To je namjerno: eTurizam i adresni registar imaju različite ID prostore.

`postanskiBroj`, `kontaktEmail` i `kontaktTelefon` su **novi** u ovom odgovoru.

> ⚠️ **Kontakt se NEĆE predpopuniti — računaj da je uvijek prazan.** Izmjereno na obje
> okoline, uzorak 100 000 aktivnih objekata:
>
> | | e-mail | telefon |
> |---|---|---|
> | **CDU (test — gdje se prezentira)** | **0 od 100 000 (0 %)** | 28 od 100 000 (0,03 %) |
> | predprodukcija | 233 (0,2 %) | 53 (0,1 %) |
>
> Naručiteljeva pretpostavka „za postojeći objekt kontakt sigurno postoji" na ovim podacima ne
> stoji — na test okolini je polje prazno u **svih 100 %** slučajeva. Kontakt blok se zato uvijek
> ponaša kao prazan obrazac koji korisnik popunjava.
>
> Poštanski broj je naprotiv pouzdan: naselja su 100 % pokrivena na obje okoline.

## 5. Kontakt blok — obavezan

Novi blok u dijelu *Podaci o iznajmljivaču*. Kontakt se sprema u **STR shemu**
(`str_rn.lessor`), ne u TuStart — registar subjekata je NIAS.

Nova polja u tijelu `POST /api/generateRegistrationNumber`:

| Polje | Obavezno | Ograničenje | Greška |
|---|---|---|---|
| `kontaktEmail` | **da** | ispravan e-mail, ≤ 255 | 400 |
| `kontaktMobitel` | **da** | ≤ 32 | 400 |
| `kontaktTelefon` | ne | ≤ 32 | — |
| `kontaktOsoba` | ne | ≤ 128 | — |

- Za postojeći objekt predpopuni iz `FacilityClaimResponse`, ali **ostavi izmjenjivim** —
  kontakt nije zaključan podatak i zastarjeli e-mail mora biti ispravljiv.
- NIAS ne vraća kontakt, pa ga za novog iznajmljivača korisnik uvijek upisuje.
- Prazan string se na backendu tretira kao „nije upisano" i sprema kao `null`.

Na `POST /api/generateRegistrationNumberExternal` (prijavljeni non-EU iznajmljivač)
**`kontaktMobitel` je isto obavezan**, a `kontaktEmail` nije:

| Polje | NIAS | non-EU (prijavljen) |
|---|---|---|
| `kontaktEmail` | **obavezan** | neobavezan — račun ga već ima i ne mijenja se |
| `kontaktMobitel` | **obavezan** | **obavezan** |
| `kontaktTelefon`, `kontaktOsoba` | neobavezni | neobavezni |

Razlog asimetrije: samoregistracija non-EU iznajmljivača **traži e-mail**, pa `lessor` nikad nije
bez njega — i ne bi ga se moglo prepisati jer je stupac identitet računa. Telefon je ondje bio
**neobavezan**, pa je iznajmljivač mogao ostati bez ijednog broja; zahtjev za RB je jedino mjesto
gdje se ta rupa zatvara. Neobavezna polja koja ne pošalješ **ne brišu** zatečenu vrijednost.

## 7 + 14. Ne prikazivati zahtjev, samo obavijest

Nakon izdavanja RB-a korisnik vidi **jedan** dokument — Obavijest o dodjeli registracijskog broja.

```
GET /api/rn/{rn}/documents
→ [ { "slug": "dodjela", "naziv": "Obavijest o dodjeli registracijskog broja",
      "smjer": "IZLAZNO", "izdano": "2026-09-11",
      "href": "/api/rn/HR.../documents/dodjela" } ]
```

Podnesak (`slug: "zahtjev"`) **više se ne pojavljuje u popisu** na test i predprodukcijskoj
okolini. Ako FE renderira popis iz ovog odgovora, ništa se ne mora mijenjati.

> Podnesak i dalje postoji i dohvatljiv je na `/api/rn/{rn}/documents/zahtjev` — nosi urudžbeni
> broj 1 i po ZUP-u je dio spisa, pa ga se nije smjelo ukinuti. **FE ga jednostavno ne smije
> nuditi ni linkati nigdje.** Isto vrijedi za
> `/api/generateRegistrationNumber/{submissionId}/pdf` — ako se negdje koristi za prikaz
> korisniku, maknuti.

## 10. Maknuti gumb „Upiši novi smještajni objekt"

Ekran *Detalji smještajnog objekta*. Bez backend promjene.

## 11. PDF u novom tabu

Backend sad šalje `Content-Disposition: inline` (bilo `attachment`) na:
- `GET /api/rn/{rn}/documents/{tip}`
- `GET /api/rn/{rn}/documents/pohranjeno/{aktId}`

**Otvarati kao top-level navigaciju** — `window.open(url, '_blank')`, ne `<a download>` i ne
`fetch`:

- Novi tab je obična GET navigacija, pa sesijski kolačić putuje pod `SameSite=Lax` i
  autorizacija prolazi. `fetch` bez `credentials: 'include'` vraća **401**.
- `<iframe>` **ne radi** — vrijedi Springov default `X-Frame-Options: DENY`.

Izvozi (`/api/rn/export/*`, statistike) namjerno ostaju `attachment` — to su datoteke za
preuzimanje, ne za čitanje.

## 12. Blokada submita dok captcha nije označena

**Submit mora biti onemogućen dok kvačica nije označena**, uz validaciju i na samom submitu.

Kontekst, jer je bilo zabune: ALTCHA **postoji na backendu i uključena je na test okolini**
(`cdu`) — ondje ima HTTPS, pa `crypto.subtle` radi. Ugašena je samo na predprodukciji, koja je
čisti HTTP, a Web Crypto traži siguran kontekst.

- Zaglavlje `X-Altcha` s riješenim izazovom (`GET /api/captcha/challenge`) šalje se na:
  `POST /api/registerLessor`, `POST /api/generateRegistrationNumber`, `GET /api/verify/{rn}`.
- Bez zaglavlja ili s neispravnim → **422**.
- `VITE_CAPTCHA_ENABLED` mora pratiti backendov `APP_CAPTCHA_ENABLED` po okolini: FE uključen a
  backend isključen prolazi, obrnuto sve puca s 422.

## 15. Zvjezdica na checkboxu izjave

Checkbox „izjavljujem da su podaci točni" je **obavezno polje** — dodaj zvjezdicu i blokiraj
submit dok nije označen. Backend to polje ne prima ni ne provjerava.

## 17. Upload fizičkog rješenja + osnovna polja

Treći slučaj: iznajmljivač ima papirnato rješenje kojeg nema među postojećim objektima.
Forma ide **ispod popisa smještajnih objekata**.

```
POST /api/nias/categorization-decisions      (multipart/form-data)
```

| Polje | Obavezno | Napomena |
|---|---|---|
| `datoteka` | **da** | PDF / JPEG / PNG, do 10 MB; tip se provjerava po sadržaju, ne po nastavku |
| `nazivObjekta` | ne | ≤ 255 |
| `vrstaSifra` | ne | ≤ 64, mora biti postojeća šifra vrste (`FS_*`) |
| `adresa` | ne | ≤ 500 |
| `brojRjesenja` | ne | ≤ 64 |
| `datumRjesenja` | ne | ISO datum, ne u budućnosti |
| `brKreveta` | ne | pozitivan cijeli broj |
| `napomena` | ne | ≤ 1000 |

Odgovor **201**: `{ decisionId, status: "SUBMITTED", fileName, fileSize, uploadedAt }`.
Greške: prazna datoteka → `error.categorization.file.empty`, krivi tip →
`error.categorization.file.type`, prevelika → **413**.

**Danas FE šalje samo `datoteka`.** Sva ostala polja već postoje na backendu i treba ih
ponuditi u formi — to je cijela stavka.

Traži se i drugi put: **objekt bez ikakvog rješenja**, s izraženim upozorenjem (vidi 20).

## 18. Označiti uploadana rješenja u popisu

Backend ih **već vraća** u istom popisu objekata, razlikuje se poljem `izvor`:

```
GET /api/nias/facilities
→ items[].izvor = "ETURIZAM" | "PRIVREMENO_RJESENJE"
```

`PRIVREMENO_RJESENJE` = sken koji je korisnik uploadao, još nije u eTurizmu. Takav red nosi
samo ono što je korisnik uz sken upisao, pa su mu većina polja `null`. Treba ga vizualno
označiti i jasno reći gdje se nalazi i što se s njim dalje događa (ide ovlaštenoj osobi na
nadopunu i unos u STR1).

## 16b. Katastar — backend ga čita iz registra

Autocomplete uz kućni broj vraća i katastar, za prikaz:

```
GET /api/address/house-numbers?ulicaId=...
→ [ { "id": 93224, "name": "34", "kcBroj": "608", "katOpcinaNaziv": "BRANJIN VRH" } ]
```

**U zahtjev se šalje `kucniBrojId`** — `id` odabranog kućnog broja. Forma ga već drži u
`kucniBrojId`, samo ga dosad nije slala. Iz njega backend sam pročita katastarsku općinu, česticu
i registarsku šifru, jer katastar ide u ZUP akte, a podatak iz preglednika može se urediti prije
submita.

| Polje u zahtjevu | Što backend radi |
|---|---|
| `kucniBrojId` | **šalje se** — iz njega se čita katastar |
| `kcBroj` | uzima se **samo** kad registar za taj kućni broj nema česticu |
| `katOpcinaNaziv` | **ignorira se** — dolazi iz registra |
| `houseNumberCode` | **ignorira se** — puni ga backend |

Nepoznata polja backend preskače, pa `katOpcinaNaziv` i `houseNumberCode` smiju ostati u
payloadu dok se ne počiste — ništa ne puca.

**Dvije FE izmjene:**

1. Dodati `kucniBrojId: values.kucniBrojId ?? null` u `buildPayload` na **obje** stranice
   (`RegistrationNumberPage.handlers.ts` i `RegistrationNumberExternalPage.handlers.ts`).
2. **Zaključati polje čestice kad je registar vratio `kcBroj`.** Kad registar ima česticu,
   drukčija vrijednost vraća **400** — povučeni podaci se ne mijenjaju, za to postoji zahtjev za
   promjenom. Polje ostaje otvoreno samo kad je `kcBroj` iz registra prazan (na CDU 28,7 % adresa).

Greške:

| Ključ | Kada |
|---|---|
| `error.cadastre.parcel.mismatch` | poslana čestica se razlikuje od one u registru |
| `error.cadastre.houseNumber.unknown` | `kucniBrojId` ne postoji |
| `error.cadastre.houseNumber.mismatch` | `kucniBrojId` ne pripada poslanoj ulici i broju — forma ih puni iz istog retka, pa se ovo događa samo ako se payload sastavlja ručno |

> ⚠️ **Dok FE ne šalje `kucniBrojId`, backend nema iz čega pročitati katastar:** katastarska
> općina se ne sprema, a čestica samo ako ju je korisnik upisao. Ništa ne vraća grešku — samo se
> podatak tiho gubi. Izmjena 1 mora ući zajedno s backendom.

## 19. Maknuti „Novi objekt" iz lijevog menija

To je forma, ne ruta — sve je dostupno s homepagea. Bez backend promjene.

## 20. Crvena napomena o rješenju o kategorizaciji

Na ekranu novog objekta, **crvenim slovima**: obavezno je dostaviti rješenje o kategorizaciji,
u roku od **30 dana**.

> Backend **nema** pojam tog roka — nema polja, schedulera ni podsjetnika. Ovo je zasad čisti
> tekst na ekranu. Ako se traži da rok bude podatak (odbrojavanje, podsjetnik, izvještaj), to je
> zaseban zahvat i treba ga naručiti.

---

## Što NIJE u ovom briefu

- **Stavka 2 (oznaka smještajnog objekta)** — čeka odgovor naručitelja. U `str.facility` nema
  stupca „oznaka": `quality_mark` je boolean, `registration_mark` je registarska oznaka plovila
  (0,3 %). Ostaju dva čitanja: `external_uid` (84,2 % popunjen) ili standardizirana ploča
  vrste i kategorije, koju već vraćamo kao `vrstaNaziv` + `kategorija`.
- **Stavka 8 (template obavijesti)** — čeka se predložak od ministarstva.
- **Stavka 13 (što ide u korisnički pretinac)** — čeka poslovnu odluku. RB je upotrebljiv
  odmah po izdavanju, to već vrijedi.
- **Interni pregled uploadanih rješenja po roli** — endpointi postoje, ali role-gate čeka NIAS
  role (BX0), pa su zasad otvoreni svakom prijavljenom korisniku.
