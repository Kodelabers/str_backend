# Korisnički pretinac (KP) — izravna dostava preko SZP-a APIS IT-a

Izlazna pismena (akti) iznajmljivačima s OIB-om dostavljaju se u **korisnički pretinac** sustava
e-Građani. Od 21. 10. 2026. to ide **izravno**, preko APIS IT „Sustava za zaprimanje poruka" (SZP),
a ne preko eGOP-ove metode `OtpremiPismenoUKorisnickiPretinac`. eGOP i dalje urudžbira.

Izvori:

- *Tehnička specifikacija za spajanje na KP*, v3.6 (APIS IT, 8. 10. 2026.);
- *Poslovna specifikacija za spajanje na Korisnički pretinac*, v3.2 (15. 11. 2024.);
- WSDL/XSD paket `KP_wsdl_sheme_8.9.2026_2`, u repou nepromijenjen pod `src/main/wsdl/kp/`.

Kod je u `com.str.backend.kp`, a tablica je `str_rn.kp_otprema` (changeset 140).

## Odluke

| Datum | Odluka |
| :--- | :--- |
| 19. 10. 2026. | U KP idu sva izlazna pismena. Šalje se i bez ePečata (svjesno prihvaćen rizik prema čl. 98. st. 8 ZUP-a; pečat uključiti prije produkcije). Status u KP-u se samo zapisuje, na API-ju se ne izlaže. `ZatvoriPredmet` je izvan opsega. Šalju se samo novi akti, asinkrono. |
| 21. 10. 2026. | **Zamjena:** KP ide samo izravno preko SZP-a, a eGOP KP metode su uklonjene. **Pravna osoba** prima u **poslovni KP tvrtke** (JIPS = OIB tvrtke), ne u pretinac zastupnika. **Primatelj bez aktivnog KP-a** (K006/Z005) dobiva akt **e-poštom**. |

Non-EU iznajmljivač nema OIB ni pretinac, pa mu se akt i dalje dostavlja e-poštom.

## Kanal

| | |
| :--- | :--- |
| Protokol | SOAP 1.1, document-literal, jedna operacija `sendMessage` (`soapAction=http://www.apis-it.hr/umu/2013/services/GSBService/sendMessage`); `echo` služi za provjeru veze |
| Usmjeravanje | `MessageHeader/ServiceId`: **123** slanje FO, **125** slanje PS, **129** aktivnost FO, **128** aktivnost PS. Ne koristimo 124 (lista/provjera OIB-a), 126 (provjera JIPS-a) ni 127 (lista JIPS-ova): odbijanje pri slanju (K006/Z005) daje istu informaciju bez dodatnog poziva |
| Poslovna poruka | u `Content/Data`, kao ugniježđeni XML (`EMBEDDED`) ili Base64 (`BASE64`); bira se svojstvom `str.kp.data-encoding` dok APIS ne potvrdi |
| Adrese | test `https://cistest.apis-it.hr:8451/KorisnickiPretinacServisiTest`, prod `https://cis.apis-it.hr:8451/KorisnickiPretinacServisi` (TCP 8451, Internet ili Hitronet). `soap:address` u WSDL-u je placeholder |
| TLS | Obostrani. Poslužitelj ima certifikat Fina Demo CA 2020 (test) odnosno Fina RDC 2020 (prod) i šalje **samo list**, pa truststore mora imati i posrednički CA. Klijentski certifikat traži se tek nakon handshakea („No client certificate CA names sent", provjereno 21. 10. 2026.), pa je transport `HttpsURLConnection` (HTTP/1.1), kao kod e-Ovlaštenja |
| FO / PS | FO je KP građana, primatelj je `PinPrimatelja` = OIB uz `OznakaDrzave=HR`. PS je poslovni KP, primatelj je JIPS = `IPS` (OIB tvrtke) + `IzvorRegistar` |

## Tok

1. **Zapis kad akt nastane** (`PENDING`), samo dok je KP uključen.
   - Akt životnog ciklusa zapisuje se u `RnLifecycleFilingListener`, i za vrste bez šifre.
   - Obavijest o dodjeli zapisuje se kad se izlazno pismeno prvi put spremi.
   - Redak odmah dobiva vlastiti **`id_poruke`**, koji se više ne mijenja.
   - Akt nastao dok je KP bio ugašen nema zapisa i nikad ne ide u KP.
2. **Slanje** kad je pismeno urudžbirano s dokumentom (`SYNCED`).
   - Šalje se odmah, ali izvan niti zahtjeva (`KpExecutor`, 2 niti, red 20).
   - Izgubljen zadatak pokupi `EgopRetryJob.retryKpOtpreme` nakon grace roka.
   - Akt bez eGOP-a se ne šalje, jer nema klasu ni urudžbeni broj.
3. **Poruka:**
   - naslov je registrirani naslov vrste (default `vrstaPismenaNaziv`);
   - tekst je `kp.sadrzaj` iz `documents/hr/labels.properties`, UTF-8 kodiran u Base64;
   - `KlasaBrDok` je klasa predmeta, a `UrBroj` dolazi s pismena;
   - privitak je jedan PDF akta (`<slug>-<RB>.pdf`), s `JedinstveniIdentifikatorPismena` = id pismena;
   - veličina se provjerava prije poziva: cijeli zahtjev ≤ 3 000 000 B. Uz `data-encoding=BASE64`
     poslovna poruka se, zajedno s privitkom koji je već u Base64, kodira još jednom, pa je granica
     za PDF oko 1,6 MB umjesto oko 2,2 MB.
   - PDF obavijesti o dodjeli sprema se na pismeno pri urudžbi; to je isti dokument koji je priložen u eGOP.
4. **Ishod** (`KpGreske`):

| Ishod | Šifre | Što se radi |
| :--- | :--- | :--- |
| uspjeh | — | `SENT` |
| duplikat | K009, Z012 | `SENT`. Svaki pokušaj nosi isti `id_poruke`, pa je ponavljanje idempotentno |
| nema pretinca | K006, Z005 | `NEMA_PRETINCA` (konačno). Akt ide **e-poštom** (`KpZamjenskaDostava` → `AktDostavaMailer`, isti predlošci i klauzula kao za non-EU); neuspjelo slanje ide u `mail_retry` kao `KP_ZAMJENSKA` |
| prolazno | M008–M010, K011, K999, Z003, transport, nepoznata šifra | `FAILED` + backoff (`EgopRetryPolicy`) |
| trajno | M001–M007, M011, M012, K001–K005, K007, K010, K012–K029, Z001, Z002, Z004, Z006–Z011, Z013–Z023; i akt koji se ne može složiti (bez PDF-a, prevelik) | `FAILED` s odmah iscrpljenim pokušajima i ERROR `kp_config_error`. Nakon ispravka otpremu treba vratiti ručno (`attempts = 0`) |

   Uz svaki ishod sprema se `gsb_id`, identifikator koji SZP dodjeljuje poruci (spec. 3.1). Tekst
   greške nosi i GUID pojave greške (`[guid …]`). Po ta dva podatka helpdesk APIS-a traži
   incident (pogl. 7). `ExchangeStatus` iz zaglavlja odgovora (spec. 3.2) samo se logira, jer
   spec ne navodi njegove vrijednosti. Odgovor bez poslovnog sadržaja i bez šifre greške ne
   smatra se uspjehom, nego prolaznom greškom.

5. **Zastarjelost:** akt urudžbiran kasnije od `str.kp.max-akt-age` (7 dana) od nastanka ide u
   `SKIPPED`, jer zastarjeli akt ne smije stranci otvoriti rok.
6. **Aktivnost** (`KpStatusJob`, svaki sat):
   - dohvaća se samo za poruke poslane s pravnim učinkom;
   - zapisuje se status (0–4), primitak, zadnja aktivnost, istek roka i potpisani **PDF potvrde
     primitka** (`kp_potvrda_pdf`);
   - provjera traje dok status ne bude 2 ili 3, a najdulje 30 dana.

Kad se prolazni pokušaji iscrpe, **ne šalje se** automatski e-pošta. Prekid rada SZP-a nije isto što
i „primatelj nema KP", a poruka je možda i stigla. Ide ERROR `kp_retry_exhausted` i rješava se ručno.

## Što određuje specifikacija, a što ne

**Određuje specifikacija** (konstante ili validacija u kodu):

- ServiceId, adrese, SOAP i TLS iz tablice gore.
- Ograničenja:
  - cijela poruka ≤ 3 MB (K001/Z006);
  - PS privitak ≤ 1,5 MB u Base64;
  - naziv privitka samo `[a-zA-Z0-9 _.-]`;
  - naslov ≤ 200 znakova;
  - `KlasaBrDok` i `UrBroj` od 5 do 30 znakova, i samo zajedno (K021);
  - UUID malim slovima.
- Dopuštene kombinacije suglasnost/potvrda/e-potpis za poruku s pravnim učinkom (Z019): F/F/F,
  F/T/F, F/T/T, T/F/F, T/T/F, T/T/T.
- Uz suglasnost ili potvrdu obavezni su `RokDani` > 0 i `NeradniDanURok`; inače `RokDani=0` i
  `NeradniDanURok=F` (Z023).
- `TrajnoCuvanje=F` traži `GodineCuvanja` od 1 do 99.
- Poruka s pravnim učinkom mora imati privitak s JIP-om (Z020/K026).
- Status aktivnosti ima vrijednosti 0–4 i dohvaća se samo za poruke s pravnim učinkom
  (inače A005/C005).

**Dodjeljuje APIS/REGUP pri registraciji** i ne može se izvesti: `str.kp.sender-id`,
`id-posiljatelja-fo`, `id-posiljatelja-ps` te `tip-poruke-fo/ps.<vrsta>` za svaku od 7 izlaznih
vrsta. Uz to, naslov poruke mora biti **jednak** naslovu registrirane e-poruke (poslovna spec. 3.1).

**Prijavljuje MINT u obrascu za e-poruku**, a poruka mu mora odgovarati (Z007/K015, Z002/K013):

- `pravni-ucinak`;
- `potrebna-suglasnost`, `potrebna-potvrda`, `potreban-dsig`;
- `rok-dani`, `neradni-dan-u-rok`;
- `trajno-cuvanje`, `godine-cuvanja`;
- `razina-sigurnosti` (2 niska, 3 značajna, 4 visoka).

Bez ijedne od ovih vrijednosti aplikacija uz `KP_ENABLED=true` ne krene. `KpProperties.requireComplete`
na startu provjerava i Z019/Z023 te navodi sve što nedostaje odjednom.

**Prijedlog za MINT** (treba ga potvrditi, nije odluka):

- pravni učinak T, jer je riječ o ZUP aktu; samo tada radi dohvat aktivnosti i stiže PDF potvrde;
- kombinacija F/T/F: bez suglasnosti, s potvrdom primitka, bez e-potpisa;
- naslovi e-poruka jednaki nazivima vrsta pismena, registrirani i za FO i za PS:

| Vrsta (`slug`) | Naslov |
| :--- | :--- |
| `dodjela` | Obavijest o dodjeli registracijskog broja |
| `opoziv` | Obavijest o opozivu registracijskog broja |
| `prijedlog-suspenzije` | Obavijest o prijedlogu suspenzije registracijskog broja |
| `suspenzija` | Obavijest o suspenziji registracijskog broja |
| `povlacenje` | Obavijest o povlačenju registracijskog broja |
| `reaktivacija` | Obavijest o reaktivaciji registracijskog broja |
| `obustava-suspenzije` | Obavijest o obustavi postupka suspenzije registracijskog broja |

## Preduvjeti za paljenje

1. **Registracija** (MINT): institucija u REGUP-u i obrazac za svaku e-poruku, uz podatke o certifikatu.
   Za testnu okolinu stiglo je 21. 10. 2026. (InfoDom konfig): `SenderId` = 117, `IdPosiljatelja` = 286
   (isti za KP građana i poslovni KP). `TipPoruke` po vrsti akta još nije dodijeljen.
2. **Klijentski certifikat** za mTLS, prijavljen u obrascu. Spec. 6.2 navodi „Tip certifikata:
   poslužiteljski", a poslovna spec. 3.2 „nabavu poslužiteljskog certifikata". Prihvaćaju se izdavatelji
   s EU liste i priznati komercijalni izdavatelji. Prijedlog je vlastiti CN=e-Turizam
   (`/opt/str/config-external/keystore.p12` na cdupreprod), ne posuđeni InterniTurizam. Na testu
   APIS mora prihvatiti FINA demo certifikat.
3. **Truststore:** test radi s `classpath:eovlastenja/ca-bundle.crt` (Fina Demo CA 2020 + root), što
   je default. Za prod treba dodati **Fina RDC 2020** i **Fina Root CA** (`KP_TRUSTSTORE_PATH`).
   Spec. 6.2 za njih upućuje na http://www.fina.hr/finadigicert. To su certifikati APIS-ova
   poslužitelja, ne naš klijentski certifikat.
4. **Mreža:** provjeriti s kutije:

   ```
   openssl s_client -connect cistest.apis-it.hr:8451 -servername cistest.apis-it.hr </dev/null
   ```

   Treba se pojaviti certifikat koji izdaje Fina Demo CA 2020, a ne timeout. Nakon paljenja u logu
   se traži `kp_echo ok` (`KpConfig` šalje jedan `echo` nakon starta, a pad ne ruši aplikaciju).
5. **Prvo test, pa produkcija:** integracija se mora provesti na testnoj okolini prije produkcije
   (poslovna spec. 2.2).
6. **Konfiguracija:** svi `KP_*` iz `application.properties` (komentari uz svaki ključ) i
   `.env.cdu.example`, uz `EGOP_ENABLED=true`.

## Otvorena pitanja

**APIS helpdesk** (`helpdesk@apis-it.hr`, naslov „Korisnički pretinac TEST"):

1. Primjeri XML-a iz poglavlja 8.2 i 8.3 nisu u paketu. Spec se uz to poziva na paket
   `KP_wsdl_sheme_11.2022`, a stigao je `8.9.2026_2`.
2. `Content/MimeType` i `Data@encoding` (EMBEDDED ili BASE64) za svaki servis.
3. Je li `ds:Signature` omotnice obavezan?
4. `IzvorRegistar` za OIB pravne osobe: tablica kaže „1", a shema traži `[0-9]{3}`. Je li ispravno „001"?
5. Nedosljednosti FO sheme i speca:
   - `SifraGreske` je u shemi `s[0-9]{3}`, a šifre su K001… (odgovore zato ne validiramo);
   - `StatusOdgovora` je u shemi OK/Greska, a u specu 1/2;
   - `Sadrzaj` je u shemi string do 2000 znakova, a u specu Base64 do 5000.
6. Zona vremena u `TsSlanja` / `DatumVrijemeSlanja` i u odgovorima (zasad čitamo Europe/Zagreb).
7. Znači li K009/Z012 sigurno da je poruka s tim ID-om već u pretincu? Na tome počiva idempotentnost.
8. Jesu li `TipPoruke` za FO i `IdPorukeURegistru` za PS isti broj za istu e-poruku?
9. MIME tip privitka za PDF (`application/pdf`?), zbog K024.
10. Ograničenje učestalosti poziva, posebno za dohvat aktivnosti.
11. Spec. 4.1 kaže da su „dopuštene sve četiri kombinacije" potvrde primitka i e-potpisa, a Z019
    zabranjuje e-potpis bez potvrde primitka (F/F/T, T/F/T). Što vrijedi? Kod slijedi Z019.
12. K021/Z022: provjerava li se oblik klase i urudžbenog broja? Naša klasa iz neupravne upisne
    knjige ima oblik `334-01/26-01/55`, bez `UP/I-`.
13. Koje vrijednosti ima `ExchangeStatus` u zaglavlju odgovora (spec. 3.2)?
14. Je li naš certifikat (CN=e-Turizam, FINA) prihvatljiv kao „poslužiteljski" certifikat iz spec. 6.2?
15. Preporuka iz 4.2/5.2 da se prije slanja koristi popis aktivnih pretinaca (124/126/127): je li
    obavezna? Mi koristimo odgovor K006/Z005 pri slanju.

**MINT (pravno):**

1. Potvrditi parametre iz prijedloga gore i registrirati e-poruke.
2. Pravna posljedica statusa 0 nakon `IstekRoka` te statusa 3 (odbijeno). Treba li tada zamjenska
   dostava? Zasad se samo zapisuje.
3. Vrijedi li dostava e-poštom za OIB-ovca bez KP-a? Klauzula čl. 94. st. 4 dosad je pisana za non-EU.
4. Vrijednost `JedinstveniIdentifikatorPismena`: zasad je to naš UUID pismena. Treba li umjesto
   njega oznaka iz eGOP-a?
5. Treba li PDF potvrde primitka urudžbirati u eGOP kao dostavnicu? Zasad se samo sprema.
6. Treba li automatska e-pošta i kad se prolazni pokušaji iscrpe? Zasad ne, jer postoji rizik
   dvostruke dostave.
7. Razina sigurnosti nije tehnički detalj: određuje kojom razinom vjerodajnice primatelj uopće može
   otvoriti poruku (spec. 2, 5.1). Uz razinu 3 akt ne otvara korisnik prijavljen niskom razinom.
8. Rok se u specifikaciji računa dvojako: kod slanja `RokDani` teče od zaprimanja poruke u KP, a
   u odgovoru aktivnosti od slanja (spec. 4.4.3). Koje je pravno relevantno?

## Mogućnosti koje ne koristimo

- **Kalendarski događaji** (`KalendarDogadjaji`, spec. 4.1.3), samo u poslovnom pretincu (FO shema
  ih nema): datum, naslov i opis prikazuju se kao podsjetnik u kalendaru KP-a. Dobro bi pristajali
  prijedlogu suspenzije s rokom za ispravak. To je zaseban zadatak, uz potvrdu MINT-a.
- **Povezivanje poruka u slijed** (`OznakaSljedivosti`, najviše 6 znakova): svi akti istog RB-a mogli
  bi biti jedna nit. RB ima 20 znakova pa ne stane, a trebala bi kraća oznaka.
- **HTML u sadržaju** (do 5000 znakova bez oznaka, spec. 4.1): šaljemo običan tekst.
- **Popis i provjera aktivnih pretinaca** (124, 126, 127): spec ih preporučuje radi manjeg
  opterećenja, a mi koristimo odgovor na slanje.
- **`Content/Description`** (do 255 znakova), **`AditionalServiceId`** i **`ds:Signature`** omotnice:
  nisu obavezni.

## Svjesno ostavljeno

- Jobovi nemaju zaključavanje. Uz više instanci retry može poslati istu poruku dvaput, ali
  `id_poruke` to pretvara u duplikat, a ne u dvostruku dostavu.
- SSL kod u `KpConfig` namjerno je kopija obrasca iz `EOvlastenjaConfig`, a ne zajednička klasa,
  da se ne dira e-Ovlaštenja koja rade na CDU-u.
