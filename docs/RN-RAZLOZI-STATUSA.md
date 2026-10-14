# Razlozi promjene statusa registracijskog broja

Popis za pregled i dopunu naručitelja (primjedba s UAT-a: *„Jesu li razlozi za suspenziju i
povlačenje u nekoj tablici? Možemo li ih izbaciti u neki format da ih pregledaju i dopune?"*).

**Stanje:** razlozi **nisu** u tablici. Žive kao Java enum `RnTrigger` (10 vrijednosti), a hrvatski
natpisi u `src/main/resources/documents/hr/labels.properties`. U bazi se u
`str_rn.registration_number_log.trigger_name` upisuju kao slobodan string, bez ograničenja.

Od sada ih izlaže `GET /api/lookups/rn-triggers?kontekst=SUSPENZIJA|POVLACENJE`, pa ih frontend
više ne hardkodira.

---

## 1. Razlozi koje službenik bira

Ovo su razlozi koji se nude u obrascima — jedini na koje se dopuna popisa stvarno odnosi.

### 1a. Suspenzija — `POST /api/rn/{rb}/suspend?reason=...`

Vodi iz `ACTIVE` u `SUSPENSION_PROPOSED` (prijedlog suspenzije, čl. 30. st. 2 ZUP-a). Generira akt
**„Obavijest o prijedlogu suspenzije registracijskog broja"**.

| Šifra | Hrvatski naziv | Traži bilješku | Dopuna naručitelja |
| :--- | :--- | :---: | :--- |
| `CONSENT_EXPIRY` | istek suglasnosti suvlasnika | ne | |
| `INSPECTION` | nalaz inspekcijskog nadzora | ne | |
| `INCOMPLETE_DOCUMENTATION` | nepotpuna dokumentacija | ne | |
| `OTHER` | drugi razlog | **da** | |

`OTHER` traži bilješku jer nema kodiranog razloga koji bi se ispisao — bilješka nosi obrazloženje
u izreku akta. Ostala tri ispisuju svoj naziv iz tablice.

### 1b. Povlačenje — `POST /api/rn/{rb}/withdraw?reason=<slobodan tekst>`

Vodi u `WITHDRAWN` („Povučen"), **trajno** — reaktivacije nema (čl. 6. STR Uredbe). Mjera
nadležnog tijela; generira akt „Obavijest o povlačenju registracijskog broja" (s uputom o pravnom
lijeku).

| Šifra | Hrvatski naziv | Napomena | Dopuna naručitelja |
| :--- | :--- | :--- | :--- |
| `WITHDRAWAL` | povlačenje registracijskog broja | jedini razlog; obrazloženje ide kao slobodan tekst | |

Opoziv iznajmljivača **nije** povlačenje i nema ga na ovom popisu — v. §2a.

> **Pitanje za naručitelja:** treba li povlačenje kodirane razloge kao i suspenzija (npr. „na
> zahtjev stranke", „pravomoćna odluka inspekcije", „prestanak obavljanja djelatnosti")? Sada je
> sve slobodan tekst, pa se po razlogu ne može izvještavati.

---

## 2. Ostali okidači

Nisu na izbor službeniku — navedeni radi potpunosti, jer se pojavljuju u revizijskom zapisu i u
aktima.

### 2a. Opoziv iznajmljivača — `POST /api/nias/registrations/{rb}/withdraw` / `POST /api/lessor/registrations/{rb}/withdraw`

Iznajmljivač sam opoziva svoj RB u TuRegBroju („Moji registracijski brojevi" → Opoziv), bez
razloga. Vodi u **`REVOKED` („Opozvan")**, konačni status (čl. 5. st. 5 STR Uredbe); podaci se
čuvaju najviše 18 mjeseci, a zatim ih sustav briše (`WithdrawnRnRetentionJob`). Generira akt
„Obavijest o opozivu registracijskog broja" (bez upute o pravnom lijeku).

| Šifra | Hrvatski naziv | Prijelaz | Tko okida | Akt |
| :--- | :--- | :--- | :--- | :--- |
| `REVOCATION` | opoziv registracijskog broja na zahtjev iznajmljivača | `ACTIVE` / `SUSPENSION_PROPOSED` / `SUSPENDED → REVOKED` | iznajmljivač | Obavijest o opozivu |

`GET /api/lookups/rn-triggers` ga ne nudi ni u kontekstu `POVLACENJE` ni u `SUSPENZIJA`.

Do changeseta 137 opoziv je završavao u `WITHDRAWN` uz okidač `WITHDRAWAL`, a od povlačenja ga je
razlikovao samo akter u `registration_number_log` (`LESSOR:` / `NIAS:`). Changeset 137 je
postojeće opozive po tom kriteriju preveo u `REVOKED` / `REVOCATION`.

### 2b. Okidači koje sustav ili službenik postavlja izvan obrazaca

| Šifra | Hrvatski naziv | Prijelaz | Tko okida | Akt |
| :--- | :--- | :--- | :--- | :--- |
| `DEADLINE_EXCEEDED` | istek roka za očitovanje | `SUSPENSION_PROPOSED → SUSPENDED` | sustav (`SuspensionDeadlineJob`, dnevno u 01:00) | Obavijest o suspenziji |
| `REVOKE_PROPOSAL` | obustava postupka suspenzije | `SUSPENSION_PROPOSED → ACTIVE` | službenik (stranka je ispravila nedostatak) | Obavijest o obustavi postupka |
| `REACTIVATE` | reaktivacija registracijskog broja | `SUSPENDED → ACTIVE` | službenik | Obavijest o reaktivaciji |

---

## 3. Rok za očitovanje

Suspenzija je dvofazna: prijedlog otvara rok, a suspendira tek njegov istek.

- Rok se šalje uz zahtjev (`?suspensionDeadline=2026-09-01`). **Rok u prošlosti se odbija**
  (`error.rn.suspend.deadline.past`) — inače bi ga `SuspensionDeadlineJob` pokupio već iduće noći i
  RB bi bio suspendiran a da se stranka nije imala kad očitovati. Rok koji ističe danas je valjan.
- **Ako se ne pošalje, sustav ga sam postavlja na `danas + 15 dana`** — zadana vrijednost prati
  tekst akta („u roku od 15 dana od dana dostave ove Obavijesti") i mijenja se postavkom
  `str.rn.suspension.response-days`.
- Radna lista „rok ističe uskoro":
  `GET /api/rn?view=SUSPENSION_PROPOSED&deadlineWithinDays=7`. Filtar nema donju granicu, pa
  obuhvaća i već istekle, a još neobrađene rokove.

> **Pitanje za naručitelja:** je li 15 dana ispravan zadani rok i treba li se razlikovati po
> razlogu suspenzije (npr. kraći za inspekcijski nalaz)? Sada je jedan za sve.

---

## 4. Što tablica razloga ne može riješiti sama

Naručitelj je najavio **formu za administriranje ovih klasifikacija**. Prije dogovora o opsegu
treba biti jasno što se administracijom iz baze dobiva, a što ne:

| Može iz baze | Ostaje u kodu |
| :--- | :--- |
| Hrvatski naziv razloga | Iz kojeg statusa u koji razlog vodi (`RnStatus.canTransitionTo`) |
| Opis / pomoćni tekst | Koje se pismeno po ZUP-u generira (`StrDocumentType.forTransition`) |
| Je li razlog aktivan (skriven s izbora) | Traži li razlog bilješku i što ide u izreku akta |
| Redoslijed u padajućem izborniku | Koje sekcije akt mora imati (čl. 98. ZUP-a) |

Drugim riječima: **postojećim razlozima se naziv i dostupnost mogu administrirati; potpuno novi
razlog i dalje traži izmjenu koda**, jer mora dobiti svoj akt, svoje sekcije i svoj natpis. Bez
toga bi novi razlog prošao kroz obrazac i pao pri renderu akta — dakle *nakon* što je status već
promijenjen.

---

## 5. Kako popuniti

Stupac **„Dopuna naručitelja"** u tablicama §1a i §1b je za vas: dopišite razloge koji nedostaju,
prekrižite one koji se ne koriste, ispravite nazive. Za svaki **novi** razlog treba i:

1. hrvatski naziv kakav treba pisati u rješenju,
2. traži li obrazloženje (kao `OTHER`),
3. je li riječ o suspenziji ili povlačenju,
4. koji se akt njime pokreće — postojeći ili novi.
