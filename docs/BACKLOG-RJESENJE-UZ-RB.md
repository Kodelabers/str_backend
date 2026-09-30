# Backlog — rješenje o kategorizaciji vezano uz registracijski broj

Otvoreno nakon reviewa i PR-a za granu `feature/rjesenje-uz-registracijski-broj` (frontend:
`feature/jedinstvena-forma-postojeci-objekti`). Nalazi uvedeni ovom izmjenom su popravljeni i
pokriveni testovima (utrka dviju predaja → `CATEGORIZATION_ALREADY_SUBMITTED`, prevedena 404
poruka, povučen RB ne traži rješenje, `safeFileName`). Ovdje je ono što je ostalo — većinom
nalazi koji postoje od prije, a ova ih izmjena čini vidljivijima. Frontend stavke su u
`str_frontend/docs/BACKLOG-JEDINSTVENA-FORMA.md`.

## Sigurnost

- [ ] **Kritično — `/api/admin/**` je bez autentikacije.** `SecurityConfig` završava s
  `anyRequest().permitAll()`, a `AdminCategorizationDecisionController` i
  `AdminPendingRegistrationController` nemaju provjeru uloge. Svatko tko dođe do backenda može
  izlistati rješenja (OIB, adresa, sada i RB), preuzeti skenove i potvrditi ili odbiti rješenje;
  `actorId` dolazi iz tijela zahtjeva (`TODO(auth)`), pa se i revizijski zapis može krivotvoriti.
  Popravak: `requestMatchers("/api/admin/**").hasRole(...)`, izvršitelj iz `SecurityContext`-a.
  Do tada potvrditi da nginx blokira `/api/admin` na cdu i cdupreprod.
- [ ] **Visoko — admin popis rješenja učitava cijele skenove.**
  `AdminCategorizationDecisionService.list` vraća entitete s eager `file_content`; `?size=2000`
  učita tisuće datoteka do 10 MB. Popravak: projekcija bez `file_content` (ili lazy / zasebna
  tablica) i manja najveća veličina stranice.
- [ ] **Visoko na okolinama sa `SameSite=None` (cdu, cdupreprod, mock) — CSRF.** CSRF je
  isključen, a `ActingSubjectGuard.requireUnchanged` bez zaglavlja `X-Acting-Subject` ne provjerava
  ništa. Tuđa stranica može prijavljenom iznajmljivaču podmetnuti rješenje uz njegov RB (pravo
  rješenje tada dobiva 409 dok službenik ne odbije podmetnuto) i, vjerojatno, povući RB
  (`POST /api/nias/registrations/{rn}/withdraw`, tijelo nije obavezno — provjeriti testom na cdu).
  Popravak: `X-Acting-Subject` obavezan na NIAS rutama koje mijenjaju stanje (frontend ga već
  uvijek šalje; custom zaglavlje traži preflight koji CORS odbija) ili CSRF tokeni. Prod je
  `SameSite=Strict`.
- [ ] **FINA provjera prije jeftinih provjera, bez ograničenja.** `ownerOibForChange` ponovo
  potvrđuje zastupanje prije provjere vlasništva i „već predano", a tijelo do 10 MB parsira se
  svaki put. Ponovljeni uploadi na RB koji već ima rješenje okidaju neograničene pozive e-Ovlaštenja
  (isto kod povlačenja i predaje). Popravak: ograničenje po sesiji ili jeftine provjere prije.

## Podaci i poslovna logika

- [ ] **Tko je predao rješenje u ime tvrtke.** Uz rješenje se sprema OIB tvrtke (ispravno za
  vlasništvo), ali ne i OIB zastupnika koji ga je predao. Dodati kolonu (npr. `uploaded_by_oib`),
  kao što `applyNiasLegalEntity` radi za iznajmljivača.
- [ ] **Životni ciklus rješenja vezanog uz RB.** Potvrda (`VERIFIED`) je i dalje čista oznaka
  (`TODO(eTurizam)` u `AdminCategorizationDecisionService`): ne upisuje objekt u eTurizam i ne
  postavlja `facility_id`. Dogovoriti s MINTS-om što potvrda rješenja uz RB treba pokrenuti.
- [ ] **Brisanje povučenih RB-ova.** `categorization_decision.rn` ima FK na
  `registration_number.rn` bez `ON DELETE`. Faza 2 `WithdrawnRnRetentionJob`-a (stvarno brisanje)
  mora prvo obrisati ili anonimizirati rješenja i skenove, inače pada na FK-u.
- [ ] **Automatska suspenzija zbog nedostavljenog rješenja** (rok od 30 dana, scheduler) nije
  dio ovog taska — frontend samo upozorava. Traži odluku MINTS-a.

## Testovi i dokumentacija

- [ ] **Changeset 126 nije testiran na Postgresu.** Testovi rade na H2 bez Liquibasea, pa ni
  parcijalni unique index ni mapiranje njegovog kršenja na 409 nisu izvršeni nad pravom bazom
  (mapiranje je pokriveno jediničnim testom). Kandidat za Testcontainers test.
- [ ] **Postojeći crveni testovi na `develop`-u:** `RegistrationIntegrationTest`
  (`generates_rn_and_stores_submission_with_pdf` dobiva 409 umjesto 201) i
  `Go3LegalityCheckTest.rejects_whenAccommodationNotLegalized`. Ne dolaze od ove izmjene.
- [ ] **`str-api.yaml` ne opisuje endpointe rješenja o kategorizaciji** (ni NIAS upload ni admin).
