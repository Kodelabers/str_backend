# ZUP predlošci akata i poruka

Kako su strukturirani PDF akti i poruke e-pošte koje STR izdaje, i gdje se mijenja njihov tekst.

Izvor strukture: **Zakon o općem upravnom postupku** (NN 47/09, 110/21), na snazi od 01.01.2022.
Popis vrsta pismena: mail InfoDoma od 22.07.2026. (vidi `eGOP-endpoint-analiza.md` §14.3).

---

## 1. Zašto ovako

Naručitelj traži: *„Strukturirati predloške po ZUP-u (Zaglavlje, Uvod, Naslov, Izreka,
Obrazloženje, Uputa o pravnom lijeku, Prilozi, Dostavna lista, Potpisnik) te povezati varijable
(placeholdere) s podacima u sustavu."*

Šest od devet sekcija je doslovno **čl. 98. st. 1**. Preostale tri (Naslov/adresat, Prilozi,
Dostavna lista) nisu u ZUP-u nego dolaze iz uredskog poslovanja, na koje ZUP upućuje u čl. 164.

Tekst akata živi **izvan koda**, u `src/main/resources/documents/hr/*.txt`. Razlog: tekst prolazi
pravnu reviziju i mijenjat će se bez ijedne izmjene logike. Raspored stranice (margine, fontovi,
zaglavlje, potpisni blok) ostaje u Javi jer je stabilan.

| Odredba ZUP-a | Što iz nje slijedi |
| :--- | :--- |
| **čl. 98. st. 1** | devet sekcija, redoslijed fiksiran u `ZupSection` |
| **čl. 98. st. 2** | uvod nosi naziv **i OIB** tijela, propis o nadležnosti, ime/naziv **i OIB** stranke, oznaku predmeta i način pokretanja postupka |
| **čl. 98. st. 3** | rok mora biti **u izreci** — otud `${rok.ispravak}` u izreci prijedloga suspenzije |
| **čl. 98. st. 5** | obrazloženje navodi činjenično stanje i **propise na temelju kojih je riješeno** |
| **čl. 98. st. 6** | uputa kaže koje sredstvo, kojem tijelu, u kojem roku i na koji način |
| **čl. 98. st. 8** | akt iz informacijskog sustava ovjerava se **samo** kvalificiranim elektroničkim pečatom |
| **čl. 71. st. 2** | zahtjev i prigovor su *podnesci*: tijelo kojem se upućuju, upravna stvar, ime/adresa/OIB, potpis. Bez izreke i upute. |
| **čl. 75. st. 2** | podnesak s kvalificiranim potpisom smatra se vlastoručno potpisanim |
| **čl. 94. st. 5–6** | dostava u korisnički pretinac = **osobna dostava**; od nje teku rokovi |
| **čl. 111.** | pogrešna ili nepotpuna uputa ide **na štetu tijela**, ne stranke |
| **čl. 14.** | postupak je na hrvatskom jeziku → akti su jednojezični; engleski ostaje samo u mailu |
| **čl. 159.** | potvrde o činjenicama iz službene evidencije su javne isprave |

Dvije posljedice koje su oblikovale kod:

1. **Mail nije dostava.** Svaki mail životnog ciklusa nosi klauzulu da je akt dostavljen u
   korisnički pretinac i da rokovi teku odande. Bez toga bi stranka rok računala od maila —
   dakle od krivog dana.
2. **Klauzula o pečatu se ne ispisuje dok pečata nema.** Tvrdnja o ovjeri na nepečaćenom PDF-u
   bila bi neistinita izjava na pravnom aktu. Ispis je iza `str.documents.epecat.enabled`.

---

## 2. Vrste akata

Definirane u `document/StrDocumentType.java`. `vrstaPismenaNaziv` je **točan ključ eGOP
šifrarnika** — mora se poklapati znak za znak, jer po njemu `EgopCodebooks` razrješava šifru.

| slug | Vrsta pismena (eGOP) | Smjer | Obvezne sekcije | Okidač |
| :--- | :--- | :--- | :--- | :--- |
| `zahtjev` | Zahtjev za registracijski broj | ulazno | čl. 71. — vlastiti generator | registracija |
| `dodjela` | Obavijest o dodjeli registracijskog broja | izlazno | naslov, uvod, izreka, dostavna lista | registracija |
| `opoziv` | Obavijest o opozivu registracijskog broja | izlazno | isto kao `dodjela` | `LessorRnActionService.withdrawOwn` |
| `prijedlog-suspenzije` | Obavijest o prijedlogu suspenzije registracijskog broja | izlazno | + obrazloženje; **rok u izreci** | ❌ nema |
| `suspenzija` | Obavijest o suspenziji registracijskog broja | izlazno | **+ uputa o pravnom lijeku** | `RnService.suspend` |
| `povlacenje` | Obavijest o povlačenju registracijskog broja | izlazno | **+ uputa o pravnom lijeku** | `RnService.withdraw` |
| `prigovor` | Prigovor na prijedlog suspenzije | **ulazno** | čl. 71. — podnesak stranke | ❌ nema |
| `reaktivacija` | Obavijest o reaktivaciji registracijskog broja | izlazno | kao `dodjela` | `RnService.reactivate` |

`reaktivacija` **nije među 7 vrsta pismena** iz InfoDomovog maila i nema šifru u eGOP šifrarniku,
iako je Knjiga testiranja spominje. Predložak i okidač postoje; urudžbiranje je iza
`str.egop.urudzbiraj-reaktivaciju` (ugašeno), a obavijest e-poštom ide neovisno o tome.

`prijedlog-suspenzije` i `prigovor` **nemaju okidač**: `RnService.suspend()` ide odmah u
`SUSPENDED` (nema međustanja), a prigovor ne postoji nigdje u `src/main`. Predlošci su
isporučeni i dostupni preko endpointa, ali automatike nema dok se ne uvede dvofazna suspenzija.

Stari slugovi iz Knjige testiranja (`dopis-namjere`, `nalog-suspenzija`, `nalog-povlacenje`) i
dalje rade kao alias — postojeći linkovi ne pucaju.

### Zašto zahtjev nije prešao na predloške

Zahtjev je **podnesak po čl. 71.**, ne akt po čl. 98. — nema izreke ni upute, a `SubmissionPdfGenerator`
ga renderira kao tablični obrazac koji radi. Prevođenje u linearni predložak izgubilo bi izgled bez
dobitka. Dodano mu je samo ono što čl. 71. traži a nedostajalo je: zaglavlje s tijelom kojem se
podnesak upućuje, OIB zastupnika i potpisni blok podnositelja.

---

## 3. Format predloška

```
# Komentari počinju s # i ne ispisuju se.

[NASLOV]
${stranka.naziv}
${stranka.adresa}

[IZREKA]
1. Suspendira se registracijski broj ${rn.broj} ...
```

- **Redoslijed sekcija u datoteci je nebitan** — renderer ih ispisuje poretkom konstanti
  `ZupSection`. Premještanje bloka ne može promijeniti izgled akta.
- **Prazan redak dijeli odlomke.** U sekcijama tipa `PROZA` (uvod, izreka, obrazloženje, uputa)
  prelomi unutar odlomka spajaju se u razmak; u `BLOK` sekcijama (naslov, prilozi, dostavna lista)
  ostaju kako su napisani.
- **Redak koji nakon zamjene ostane prazan izbacuje se** — neobavezni podaci (zastupnik, poštanski
  broj) ne ostavljaju rupu u adresnom bloku.
- `ZAGLAVLJE` i `POTPISNIK` renderer zna složiti sam iz konfiguracije; predložak ih smije nadjačati.

Renderer prolazi kroz `ZupSection.values()`, ne kroz ručni popis poziva — nova konstanta time
automatski dobiva svoje mjesto na papiru. S ručnim popisom bi se sekcija mogla dodati u enum,
proći validaciju predloška i tiho izostati iz PDF-a.

### Provjere na startu

`ZupTemplateLoader` ruši podizanje aplikacije ako predlošku nedostaje sekcija koju ZUP traži za
taj tip akta. Namjerno: alternativa je otkriti to tek kad rješenje bez upute o pravnom lijeku već
ode stranci, što po čl. 111. ide na štetu tijela.

`ZupPlaceholders` baca na **nepoznat** `${...}`, ne ostavlja prazninu — tipfeler u predlošku
ne smije proizvesti akt koji izgleda uredno a nema broj ili rok. Prazna *vrijednost* je legitimna
i mora biti izričito u kontekstu.

---

## 4. Katalog placeholdera

| Oznaka | Izvor | Napomena |
| :--- | :--- | :--- |
| `${tijelo.naziv}`, `${tijelo.oib}` | `str.documents.tijelo.*` | čl. 98. st. 2; nekonfigurirano → vidljiva oznaka + ERROR u logu |
| `${tijelo.adresa}`, `${tijelo.mjesto}`, `${tijelo.ustrojstvenaJedinica}` | isto | neobavezno |
| `${tijelo.propisNadleznosti}` | `str.documents.tijelo.propis-nadleznosti` | čl. 98. st. 2 |
| `${potpisnik.ime}`, `${potpisnik.funkcija}` | `str.documents.potpisnik.*` | čl. 98. st. 7 |
| `${akt.naslov}` | `StrDocumentType.naslov()` | |
| `${akt.klasa}`, `${akt.urbroj}` | `submission.egop_klasa` + `egop_pismeno.ur_broj` | prazno prije urudžbiranja |
| `${akt.klasaRedak}`, `${akt.urbrojRedak}`, `${akt.mjestoDatum}` | izvedeno | gotovi redci zaglavlja; prazni kad nema oznaka |
| `${akt.datum}` | današnji datum | |
| `${stranka.naziv}` | `lessorLegalEntityName` → `firstName lastName` | |
| `${stranka.identifikator}` | OIB ili „bez dodijeljenog OIB-a" | non-EU iznajmljivač nema OIB |
| `${stranka.oib}` | `RnDetailDto.lessorOib` | sirova vrijednost |
| `${stranka.zastupnik}` | `legalRepresentativeName` + OIB | prazno kad ga nema |
| `${stranka.adresa}`, `${stranka.mjesto}` | `LessorEntity.street/streetNumber/place` | |
| `${stranka.postanskiBroj}` | — | **uvijek prazno**, vidi niže |
| `${rn.broj}`, `${rn.status}`, `${rn.datumIzdavanja}` | `RnDetailDto` | |
| `${rn.razlog}` | parametar → revizijski trag → natpis okidača | |
| `${objekt.naziv}`, `${objekt.adresa}`, `${objekt.mjesto}`, `${objekt.zupanija}`, `${objekt.vrsta}`, `${objekt.kapacitet}` | `RnDetailDto` | |
| `${rok.ispravak}` | `RnEntity.suspensionDeadline` | bez roka → `rok.default` iz `labels.properties` |
| `${uputa.tekst}` | `str.documents.uputa.<slug>` | vidi §6 |

**`${stranka.postanskiBroj}` je uvijek prazan.** `LessorEntity` nema poštanski broj — poznata rupa
(`eGOP-endpoint-analiza.md` §15.2). Prihvatljivo jer dostava ide u korisnički pretinac, ne poštom.

**Hrvatski nazivi enum vrijednosti** (`RnTrigger`, `RnStatus`) su u
`documents/hr/labels.properties`. Prije ovog rada nisu postojali nigdje u kodu: `i18n/hr.properties`
nije uvezan u `MessageSource` i pisan je bez dijakritike.

### Odakle dolazi razlog

`RnService.suspend()` ne prima slobodan tekst — samo `RnTrigger`. Zato `StrDocumentService`
razlog traži ovim redom:

1. parametar poziva (`?reason=` na endpointu, ili proslijeđen iz listenera),
2. `reason` iz zadnjeg zapisa u `registration_number_log`,
3. hrvatski natpis okidača iz `labels.properties`.

Revizijski trag je pouzdaniji izvor od `?reason=` parametra, koji nitko ne provjerava.

---

## 5. Poruke e-pošte

`src/main/resources/documents/mail/*.html`, isti mehanizam placeholdera. Okvir (omot, gumb,
escapiranje) ostaje u Javi jer je izgled, ne tekst.

| Predložak | Okidač |
| :--- | :--- |
| `odobrenje`, `odbijanje` | `AdminPendingRegistrationService.approve/reject` |
| `rb-izdan` | `EgopRegistrationDispatcher` (samo non-EU; EU ide preko KP) |
| `suspenzija` | prijelaz → `SUSPENDED` |
| `reaktivacija` | prijelaz → `ACTIVE` uz `REACTIVATE` |
| `povlacenje` | prijelaz → `WITHDRAWN` po službenoj dužnosti |
| `opoziv` | prijelaz → `WITHDRAWN` na zahtjev iznajmljivača |
| `prijedlog-suspenzije` | ❌ nema (nema dvofazne suspenzije) |

Događaj `RnLifecycleEvent` objavljuje se iz **`RnStatusTransitionService.transition()`** — jedine
točke kroz koju status smije proći (pravilo iz `CLAUDE.md`). Time obavijest ne može promaknuti
nijednom budućem pozivatelju. `RnLifecycleEmailListener` radi `AFTER_COMMIT`, pa vidi i
`suspensionDeadline` koji `RnService.suspend` upisuje *nakon* prijelaza.

Opoziv i povlačenje dijele okidač `WITHDRAWAL`; razlikuje ih jedino `actor` s prefiksom `LESSOR:`.

Akt se prilaže kao **preslika**; reaktivacija ide bez privitka jer nema svoju vrstu pismena
(otvoreno pitanje 7). Pad rendera akta ne sprječava mail — status je već promijenjen.

---

## 6. Konfiguracija

Sve u `application.properties`, blok `str.documents.*`. Ništa se ne perzistira — akti se
renderiraju na zahtjev, pa **nema Liquibase changeseta**.

```properties
str.documents.tijelo.naziv=${STR_TIJELO_NAZIV:Ministarstvo turizma i sporta}
str.documents.tijelo.oib=${STR_TIJELO_OIB:}
str.documents.tijelo.propis-nadleznosti=${STR_TIJELO_PROPIS:}
str.documents.potpisnik.ime=${STR_POTPISNIK_IME:}
str.documents.epecat.enabled=${STR_EPECAT_ENABLED:false}
str.documents.uputa.suspenzija=...
str.documents.reload=${STR_DOCUMENTS_RELOAD:false}
```

`str.documents.reload=true` čita predloške pri svakom renderu — tekst se mijenja bez restarta.
Za produkciju ostaje `false`.

**Uputa o pravnom lijeku je property, ne dio predloška.** Pravna narav postupka nije potvrđena
(vidi otvoreno pitanje 1), a po čl. 111. pogrešna uputa ide na štetu tijela — ovako je pravna
služba mijenja bez rebuilda.

---

## 7. Zaštita endpointa

`GET /api/rn/{rn}/documents/{tip}` do ovog rada je padao pod `anyRequest().permitAll()` u
`SecurityConfig`. Akt sada nosi **OIB stranke** (čl. 98. st. 2), pa je zatvoren:

- `SecurityConfig` traži prijavu za `/api/rn/*/documents/**`;
- `RnController#requireAccess` ograničava iznajmljivača na vlastite RB-ove — tuđi se prijavljuje
  kao 404, da endpoint ne otkriva postojanje.

Do dolaska internih rola (BX0) svaki prijavljeni ne-iznajmljivač prolazi bez dodatnog ograničenja.
Javni pregled RB-a ostaje otvoren; zatvoren je samo put do akata.

---

## 8. Otvoreno prema naručitelju

1. **Koja uputa o pravnom lijeku?** Knjiga testiranja postupak zove *neupravnim*, što bi značilo
   prigovor čelniku (čl. 122.), a ne žalbu ni upravni spor. Naručiteljev zahtjev ipak izrijekom
   traži sekciju „Uputa o pravnom lijeku". Isporučeno konfigurabilno, s defaultom
   „upravni spor, 30 dana". **Treba potvrdu pravne službe MINT-a** — čl. 111.
2. **Identitet tijela.** Naziv, **OIB**, adresa, ustrojstvena jedinica, ime i funkcija potpisnika.
   OIB `HR87892589782` čita se iz DN-a NIAS certifikata u `application-cdu.properties:60`, ali je
   **taj certifikat posuđen od InterniTurizam** — vrijednost treba potvrditi, ne prepisati. Do tada
   `str.documents.tijelo.oib` namjerno nema default.
3. **Propis o nadležnosti** (čl. 98. st. 2) — točan naziv i članak propisa koji MINT-u daje
   nadležnost za registracijski broj. Danas se citira samo čl. 6. Uredbe (EU) 2024/1028.
4. **ePečat.** Tko izdaje kvalificirani certifikat, gdje stoji (HSM?), koji format (PAdES). Do tada
   je klauzula ugašena i akt ovjeru ne tvrdi.
5. **Predlošci InfoDoma.** Mail od 22.07. najavljuje predloške „tijekom dana" — nisu stigli. Naši su
   izvedeni iz ZUP-a; kad njihovi dođu, mijenja se sadržaj `.txt` datoteka, ne kod.
6. **„Obavijest o suspenziji (s Nalogom)"** iz Knjige sugerira dva dokumenta (obavijest + priloženi
   nalog), dok mail navodi jednu vrstu pismena. Jedan PDF s izrekom, ili obavijest + prilog?
7. **Reaktivacija i SDEP obavijest** postoje u Knjizi, ali ne među 7 vrsta pismena iz maila i nemaju
   `vrstaPismena` šifru. Model ih podnosi: jedna enum konstanta + jedna `.txt` datoteka.
8. **Dvofazna suspenzija i prigovor** — bez njih dva predloška nemaju okidač. Traži ih
   TC-STR-2.1-001; zabilježeno kao B11/B12 u `STR-NEDOSTAJUCE-FUNKCIONALNOSTI.md`.

---

## 9. Transakcije — zašto REQUIRES_NEW

`StrDocumentService.render` i `RnLifecycleEmailListener.onLifecycleChange` nose
`Propagation.REQUIRES_NEW`. Oboje se zove iz `@TransactionalEventListener(AFTER_COMMIT)`
konteksta, gdje je izvorna transakcija **već dovršena**, ali su sinkronizacije još aktivne —
zadani `REQUIRED` bi je pokušao nastaviti. Isti razlog stoji iza `REQUIRES_NEW` na svakoj
metodi `EgopFilingStore` (vidi `eGOP-endpoint-analiza.md` §17.6, nalaz #2).

Postojeći `RegistrationEmailListener` taj problem nema jer sve podatke dobiva iz događaja i ne
dira bazu. Novi listener mora čitati (`RnDetailDto`, akt), pa mu vlastita transakcija treba.

---

## 10. Kako dodati novi akt

1. Konstanta u `StrDocumentType` — slug, naziv iz eGOP šifrarnika, smjer, naslov, obvezne sekcije.
2. `src/main/resources/documents/hr/<slug>.txt` sa svim obveznim sekcijama.
3. Ako treba novi placeholder — dodati ga u `ZupContextFactory` **i** u tablicu iz §4.
4. `mvn test` — `ZupTemplateLoaderTest` i `StrDocumentServiceTest` automatski pokrivaju novi tip
   (oba su parametrizirana nad `templateBackedTypes()`).
