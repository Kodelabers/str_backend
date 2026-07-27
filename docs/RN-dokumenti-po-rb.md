# Dokumenti po registracijskom broju (prikaz „Moji registracijski brojevi")

## Kontekst

Na prikazu „Moji registracijski brojevi" iznajmljivač treba vidjeti **sve svoje PDF dokumente
vezane uz pojedini RB** i preuzeti svaki. Backend već ima download-pojedinačnih-akata, ali nema
**popis** dostupnih dokumenata po RB-u — frontend ne zna koje dokumente ponuditi (ne zna je li RB
suspendiran, opozvan itd.), niti ima jedinstvenu listu.

### Zatečeno stanje

| Dokument | Gdje živi | Postojeći download |
|---|---|---|
| Zahtjev (ulazni) | `submission.pdf_content` | `/api/generateRegistrationNumber/{submissionId}/pdf` |
| Obavijest o dodjeli | on-demand render (ZUP predložak, `StrDocumentType.DODJELA`) | `GET /api/rn/{rn}/documents/dodjela` |
| Akti životnog ciklusa (suspenzija, opoziv, povlačenje, reaktivacija, prijedlog suspenzije, prigovor) | `egop_pismeno` retci s `rn` + `pdf_content` (sprema `RnLifecycleFilingListener` **prije** slanja, i kad je eGOP ugašen) | — |
| „Moji RB-ovi" lista | — | `GET /api/lessor/registrations`, `GET /api/nias/registrations` → `List<LessorRnSummaryDto>` (bez dokumenata) |

Ključni nalaz: **`egop_pismeno` je već ležer dokumenata s pohranjenim PDF-om po aktu**, vezan uz
`rn`, i puni se neovisno o tome je li eGOP omogućen. Akti životnog ciklusa se tako serviraju iz
**pohranjenih bajtova** (vjerodostojan original), a ne re-renderom — koji po komentaru na
`EgopPismenoEntity` drifta (obrisan `suspensionDeadline`, promijenjen zadnji zapis u tragu).

## Cilj

Endpoint koji za dani RB vrati **popis svih dokumenata** (ulazni + izlazni) s labelama, datumima i
download URL-ovima, owner-scoped, + dosljedan download koji za akte životnog ciklusa vraća pohranjeni
PDF. Bez izmjene sheme i bez diranja registracijskog/urudžbenog toka.

## Dizajn

### Endpointi (svi pod `/api/rn`, owner-scoped kroz zajednički `requireAccess`)

- `GET /api/rn/{rn}/documents` — **novo**: popis dokumenata, `List<RnDocumentDto>`.
- `GET /api/rn/{rn}/documents/{tip}` — **postoji**: on-demand render ZUP akta (`dodjela`, …).
  Prošireno: `tip=zahtjev` servira `submission.pdf_content` (zahtjev nema predložak, pa `render()` baca).
- `GET /api/rn/{rn}/documents/pohranjeno/{aktId}` — **novo**: servira `egop_pismeno.pdf_content` po
  id-u akta (6 segmenata → ne kolidira s 5-segmentnim `{tip}`).

### `RnDocumentDto`

```
record RnDocumentDto(
    UUID id,          // egop_pismeno id za pohranjene akte; null za zahtjev/dodjela
    String slug,      // "zahtjev","dodjela","suspenzija",... (može biti null ako nemapirano)
    String naziv,     // čitljiva labela
    String smjer,     // "ULAZNO" / "IZLAZNO"
    LocalDate izdano, // datum izdavanja
    String href       // relativni download URL
)
```

### `RnDocumentsService` (paket `rn`)

`listForRn(rn)` agregira i sortira po datumu:
1. **Zahtjev** — ako `submission.pdf_content != null` → jedan unos, `href=/documents/zahtjev`,
   `izdano = submission.filingDate/createdAt`.
2. **Obavijest o dodjeli** — uvijek za izdani RB → `href=/documents/dodjela`, `izdano = rn.issueDate`.
3. **Akti životnog ciklusa** — `egopPismenoRepository.findByRnOrderByCreatedAtAsc(rn)`; svaki redak =
   jedan unos, `naziv = vrstaPismenaNaziv` (već čitljiv), `smjer` s retka, `href=/documents/pohranjeno/{id}`,
   `izdano = createdAt`. Slug se izvodi reverse-lookupom `StrDocumentType.fromVrstaPismenaNaziv`.

Pomoćne metode za download: `zahtjevPdf(rn)` (iz submissiona), `storedAktPdf(rn, aktId)` (iz
`egop_pismeno`, uz provjeru da `akt.rn == rn` inače 404).

### Sigurnost / vlasništvo

`requireAccess(rn, auth)` pokriva oba tipa prijave, kao „moji RB-ovi" liste:
- `LessorPrincipal` → `rnRepository.isOwnedByLessor(rn, lessorId)`.
- NIAS (`Saml2Authentication`) → `NiasOibExtractor.extractOib` → `rnRepository.isOwnedByOib(rn, oib)` (**nova** upit).
- Ostalo (buduće interne role) → prolazi, kao i dosad.
Tuđi/nepostojeći RB → **404** (ne otkriva postojanje). Neprijavljen → **401**.

## Izmjene po datotekama

- `EgopPismenoRepository` — `List<EgopPismenoEntity> findByRnOrderByCreatedAtAsc(String rn)`.
- `RnRepository` — `boolean isOwnedByOib(String rn, String oib)`.
- `StrDocumentType` — `Optional<StrDocumentType> fromVrstaPismenaNaziv(String)`.
- `RnDocumentDto` — novi record (`rn/dto`).
- `RnDocumentsService` — novi servis (`rn`).
- `RnController` — `GET /documents`, `GET /documents/pohranjeno/{aktId}`, `zahtjev` grana u
  postojećem `document(...)`, prošireni `requireAccess`.

## Testovi

- `RnDocumentsServiceTest` — RB samo zahtjev+dodjela; RB sa suspenzijom+reaktivacijom (redoslijed,
  labeli, smjer, href); RB bez submissiona (bez zahtjeva, dodjela ostaje).
- `RnController` MockMvc — listing owner 200 / tuđi 404 / neprijavljen 401; download pohranjenog
  akta vraća bajtove i 404 za akt tuđeg RB-a; NIAS grana (OIB) vs LessorPrincipal.

## Izvan opsega

- Ne popunjavaju se eventualne praznine u mapiranju prijelaz→akt (listing prikazuje ono što u
  `egop_pismeno` postoji — otporno na buduće dopune).
- „Moji RB-ovi" liste se ne mijenjaju; frontend novi listing zove lijeno (na otvaranje RB-a).
- Bez novog skladištenja PDF-a; dodjela se renderira on-demand (potvrda o činjenici, nema drift).
