# Razlozi promjene statusa registracijskog broja

Popis za pregled i dopunu naručitelja (primjedba s UAT-a: *„Jesu li razlozi za suspenziju i
povlačenje u nekoj tablici? Možemo li ih izbaciti u neki format da ih pregledaju i dopune?"*).

**Stanje:** razlozi **nisu** u tablici. Razlozi suspenzije žive kao Java enum `RnTrigger`, razlozi
povlačenja kao enum `WithdrawalReason`, a hrvatski natpisi u
`src/main/resources/documents/hr/labels.properties`. U bazi se okidač upisuje u
`str_rn.registration_number_log.trigger_name` kao slobodan string, bez ograničenja.

Popisi u §1a i §1b konačni su popisi naručitelja (MINTS, studeni 2026.).

Od sada ih izlaže `GET /api/lookups/rn-triggers?kontekst=SUSPENZIJA|POVLACENJE`, pa ih frontend
više ne hardkodira.

---

## 1. Razlozi koje službenik bira

Ovo su razlozi koji se nude u obrascima — jedini na koje se dopuna popisa stvarno odnosi.

### 1a. Suspenzija — `POST /api/rn/{rb}/suspend?reason=...`

Vodi iz `ACTIVE` u `SUSPENSION_PROPOSED` (prijedlog suspenzije, čl. 30. st. 2 ZUP-a). Generira akt
**„Obavijest o prijedlogu suspenzije registracijskog broja"**.

Razlozi se nude u padajućem izborniku ovim redom (`RnTrigger.SUSPENSION_REASONS`):

| Šifra | Hrvatski naziv | Traži bilješku |
| :--- | :--- | :---: |
| `NO_DECISION` | za objekt nije izdano rješenje | ne |
| `DECISION_REVOKED` | rješenje je ukinuto | ne |
| `INCOMPLETE_DOCUMENTATION` | nepotpuni i/ili netočni podaci | ne |
| `INSPECTION` | nalaz inspekcijskog nadzora | ne |
| `OTHER` | drugi razlozi | **da** |

`OTHER` traži bilješku jer nema kodiranog razloga koji bi se ispisao — bilješka nosi obrazloženje
u izreku akta. Ostali ispisuju svoj naziv iz tablice (malim slovom, jer se umeće u rečenicu akta).

`CONSENT_EXPIRY` („istek suglasnosti suvlasnika") je **povijesni** razlog: ostaje u enumu i u
`labels.properties` zbog postojećih zapisa i već izdanih akata, ali se više ne nudi, a
`RnService.suspend` i `RnStatus.canTransitionTo` ga za novi prijedlog odbijaju
(`error.rn.suspend.trigger.invalid`).

### 1b. Povlačenje — `POST /api/rn/{rb}/withdraw?reason=<šifra>&note=<opis>`

Vodi u `WITHDRAWN` („Povučen"), **trajno** — reaktivacije nema (čl. 6. STR Uredbe). Mjera
nadležnog tijela; generira akt „Obavijest o povlačenju registracijskog broja" (s uputom o pravnom
lijeku).

Razlog je **obvezan** (`error.rn.withdraw.reason.required`) i bira se s popisa
(`WithdrawalReason`, `GET /api/lookups/rn-triggers?kontekst=POVLACENJE`):

| Šifra | Hrvatski naziv | Traži bilješku |
| :--- | :--- | :---: |
| `DATA_NOT_CORRECTED` | namjerno ili krajnjom nepažnjom nisu ispravljeni podaci | ne |
| `INVALID_INFORMATION` | dostavljene su nevjerodostojne ili nevaljane informacije | ne |
| `OTHER` | drugi razlozi | **da** (`error.rn.withdraw.note.required`) |

Okidač prijelaza je i dalje uvijek `WITHDRAWAL` (o njemu ovise akt, eGOP i obavijesti). Razlog se
upisuje u `registration_number_log.reason` kao tekst koji akt ispisuje: natpis razloga, a uz
`OTHER` opis službenika. Šifra razloga se zato u bazi ne čuva zasebno; izvještavanje po razlogu
povlačenja moguće je po tekstu natpisa.

Opoziv iznajmljivača **nije** povlačenje i nema ga na ovom popisu — v. §2a.

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
| `DEADLINE_EXCEEDED` | istek roka za očitovanje | `SUSPENSION_PROPOSED → SUSPENDED` | sustav (`SuspensionDeadlineJob`, dnevno u 01:00) ili službenik neuvažavanjem prigovora nakon isteka roka | Obavijest o suspenziji |
| `REVOKE_PROPOSAL` | obustava postupka suspenzije | `SUSPENSION_PROPOSED → ACTIVE` | službenik (stranka je ispravila nedostatak) | Obavijest o obustavi postupka |
| `REACTIVATE` | reaktivacija registracijskog broja | `SUSPENDED → ACTIVE` | službenik | Obavijest o reaktivaciji |

---

## 3. Rok za očitovanje

Suspenzija je dvofazna: prijedlog otvara rok, a suspendira tek njegov istek.

- Rok se šalje uz zahtjev (`?suspensionDeadline=2026-09-01`) i **obvezan je**
  (`error.rn.suspend.deadline.required`); sustav ga više ne postavlja sam, pa nema ni postavke
  `str.rn.suspension.response-days`. **Rok u prošlosti se odbija**
  (`error.rn.suspend.deadline.past`) — inače bi ga `SuspensionDeadlineJob` pokupio već iduće noći i
  RB bi bio suspendiran a da se stranka nije imala kad očitovati. Rok koji ističe danas je valjan.
- Istekom roka RB se **automatski suspendira**, osim ako uz njega čeka prigovor (`SUBMITTED`)
  predan do kraja dana roka (po hrvatskom vremenu). Tada odlučuje službenik: uvažavanje obustavlja
  postupak, a neuvažavanje RB odmah suspendira u ime službenika (isti prijelaz i akt kao job).
  Prigovor predan nakon roka suspenziju ne zaustavlja.
- Radna lista „rok ističe uskoro":
  `GET /api/rn?view=SUSPENSION_PROPOSED&deadlineWithinDays=7`. Filtar nema donju granicu, pa
  obuhvaća i već istekle, a još neobrađene rokove.

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

Popisi u §1a i §1b su konačni. Za svaki **novi** razlog treba i:

1. hrvatski naziv kakav treba pisati u rješenju,
2. traži li obrazloženje (kao `OTHER`),
3. je li riječ o suspenziji ili povlačenju,
4. koji se akt njime pokreće — postojeći ili novi.
