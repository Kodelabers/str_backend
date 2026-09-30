# e-Ovlaštenja — djelovanje u ime pravne osobe (e-Zastupanja)

Stavka 2: iznajmljivač može biti **pravna osoba**. Zastupnik se prijavi NIAS-om, navede OIB tvrtke,
a backend kroz e-Ovlaštenja (modul **e-Zastupanja**) provjeri je li on zakonski zastupnik te tvrtke.
Tek tada korisnik djeluje u ime tvrtke i RB se izdaje na nju.

Opseg: samo e-Zastupanja (zakonski zastupnici po registru). e-Punomoći i registracijski obrazac za
dodjelu prava se **ne** rade. Prva faza pokriva samo `IZVOR_REG=1` (tvrtke po OIB-u); obrt i OPG su
otvoreni.

## Tijek

```
1. NIAS prijava               → assertion: oib, ime, prezime, tid, sesija_id
1a. GET /api/nias/acting-subject/options → GetNavigationData: tvrtke koje osoba zastupa (za izbornik)
2. POST /api/nias/acting-subject {"oib": "<OIB tvrtke>"}   (odabir iz izbornika ili upis OIB-a)
3. backend → e-Ovlaštenja     GetAuthorizationUnionPermission (mTLS, XML)
                              Sesija_Id, PersonOIB, JipsTo = IdentfiersFor = tvrtka
4. e-Ovlaštenja → backend     SignedAuthorizationUnionPermissionResponse (XML-DSig)
5. potvrđeno zastupanje       → ActingSubject u server-side sesiji
6. sve vezano uz vlasnika     → OIB tvrtke (EffectiveOibResolver)
7. radnja koja mijenja stanje → ponovna provjera (3–4), pa tek onda radnja:
                              izdavanje RB-a (RB na tvrtku), povlačenje RB-a, upload rješenja
```

**Popis tvrtki** dolazi iz `GetNavigationData` (`NavigationDataRequest` — „e-Usluga šalje zahtjev za
dohvatom sadržaja navigacijske trake"). Odgovor po shemi **nije potpisan**, pa popis samo predlaže:
u njega idu tvrtke (`IZVOR_REG=1`) na temelju e-Zastupanja (`BasedOnRepresentation=true`), unutar same
tvrtke ili kao građanin; e-Punomoći i djelovanje kao djelatnik druge tvrtke ne ulaze. Odabir iz popisa
ide istim `POST`-om i potpisanom provjerom. Popis se u sesiji čuva 5 minuta, po osobi.

**Izbor subjekta je vlastiti** (izbornik ili unos OIB-a), ne FINA-ina zajednička navigacijska traka: njezin
`change_entity_url` registriran je uz uslugu, pa bi uz posuđeni identitet InterniTurizma vodio na
njihov URL (isti razlog zašto ne radi odjava, v. memoriju o NIAS registraciji).

**Ponovna provjera** (`EffectiveOibResolver.reverifiedActingSubject`): odabir u sesiji vrijedi do
odjave, a zastupanje je moglo prestati. Zato se prije izdavanja RB-a, povlačenja RB-a (nepovratno)
i uploada rješenja zastupanje ponovo potvrđuje. Neuspjeh briše subjekt iz sesije i radnja se ne
izvodi. Čitanja (popisi, PDF, akti) koriste odabir iz sesije bez novog poziva.

## Endpointi

| Endpoint | Ishod |
|---|---|
| `POST /api/nias/acting-subject` `{"oib"}` | 200 `ActingSubjectResponse` · 429 previše odabira (`EOVLASTENJA_RATE_LIMIT`, v. niže) · 400 neispravan OIB (`error.actingSubject.invalidOib`), vlastiti OIB (`error.actingSubject.self`) ili tvrtka ne postoji (`EOVLASTENJA_SUBJECT_NOT_FOUND`) · 403 niste zastupnik (`EOVLASTENJA_NOT_REPRESENTATIVE`) · 401 nevažeća NIAS sjednica (`EOVLASTENJA_SESSION`, samo FINA šifre 200–203) · 503 e-Ovlaštenja nedostupna, nisu uključena na okolini ili prijava ne nosi `sesija_id` (`details.registry = EOVLASTENJA`) |
| `GET /api/nias/acting-subject/options` | 200 `[{ oib, naziv }]` sortirano po nazivu (`naziv` može biti `null`); prazan popis i kad osoba nije u e-Ovlaštenjima (FINA 400/401/500) · 503 `EOVLASTENJA_OPTIONS_UNAVAILABLE` (`details.registry=EOVLASTENJA`) kad popis nije dostupan: FINA nedostupna, šifra 100, isključeno na okolini ili FINA ne prihvaća sjednicu — tada ostaje upis OIB-a. **Nikad 401 zbog FINA-e** (popis frontend dohvaća sam; 401 samo bez NIAS prijave). Uspjeh se u sesiji čuva 5 min, neuspjeh 60 s |
| `GET /api/nias/acting-subject` | 200 subjekt · 204 korisnik djeluje u svoje ime |
| `DELETE /api/nias/acting-subject` | 204 — povratak na djelovanje u svoje ime |
| `GET /api/nias/subject` | u svoje ime kao i dosad, uz `pravnaOsoba = null`. U ime tvrtke: `oib`/`ime`/`prezime` **zastupnika**, adresa `null` (prebivalište zastupnika se ne traži u registru — iznajmljivač je tvrtka) i `pravnaOsoba` s podacima o tvrtki |

`ActingSubjectResponse`: `oib`, `naziv`, `funkcije[]`, `zastupnikOib`, `zastupnikIme`,
`zastupnikPrezime`, `provjereno`, `izvor = "E_OVLASTENJA"`.

Greške nose `details.code` (`EOVLASTENJA_SESSION` / `EOVLASTENJA_NOT_REPRESENTATIVE` /
`EOVLASTENJA_SUBJECT_NOT_FOUND`) i, kad postoji, `details.eovlastenjaCode` (šifra iz FINA šifarnika).
Iste greške (401/403/400/503) može vratiti i ponovna provjera pri izdavanju RB-a, povlačenju i uploadu.

**401 znači samo „NIAS sjednica je istekla"** — ponovna prijava pomaže. Prijava bez `sesija_id` je
stanje registracije usluge, ne sjednice: ponovna prijava ga ne donosi, pa je to 503 (inače bi
frontend korisnika vrtio u krug na NIAS).

## Zaštita od promjene subjekta (409 `ACTING_SUBJECT_CHANGED`)

Odabrana tvrtka vrijedi za cijelu sesiju, pa i za druge prozore. Bez zaštite bi obrazac otvoren u svoje
ime, predan nakon odabira tvrtke u drugom prozoru, izdao RB tvrtki (TOCTOU). Zato radnje nose za koga
su pripremljene, a `ActingSubjectGuard` to uspoređuje sa sesijom **prije** ponovne potvrde kroz
e-Ovlaštenja (za odbijen zahtjev nema poziva FINA-i):

| Radnja | Očekivani vlasnik |
|---|---|
| `POST /api/generateRegistrationNumber` | `oib` iz tijela (OIB tvrtke u ime tvrtke, osobni u svoje ime) — više se **ne zamjenjuje tiho**; i zaglavlje, ako je poslano |
| `POST /api/nias/registrations/{rn}/withdraw`, `POST /api/nias/categorization-decisions`, `POST/PUT/DELETE /api/drafts` | zaglavlje `X-Acting-Subject`: OIB tvrtke ili `SELF`; bez zaglavlja nema provjere (kompatibilnost) |

Neslaganje → **409**, `details = { code: "ACTING_SUBJECT_CHANGED", current: "<OIB tvrtke iz sesije>" | null }`,
ništa nije izvedeno. Zaglavlje koje nije OIB ni `SELF` → 400 (`error.actingSubject.invalidHeader`).
Bez NIAS-a (local/mock) OIB iz tijela u svoje ime ostaje slobodan (lokalno se testiraju razni
iznajmljivači); u ime tvrtke (mock par) provjera vrijedi i lokalno.

## Ograničenje odabira (429 `EOVLASTENJA_RATE_LIMIT`)

Svaki odabir ide FINA-i, a razlika „tvrtka ne postoji" / „niste zastupnik" bez ograničenja služi za
ispitivanje OIB-ova. Klizni prozor po NIAS osobi i po sesiji: **10 u minuti, 50 na sat**
(`app.eovlastenja.rate-limit.per-minute/-per-hour`). Neispravan i vlastiti OIB odbijaju se prije i ne
troše kvotu; odbijen pokušaj se ne broji. Prekoračenje → **429**, zaglavlje `Retry-After` i
`details = { code: "EOVLASTENJA_RATE_LIMIT", retryAfterSeconds }`. Pozitivan odgovor se ne kešira.
Ponovna potvrda pri radnjama nije ograničena i uvijek je svježa.

Dok je subjekt odabran, **OIB tvrtke** vrijedi za: `/api/nias/registrations`, `/facilities`,
`/facilities/{id}`, upload rješenja, povlačenje RB-a, akte `/api/rn/{rn}/documents/**`, PDF zahtjeva
i nacrte. Izvor je jedan: `EffectiveOibResolver`.

## Iznajmljivač u ime tvrtke

| Polje `lessor` | Vrijednost |
|---|---|
| `lessor_oib` | OIB tvrtke |
| `is_legal_entity_owner` | `true` |
| `legal_entity_name` | naziv iz e-Ovlaštenja (obavezan — odgovor bez naziva se odbija) |
| `representative_oib`, `legal_representative_name` | NIAS osoba (zastupnik) |
| `first_name`, `last_name` | ime i prezime zastupnika (NOT NULL stupci) |
| adresa | prazna — e-Ovlaštenja je ne daju (otvoreno pitanje). GO-1 zato daje `host=false`, označeno „pravna osoba: status domaćina se ne utvrđuje" |

PDF: „OIB" = OIB tvrtke, „Pravna osoba", „Osoba ovlaštena za zastupanje" = zastupnik s OIB-om.
ZUP akti: stranka = naziv i OIB tvrtke, zastupnik u zasebnom retku. eGOP: subjekt je tvrtka
(`tipOsobe` pravna, `lessorOib`).

## Provjera odgovora (`EOvlastenjaResponseParser`)

1. XML bez DOCTYPE-a i vanjskih entiteta.
2. Root `SignedAuthorizationUnionPermissionResponse` s `Id`; kao ID registriran **samo** root.
3. **Prije** `validate()` (koji izvršava transformacije iz dokumenta): jedina `Reference` je
   `#<root Id>` — zaštita od XML Signature Wrappinga — a algoritmi su s popisa: kanonikalizacija
   exc-c14n/c14n, potpis RSA-SHA256/384/512, digest SHA-1/256/512, najviše dvije transformacije
   (enveloped, c14n). Taj popis zamjenjuje secure validation, koji je isključen jer FINA koristi
   SHA-1 digest.
4. XML-DSig potpis valjan i napravljen **pinanim** certifikatom e-Ovlaštenja (ključ iz `KeyInfo`
   se ne koristi kao takav).
5. `ForRequestId` = `Id` našeg zahtjeva; osoba i tvrtka u odgovoru = one iz zahtjeva; tvrtka ima naziv.
6. Zastupanje = `Representation/**/Function` neprazno. `Authorization` (e-Punomoći) se ignorira.

Šifre grešaka (šifarnik „Popis grešaka-rest (V2)"): 200–203 → sjednica (401), 400/401 → nije
zastupnik ili nema privolu (403), 500 → tvrtka ne postoji (400), 100/402 → registracija usluge (503),
403/404 → informativno.

Iste šifre FINA vraća i **nepotpisano**, kao JSON `{"Code":"100","Message":…}` uz HTTP 400 (izmjereno
na CDU-u za šifru 100). Klijent ih čita i mapira istom tablicom (`EOvlastenjaErrorCodes`) — sve su
odbijanja, pa nepotpisanost ne može dati pravo, samo uskratiti.

## Transport

`HttpsURLConnection` (blokirajući `SSLSocket`, uvijek **HTTP/1.1**), bez redirecta, s timeoutima.
Izmjereno 29.09.2026. s javnog interneta:

- `roapiservistst.fina.hr` nudi h2 i klijentski certifikat **ne traži u prvom handshakeu**
  („No client certificate CA names sent"), nego naknadno — renegotiationom, koji HTTP/2 zabranjuje.
- Serverski certifikati `roapiservistst.fina.hr` (Sectigo OV R36) i `roapiservis.gov.hr` (Entrust OV)
  idu do javnog **Sectigo Public Server Authentication Root R46** — ne do Fina Demo CA, kako
  pretpostavlja JTI-jev truststore. Zato je truststore JDK `cacerts` + dodatak iz jara.

## Konfiguracija

| Property | Env | Napomena |
|---|---|---|
| `app.eovlastenja.enabled` | `APP_EOVLASTENJA_ENABLED` | default `false`; **CDU `true`** |
| `app.eovlastenja.url` | `APP_EOVLASTENJA_URL` | test `https://roapiservistst.fina.hr/api/AuthUnionApi/GetAuthorizationUnionPermission` (CDU default), prod `https://roapiservis.gov.hr/api/AuthUnionApi/GetAuthorizationUnionPermission` |
| `app.eovlastenja.keystore-path/-password`, `key-alias` | — | default = `nias.saml.*` (isti aplikacijski certifikat) |
| `app.eovlastenja.truststore-path` | `APP_EOVLASTENJA_TRUSTSTORE_PATH` | **dodatak** JDK `cacerts`-u; default `classpath:eovlastenja/ca-bundle.crt` (PEM: Sectigo R46, Fina Demo CA 2020, Fina Demo Root CA). Prima i `.p12`/`.pfx`, tada uz `-password` |
| `app.eovlastenja.signer-cert-path` | `APP_EOVLASTENJA_SIGNER_CERT_PATH` | test `classpath:eovlastenja/eovlastenja-tst.cer` (CDU default; `CN=Upravljanje-eOvlastenjimaTst`, do 27.02.2029.), prod `classpath:eovlastenja/eovlastenja-prod.cer` (`CN=eovlastenjaprod`, do 29.11.2027.). Izvor: `niastst.fina.hr/integracija/#ro-certs` |
| `app.eovlastenja.mock[i].person-oib/legal-oib/legal-name/function` | — | samo **bez NIAS-a** (local/mock): `99999999990` zastupa `33333333360` „TESTNA TVRTKA d.o.o." |

Uz `enabled=false`: bez NIAS-a radi mock s parovima; s NIAS-om (dev, preprod, cdupreprod) svaki
odabir tvrtke vraća 503. Mock parovi uz `nias.saml.enabled=true` **ruše start** — stvarni NIAS
korisnik s OIB-om iz para djelovao bi u ime tvrtke bez provjere. Uz `enabled=true` nepotpuna
konfiguracija ili neučitljiv keystore/certifikat također ruše start (fail-fast, kao eGOP).

Potpisni certifikati i CA-ovi su javni, pa su u repou (`src/main/resources/eovlastenja/`). Kad FINA
zamijeni potpisni certifikat, svaka provjera pada s 503 („provjera XML potpisa nije uspjela") dok se
novi ne stavi u jar ili ne postavi `APP_EOVLASTENJA_SIGNER_CERT_PATH`. Rok vidi log
`eovlastenja url=… signer_valid_until=…`.

## Prije uključivanja na okolini (korak 0)

Sve ovisi o tome što je FINA registrirala uz certifikat InterniTurizma. Postupak s naredbama i
tumačenjem loga je u `DEPLOY-CDU.md` §8b.

1. **`sesija_id` u assertionu.** Nakon deploya jedna NIAS prijava; u logu `nias_login attributes=[...]`
   mora biti `sesija_id`. U srpnju 2026. ga nije bilo (`ime, prezime, oib, oznaka_drzave_eid, tid,
   nav_token`). Bez njega svaka provjera daje 503 („NIAS prijava ne nosi sesija_id").
2. **Registracija u e-Ovlaštenjima.** Odabrati tvrtku: šifra **203**/200 znači da je metoda
   dopuštena, **100** („Pristup metodi nije dozvoljen") da InterniTurizam nije registriran.
   Dobar znak: produkcijski keystore InterniTurizma sadrži (istekli) `eovlastenjaprod` certifikat
   (`DEPLOY-PREPROD.md`), dakle usluga je već bila spojena na e-Ovlaštenja.
3. **Mreža / TLS** prema `roapiservistst.fina.hr:443` s kutije, uz prihvaćen klijentski certifikat.

Ako 1 ili 2 ne prođu, potrebna je vlastita registracija STR-a (NIAS e-Poslovanje + e-Ovlaštenja).
Kod ostaje; okolina se gasi s `APP_EOVLASTENJA_ENABLED=false`.

**Rok certifikata:** produkcijski certifikat InterniTurizma (`Fina RDC 2020`) vrijedi do
**08.11.2026.** — isti se koristi za obostrani TLS prema produkcijskim e-Ovlaštenjima.

## Otvoreno

- Adresa sjedišta i kontakt tvrtke (izvor?) — do tada prazno / upisuje korisnik.
- Može li pravna osoba biti „domaćin" (GO-1)?
- Smije li svaka funkcija iz e-Zastupanja tražiti RB (sada: da); „official id" iz stavke 2.
- Obrt i OPG (`IZVOR_REG` 2/3).
- Format `sesija_id`: prihvaća se `[A-Za-z0-9-]{1,100}`; drugi znakovi daju 503 („neispravan
  sesija_id za e-Ovlaštenja"). Provjeriti na CDU-u i po potrebi proširiti uzorak.

Referenca: kolegin `JTI-backend` (`security/eovlastenja/RealEovlastenjaClient`), čiji su obrasci
preuzeti i pojačani (validacija ulaza, zaštita od wrappinga, whitelist algoritama, `ForRequestId`,
parsiranje po putanji, HTTP/1.1, javni CA za serverski TLS).
