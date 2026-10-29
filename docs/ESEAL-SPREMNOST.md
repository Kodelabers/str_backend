# eSeal — review specifikacije i spremnost

Specifikacija: **STR eSeal API v1.0.0** (OpenAPI 3.1.1, `eseal-v1.openapi.json`, dobivena 26.10.2026.).
Kvalificirani elektronički pečat (PAdES, ETSI EN 319 142-1) za izlazne akte tijela — čl. 98. st. 8
ZUP-a: akt iz informacijskog sustava smije se ovjeriti **samo** kvalificiranim e-pečatom. Sve što
ide u korisnički pretinac (KP) mora biti pečatirano.

Stanje: **iteracija 1 implementirana** (vidi §4), čeka prvi deploy na CDU test. Upute za deploy i
čitanje loga: `DEPLOY-CDU.md` §8d.

---

## 1. Specifikacija

### Ugovor

| | Endpoint | Svrha |
|---|---|---|
| E1 | `POST /api/v1/seals/pdf` | sirovi `application/pdf` → pečaćeni PDF; `201` novo, `200` ponovljeni ključ |
| E2 | `GET /api/v1/seals/pdf/{documentGuid}` + header `Control-Number` | dohvat pečaćenog PDF-a |

Autentikacija: `X-API-Key`. `Project` header obvezan kad ključ daje više projekata (šaljemo ga uvijek).
Odgovor E1: `Document-Guid`, `Control-Number` (`XXXX-XXXX-XXXX`, Crockford Base32, **tajna**),
`Seal-Level` (B-B / B-T / B-LT / B-LTA), `Repr-Digest` (SHA-256, RFC 9530), `Service-Version`.

### Što je dobro
- Idempotentnost: isti `Idempotency-Key` + isto tijelo → spremljeni ishod (`200`, `Idempotent-Replayed`);
  drugo tijelo → `422 IDEMPOTENCY_KEY_REUSED`; u tijeku → `409` + `Retry-After`.
- Eksplicitna pravila retryja i `retryable` u svakom problem+json tijelu — klijent ne treba vlastitu tablicu statusa.
- Stabilan `errorCode` enum (27 kodova), RFC 9457, `traceId` za podršku; tijelo greške nikad ne nosi sadržaj dokumenta.
- `Repr-Digest` za provjeru integriteta; kontrolni broj samo u headeru; E2 svaki razlog odbijanja svodi na isti `404`.
- `Content-Length` obvezan i provjeren prije čitanja tijela (`411`/`413`); `Expect: 100-continue` podržan.

### Implikacije za naš klijent
1. **Timeout:** E1 ≥ 90 s (servis sam ograničava na 60 s), E2 ≥ 30 s; proxyji na putu ≥ 120 s.
2. **Najgori slučaj s retryjima ≈ 8 min** (5 × 90 s + backoff do 30 s) → nikad u DB transakciji.
3. **Retry mora poslati iste bajtove.** OpenPDF pri svakom renderu upisuje novi `/ID` i datum, pa bi
   ponovni render pod istim ključem dobio `422 IDEMPOTENCY_KEY_REUSED`. Nepečaćeni PDF i ključ se
   spremaju zajedno **prije** prvog poziva.
4. **Nakon pečata PDF se više ne mijenja** — eGOP, KP, e-mail dostava i portal koriste pohranjene pečaćene bajtove.
5. `Control-Number` ne ide u log ni u poruke grešaka.
6. `Business-Reference` prima samo `A-Z a-z 0-9 razmak . _ - / : # ( )` — šaljemo URBROJ očišćen na taj skup.

### Nedostaci — pitanja pružatelju
1. **PDF profil nije specificiran.** Postoje `PDF_NOT_PDFA`, `PDF_NO_SEAL_SPACE`, `PDF_POLICY_VIOLATION`,
   `PDF_UNSUPPORTED_FEATURE`, ali ne piše: je li PDF/A obvezan i koja razina; što je „seal space";
   što provjerava policy.
2. **Vidljivost pečata.** Iscrtava li servis vidljivi pečat, gdje, s kojim podacima (GUID, kontrolni
   broj, QR, URL provjere)? Možemo li zadati poziciju?
3. `Seal-Level` i TSA za naš projekt; podaci certifikata (izdavatelj, naziv, algoritam) za tekst na aktu.
4. Brojke kojih nema: max veličina PDF-a, rate limit, istodobnost, TTL idempotency ključa, rok čuvanja za E2.
5. Dokument zahtjeva na koji se spec poziva (R-14, R-16, R-19, R-61, R-63, R-82); health endpoint.
6. Testno okruženje (autentikacija, testni certifikat, uzorak pečaćenog PDF-a), CA lanac, IP allowlist.
7. **Javna provjera vjerodostojnosti:** E2 traži API ključ → nije za javnost. Postoji li portal
   pružatelja (naš `str.documents.epecat.url-provjere`), ili ga gradimo mi kao proxy (P-2)?
8. Sitnice: koji `errorCode` za nedostajući `Control-Number` na E2 (spec kaže „samo to je 400", a 400
   navodi `HEADER_INVALID`/`PROJECT_REQUIRED`); `X-Correlation-ID` bez sheme; `503 SEAL_KEY_UNAVAILABLE`
   bez `Retry-After`; nema `servers` bloka.

---

## 2. PDF/A ukratko

PDF/A (ISO 19005) je PDF za dugotrajno arhiviranje: svi fontovi ugrađeni, boje vezane uz ugrađeni
ICC profil (*output intent*), XMP metapodaci s oznakom razine; zabranjeni enkripcija, JavaScript,
vanjske reference. Razine: dio 1 (PDF 1.4, bez prozirnosti), 2 (prozirnost, slojevi), 3 (+ privitci);
slova **b** (vizualno vjerno), **u** (+ Unicode tekst), **a** (+ struktura za pristupačnost).

PAdES pečat se dodaje inkrementalno, pa PDF/A ulaz daje PDF/A izlaz — akt prikladan za arhivu.

Naš akt (OpenPDF 1.3.43, `ZupDocumentRenderer`) je imao ugrađene fontove, ali ne i output intent ni
XMP, a verzija je 1.5. OpenPDF zna PDF/A-1a/1b (ne 2/3), pa je PDF/A-1b izveden bez nove biblioteke,
iza prekidača `STR_DOCUMENTS_PDFA`. Ako servis traži baš 2b/3b, treba odluka (biblioteka ili ručni XMP).
Testovi provjeravaju oznake (verzija, output intent, XMP), ne punu sukladnost — nju potvrđuje validator
(veraPDF) ili sam servis.

---

## 3. Spremnost prije iteracije 1

| Područje | Stanje | Napomena |
|---|---|---|
| Konfiguracija | 🟡 | nema `eseal.*`; obrazac properties recorda s `requireComplete()` postoji |
| HTTP klijent | 🟡 | RestClient obrazac (`OibRegistryConfig`, `EOvlastenjaHttpClient`) |
| Retry / idempotentnost | 🟡 | resumabilni eGOP tok + `EgopRetryJob`; bez jittera i `Retry-After` |
| PDF/A | 🔴 | nije PDF/A |
| Blok e-pečata na aktu | 🔴 | nasumični kontrolni broj (8 znamenki) prije pečata — nije onaj iz servisa |
| Pohrana | 🔴 | nema seal kolona; obavijest o dodjeli se ne pohranjuje; e-mail šalje zaseban render |
| Mreža | 🟡 | nginx `/api/` 60 s; egress i CA prema servisu neprovjereni |
| Frontend | 🟡 | nema statusa pečata ni javne provjere vjerodostojnosti |

---

## 4. Iteracija 1 (implementirano)

- **Konfiguracija** `str.eseal.*` ↔ `ESEAL_ENABLED`, `ESEAL_BASE_URL`, `ESEAL_API_KEY`, `ESEAL_PROJECT`,
  timeouti 5 s / 90 s; fail-fast uz `enabled=true`; `startup_eseal` redak u logu. `https://` je obvezan;
  `ESEAL_ALLOW_HTTP=true` ga popušta za internu mrežu (CDU test: servis je na internoj IP adresi samo
  preko HTTP-a) uz `WARN eseal_insecure_http`. Za produkciju treba `https://` adresa — pitanje pružatelju.
- **Klijent** `eseal/EsealHttpClient` (samo E1): `byte[]` tijelo s `Content-Length`, `Idempotency-Key` =
  `X-Correlation-ID`, problem+json → `EsealException(status, errorCode, traceId, retryable)`; transport →
  `TRANSPORT`, proxy bez tijela → `HTTP_<status>`.
- **Odgovor servisa** se provjerava: `%PDF`, `Repr-Digest`, oblik `Control-Number`, `Seal-Level` iz enuma,
  da nije vraćen nepromijenjen ulaz, gornja granica 64 MB.
- **Pohrana** (Liquibase 147): `egop_pismeno.seal_*` + `sealed_at`; pečaćeni PDF zamjenjuje nepečaćeni u `pdf_content`.
- **Tok** (`EgopFilingService.sealOnce`): izlazni akt se pečatira nakon konačnog rendera (KLASA/URBROJ/JOP),
  a prije prilaganja u eGOP; pad → `EgopSealingException` → `sync_error`, **bez prilaganja i bez KP otpreme**.
- **Zamrzavanje:** bajtovi i ključ upisuju se zajedno prije poziva (`freezeForSealing`, uvjetni UPDATE). Od
  tada se akt ne renderira ponovo; retry nakon timeouta/503 šalje isti par i dobiva pečat iz prvog poziva.
  Paralelni tok (retry job, druga instanca) dobiva isti par.
- **Konačno odbijanje** (`retryable=false` uz 4xx/5xx, npr. `PDF_NOT_PDFA`): ključ se briše, pa sljedeći
  pokušaj renderira akt iznova (npr. nakon `STR_DOCUMENTS_PDFA=true`) pod novim ključem — kako spec traži.
- **Utrka tokova:** zakašnjeli pad drugog toka ne vraća dovršen (SYNCED) akt na FAILED; pečat koji stigne
  nakon prilaganja ne dira priloženi akt.
- **E-mail dostava non-EU** (registracija i akti životnog ciklusa): uz eSeal ide samo pečaćeni akt iz
  `egop_pismeno`; dok ga nema, poruka čeka u redu ponovnog slanja (`not_sealed`, `MailRetryJob` svakih 5 min,
  najdulje 3 dana). Iznajmljivač s OIB-om i dalje dobiva presliku u mailu, a akt u KP.
- **Portal:** obavijest o dodjeli poslužuje se pečaćena kad postoji; akti životnog ciklusa ionako iz
  `pdf_content`, koji pečat zamjenjuje.
- **PDF/A-1b** iza `STR_DOCUMENTS_PDFA`.

### Svjesno izvan iteracije 1
- Preslika u mailu iznajmljivaču s OIB-om je nepečaćeni render (akt mu stiže pečaćen u KP); prije pečata
  portal poslužuje nepečaćenu verziju (snimak akta, odnosno render obavijesti o dodjeli).
- Blok e-pečata na aktu ostaje ugašen (`STR_EPECAT_ENABLED=false`) do odgovora na pitanje 2.
- E2, javna stranica provjere, status pečata na frontendu.
- Retry ne čita `Retry-After` (backoff vodi postojeći `EgopRetryPolicy`).
- Promjena `ESEAL_PROJECT` ili ključa dok akti čekaju retry: zamrznuti ključevi pripadaju starom projektu —
  prije promjene pustiti red da se isprazni ili ručno obrisati `seal_idempotency_key` nepečaćenih akata.
- Uključivanje eSeala ne pečatira akte koji su već priloženi nepečaćeni.
- Akt vrste s popisa `EGOP_AKTI_BEZ_SIFRE` ne ide u eGOP, pa se ni ne pečatira; non-EU dostava mailom za
  njega uz eSeal čeka do isteka reda (popis je prazan od 22.10.2026.).
- Na CDU-u nema `EgopRetryJob` (eGOP je mock) — pali pečat se ne ponavlja sam.
- Pečatiranje obavijesti o dodjeli je sinkrono u zahtjevu izdavanja RB-a; servis > 60 s → nginx 504, iako backend završi.
- `Control-Number` je u bazi u čistom obliku — ista razina zaštite kao PDF u istom retku.
