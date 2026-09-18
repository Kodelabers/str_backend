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
2. **Blok e-pečata se ne ispisuje dok pečata nema.** Tvrdnja o ovjeri na nepečaćenom PDF-u
   bila bi neistinita izjava na pravnom aktu. Ispis je iza `str.documents.epecat.enabled`.

---

## 2. Vrste akata

Definirane u `document/StrDocumentType.java`. `vrstaPismenaNaziv` je **točan ključ eGOP
šifrarnika** — mora se poklapati znak za znak, jer po njemu `EgopCodebooks` razrješava šifru.

| slug | Vrsta pismena (eGOP) | Smjer | Obvezne sekcije | Okidač |
| :--- | :--- | :--- | :--- | :--- |
| `zahtjev` | Zahtjev za registracijski broj | ulazno | čl. 71. — vlastiti generator | registracija |
| `dodjela` | Obavijest o dodjeli registracijskog broja | izlazno | uvod, izreka | registracija |
| `opoziv` | Obavijest o opozivu registracijskog broja | izlazno | uvod, izreka | `LessorRnActionService.withdrawOwn` / `withdrawOwnByOib` |
| `prijedlog-suspenzije` | Obavijest o prijedlogu suspenzije registracijskog broja | izlazno | + obrazloženje; **rok u izreci** | `RnService.suspend` |
| `suspenzija` | Obavijest o suspenziji registracijskog broja | izlazno | **+ uputa o pravnom lijeku** | `SuspensionDeadlineJob` (istek roka) |
| `obustava-suspenzije` | Obavijest o obustavi postupka suspenzije registracijskog broja | izlazno | kao `opoziv` | `RnService.revokeProposal` |
| `povlacenje` | Obavijest o povlačenju registracijskog broja | izlazno | **+ uputa o pravnom lijeku** | `RnService.withdraw` |
| `prigovor` | Prigovor na prijedlog suspenzije | **ulazno** | čl. 71. — podnesak stranke | ❌ nema |
| `reaktivacija` | Obavijest o reaktivaciji registracijskog broja | izlazno | kao `opoziv` | `RnService.reactivate` |

Suspenzija je **dvofazna**: `RnService.suspend()` ide u `SUSPENSION_PROPOSED` i šalje poziv na
izjašnjavanje (`prijedlog-suspenzije`), a tek `SuspensionDeadlineJob` po isteku roka prelazi u
`SUSPENDED` i šalje `suspenzija`. Ispravi li stranka nedostatak, `revokeProposal` vraća RB u
`ACTIVE` uz `obustava-suspenzije`.

`reaktivacija` i `obustava-suspenzije` **nisu među 7 vrsta pismena** iz InfoDomovog maila i
nemaju šifru u eGOP šifrarniku (obje su nastale uz dvofaznu suspenziju, koje u trenutku dogovora
nije bilo). Predlošci i okidači postoje; urudžbiranje je iza `str.egop.akti-bez-sifre` (vidi §6),
ali se akt **svejedno zapisuje i vidi u popisu dokumenata RB-a**, a obavijest e-poštom ide
neovisno o tome. `ZupTemplateLoaderTest` drži taj popis u šahu.

`prigovor` **nema okidač** — ne postoji nigdje u `src/main`. Predložak je isporučen i dostupan
preko endpointa, ali automatike nema dok se ne uvede domenski model prigovora.

> Natpisi statusa i okidača žive u `documents/hr/labels.properties`. `ZupContextFactory` traži
> natpis statusa pri **svakom** renderu i nedostajući ključ baca — a to se dogodi tek u
> `AFTER_COMMIT` listeneru, nakon što je status već promijenjen. Novi `RnStatus`/`RnTrigger`
> zato uvijek nosi i natpis; `DocumentLabelsTest` to provjerava.

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
- **Redak koji nakon zamjene ostane prazan izbacuje se** — neobavezni podaci (URBROJ prije
  urudžbiranja, zastupnik, poštanski broj) ne ostavljaju rupu ni prelom odlomka
  (`ZupDocumentRenderer.vezi`). Prazni redci samog predloška i dalje dijele odlomke.
- `ZAGLAVLJE` i `POTPISNIK` renderer zna složiti sam iz konfiguracije; predložak ih smije nadjačati.
  Nadjačano `ZAGLAVLJE` mijenja samo urudžbeni blok (KLASA, URBROJ, mjesto i datum) — grb i naziv
  tijela uvijek crta renderer.

### Okvir akta (predložak MINT-a od 11.09.2026.)

Naručitelj je 11.09.2026. vratio naš PDF obavijesti o dodjeli s praćenim izmjenama
(„Obavijest o dodjeli RB IP DŠ.docx"). Okvir iz tog predloška vrijedi za **sve akte tijela**
(smjer `IZLAZNO`); razmaci u `ZupDocumentRenderer` izmjereni su na njemu i ne treba ih
„zaokruživati" bez usporedbe:

- **Zaglavlje:** tablica bez okvira. Lijevo grb (`documents/grb-rh.png`, 46×58 pt), ispod
  „REPUBLIKA HRVATSKA" i naziv tijela velikim slovima; desno `P/<jop>` (eGOP JOP izlaznog
  pismena) ispod retka rezerviranog za barkod jedinstvene oznake pismena. Bez ustrojstvene
  jedinice i bez crte ispod zaglavlja.
- **Urudžbeni blok** ispod zaglavlja: `KLASA: …`, `URBROJ: …`, `Zagreb, 10. rujna 2026.`
- **Potpis:** naziv tijela velikim slovima (od x≈321 pt), ime službene osobe ispod samo ako je
  konfigurirano. Funkcija se ne ispisuje.
- **Blok e-pečata** po uzoru na Poreznu upravu (grb i tijelo | podaci o certifikatu, broj zapisa,
  kontrolni broj | QR i tekst provjere) — **samo uz `str.documents.epecat.enabled=true`**.
- **Podnožje samo na aktu s više stranica:** „KLASA: … · stranica 1 od 2". Predložak naručitelja
  je jednostranični i podnožje ne nosi, ali akt s obrazloženjem i uputom ide na dvije stranice, a
  odvojen list bez KLASE i broja stranice ne može se povezati s predmetom. Ukupan broj stranica
  zna se tek na kraju, pa se na svaku stranicu ostavi prazan `PdfTemplate` i ispuni u
  `onCloseDocument` — ili ostane prazan kad akt stane na jednu stranicu.
- **Datum u zaglavlju** je datum izdavanja RB-a za obavijest o dodjeli (renderira se na zahtjev
  i mjesecima kasnije), a datum rendera za akte životnog ciklusa, koji nastaju u trenutku
  prijelaza i kojima se PDF sprema.

Podnesak stranke (`PRIGOVOR`, smjer `ULAZNO`) nije akt tijela: nema grb ni naziv tijela u
zaglavlju, potpisuje ga podnositelj (`${stranka.naziv}`), a pečat tijela se na njega ne stavlja.

### Struktura je ista na svim obavijestima

Sadržajne izmjene iz istog predloška vrijede za **sve obavijesti tijela**, ne samo za dodjelu:

- **nema adresata** (`[NASLOV]`) — stranka je imenovana u uvodu, a dostava ide u korisnički pretinac;
- **nema naslova „I Z R E K A"** — `ZupSection.IZREKA` više nema naslov; točke idu odmah iza
  naslova akta. ZUP naslov i ne traži, traži izreku kao sastavni dio;
- **nema priloga ni dostavne liste** (`[PRILOZI]`, `[DOSTAVNA_LISTA]`) — uz PDF se ionako ništa ne
  prilaže, a dostava je evidentirana u eGOP-u;
- **uvod** je skraćen kao u predlošku: bez „u postupku pokrenutom" i bez „u predmetu …". Ostaje
  način pokretanja (na zahtjev / po službenoj dužnosti), koji čl. 98. st. 2 traži; predmet je u
  naslovu akta i u izreci;
- **objekt** se identificira skupinom i vrstom (`${objekt.skupina}`, `${objekt.vrsta}`);
- **naš registar** se u svim aktima zove „integrirani informacijski sustav turizma", ne „registar".

Što ostaje razlika, i zašto: **obrazloženje** (čl. 98. st. 5) nose prijedlog suspenzije,
suspenzija i povlačenje, a **uputa o pravnom lijeku** (čl. 98. st. 6) suspenzija i povlačenje —
to su ZUP-ovi sastavni dijelovi, ne uredski ukras, i po čl. 111. njihov izostanak ide na štetu
tijela. Zbog njih su ta tri akta duga dvije stranice: prva je puna teksta, a potpis i blok
e-pečata idu zajedno na drugu (ne razdvajaju se, jer pečat pripada uz potpis). Ostale četiri
obavijesti staju na jednu stranicu.

`StrDocumentServiceTest.everyNotice_hasSameStructureAsDodjela` i
`ZupTemplateLoaderTest.everyNotice_hasNoAddresseeOrDistributionList` drže to na svim vrstama, pa
nova obavijest ne može ispasti iz formata.

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
| `${tijelo.nazivVelikim}` | izvedeno iz `tijelo.naziv` | zaglavlje i potpis |
| `${tijelo.adresa}`, `${tijelo.mjesto}`, `${tijelo.ustrojstvenaJedinica}` | isto | neobavezno |
| `${tijelo.propisNadleznosti}` | `str.documents.tijelo.propis-nadleznosti` | čl. 98. st. 2 |
| `${potpisnik.ime}`, `${potpisnik.funkcija}` | `str.documents.potpisnik.*` | čl. 98. st. 7; neobavezno — potpis je naziv tijela |
| `${akt.naslov}` | `StrDocumentType.naslov()` | |
| `${akt.klasa}`, `${akt.urbroj}` | `submission.egop_klasa` + `egop_pismeno.ur_broj` | prazno prije urudžbiranja |
| `${akt.klasaRedak}`, `${akt.urbrojRedak}`, `${akt.mjestoDatum}` | izvedeno | gotovi redci zaglavlja; prazni kad nema oznaka |
| `${akt.jopRedak}` | `egop_pismeno.jop` | „P/21748084"; prazno prije urudžbiranja (akti životnog ciklusa ga zato nemaju) |
| `${akt.datum}` | datum izdavanja RB-a za dodjelu, inače današnji | svi datumi u obliku „10. rujna 2026." (genitiv, `ZupContextFactoryTest`) |
| `${stranka.naziv}` | `lessorLegalEntityName` → `firstName lastName` | |
| `${stranka.identifikator}` | OIB ili „bez dodijeljenog OIB-a" | non-EU iznajmljivač nema OIB |
| `${stranka.oib}` | `RnDetailDto.lessorOib` | sirova vrijednost |
| `${stranka.zastupnik}` | `legalRepresentativeName` + OIB | prazno kad ga nema |
| `${stranka.adresa}`, `${stranka.mjesto}` | `LessorEntity.street/streetNumber/place` | |
| `${stranka.adresaRedak}` | izvedeno | gotov redak „Adresa: …"; prazan kad adrese nema, pa ne ostaje goli natpis |
| `${stranka.postanskiBroj}` | — | **uvijek prazno**, vidi niže |
| `${rn.broj}`, `${rn.status}`, `${rn.datumIzdavanja}` | `RnDetailDto` | |
| `${rn.razlog}` | parametar → revizijski trag → natpis okidača | |
| `${objekt.naziv}`, `${objekt.adresa}`, `${objekt.mjesto}`, `${objekt.zupanija}`, `${objekt.vrsta}`, `${objekt.kapacitet}` | `RnDetailDto` | |
| `${objekt.skupina}` | šifra skupine **iz RB-a** → `labels.properties` (`objekt.skupina.<kod>`) | danas samo `00` = domaćinstvo; nepoznata šifra daje vidljivu oznaku + ERROR, ne tiho domaćinstvo |
| `${rok.ispravak}` | `RnEntity.suspensionDeadline` | bez roka → `rok.default` iz `labels.properties` |
| `${uputa.tekst}` | `str.documents.uputa.<slug>` | vidi §6 |

**Adresni placeholderi su ostali samo prigovoru.** `${stranka.zastupnik}`, `${stranka.adresa}`,
`${stranka.postanskiBroj}` i `${stranka.mjesto}` koristi još samo podnesak stranke — obavijesti
nemaju adresata (§3). Ključevi ostaju u katalogu jer ih predložak smije vratiti.

**`${stranka.postanskiBroj}` je uvijek prazan.** `LessorEntity` nema poštanski broj — poznata rupa
(`eGOP-endpoint-analiza.md` §15.2). Prihvatljivo jer dostava ide u korisnički pretinac, ne poštom.

**Hrvatski nazivi enum vrijednosti** (`RnTrigger`, `RnStatus`) su u
`documents/hr/labels.properties`. Prije ovog rada nisu postojali nigdje u kodu: `i18n/hr.properties`
nije uvezan u `MessageSource` i pisan je bez dijakritike.

### Odakle dolazi razlog

`RnService.suspend()` ne prima slobodan tekst — samo `RnTrigger`. Zato `StrDocumentService`
razlog traži ovim redom:

1. parametar poziva (`?reason=` na endpointu, ili proslijeđen iz listenera),
2. `reason` iz odgovarajućeg zapisa u `registration_number_log`,
3. hrvatski natpis okidača iz `labels.properties`.

Revizijski trag je pouzdaniji izvor od `?reason=` parametra, koji nitko ne provjerava.

**Koji zapis — suspenzija i obustava čitaju s prijedloga.** Suspenzija je dvofazna, pa zadnji
zapis nosi samo procesni okidač: `DEADLINE_EXCEEDED` za suspenziju, `REVOKE_PROPOSAL` za obustavu.
Iz njega je obustava ispisivala samu sebe („pokrenut zbog sljedećeg razloga: obustava postupka
suspenzije"), a suspenzija rok umjesto razloga („zbog sljedećeg razloga: istek roka za
očitovanje") — procesni okidač na mjestu gdje čl. 98. st. 3 i 5 traže materijalni razlog. Zato
`SUSPENZIJA` i `OBUSTAVA_SUSPENZIJE` razlog čitaju sa zapisa prijelaza u `SUSPENSION_PROPOSED`
(`findFirstByRnAndToStatusOrderByOccurredAtDesc`), a ostali akti sa svog, zadnjeg prijelaza.
Nema li prijedloga u tragu (jednofazna suspenzija iz starijih podataka), vrijedi zadnji zapis.

---

## 5. Poruke e-pošte

`src/main/resources/documents/mail/*.html`, isti mehanizam placeholdera. Okvir (omot, gumb,
escapiranje) ostaje u Javi jer je izgled, ne tekst.

| Predložak | Okidač |
| :--- | :--- |
| `odobrenje`, `odbijanje` | `AdminPendingRegistrationService.approve/reject` |
| `rb-izdan` | `EgopRegistrationDispatcher` (samo non-EU; EU ide preko KP) |
| `prijedlog-suspenzije` | prijelaz → `SUSPENSION_PROPOSED` |
| `suspenzija` | prijelaz → `SUSPENDED` uz `DEADLINE_EXCEEDED` |
| `obustava-suspenzije` | prijelaz → `ACTIVE` uz `REVOKE_PROPOSAL` |
| `reaktivacija` | prijelaz → `ACTIVE` uz `REACTIVATE` |
| `povlacenje` | prijelaz → `WITHDRAWN` po službenoj dužnosti |
| `opoziv` | prijelaz → `WITHDRAWN` na zahtjev iznajmljivača |

Događaj `RnLifecycleEvent` objavljuje se iz **`RnStatusTransitionService.transition()`** — jedine
točke kroz koju status smije proći (pravilo iz `CLAUDE.md`). Time obavijest ne može promaknuti
nijednom budućem pozivatelju. `RnLifecycleEmailListener` radi `AFTER_COMMIT`, pa vidi i
`suspensionDeadline` koji `RnService.suspend` upisuje *nakon* prijelaza.

Opoziv i povlačenje dijele okidač `WITHDRAWAL`; razlikuje ih jedino `actor` — prefiks `LESSOR:`
(prijava lozinkom) ili `NIAS:` (prijava preko NIAS-a) znači opoziv na zahtjev stranke, sve
ostalo povlačenje po službenoj dužnosti. Oba prefiksa moraju biti u
`RnLifecycleEvent.initiatedByLessor()`; NIAS je produkcijski put prijave, pa bi izostavljen
prefiks krivo klasificirao većinu samo-opoziva.

Akt se prilaže kao **preslika**. Pad rendera akta ne sprječava mail — status je već promijenjen.
Mail ide i za vrste bez šifre u eGOP šifrarniku (`reaktivacija`, `obustava-suspenzije`), jer
blokadna lista `str.egop.akti-bez-sifre` zaustavlja samo urudžbiranje, ne i obavijest.

---

## 6. Konfiguracija

Sve u `application.properties`, blok `str.documents.*`. Ništa se ne perzistira — akti se
renderiraju na zahtjev, pa **nema Liquibase changeseta**.

```properties
str.documents.tijelo.naziv=${STR_TIJELO_NAZIV:Ministarstvo turizma i sporta}
str.documents.tijelo.oib=${STR_TIJELO_OIB:87892589782}
str.documents.tijelo.propis-nadleznosti=${STR_TIJELO_PROPIS:<čl. 46. Zakona o ugostiteljskoj djelatnosti (NN ___)>}
str.documents.potpisnik.ime=${STR_POTPISNIK_IME:}
str.documents.epecat.enabled=${STR_EPECAT_ENABLED:false}
str.documents.epecat.izdavatelj-certifikata=${STR_EPECAT_IZDAVATELJ:}
str.documents.epecat.naziv-certifikata=${STR_EPECAT_NAZIV_CERTIFIKATA:}
str.documents.epecat.algoritam=${STR_EPECAT_ALGORITAM:SHA256withRSA}
str.documents.epecat.url-provjere=${STR_EPECAT_URL_PROVJERE:}
str.documents.uputa.suspenzija=...
str.documents.reload=${STR_DOCUMENTS_RELOAD:false}
```

**Dijakritika u vrijednostima ide kao `\uXXXX` escape.** Spring Boot `.properties` čita kao
ISO-8859-1, pa izravno upisani UTF-8 („dopuštena žalba") na aktu izlazi kao „dopuÅ¡tena Å¾alba".
Tako je bilo s uputom o pravnom lijeku do 18.09.2026. Komentari smiju imati dijakritiku.

**E-pečat.** Uz `enabled=true` akt dobiva blok e-pečata; nedostaje li izdavatelj, naziv
certifikata ili URL provjere, u bloku stoji vidljiva oznaka i u log ide ERROR. **QR se ne crta
dok URL portala nije postavljen** — skenirajući kod koji vodi na oznaku „nije konfigurirano" gori
je od nikakvog (`EpecatBlokTest` ga dekodira natrag da ne ostane necitljiv). Pečat je pečat
tijela, pa se podaci ne grade za podnesak stranke.

**Provjera nakon deploya.** `StartupDiagnostics` ispiše redak `startup_documents` s
naziv/oib/propis/potpisnik/epecat; `PRAZAN (pregazio je default — provjeri .env)` znači da je
ključ u `.env`-u postavljen na prazno i da će akt nositi oznaku „nije konfigurirano". Broj zapisa (UUID)
i kontrolni broj (8 znamenki) zasad se samo generiraju pri renderu — trajni zapis para uz PDF i
portal za provjeru izvornika dio su faze pečatiranja. Do tada `enabled` ostaje `false` u svim
okruženjima, jer bi inače van išli brojevi koje nitko ne može provjeriti.

`str.documents.reload=true` čita predloške pri svakom renderu — tekst se mijenja bez restarta.
Za produkciju ostaje `false`.

Uz to, jedan ključ iz `str.egop.*` bloka dira izravno akte:

```properties
str.egop.akti-bez-sifre=${EGOP_AKTI_BEZ_SIFRE:reaktivacija,prijedlog-suspenzije,obustava-suspenzije}
```

Slugovi vrsta pismena koje eGOP šifrarnik (još) nema. Takav se akt **renderira, zapisuje u
`str_rn.egop_pismeno` i prikazuje stranci u popisu dokumenata RB-a — ali se ne šalje eGOP-u**;
slanje bi palo na razrješavanju vrste pismena i vrtjelo retry do iscrpljenja pokušaja.

Zapisivanje je namjerno odvojeno od urudžbiranja: `GET /api/rn/{rn}/documents` popis akata gradi
iz `egop_pismeno` (`RnDocumentsService.listForRn`), pa bi akt koji se ne zapiše postojao samo kao
privitak e-pošte i stranka ga ne bi vidjela među svojim dokumentima. `EgopAktiBezSifre` je zato
zajednički za listener (preskače slanje) i `EgopRetryJob` (izuzima te akte iz reda) — da popis
živi na samo jednom mjestu, cron bi slao upravo ono što je listener preskočio.

Slug se miče s popisa čim InfoDom potvrdi šifru, bez izmjene koda; zaostali akti se tada
urudžbiraju pri prvom sljedećem prolasku retry joba. Prazna vrijednost znači da se urudžbira sve.

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
2. **Identitet tijela.** OIB `87892589782` upisao je naručitelj u predložak od 11.09.2026., pa je
   postavljen kao default. Ime službene osobe (neobavezno, ispisuje se ispod naziva tijela) još
   nije dostavljeno.
3. **Propis o nadležnosti** (čl. 98. st. 2) — predložak od 11.09.2026. navodi „članka 46. Zakona o
   ugostiteljskoj djelatnosti (Narodne novine, broj ___)". **Brojevi Narodnih novina nedostaju**;
   default je doslovno naručiteljev tekst dok ne stignu.
4. **ePečat.** Vizualni blok je gotov (vidi §6). Nedostaju: izdavatelj i naziv kvalificiranog
   certifikata (FINA), gdje stoji (HSM?), format (PAdES), URL portala za provjeru izvornika, te
   potvrda da smijemo preuzeti tekst Porezne uprave uz zamjenu tijela.
5. **Predlošci InfoDoma.** Mail od 22.07. najavljuje predloške „tijekom dana" — nisu stigli. Naši su
   izvedeni iz ZUP-a; kad njihovi dođu, mijenja se sadržaj `.txt` datoteka, ne kod.
6. **„Obavijest o suspenziji (s Nalogom)"** iz Knjige sugerira dva dokumenta (obavijest + priloženi
   nalog), dok mail navodi jednu vrstu pismena. Jedan PDF s izrekom, ili obavijest + prilog?
7. **Reaktivacija i SDEP obavijest** postoje u Knjizi, ali ne među 7 vrsta pismena iz maila i nemaju
   `vrstaPismena` šifru. Model ih podnosi: jedna enum konstanta + jedna `.txt` datoteka.
8. **Dvofazna suspenzija i prigovor** — bez njih dva predloška nemaju okidač. Traži ih
   TC-STR-2.1-001; zabilježeno kao B11/B12 u `STR-NEDOSTAJUCE-FUNKCIONALNOSTI.md`.
9. **Grb u visokoj rezoluciji.** `documents/grb-rh.png` je bitmapa iz naručiteljevog predloška
   (79×100 px, ≈124 dpi na 16×20 mm) — u tisku je mekan. Treba vektor ili PNG ≥ 300 px širine.
10. **Barkod jedinstvene oznake pismena** u desnom kutu zaglavlja (u predlošku font
    IDAutomationC93M, Code 93). Vrijednost je eGOP-ov `jedinstvenaOznakaPismena`, koju legacy
    `KreirajPismeno2` ne vraća (vraćaju ga `KreirajPismenoPoUredbi` i `PismenoPoKriterijima3`).
    Treba li uopće, kad dostava ide elektronički? Redak mu je rezerviran.
11. **KLASA i URBROJ** u predlošku: `334-06/26-10/…` i `529-06-03/01-26-TUREGBROJ-2`. Oznake
    dodjeljuje eGOP — što je „TUREGBROJ"? Mock alokator (`LocalFilingNumberAllocator`) još koristi
    `334-01/…-01/…` i `529-06/…`.
12. **Datum:** „09. rujna" s vodećom nulom (kao u predlošku) ili „9. rujna" (pravopis)? Jedna
    konstanta u `ZupContextFactory`.
13. **Potpis:** samo naziv tijela (kao u predlošku) ili i ime službene osobe?
14. **Padež uz ime stranke.** Ime se ne može deklinirati programski, pa akti po službenoj dužnosti
    koriste neutralan oblik „po službenoj dužnosti, stranka: Ana Anić, OIB: …". Dodjela i opoziv
    zadržali su naručiteljev oblik „na zahtjev Ana Anić, OIB: …" (nominativ). Ako pravna služba
    traži genitiv, treba nam polje s imenom u genitivu — iz podataka se ne može izvesti.

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
4. Ako akt nastaje iz prijelaza statusa: grana u `StrDocumentType.forTransition` (jedno mjesto
   za oba potrošača — urudžbiranje i mail), konstanta u `MailTemplate` s
   `documents/mail/<slug>.html`, i `case` u `RnLifecycleEmailListener.mailTemplateFor`
   (switch je iscrpan, pa build padne ako se zaboravi).
5. Ako vrsta nema potvrđenu šifru u eGOP šifrarniku — slug na `str.egop.akti-bez-sifre` i u
   popis u `ZupTemplateLoaderTest.typesOutsideAgreedCodebookSet_areKnownAndUnfiled`.
6. Novi `RnStatus`/`RnTrigger` traži i natpise u `documents/hr/labels.properties` — bez njih
   render puca u `AFTER_COMMIT` listeneru, nakon što je status već promijenjen
   (`DocumentLabelsTest` to hvata).
7. `mvn test` — `ZupTemplateLoaderTest` i `StrDocumentServiceTest` automatski pokrivaju novi tip
   (oba su parametrizirana nad `templateBackedTypes()`).
