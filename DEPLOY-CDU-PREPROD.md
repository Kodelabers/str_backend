# CDU predprodukcija — `str-preprod-eturizam.gov.hr` (PLAN)

> **Status 18.09.2026.: okolina je INSTALIRANA i radi; čeka se samo gateway.**
>
> Backend i frontend su podignuti na `172.20.8.143:8085`, aplikacija se uredno diže, baza,
> adresni registri, captcha i NIAS keystore su provjereni u `startup_` bloku (§C7). Interno je
> dostupna na `http://172.20.8.143:8085`.
>
> **Javni URL još ne radi** i to nije do nas: `https://str-preprod-eturizam.gov.hr` prolazi TLS
> pa se veza resetira, dok `str-test-eturizam.gov.hr` na istom IP-u vraća 200 — gatewayu
> (F5 BigIP) nedostaje pravilo `443 → 172.20.8.143:8085`. Provjereno je i da je port dostupan
> s mreže (`Test-NetConnection` prolazi), pa je s naše strane sve zatvoreno (A3/13).
>
> **Otvoreno:** vrijeme reset prozora (bez njega nema crona, §C8) i potvrda da tablični `SELECT`
> grantovi prežive reset (A1/6). Za redeploy nove verzije vidi **§C9**.
>
> **10/2026 — NIAS certifikat zamijenjen.** Po novoj politici okolina se NIAS-u predstavlja
> certifikatom **CN=e-Turizam**, a ne posuđenim „InterniTurizam". Certifikat se više ne donosi
> `scp`-om: stoji na kutiji u `/opt/str/config-external/keystore.p12`, compose ga montira na istu
> putanju (read-only), lozinka je u `.env.cdupreprod`. Stari `~/str-rn/secrets/nias-prod.p12` i
> mount `/secrets` su uklonjeni. Prelazak je **§C10**.

Nova okolina je **druga kutija na istom državnom VPN-u** kao CDU test. Nije nastavak
InfoDomove predprodukcije (`s-str-02`) — s njom dijeli samo riječ „predprodukcija".

---

# ⚡ START OVDJE — instalacija u 8 koraka

**Trebaš samo dvoje da kreneš:** državni VPN gore i ~30 min. DB lozinku uzimaš sam u koraku 1;
sve ostalo je u repou. Puni tekst svakog koraka, s kontrolnim točkama i zamkama, je u **§C6**.

| # | Korak | Naredba / sekcija | Traje |
| :---: | :--- | :--- | :--- |
| 1 | Provjeri pristup kutiji + uzmi DB lozinku s testa | §C6.1 | 2 min |
| 2 | Prebaci oba repoa na kutiju (`git bundle`) | §C6.2 | 5 min |
| 3 | Provjeri NIAS certifikat u `/opt/str/config-external` *(preskoči ako nemaš lozinku)* | §C6.3 | 2 min |
| 4 | Napravi `.env.cdupreprod` + generiraj dva ključa | §C6.4 | 5 min |
| 5 | `docker compose up -d --build` | §C6.5 | 10–20 min |
| 6 | Provjeri `startup_*` blok u logu | §C7 | 3 min |
| 7 | Provjeri javni URL izvana | §C7 | 1 min |
| 8 | *(tek kad Darko javi vrijeme reseta)* postavi cron | §C8 | — |

**Jedina odluka koju moraš donijeti prije početka** — imaš li lozinku NIAS keystorea?

- **Imaš** → normalan put, koraci 1–7.
- **Nemaš** → preskoči korak 3, u koraku 4 postavi `NIAS_SAML_ENABLED=false`. Okolina radi
  **sve osim prijave eGrađanima**. Kad lozinka stigne: upišeš je, vratiš `true`, digneš (`up -d`, ne `restart`)
  backend — bez rebuilda. To je bolji ishod od čekanja.

> **Jedno pravilo za sve naredbe:** svaka `ssh` naredba ide u **jednom retku**. Prelomljenu
> PowerShell šalje u komadima, a udaljeni bash svaki komad izvrši kao zasebnu naredbu — to nam je
> 18.09. dalo tri lažna nalaza (`8080: command not found`, „nema mvn", `-c: option requires an
> argument`).

## Što je poznato

| | |
| :--- | :--- |
| Kutija | `172.20.8.143`, hostname `eturizam-0016` |
| Pristup | **vlastiti korisnik + certifikat**, isti SSH ključ kao za test (`~/.ssh/id_ed25519`). Lozinka se NE koristi |
| Korisnik | `mhangi` (uid 1004), član grupe **`docker` (988)** → `docker` radi **bez `sudo`** |
| Baza | `172.20.8.212:5432/eturizam`; uputa je došla s `currentSchema=<?>&options=-c%20role%3D<?>_owner` |
| Kredencijali baze | „ista priča kao i na test bazi" → isti user i lozinka kao CDU test (`.env.cdu` na `172.20.8.158`) |
| Reset baze | **svake noći**, planirano — „imajte spremne skripte da se izvršavaju na svaki reset" |
| Javni URL | `https://str-preprod-eturizam.gov.hr` → `85.209.13.25` → interno `…:8085` |

DNS i TLS su provjereni s javnog interneta (bez VPN-a): `str-preprod-eturizam.gov.hr` i
`str-test-eturizam.gov.hr` **oba** resolvaju na `85.209.13.25`, dakle gateway ih razlikuje po
`Host`/SNI. Certifikat je wildcard `*.gov.hr` (Entrust, vrijedi do 21.02.2027.) i **već pokriva
novo ime** — novi certifikat ne treba. TLS rukovanje prolazi, a zatim veza puca jer iza imena
još nema živog upstreama; to je očekivano dok ne deployamo.

## Izmjereno na kutiji (18.09.2026.)

| Provjera | Nalaz | Što time otpada / slijedi |
| :--- | :--- | :--- |
| Docker | **29.8.0**, Compose **v5.5.1** | v2 sintaksa (`docker compose`). Zamka `KeyError: 'ContainerConfig'` iz compose v1 **ne postoji ovdje** |
| Prava | `mhangi`, grupa `docker` | bez `sudo`, bez tuđeg home-a → nema klase kvarova s test kutije |
| Port 8085 | **slobodan** | `ss` ne nalazi ništa na 8085/8080/8086 |
| Tuđi stack | `str2-str-external-app-1` (8082), `str2-str-internal-app-1` (8081) | koegzistira; **ne dirati** |
| Internet | `github.com` → `HTTP/2 200` | izlaz na 443 radi |
| Docker Hub | `registry-1.docker.io/v2/` → `401`, `docker pull nginx:alpine` → **OK** | base imageovi se povlače; ali **build svejedno ne ide na kutiji** — vidi redak niže |
| **maven / shibboleth / npm** | `repo.maven.apache.org`, `build.shibboleth.net`, `registry.npmjs.org` → **svi `000`, connection reset** | ⛔ **build na kutiji je nemoguć** — artefakti se grade lokalno i nose `scp`-om (§B3, §C9) |
| GitHub SSH (22) | **zatvoren** (bez odgovora) | deploy ključ preko SSH-a **otpada** → §B6 |
| `mvn` / `npm` / `java` na hostu | **nema ih** | nebitno: build ide u kontejneru |
| Cacheirani imageovi | `eclipse-temurin:11-jre`, `str-external`, `str-internal` | naš base (**21**) i `maven`/`node` povlače se pri prvom buildu |
| Disk | 17 GB ukupno, **8,5 GB slobodno** | dovoljno, ali prvi build povuče ~2–3 GB → vidi zamke |
| NIAS produkcijski | `https://nias.gov.hr/metadata` vraća **punu, potpisanu metadatu** (`CN=niasap`, SSO `/sso-http`, Fina RDC 2020) | najvažniji preflight prošao — bez njega aplikacija ne bi ni startala |
| Baza | TCP `172.20.8.212:5432` **dohvatljiv s kutije** | ostaje samo autentikacija i prava |

## Izmjereno na bazi (18.09.2026.)

User `shorttermrental`, mjereno uz `PGOPTIONS=-c role=str_owner` (isto što radi JDBC URL):

| Provjera | Nalaz | Posljedica |
| :--- | :--- | :--- |
| `current_user` / `session_user` | `str_owner` / `shorttermrental` | `SET ROLE` prolazi **na handshakeu** → URL s `options=-c%20role%3Dstr_owner` je točan |
| `str_rn` | postoji, vlasnik `str_owner`, **0 tablica** | ne treba je kreirati; prvi deploy vrti cijeli changelog, registar starta prazan |
| `has_schema_privilege('str_rn','CREATE')` | `t` | Liquibase smije kreirati tablice |
| `has_database_privilege(…,'CREATE')` | **`f`** — i bez role i s rolom | shemu ne bismo mogli vratiti da nestane; DBA je potvrdio da **ostaje** (A1/4), pa je provjera u bootstrapu samo zaštita |
| `SELECT` na `str.facility`, `str.subject`, `str.country` | `t` | popis objekata, OIB lookup i izbornik država rade |
| `SELECT` na `rpj_dgu.zupanije`, `eturizam_test.ar_ulice` | `t` | **adresna kaskada radi** — InfoDom blokada ovdje ne postoji |
| `UPDATE` na `str.facility` | `f` | RB se ne upisuje natrag u eTurizam → tuStart handoff nije testabilan end-to-end |

## Tri okoline jedna uz drugu

| | CDU test (radi) | **CDU preprod (nova)** | InfoDom preprod |
| :--- | :--- | :--- | :--- |
| Kutija | `172.20.8.158` | `172.20.8.143` (`eturizam-0016`) | `s-str-02.infodom.hr` |
| VPN | državni | državni | InfoDom Sophos |
| Korisnik | `mhangi`, ali u **tuđem** home-u, grupa `kodelab-d` | **`mhangi`, vlastiti home, grupa `docker`** | `mhangi` + `sudo -iu vviskov` |
| Baza | `172.20.8.196:5432/eturizam` | `172.20.8.212:5432/eturizam` | `s-str-02:5431/eturizam` |
| Reset baze | ne | **svake noći** | ne |
| HTTPS | da (gateway) | **da (gateway)** | ne (plain HTTP) |
| Captcha | uključena | **uključena** (ima HTTPS) | ugašena (nema HTTPS) |
| NIAS | `niastst.fina.hr`, demo cert | **`nias.gov.hr`, produkcijski cert** | `nias.gov.hr`, prod cert |
| eGOP | ugašen | **ugašen** | ugašen |
| Profil | `cdu` | **`cdupreprod`** | `preprod` |
| Build | lokalno + `scp` (nema mvn/npm/DockerHub) | **lokalno + `scp`** (maven/npm blokirani) | build-on-box |

---

# A. Pitanja — što još blokira

## A1. Darko Bosnar / DBA (baza `172.20.8.212`) — najveći ostatak

1. ~~Kako se zove shema i rola?~~ **Izmjereno: `str_rn` + `str_owner`** — kao na InfoDom
   preprodu, a **ne** kao na CDU testu (ondje URL nema ni `currentSchema` ni `SET ROLE`).
   `CDUPREPROD_DB_URL` s `?currentSchema=str_rn&options=-c%20role%3Dstr_owner` je točan.
2. ~~Je li user član role?~~ **Jest.** Uz `PGOPTIONS=-c role=str_owner` konekcija daje
   `current_user=str_owner`, `session_user=shorttermrental` — dakle `SET ROLE` prolazi već na
   handshakeu, kako aplikacija i radi.
3. ~~Postoji li shema?~~ **Postoji, vlasnik je `str_owner`, i prazna je (0 tablica).** Liquibase
   ima `CREATE` **na shemi** (`has_schema_privilege('str_rn','CREATE')=t`), pa migracije prolaze;
   prvi deploy vrti cijeli changelog od nule i registar starta bez ijednog RB-a — to je ispravno.
4. ~~Što točno noćni reset radi?~~ **Odgovoreno (18.09.): tablice se dropaju, shema i role
   ostaju.** „Kod startanja STR-a treba rekreirati tablice i popuniti inicijalnim podacima."
   **Blokada je time pala** — nedostatak `CREATE` na bazi više ne smeta jer shemu nitko ne briše,
   a unutar `str_rn` imamo `CREATE` i vlasništvo. Praktično:
   - Liquibase pri svakom jutarnjem restartu vrti **cijeli changelog od nule** i ponovno gradi
     tablice; inicijalne podatke pokrivaju seed changeseti bez konteksta (vrste smještaja,
     platforme). Demo seedovi (`context="cdu"`, `"dev"`, `"local"`) se **ne** primjenjuju.
   - **Jutarnji restart je time obavezan svaki dan**, ne samo mjera opreza → cron u §C8.
   - Sve što testeri unesu tijekom dana **nestaje preko noći**: RB-ovi, skice, sesije.
5. ⚠️ **U koje vrijeme reset kreće i koliko traje?** Jedino što još nedostaje za §C8. Bez toga se
   cron ne postavlja — skripta bi lako krenula usred njihovog prozora.
6. ⚠️ **Preživljavaju li TABLIČNI grantovi?** Shema i role ostaju, pa `USAGE` na `str`, `rpj_dgu`
   i `eturizam_test` preživljava. Ali `GRANT SELECT` se veže **uz tablicu**, a ne uz shemu — ako
   se i njihove tablice dropaju i rekreiraju, naši `SELECT` grantovi **nestaju s njima**, osim ako
   postoji `ALTER DEFAULT PRIVILEGES` ili ih reset skripta ponovno dodijeli.
   Simptom bi bio: aplikacija se digne, ali se ujutro u logu pojavi `startup_schema_unreadable` i
   **adresna kaskada u formularu ne radi**, iako je večer prije radila. Pitanje za Darka glasi:
   jesu li `SELECT` grantovi za `str_owner` dio reset skripte ili je postavljen
   `ALTER DEFAULT PRIVILEGES`?
6b. ⚠️ **Dropa li se i `databasechangelog`?** Ako se dropaju **sve** tablice u `str_rn`
   (uključujući `databasechangelog` i `databasechangeloglock`), Liquibase uredno gradi shemu od
   nule — to je ispravan slučaj. Ako se pak obrišu samo „podatkovne" tablice a `databasechangelog`
   **ostane**, Liquibase zaključi da je sve već primijenjeno, **ne kreira ništa**, i aplikacija se
   digne nad praznom shemom. Popravak je u našim rukama (vlasnici smo `str_rn`) i opisan je u
   `tools/cdupreprod-bootstrap.sql`, ali je bolje unaprijed potvrditi da drop pokriva i te dvije.
7. ~~Grantovi na vanjske sheme?~~ **Već su na mjestu.** `USAGE` na `str`, `rpj_dgu` i
   `eturizam_test` je `t`, a `SELECT` prolazi na `str.facility`, `str.subject`, `str.country`,
   `rpj_dgu.zupanije` i `eturizam_test.ar_ulice`. **Blokada koja je zaustavila InfoDom
   predprodukciju ovdje ne postoji** — adresna kaskada radi od prvog dana. Ostaje samo pitanje
   preživljavaju li ti grantovi noćni reset (t. 6).
8. **Je li u `str` shemi produkcijski dump** (prave adrese i e-mailovi)? Čitanje radi, ali ne
   znamo čije su to osobe. Odgovor određuje smije li se ikad upaliti e-pošta i smije li se
   okolina koristiti za prezentaciju. Do odgovora vrijedi `APP_MAIL_ENABLED=false`.
9. ~~Treba li `GRANT UPDATE (registration_number) ON str.facility`?~~ **Izmjereno: `UPDATE = f`.**
   Nije blokada za deploy — `FacilityRegistrationNumberWriteBack` guta grešku i logira
   `facility_writeback_failed`, RB ostaje valjan. Posljedica: **tuStart handoff se ne može
   testirati end-to-end**. Ako taj tok ulazi u opseg testiranja, tražiti grant od `tustart_owner`.

## A2. Simon / InfoDom (NIAS)

10. ~~Koji NIAS?~~ **Odgovoreno: produkcijski.** Dohvatljivost s kutije je izmjerena (metadata se
    učitava). ~~Nosi se produkcijski keystore `nias-prod.p12`~~ — **od 10/2026 certifikat
    CN=e-Turizam** iz `/opt/str/config-external/keystore.p12` na kutiji (§C10).
11. ~~Je li nova domena registrirana uz certifikat?~~ **Odgovoreno (02.10.): jest** — uz e-Turizam
    certifikat su registrirani ACS i SLO za `str-preprod-eturizam.gov.hr`, pa bi uz prijavu
    trebala raditi i **odjava** (s posuđenim InterniTurizmom nije mogla).
12. ~~Certifikat istječe 08.11.2026.~~ To je vrijedilo za InterniTurizam. Rok e-Turizam
    certifikata zapisuje se iz `keytool` ispisa (§C10.1); backend ga i sam logira na startu
    (`NIAS SP certifikat: ... vrijedi_do=`) i upozorava 90 dana prije isteka.

## A3. Mrežni tim / CDU

13. **Je li gateway pravilo postavljeno?** Treba potvrda da `443 → 172.20.8.143:8085` radi.
    Certifikat je riješen (wildcard `*.gov.hr`).
14. ~~Što znači `16:8085`?~~ **Odgovoreno: 8085 je port na kojem front STR-a mora biti izložen**
    („docker-compose ima konfiguraciju porta, npr. `ports: - 8085:3000`"). Naš compose to već
    ispunjava s `8085:80` — lijeva strana je host port i mora biti 8085, desna je port **unutar**
    kontejnera, a naš nginx sluša na 80 (`PORT: "80"`). Njihov `3000` je samo primjer iz druge
    aplikacije; ne prepisivati ga.
15. ~~Ima li kutija izlaz prema internetu?~~ **Odgovoreno: ima na 443** (GitHub 200, Docker Hub
    pull prolazi), a **SSH prema `github.com:22` je zatvoren**.
16. *(neobavezno)* Može li se otvoriti izlaz na `github.com:22`? Time bi deploy ključ postao
    najčišći način dohvaćanja privatnog repoa (bez ijedne tajne na kutiji) — vidi §B6.

## A4. Naručitelj / MINTS

17. ~~Čemu okolina služi?~~ **Odgovoreno: ponaša se kao CDU testna okolina** —
    `EGOP_MOCK_FILING_PREFIX=`, `RN_DOCUMENTS_ZAHTJEV_VISIBLE=false`,
    `APP_CADASTRAL_ENABLED=true`, captcha uključena. Ostaje otvoreno samo jesu li u bazi pravi
    podaci iznajmljivača (A1/8).
18. Tko su testeri i **treba li im interni pristup**? Uloge se razrješavaju po OIB-u iz baze — na
    okolini koja se resetira to znači da popis mora biti dio reset skripte.
19. Ostaje li CDU test gore paralelno? (Pretpostavka: da.)

---

# B. Odluke

## B1. Novi Spring profil `cdupreprod`, ne „reuse" profila `preprod`

`application-preprod.properties` je vezan uz InfoDom: `s-str-02` DB URL, plain HTTP (otuda
`request-store=database`, bez `secure` cookieja i bez `forward-headers`) i InfoDom SMTP. Nova
okolina je **bliža CDU testu** (HTTPS iza gatewaya, jedan origin, captcha radi), pa je model
`application-cdu.properties`.

Novi profil je jeftin: `@Profile` se u kodu pojavljuje **dva puta** (`NonEuTestLessorSeeder` na
`local/mock/dev`, `StartupDiagnostics` na `!test`), sve ostalo ide kroz `${ENV:...}`.

U repou (`develop`, stiglo kroz PR #84):

| Datoteka | Sadržaj |
| :--- | :--- |
| `src/main/resources/application-cdupreprod.properties` | profil; produkcijski NIAS; DB i URL-ovi kroz env |
| `docker-compose.cdupreprod.yml` | `name: str-cdupreprod`, `env_file`, jedan origin, captcha i eGOP prekidači |
| `.env.cdupreprod.example` | tajne + komentari |
| `Dockerfile.artifact` + `.dockerignore` iznimka | **rezerva** za kutiju bez Docker Huba (vidi B3) |
| `.gitattributes` | `*.sh`/`*.sql` = LF |
| `tools/cdupreprod-bootstrap.sql` | `CREATE SCHEMA` + ispis prava — na **svaki** reset |
| `tools/cdupreprod-nightly.sh` | jutarnji oporavak: čekaj bazu → bootstrap → restart → smoke |

Nijedna datoteka ne dira postojeće okoline: novi profil, novi compose projekt (`name:`), nove
skripte. Jedina zajednička je `.dockerignore`, gdje dodani redak samo **prestaje isključivati**
jar iz build konteksta.

Liquibase: `spring.liquibase.contexts=cdupreprod` — **bez** `local`, `dev` i `cdu` seedova.
`120-seed-cdu-activity` veže se na postojeće `ACTIVE` RB-ove; na bazi koja se resetira njih
nema, pa bi ionako ubacio nula redaka.

## B2. Deploy kao vlastiti korisnik, u vlastiti home

`~/str-rn/` (tj. `/home/mhangi/str-rn`), tajne u `~/str-rn/str_backend/.env.cdupreprod` s
`chmod 600`. NIAS certifikat nije u našem home-u, nego u `/opt/str/config-external` (§C10).

Ovo je bitna razlika prema CDU testu, gdje je deploy u **tuđem** home-u (`/home/vviskov/str-rn`)
kroz grupu `kodelab-d`. Odatle dolaze `Permission denied` na `scp`-u, nečitljivi modovi za nginx
i onaj bijeli ekran. Ovdje toga nema: vlastiti home, vlastite datoteke, `docker` bez `sudo`.

Na kutiji je (18.09.) potvrđeno da drukčije ionako ne ide: `/home/vviskov` je nedostupan
(`Permission denied`), pa se raspored s testa ne može ponoviti ni da želimo.

### Cijena te odluke: stack nije timski upravljiv

Homovi su zatvoreni, pa kolege (`gcolic`, `kkovacevic`, `sroncevic`, `vviskov`) neće moći ući u
`~/str-rn`. Kroz `docker` grupu i dalje mogu `docker ps`, `logs` i `restart` — ali **ne**
`docker compose up --build`, jer im compose datoteka i `.env` nisu čitljivi.

Za jednu okolinu koju deploya jedna osoba to je u redu i **preporuka je krenuti ovako** (manje
pomičnih dijelova pri prvoj instalaciji). Ako se poslije pokaže da stack mora dizati više ljudi,
najjeftinije rješenje koristi **grupu `docker`**, u kojoj su ionako svi koji smiju deployati:

```bash
chmod 711 /home/mhangi                 # ulazak u home, bez listanja sadržaja
chgrp -R docker ~/str-rn && chmod -R g+rX ~/str-rn
find ~/str-rn -type d -exec chmod g+s {} \;   # nove datoteke zadržavaju grupu
chmod 640 ~/str-rn/str_backend/.env.cdupreprod   # tajne ostaju uže
```

**Ne raditi ovo unaprijed.** `chmod 711` na home je popuštanje privatnosti vlastitog direktorija
i ima smisla tek kad postoji stvarna potreba; do tada je manje izloženosti bolji default.

## B3. Build ide LOKALNO, ne na kutiji

> **Ovo je bilo drukčije do prvog deploya.** Prvotno je pisalo „build ide na kutiji", jer je
> `docker pull` prolazio pa se činilo da je sve dohvatljivo. Prvi build (18.09.) pao je na
> `mvn dependency:go-offline` i pokazao da Docker Hub nije mjerilo: **paketni registryji su
> blokirani**. Ostavljam trag te promjene jer je to glavna razlika u odnosu na InfoDom okolinu.

Izmjereno s kutije — sva tri `000`, connection reset:

| Host | Čemu služi | Bez njega |
| :--- | :--- | :--- |
| `repo.maven.apache.org` | standardne Java ovisnosti | `mvn` ne razriješi ni roditeljski POM |
| `build.shibboleth.net` | `opensaml-saml-impl:4.3.1` — **nije na Maven Centralu** | nema SAML2, dakle nema NIAS prijave |
| `registry.npmjs.org` | frontend ovisnosti | `npm ci` ne prolazi |

Zato **oba** artefakta nastaju lokalno i nose se `scp`-om, a na kutiji se koriste thin imageovi
koji ih samo kopiraju:

```
BACKEND_DOCKERFILE=Dockerfile.artifact       # str_backend/Dockerfile.artifact
FRONTEND_DOCKERFILE=Dockerfile.artifact      # str_frontend/Dockerfile.artifact
```

Oba `Dockerfile.artifact` imaju `chmod -R a+rX` **u imageu**, pa modovi koje donese Windows
`scp` više ne mogu proizvesti bijeli ekran — to je na CDU testu bio ručni korak koji se
zaboravljao.

Cijena je da svaki deploy nosi ~110 MB jara i traje ~15 minuta umjesto ~3. Ako mrežni tim ikad
otvori `repo.maven.apache.org` i `build.shibboleth.net`, dovoljno je maknuti ta dva retka iz
`.env.cdupreprod` i build se vraća na kutiju bez ijedne druge izmjene.

## B4. Captcha UKLJUČENA

ALTCHA traži sigurni kontekst (Web Crypto), a okolina je iza HTTPS-a. Oba prekidača na `true`:
`APP_CAPTCHA_ENABLED` (backend) i `VITE_CAPTCHA_ENABLED` (FE build arg). **Moraju se mijenjati
zajedno** — razidu li se, okolina je neupotrebljiva. `CAPTCHA_HMAC_KEY` je obavezan;
`AltchaService` odbija i prazan ključ i ugrađeni default, pa backend bez njega ne starta.

## B5. E-pošta i eGOP ugašeni

`APP_MAIL_ENABLED=false`, `EGOP_ENABLED=false`. Ugašen eGOP **ne znači** da se ništa ne
urudžbira — `EgopClientMock` i dalje dodjeljuje KLASU/URBROJ, ovdje bez `MOCK-` prefiksa.

## B6. Kako privatni repozitoriji dolaze na kutiju

`git` na kutiji postoji, HTTPS izlaz radi, ali su repozitoriji **privatni**, a
`github.com:22` je **zatvoren** — deploy ključ preko SSH-a otpada. Dvije opcije:

**(a) `git bundle` — preporuka za prvu instalaciju.** Nijedna tajna ne ostaje na dijeljenoj
kutiji. Cijena: prijenos ide s laptopa (backend `.git` je 130 MB, frontend 13 MB), a svaki
sljedeći update traži novi bundle — doduše inkrementalni je sitan.

```powershell
# lokalno (VPN gore)
git -C C:\Users\MladenHangi\str_backend  bundle create ..\str_backend.bundle  develop
git -C C:\Users\MladenHangi\str_frontend bundle create ..\str_frontend.bundle develop
scp ..\str_backend.bundle ..\str_frontend.bundle cdu-preprod:~/
```

```bash
# na kutiji
mkdir -p ~/str-rn && cd ~/str-rn
git clone -b develop ~/str_backend.bundle  str_backend
git clone -b develop ~/str_frontend.bundle str_frontend
```

Update kasnije (inkrementalno, nekoliko KB umjesto 130 MB):

```powershell
git -C ...\str_backend bundle create ..\upd.bundle <zadnji-deployani-sha>..develop
scp ..\upd.bundle cdu-preprod:~/
```
```bash
git -C ~/str-rn/str_backend fetch ~/upd.bundle develop:develop && git -C ~/str-rn/str_backend checkout develop
```

**(b) HTTPS + fine-grained PAT** — ako deploy postane čest pa `git pull` na kutiji bude
isplativiji. Token mora biti read-only, samo za ta dva repoa, s rokom trajanja, i u
`~/.git-credentials` uz `chmod 600`. Svjesno se prihvaća da tajna živi na dijeljenoj kutiji.

Dugoročno najčišće je (16) iz §A3: otvoriti `github.com:22` i koristiti deploy ključ.

---

# C. Faze

## C0. Pristup — ✅ riješeno

Lozinka nije potrebna: prijava ide vlastitim korisnikom i certifikatom, kao na testu.

```
~/.ssh/config
Host cdu-preprod
    HostName 172.20.8.143
    User mhangi
    IdentityFile ~/.ssh/id_ed25519
    IdentitiesOnly yes
```

**Zamka:** dva slična aliasa — `cdu` je **test** (`.158`), `cdu-preprod` je nova kutija (`.143`).
Jedan pogrešan `scp` i debugira se kriva okolina.

**Zamka:** prvi spoj traži prihvaćanje host keya. Ne koristiti `BatchMode=yes` (ugasi pitanje i
padne s `Host key verification failed`); ide `-o StrictHostKeyChecking=accept-new`, koji i dalje
odbija **promijenjen** ključ poznatog hosta. Nikad `StrictHostKeyChecking=no`.

## C1. Recon kutije — ✅ odrađeno 18.09.

Rezultati su u tablici „Izmjereno na kutiji" na vrhu. Zaključak: **build-on-box**, bez `sudo`,
8085 slobodan, NIAS i baza dohvatljivi.

**Zamka koja nas je koštala tri lažna nalaza:** naredbu za `ssh` pisati u **jednom retku**. Kad
se u PowerShellu prelomi (`>>`), novi redci odlaze udaljenom bashu koji svaki fragment izvrši kao
zasebnu naredbu — otud `8080: command not found`, lažni „nema mvn" i `-c: option requires an
argument`.

## C1b. Repozitoriji na kutiju

Po §B6 (bundle). Rezultat mora biti:

```
~/str-rn/
  str_backend/     ← git repo, grana develop (+ .env.cdupreprod, nije u gitu)
  str_frontend/    ← git repo, grana develop (build context za compose)

/opt/str/config-external/keystore.p12   ← NIAS certifikat CN=e-Turizam (nije naš, samo se montira)
```

Compose očekuje frontend kao **susjedni direktorij** (`context: ../str_frontend`) — bez njega
build frontenda puca.

## C2. Baza — preflight i grantovi

Kredencijali se uzimaju s test kutije:

```bash
ssh cdu 'grep CDU_DB ~/str-rn/str_backend/.env.cdu'
```

`psql` na kutiji vjerojatno nema; provjera ide s laptopa preko VPN-a (port 5432 je s kutije
dohvatljiv, a s laptopa je dohvatljiv jednako kao i sve ostalo na toj mreži):

```powershell
# replicira TOČNO ono što radi aplikacijski JDBC URL, uključujući SET ROLE:
psql "postgresql://<user>@172.20.8.212:5432/eturizam?options=-c%20role%3D<rola>" -c "select current_user, session_user"
```

```powershell
psql "postgresql://<user>@172.20.8.212:5432/eturizam" `
  -c "\dn" `
  -c "select rolname from pg_roles where pg_has_role(current_user, oid, 'member') order by 1" `
  -c "select has_schema_privilege('rpj_dgu','USAGE') as rpj, has_schema_privilege('eturizam_test','USAGE') as etur, has_schema_privilege('str','USAGE') as str" `
  -c "select count(*) from pg_tables where schemaname='str_rn'"
```

**Zamke:**
- `information_schema.tables` je filtriran po privilegijama i broji poglede → za stvarno stanje
  ide `pg_tables`.
- `to_regclass('rpj_dgu.zupanije')` **puca** ako nema `USAGE`; za sondiranje prava ide
  `has_schema_privilege`.
- Lozinku ne stavljati u `$env:PGPASSWORD` ni u argument — ostaje u historyju i u popisu procesa.

**Izlaz faze:** točna imena sheme i role u `tools/cdupreprod-bootstrap.sql` i
`CDUPREPROD_DB_URL`, te popis grantova koje traže vlasnici (`gis_owner`, `tustart_owner`).

## C3. Noćni reset — srce ove okoline

**Potvrđeno 18.09.:** reset **dropa tablice**, a **shema `str_rn` i role ostaju**. To je najbolji
mogući ishod za nas — shemu ne bismo mogli vratiti (`CREATE` na bazi = `f`), a tablice unutar nje
možemo, jer smo vlasnici (`str_owner`) i imamo `CREATE` na shemi.

| Posljedica | Zašto |
| :--- | :--- |
| Sve naše tablice nestanu | reset ih dropa; shema ostaje prazna |
| `databasechangelog` nestane s njima | idući start vrti **cijeli changelog od nule** → tablice i inicijalni podaci se ponovno grade |
| Aplikacija radi protiv prazne sheme | **Liquibase se vrti samo pri dizanju konteksta.** Konekcije se oporave same (Hikari validira pri posudbi), ali tablice ne — vraća ih tek restart |
| Sesije nestanu (`str_rn.spring_session`) | svi prijavljeni testeri su odjavljeni |
| RB-ovi i skice nestanu | `DRAFT_ENC_KEY` ostaje isti (u `.env`), ali podaci ne |
| Tablični grantovi možda nestanu | `SELECT` se veže uz tablicu; ako se dropaju i njihove, grant ide s njima (→ A1/6) |

**Jutarnji restart je time obavezan svaki dan**, ne mjera opreza: bez njega aplikacija cijeli dan
radi nad praznom shemom. Inicijalne podatke („popuniti inicijalnim podacima ako su potrebni" iz
njihovog odgovora) pokrivaju seed changeseti bez konteksta — vrste smještaja i platforme; demo
seedovi se ne primjenjuju jer `contexts=cdupreprod` ne uključuje `cdu`/`dev`/`local`.

Redoslijed jutarnjeg oporavka (`tools/cdupreprod-nightly.sh`, cron **nakon** njihovog prozora):

```
1. čekaj da baza prihvaća konekcije      (retry — ne pretpostavljaj da je reset gotov)
2. psql -f cdupreprod-bootstrap.sql      (PROVJERA sheme + ispis prava; ne kreira — nemamo CREATE)
3. docker compose -f docker-compose.cdupreprod.yml restart backend
4. čekaj "Started StrBackendApplication" u logu, s timeoutom
5. smoke: /api/captcha/challenge + startup_ linije
6. zapiši ishod u log
```

**Zamke:**
- **Ne dizati aplikaciju usred reset prozora.** `restart: unless-stopped` + pad na Liquibaseu =
  restart petlja. Zato korak 1 nije `sleep`, nego provjera.
- Skripta traži `psql` ili postgres image u lokalnom cacheu. Docker Hub je dohvatljiv, pa je
  `docker pull postgres:16-alpine` jednokratni preduvjet — inače skripta javi da shemu nakon
  reseta netko mora kreirati ručno.
- Skripta mora biti **idempotentna** — pušta se i ručno, i po dva puta.
- **Ako `databasechangelog` preživi drop, a ostale tablice ne**, Liquibase neće ništa kreirati i
  aplikacija se digne nad praznom shemom (vidi A1/6b). `bootstrap` to otkriva i javlja s točnim
  popravkom; popravak je u našoj nadležnosti jer smo vlasnici sheme.

## C4. Repo rad — ✅ napravljeno

Stiglo kroz granu `feat/cdu-preprod-okolina` → PR #84 → `develop` (nikad push na `develop`,
nikad PR na `main`).

**Zamka:** `VITE_API_URL` ide **bez** `/api` sufiksa — `backendApi.ts` uzima `VITE_API_URL` kao
`baseURL`, a putanje već počinju s `/api/…`. U repou postoji i suprotan primjer
(`docker-compose.cdu.yml` ima `/api`), ali to je referentna datoteka koja se na CDU ne izvršava.

## C5. NIAS i gateway — što treba tražiti od drugih

Ovo su **zahtjevi prema drugima**, ne koraci instalacije; provjera certifikata na kutiji je
korak §C6.3, a prelazak sa starog certifikata §C10.

- [x] **certifikat CN=e-Turizam** na kutiji u `/opt/str/config-external` (politika 10/2026)
- [ ] **lozinka e-Turizam keystorea** → `NIAS_KEYSTORE_PASSWORD` (jedino što tvrdo blokira NIAS)
- [x] **registracija ACS/SLO za novu domenu** uz e-Turizam certifikat (potvrđeno 02.10.):
      `https://str-preprod-eturizam.gov.hr/login/saml2/sso/nias` i `…/logout/saml2/slo/nias`
- [ ] **potvrda gateway pravila** `443 → 172.20.8.143:8085` (A3/13); certifikat ne treba —
      wildcard `*.gov.hr` već pokriva ime

**Zamka:** ako registracija ne stigne na vrijeme, okolina se **svejedno diže** s
`NIAS_SAML_ENABLED=false` — radi sve osim prijave eGrađanima. Bolji ishod od čekanja.

## C6. Prvi deploy — korak po korak

> **Pravilo za cijeli odjeljak:** svaku `ssh` naredbu piši u **jednom retku**. Kad je PowerShell
> prelomi, udaljeni bash svaki fragment izvrši kao zasebnu naredbu — danas nas je to koštalo tri
> pogrešna zaključka. Naredbe ispod su namjerno jednoredne.

### C6.0 Preduvjeti — bez ovoga se ne kreće

| # | Što | Odakle | Ako fali |
| :--- | :--- | :--- | :--- |
| 1 | Državni VPN gore | — | ništa dalje ne radi |
| 2 | DB lozinka | s test kutije, korak C6.1 | **blokada** |
| 3 | Lozinka e-Turizam keystorea | tko je postavio certifikat | **nije blokada** → `NIAS_SAML_ENABLED=false`, sve osim prijave radi |
| 4 | `/opt/str/config-external/keystore.p12` | već na kutiji (ne donosi se) | isto kao gore |
| 5 | Gateway `443 → .143:8085` | mrežni tim | nije blokada — provjerava se lokalno na kutiji |
| 6 | Vrijeme noćnog reseta | Darko | nije blokada za `up`; blokira samo postavljanje crona (C8) |

Trajanje: ~15 min rada + 10–20 min prvog builda.

### C6.1 · KORAK 1 — VPN, pristup i DB lozinka

```powershell
ssh -o StrictHostKeyChecking=accept-new cdu-preprod "hostname; id"
```
Očekuj `eturizam-0016` i `groups=...,988(docker)`. Ako traži lozinku, ključ nije prihvaćen.

```powershell
ssh cdu "grep CDU_DB ~/str-rn/str_backend/.env.cdu"
```
Odatle prepiši `CDU_DB_USERNAME` (`shorttermrental`) i `CDU_DB_PASSWORD` — isti vrijede za novu
bazu. Rezerva ako je datoteka u međuvremenu mijenjana:
`ssh cdu "docker exec str-backend-cdu env | grep CDU_DB"`.

**Kontrolna točka:** imaš hostname, `docker` grupu i DB lozinku.

### C6.2 · KORAK 2 — Repozitoriji na kutiju

Repozitoriji su privatni, a `github.com:22` je s kutije zatvoren, pa ide `git bundle` (§B6).
Bundle se radi iz **lokalnog** checkouta, pa mora biti na željenoj grani.

**PRIJE VPN-a** (na VPN-u nema interneta, pa se repo više ne može osvježiti):
```powershell
git -C C:\Users\MladenHangi\str_backend pull; git -C C:\Users\MladenHangi\str_frontend pull
```
```powershell
git -C C:\Users\MladenHangi\str_backend bundle create C:\Users\MladenHangi\str_backend.bundle develop
```
```powershell
git -C C:\Users\MladenHangi\str_frontend bundle create C:\Users\MladenHangi\str_frontend.bundle develop
```

> `git bundle` pakira **ref**, ne radnu kopiju — radi i kad je lokalni checkout na drugoj grani.
> Od PR-a #84 (18.09.) sve je u `develop`, pa se bundla `develop`; prije toga se bundlala grana
> `feat/cdu-preprod-okolina`.

**NA VPN-u:**
```powershell
scp C:\Users\MladenHangi\str_backend.bundle C:\Users\MladenHangi\str_frontend.bundle cdu-preprod:~/
```
```powershell
ssh cdu-preprod "mkdir -p ~/str-rn && cd ~/str-rn && git clone -b develop ~/str_backend.bundle str_backend && git clone -b develop ~/str_frontend.bundle str_frontend && ls -la ~/str-rn"
```

**Kontrolna točka:** `~/str-rn/` sadrži `str_backend/` i `str_frontend/`. Frontend
**mora** biti susjedni direktorij — compose ga gradi preko `context: ../str_frontend`.

> **Provjereno na kutiji 18.09.:** `/home/mhangi` je **prazan**, a `/home/vviskov` nam je
> nedostupan (`Permission denied`). Raspored s CDU testa — gdje deploy živi u tuđem home-u i piše
> se kroz grupu `kodelab-d` — ovdje dakle **nije ni moguć**, a i ne treba: `~/str-rn` je naš,
> `docker` radi bez `sudo`, i cijela klasa kvarova s pravima na datotekama otpada.
> Posljedica koju treba znati: kolege (`gcolic`, `kkovacevic`, `sroncevic`, `vviskov`) **neće
> moći pokretati `docker compose`** nad ovim stackom jer im `~/str-rn` nije čitljiv — mogu samo
> `docker ps` / `logs` / `restart` kroz `docker` grupu. Ako stack treba biti timski upravljiv,
> vidi §B2.

### C6.3 · KORAK 3 — NIAS certifikat (preskočivo)

Preskoči cijeli korak ako lozinka još nije stigla (vidi C6.4, varijanta B).

Certifikat **CN=e-Turizam** se ne donosi: stoji na kutiji u `/opt/str/config-external`, a
compose montira taj direktorij na istu putanju (read-only). Ovdje se samo iz njega čitaju alias
i DN — postupak je **§C10.1**. Rezultat ide u C6.4 (`NIAS_KEY_ALIAS`, `NIAS_ENTITY_ID`).

**Kontrolna točka:** `keytool` prihvaća lozinku, unos je `PrivateKeyEntry`, izdavatelj je
produkcijski FINA (`Fina RDC 2020`).

### C6.4 · KORAK 4 — `.env.cdupreprod`

```powershell
ssh cdu-preprod 'cd ~/str-rn/str_backend && cp .env.cdupreprod.example .env.cdupreprod && chmod 600 .env.cdupreprod && echo DRAFT_ENC_KEY=$(openssl rand -base64 32) && echo CAPTCHA_HMAC_KEY=$(openssl rand -base64 32)'
```

> **Navodnici su ovdje bitni:** JEDNOSTRUKI. U dvostrukima bi PowerShell `$(openssl …)` izvršio
> **lokalno** prije slanja — a `openssl` na Windowsu najčešće nije na PATH-u, pa naredba padne
> (ili, gore, ključ nastane na krivom stroju). U jednostrukima niz putuje netaknut i generira ga
> bash na kutiji.

Ispisane vrijednosti prepiši u datoteku (`nano ~/str-rn/str_backend/.env.cdupreprod`). Postavlja
se **sedam** vrijednosti; ostalo je u predlošku već popunjeno:

| Ključ | Vrijednost |
| :--- | :--- |
| `CDUPREPROD_DB_USERNAME` | `shorttermrental` (iz C6.1) |
| `CDUPREPROD_DB_PASSWORD` | iz C6.1 |
| `DRAFT_ENC_KEY` | generirano gore |
| `CAPTCHA_HMAC_KEY` | generirano gore |
| `NIAS_KEYSTORE_PASSWORD` | lozinka e-Turizam keystorea |
| `NIAS_KEY_ALIAS` | `Alias name` iz C6.3 / §C10.1 |
| `NIAS_ENTITY_ID` | `Owner` iz C6.3 / §C10.1, točno |

**Varijanta B — lozinka keystorea još nije stigla:** u `.env.cdupreprod` postavi
`NIAS_SAML_ENABLED=false` i `NIAS_KEYSTORE_PASSWORD` ostavi **zakomentiran**. Okolina radi sve
osim prijave eGrađanima. Kad lozinka stigne: upiši je, vrati `NIAS_SAML_ENABLED=true` i podigni
backend s `docker compose … up -d` — rebuild nije potreban. **Ne `restart`**: on ne čita
`env_file` ponovno, pa bi kontejner ostao sa starim vrijednostima.

**Dvije zamke koje ovdje najviše koštaju:**
- `.env.cdupreprod` **mora postojati** prije `up`, inače compose puca na `env_file`.
- Ključ koji ne postavljaš **zakomentiraj**, ne ostavljaj prazan. Prazna vrijednost je
  *postavljena* vrijednost i pregazi Spring default. Najgori slučaj je `CAPTCHA_HMAC_KEY=`:
  nepostavljen obori start s jasnom porukom, a **prazan pusti start** pa tek
  `/api/captcha/challenge` vrati 500 i sva 4 javna formulara su mrtva bez traga u logu.

**Kontrolna točka:** `ssh cdu-preprod "grep -c '^[A-Z]' ~/str-rn/str_backend/.env.cdupreprod"`
vraća broj > 10, a `ls -l` pokazuje `-rw-------`.

### C6.5 · KORAK 5 — Build i podizanje

Prije builda oslobodi prostor (8,5 GB slobodno, prvi build povlači ~2–3 GB):
```powershell
ssh cdu-preprod "docker system df; df -h / | tail -1"
```

```powershell
ssh cdu-preprod "cd ~/str-rn/str_backend && docker compose -f docker-compose.cdupreprod.yml --env-file .env.cdupreprod up -d --build"
```

Prvi build traje najdulje — povlače se `maven`, `node`, `eclipse-temurin:21-jre` i `nginx:alpine`
(na kutiji je cacheiran samo temurin **11**). Sljedeći su znatno brži.

Praćenje:
```powershell
ssh cdu-preprod "cd ~/str-rn/str_backend && docker compose -f docker-compose.cdupreprod.yml logs -f backend"
```

**Kontrolna točka:** `docker ps` pokazuje `str-backend-cdupreprod` i `str-frontend-cdupreprod` kao
`Up`, a tuđi `str2-*` kontejneri su **netaknuti**.

### C6.6 · Nakon uspješnog `up`-a

1. **Koraci 6 i 7** iz „START OVDJE" — verifikacija je §C7 (`startup_*` blok pa javni URL).
2. **Reci testerima što ih čeka:** sve što unesu tijekom dana (RB-ovi, skice, prijave)
   **nestaje preko noći** jer reset dropa tablice. Bez te napomene svako jutro stiže „bug".
3. **Cron tek kad Darko javi vrijeme reseta** (§C8). Do tada se jutarnji oporavak pokreće ručno:
   ```powershell
   ssh cdu-preprod "~/str-rn/str_backend/tools/cdupreprod-nightly.sh"
   ```
4. **Update kasnije** (nova verzija koda) ide inkrementalnim bundleom — §B6, bez ponovnog
   prijenosa cijelog repoa.

### C6.7 · Ako pođe po zlu

Rušenje **samo našeg** stacka (`name: str-cdupreprod` ga izolira od `str2-*`):
```powershell
ssh cdu-preprod "cd ~/str-rn/str_backend && docker compose -f docker-compose.cdupreprod.yml --env-file .env.cdupreprod down"
```

| Simptom | Prvo provjeri |
| :--- | :--- |
| Backend se diže pa ruši u petlji | `docker logs str-backend-cdupreprod 2>&1 \| head -50` — pad je gotovo uvijek NIAS keystore, captcha ključ ili baza |
| `Could not resolve placeholder` | `.env.cdupreprod` nije primijenjen ili ključ fali |
| Build stane na „no space left" | `docker system prune -f` pa ponovi |
| `permission denied` na `/var/run/docker.sock` | nisi u grupi `docker` — odjavi se i prijavi ponovno |

Ništa od ovoga ne dira bazu: mi u njoj samo kreiramo **svoje** tablice kroz Liquibase.

## C7. Verifikacija

```bash
docker ps
docker logs str-backend-cdupreprod 2>&1 | grep startup_
curl -I http://localhost:8085
```

| Linija | Mora pisati |
| :--- | :--- |
| `startup_config` | `profili=[cdupreprod]`, `liquibase_contexts=cdupreprod` |
| `startup_db current_user=… session_user=…` | `SET ROLE` je prošao (dva različita imena) |
| `startup_schema_ok tablica=str_rn.registration_number` | `redova=0` je **ispravno** nakon reseta |
| `startup_schema_ok tablica=str.facility` | pravi eTurizam podaci |
| `startup_schema_missing shema=rpj_dgu` (ERROR) | grant nije stigao → adresna kaskada ne radi |
| `startup_captcha hmac_key=postavljen` | `PRAZAN`/`UGRAĐENI DEFAULT` = `.env` nije primijenjen |
| `startup_mail enabled=false` | mora biti `false` |
| `startup_nias_urls acs=… slo=…` | usporediti s onim što je NIAS registrirao |
| `startup_nias enabled=true … keystore=… alias=…` | `keystore=/opt/str/config-external/keystore.p12`, alias e-Turizam certifikata |
| `NIAS SP certifikat: alias=… subjectDN=… vrijedi_do=…` | `subjectDN` sadrži `CN=e-Turizam` |
| `NIAS entity-id se NE poklapa sa Subject DN-om` (WARN) | **ne smije** se pojaviti — `NIAS_ENTITY_ID` ≠ DN certifikata |

Zatim izvana: `curl -sS -o /dev/null -w '%{http_code}\n' https://str-preprod-eturizam.gov.hr`.
Ako i dalje puca veza uz ispravan TLS, gateway pravilo nije postavljeno (A3/13) — a ne aplikacija.

Iz browsera **uvijek Ctrl+F5**: statika ima `immutable, 7 dana`, pa preglednik inače servira
stari `index.html` koji traži hash kojeg u novom buildu više nema.

## C8. Automatizacija + primopredaja

- [ ] `docker pull postgres:16-alpine` (preduvjet nightly skripte)
- [ ] **doznati vrijeme reset prozora od Darka** (A1/5) — bez toga cron ne ide
- [ ] cron za `cdupreprod-nightly.sh`, log u datoteku. **Obavezan je**, nije mjera opreza:
      tablice se dropaju svake noći, pa bez jutarnjeg restarta aplikacija cijeli dan radi nad
      praznom shemom
- [ ] prvo jutro **provjeriti tablične grantove** (A1/6): `docker logs … | grep startup_schema`
      — ako se pojavi `startup_schema_unreadable`, `SELECT` grantovi nisu preživjeli reset i
      adresna kaskada je mrtva, iako je večer prije radila
- [ ] tri jutra zaredom provjeriti log prije nego se okolina proglasi stabilnom
- [ ] testerima napisati **što nakon reseta nestaje** — RB-ovi, skice, sesije, dakle **sve što
      unesu tijekom dana**. Inače to dolazi kao prijavljeni bug svako jutro

## C9. REDEPLOY — nova verzija koda na `develop`-u

Ovo je postupak koji se koristi **nakon prve instalacije**, svaki put kad netko spoji fix u
`develop`. Nije isto što i §C6 (prva instalacija) — repoi su već na kutiji, `.env` i NIAS
certifikat (`/opt/str/config-external`) stoje, i ne dira ih se.

> **Zašto se ne buildA na kutiji:** `repo.maven.apache.org`, `build.shibboleth.net` i
> `registry.npmjs.org` su s te kutije blokirani (izmjereno 18.09., §C1). Build ide **lokalno**,
> na kutiju se nose gotovi artefakti. Bez toga `mvn` na kutiji ne razriješi ni roditeljski POM.

### Korak 0 — jednokratno, postavi putanje za svoju sesiju

Naredbe niže koriste ove tri varijable, pa vrijede za bilo čiji račun i bilo koji checkout.
Postavi ih na početku svake PowerShell sesije (ili ih trajno upiši u svoj `$PROFILE`):

```powershell
$BE = "C:\Users\MladenHangi\str_backend"; $FE = "C:\Users\MladenHangi\str_frontend"
```
```powershell
$MVN = "C:\Users\MladenHangi\Desktop\Maven\bin\mvn.cmd"; $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
```

Vrijednosti gore su s jednog konkretnog računala — **zamijeni ih svojima**. Ako su `mvn` i
`java` kod tebe na `PATH`-u, umjesto pune putanje može stajati `$MVN = "mvn"`, a `JAVA_HOME`
se onda ne mora postavljati. Provjera da je postavljeno ispravno:

```powershell
Test-Path $BE, $FE; & $MVN -v | Select-Object -First 1
```

### Korak 1 — PRIJE VPN-a: povuci kod (treba internet)

```powershell
git -C $BE checkout develop; git -C $BE pull; git -C $FE checkout develop; git -C $FE pull
```

**Pogledaj što je došlo** — određuje koliko posla slijedi:

```powershell
git -C $BE log --oneline -5; git -C $FE log --oneline -5
```

- Ako `str_frontend` nije donio ništa novo → **preskoči korak 3 i frontend dio koraka 4**.
- Ako je `package-lock.json` mijenjan → prije builda treba `npm ci`, ne samo `npm run build`.

**Provjeri je li stigao novi konfiguracijski ključ** (najtiša zamka pri updateu — novi ključ
dolazi u predlošku, a `.env` na kutiji ga nema, pa Spring uzme default ili start padne):

```powershell
git -C $BE diff HEAD@{1} HEAD -- .env.cdupreprod.example
```

Ako ispis nije prazan, isti ključ dodaj i u `~/str-rn/str_backend/.env.cdupreprod` na kutiji.

### Korak 2 — PRIJE VPN-a: backend jar

```powershell
& $MVN -f $BE\pom.xml clean package -DskipTests
```

**Namjerno bez `-o` (offline).** Fix može donijeti novu ovisnost — točno se to dogodilo s
`ZXing` u PR-u #82 — a offline build bi tada pao, ili, gore, prošao sa starim artefaktima.
Zato se builda dok još ima interneta.

**Prvi put na novom računalu build MORA ići izvan VPN-a.** `opensaml-saml-impl` se povlači s
`build.shibboleth.net`, koji je sa svih CDU okolina blokiran; jednom kad sjedne u lokalni
`.m2`, kasniji buildovi rade i bez njega.

### Korak 3 — PRIJE VPN-a: frontend (samo ako je mijenjan)

```powershell
Set-Location $FE; $env:VITE_API_URL="https://str-preprod-eturizam.gov.hr"; $env:VITE_USE_MOCK="false"; $env:VITE_NIAS_ENABLED="true"; $env:VITE_CAPTCHA_ENABLED="true"; npm run build
```

**Varijable se postavljaju svaki put.** Vrijede samo za tu PowerShell sesiju i **zapeku se u
build**. Zaboraviš li ih, fronta će gađati `localhost:8080`; zaboraviš li ih promijeniti nakon
builda za drugu okolinu, gađat će **testnu** domenu — a to izgleda potpuno normalno dok netko
ne primijeti da podaci idu u krivi registar.

Provjera (mora ispisati pogodak):

```powershell
Select-String -Path $FE\build\assets\*.js -Pattern "str-preprod-eturizam" | Select-Object -First 1
```

### Korak 4 — NA VPN-u: prijenos

Prvo sigurnosna kopija zatečenog jara, da se pad može vratiti u minuti:

```powershell
ssh cdu-preprod "cd ~/str-rn/str_backend/target && cp -f str-backend-0.0.1-SNAPSHOT.jar str-backend-PRETHODNI.jar 2>/dev/null; ls -l"
```

Backend:

```powershell
scp $BE\target\str-backend-0.0.1-SNAPSHOT.jar cdu-preprod:~/str-rn/str_backend/target/
```

Frontend (**samo ako je mijenjan**) — staro se prvo briše:

```powershell
ssh cdu-preprod "rm -rf ~/str-rn/str_frontend/build/*"
```
```powershell
scp -r $FE\build\* cdu-preprod:~/str-rn/str_frontend/build/
```

Dvije stvari koje ovdje štede sat vremena:

- **`scp` ne briše zaostale datoteke.** Stari `index-<hash>.js` bi ostao uz novi; preglednik s
  cacheanim `index.html` traži hash kojeg više nema → **bijeli ekran**. Zato `rm -rf build/*`.
- Kopira se **`build\*`, ne `build`**. Kod same mape `scp` na kraju pokušava postaviti mod i
  vrijeme, pa javi `remote setstat: Permission denied` iako je upis prošao.

### Korak 5 — NA VPN-u: podizanje

```powershell
ssh cdu-preprod "cd ~/str-rn/str_backend && docker compose -f docker-compose.cdupreprod.yml --env-file .env.cdupreprod up -d --build"
```

`up -d --build` je dovoljan: promijenjen jar poništi cache na `COPY` sloju, image se pregradi i
kontejner rekreira. `down` **ne treba** — bug `KeyError: 'ContainerConfig'` je iz compose v1, a
ovdje je v2. Nove Liquibase changesete primijeni sam backend pri dizanju.

### Korak 6 — provjera

```powershell
ssh cdu-preprod "docker logs --since 5m str-backend-cdupreprod 2>&1 | grep -E 'Started StrBackendApplication|startup_'"
```

Mora se vidjeti `Started StrBackendApplication` i blok `startup_` bez ERROR-a (popis očekivanih
vrijednosti je u §C7). Zatim fronta:

```powershell
ssh cdu-preprod "curl -sI http://localhost:8085 | head -1; curl -s -o /dev/null -w 'captcha %{http_code}\n' http://localhost:8085/api/captcha/challenge"
```

U pregledniku **uvijek Ctrl+F5** — statika ima `immutable, 7 dana`.

### Ako nova verzija ne valja — povratak

```powershell
ssh cdu-preprod "cd ~/str-rn/str_backend/target && cp -f str-backend-PRETHODNI.jar str-backend-0.0.1-SNAPSHOT.jar && cd ~/str-rn/str_backend && docker compose -f docker-compose.cdupreprod.yml --env-file .env.cdupreprod up -d --build"
```

Vraća **samo aplikaciju**. Ako je nova verzija u međuvremenu primijenila Liquibase changeset,
on **ostaje primijenjen** — shema se ne vraća unatrag. Na ovoj okolini to rijetko smeta jer se
tablice ionako dropaju svake noći, pa idući jutarnji restart izgradi shemu po staroj verziji.

### Ako deploya netko drugi — što mu treba prije prvog puta

Postupak je isti, ali se **ništa od ovoga ne podrazumijeva**. Redoslijed je bitan: prve dvije
stavke radi vlasnik kutije, ostale novi kolega sam.

| # | Što | Tko | Napomena |
| :---: | :--- | :--- | :--- |
| 1 | Prava na `~/str-rn` za grupu `docker` | dosadašnji deployer | §B2; jednokratno |
| 2 | `chmod 660` na `.env.cdupreprod` | dosadašnji deployer | **samo ako treba moći dodavati nove ključeve**; uz `640` ga može čitati, ali ne mijenjati |
| 3 | SSH alias `cdu-preprod` u `~/.ssh/config` | novi kolega | `HostName 172.20.8.143`, `User <njegov račun>` |
| 4 | Java 21 + Maven + **popunjen `.m2`** | novi kolega | prvi `mvn package` **mora izvan VPN-a** — `opensaml` se povlači sa `shibboleth`, koji je s CDU mreže blokiran |
| 5 | Node + `npm ci` odrađen | novi kolega | isto, izvan VPN-a |
| 6 | `umask 002` prije svakog `scp`-a | **svi** | inače datoteka ostaje `644` u njegovom vlasništvu i **sljedeći je kolega ne može prepisati** |

Tajne mu **ne treba slati**: `.env.cdupreprod` i NIAS certifikat već stoje na kutiji i redeploy
ih ne dira. To je namjerna posljedica ovog rasporeda — lozinka baze i NIAS keystorea ne putuju.

Dogovorite i tko deploya kad: dvoje ljudi koji istovremeno rade `up -d --build` nad istim
compose projektom rekreira kontejnere jedan drugome ispod ruke.

## C10. Prelazak na NIAS certifikat CN=e-Turizam (jednokratno, 10/2026)

Nova politika: okolina se NIAS-u predstavlja certifikatom **CN=e-Turizam**, a ne posuđenim
„InterniTurizam". Odnosi se **samo na `cdupreprod`** — CDU test (`cdu`) i InfoDom `preprod` se
ne diraju.

| | Prije | Sada |
| :--- | :--- | :--- |
| Datoteka | `~/str-rn/secrets/nias-prod.p12`, donesena `scp`-om | `/opt/str/config-external/keystore.p12`, već na kutiji |
| Mount | `../secrets:/secrets:ro` | `/opt/str/config-external:/opt/str/config-external:ro` |
| Putanja u aplikaciji | `NIAS_KEYSTORE_PATH=/secrets/nias-prod.p12` u `.env`-u | default u `application-cdupreprod.properties`; u `.env`-u **ne postavljati** |
| Lozinka | `NIAS_KEYSTORE_PASSWORD` u `.env.cdupreprod` | isto, nova vrijednost |
| Odjava | nije mogla raditi (SLO registriran uz tuđi cert) | ACS i SLO registrirani uz e-Turizam → mora raditi |

Prelazak ide **zajedno s redeployem** (§C9), ali uz jedan korak koji §C9 nema: §C9 prenosi samo
jar i frontend, a compose datoteka (s novim mountom) na kutiju stiže **samo kroz git** — korak
C10.2b. Bez njega na kutiji ostaje stari compose s `/secrets`, a nova `.env` vrijednost nema što
pročitati.

### C10.1 Izviđanje certifikata (ništa ne mijenja)

```powershell
ssh cdu-preprod "ls -la /opt/str/config-external"
```
Lozinku upisuješ na upit, pa ne ostaje u historyju. `keytool` dolazi iz JRE imagea, jer Jave na
hostu nema:
```powershell
ssh -t cdu-preprod "docker run --rm -it -v /opt/str/config-external:/c:ro eclipse-temurin:21-jre-jammy keytool -list -v -storetype PKCS12 -keystore /c/keystore.p12"
```

| Iz ispisa | Ide u / mora biti |
| :--- | :--- |
| `Alias name` | `NIAS_KEY_ALIAS` |
| `Owner` (cijeli DN, točno) | `NIAS_ENTITY_ID` |
| `Issuer` | produkcijski FINA (`Fina RDC 2020`) — demo CA ne radi s `nias.gov.hr` |
| `Entry type` | `PrivateKeyEntry` |
| `Valid until` | zapisati u §A2/12 |

**STOP** ako datoteka ne postoji, lozinka ne prolazi, izdavatelj je demo CA ili nema privatnog
ključa. Redeploy se tada svejedno može odraditi s `NIAS_SAML_ENABLED=false` u `.env`-u (radi sve
osim prijave eGrađanima) dok se certifikat ne riješi s onim tko ga je postavio. Mount ne smeta:
dok je NIAS ugašen, keystore se uopće ne čita.

### C10.2 Snimka za povratak

```powershell
ssh cdu-preprod "cd ~/str-rn/str_backend && cp -p .env.cdupreprod .env.cdupreprod.PRETHODNI && cp -p docker-compose.cdupreprod.yml docker-compose.cdupreprod.PRETHODNI.yml && ls -l .env.cdupreprod* docker-compose.cdupreprod*"
```
Uz to i kopija jara/fronte iz §C9 korak 4.

### C10.2b Git na kutiji (compose s novim mountom)

Lokalno, **prije VPN-a** i tek kad je ova izmjena spojena u `develop`:
```powershell
git -C C:\Users\MladenHangi\str_backend fetch origin; git -C C:\Users\MladenHangi\str_backend bundle create C:\Users\MladenHangi\be-upd.bundle origin/develop
```
Na VPN-u:
```powershell
scp C:\Users\MladenHangi\be-upd.bundle cdu-preprod:~/
```
```powershell
ssh cdu-preprod "git -C ~/str-rn/str_backend fetch ~/be-upd.bundle origin/develop:refs/remotes/upd/develop && git -C ~/str-rn/str_backend checkout --detach upd/develop && grep -n config-external ~/str-rn/str_backend/docker-compose.cdupreprod.yml"
```

**Zamka s imenom refa:** bundle napravljen iz `origin/develop` nosi ref
`refs/remotes/origin/develop`, pa se na kutiji dohvaća kao `origin/develop:…`. Sa samim
`develop:…` fetch pada na `couldn't find remote ref develop` (izmjereno 02.10.).

Grep na kraju mora ispisati redak s `/opt/str/config-external`. Neprećene datoteke (`.env`,
`target/`, `*.PRETHODNI*`) checkout ne dira.

### C10.3 `.env.cdupreprod`

```powershell
ssh -t cdu-preprod "nano ~/str-rn/str_backend/.env.cdupreprod"
```

| Ključ | Nova vrijednost |
| :--- | :--- |
| `NIAS_ENTITY_ID` | `Owner` iz C10.1, točno |
| `NIAS_KEY_ALIAS` | `Alias name` iz C10.1 |
| `NIAS_KEYSTORE_PASSWORD` | lozinka e-Turizam keystorea |
| `NIAS_KEYSTORE_PATH` | **zakomentirati** — vrijedi default `/opt/str/config-external/keystore.p12` |
| `CDUPREPROD_SECRETS_DIR` | ukloniti ako postoji (compose ga više ne koristi) |
| `NIAS_SAML_ENABLED` | `true` |

**Zamka koja ruši start:** ostane li `NIAS_KEYSTORE_PATH=/secrets/nias-prod.p12`, pregazi novi
default, a `/secrets` se više ne montira → `FileNotFoundException` i restart petlja.

Kontrola (lozinka se ne ispisuje):
```powershell
ssh cdu-preprod "ls -l ~/str-rn/str_backend/.env.cdupreprod; grep -nE '^#?(NIAS_|CDUPREPROD_SECRETS_DIR)' ~/str-rn/str_backend/.env.cdupreprod | grep -v PASSWORD"
```

### C10.4 Podizanje i provjera

Podizanje je §C9 korak 5 (`up -d --build`). **Nikad `restart`** nakon promjene `.env`-a ili
composea — `restart` ne čita ni `env_file` ni volumene ponovno.

```powershell
ssh cdu-preprod "docker logs --since 5m str-backend-cdupreprod 2>&1 | grep -E 'Started StrBackendApplication|startup_nias|NIAS SP certifikat|NE poklapa|ERROR'"
```
```powershell
ssh cdu-preprod "docker inspect -f '{{range .Mounts}}{{.Source}} -> {{.Destination}} rw={{.RW}}{{println}}{{end}}' str-backend-cdupreprod"
```
Očekivano: `keystore=/opt/str/config-external/keystore.p12`, `subjectDN` s `CN=e-Turizam`, **bez**
WARN-a „NE poklapa", i jedini mount `/opt/str/config-external -> /opt/str/config-external rw=false`.

U pregledniku (Ctrl+F5): prijava eGrađanima → `/new-registration-number`, zatim **odjava** — mora
proći kroz NIAS i vratiti na `/`. To je dokaz da je novi certifikat registriran.

**Povratak** (stari keystore još postoji do C10.5):
```powershell
ssh cdu-preprod "cd ~/str-rn/str_backend && cp -p .env.cdupreprod.PRETHODNI .env.cdupreprod && cp -f docker-compose.cdupreprod.PRETHODNI.yml docker-compose.cdupreprod.yml && docker compose -f docker-compose.cdupreprod.yml --env-file .env.cdupreprod up -d"
```
Kopija composea preko praćene datoteke ostavlja git na kutiji „prljavim" — prije idućeg
bundle checkouta `git -C ~/str-rn/str_backend checkout -- docker-compose.cdupreprod.yml`.

### C10.5 Čišćenje — tek kad su prijava I odjava prošle

```powershell
ssh cdu-preprod "rm -f ~/str-rn/secrets/nias-prod.p12 && rmdir ~/str-rn/secrets; rm -f ~/str-rn/str_backend/.env.cdupreprod.PRETHODNI ~/str-rn/str_backend/docker-compose.cdupreprod.PRETHODNI.yml; ls -la ~/str-rn"
```
`.env.cdupreprod.PRETHODNI` nosi staru lozinku, pa se briše zajedno s keystoreom. Lokalnu kopiju
`nias-prod.p12` ne brisati bez odluke — InfoDom `preprod` je i dalje koristi.

---

# D. Zamke

Prvo one koje na **ovoj** okolini vrijede:

| Simptom | Uzrok | Rješenje |
| :--- | :--- | :--- |
| Ujutro svaki upit puca, kontejner „Up" | tablice su nestale s resetom, a Liquibase se vrti samo pri startu (konekcije nisu krive — Hikari ih sam obnavlja) | restart backenda u nightly skripti |
| Puna migracija svaki dan, registar prazan | reset dropa tablice (potvrđeno) — Liquibase ih gradi od nule | **očekivano i ispravno**; testerima reći da dnevni unos ne preživljava noć |
| Backend „Up", ali svaki upit puca na nepostojećoj tablici, bez greške na startu | `databasechangelog` preživio drop, ostale tablice nisu → Liquibase ne kreira ništa | bootstrap to otkriva i javlja; popravak je drop changelog tablica (A1/6b) |
| Ujutro `startup_schema_unreadable`, adresna kaskada mrtva | tablični `SELECT` grant nestao s dropanom tuđom tablicom | A1/6 — traži `ALTER DEFAULT PRIVILEGES` ili re-grant u njihovoj reset skripti |
| `permission denied for schema rpj_dgu` (servis radi) | grant nestao s resetom | grant traži vlasnik sheme; mi ga ne možemo dati |
| Ujutro `Shema str_rn ne postoji` i oporavak stane | reset ju je obrisao, a nemamo `CREATE` na bazi | namjeran prekid — backend se ne restarta; rješenje je kod DBA (A1/4) |
| `permission denied to set role` | user nije član role | A1/2 |
| `app.captcha.hmac-key is empty or unset` → backend ne starta | `CAPTCHA_HMAC_KEY` prazan ili nepostavljen | postaviti ključ; `AltchaService` namjerno obara start umjesto tihog 500 na formularima |
| Backend pada na start s `FileNotFoundException: /secrets/nias-prod.p12` | u `.env.cdupreprod` je ostao stari `NIAS_KEYSTORE_PATH`, a `/secrets` se više ne montira | redak zakomentirati (vrijedi default `/opt/str/config-external/keystore.p12`), pa `up -d` (§C10) |
| Promijenjena lozinka/alias u `.env`-u „ne djeluje" | backend je podignut s `restart`, koji ne čita `env_file` ponovno | `docker compose … up -d` |
| Prijava ne prolazi, u logu WARN `entity-id se NE poklapa` | `NIAS_ENTITY_ID` ≠ Subject DN e-Turizam certifikata | prepisati `Owner` iz `keytool` ispisa točno (§C10.1) |
| Prijava radi, odjava ne | do 10/2026: posuđeni „InterniTurizam" certifikat | s e-Turizam certifikatom (SLO registriran) odjava mora raditi — ako ne radi, usporediti `startup_nias_urls slo=` s registracijom |
| `git clone` s kutije traži lozinku / visi | repo je privatan, `github.com:22` zatvoren | §B6 (bundle ili PAT) |
| Build stane na „no space left" | 8,5 GB slobodno, prvi build povuče ~2–3 GB | `docker system prune -f` prije builda; ne držati stare imageove |
| Lažni nalazi iz `ssh` provjera | naredba prelomljena u više redaka → bash izvrši fragmente zasebno | remote naredbe pisati u **jednom retku** |
| Deploy otišao na krivu kutiju | dva slična SSH aliasa | `cdu` = test (`.158`), `cdu-preprod` = nova (`.143`) |
| `bad interpreter: /usr/bin/env bash^M` | `.sh` donesen s Windowsa s CRLF završecima | `.gitattributes` to čuva dok skripta dolazi gitom; `dos2unix` ako ipak dođe scp-om |

Naslijeđeno s CDU testa, a **ovdje ne vrijedi** (zapisano da se ne traži na krivom mjestu):

| Zamka s testa | Zašto ovdje ne vrijedi |
| :--- | :--- |
| Bijeli ekran od nečitljivih modova nakon `scp`-a | build je na kutiji, artefakti se ne prenose |
| `Permission denied` na `scp` u tuđi home, grupa `kodelab-d` | vlastiti home |
| `KeyError: 'ContainerConfig'` (compose v1) | ovdje je Compose v2 (v5.5.1) |
| `COPY target/*.jar` puca zbog `.dockerignore` | vrijedi samo za `Dockerfile.artifact`, koji je rezerva |

# E. Definicija gotovog

1. `https://str-preprod-eturizam.gov.hr` vraća aplikaciju preko HTTPS-a.
2. `startup_*` blok bez ijednog ERROR-a (uključujući `rpj_dgu` i `eturizam_test`).
3. Registracijski formular prolazi end-to-end: adresna kaskada → RB → PDF.
4. Prijava eGrađanima radi, ili je svjesno ugašena uz zapisan razlog.
5. Okolina preživi **tri uzastopna noćna reseta** bez ručne intervencije.
6. Postupak je u ovom dokumentu, konfiguracija u repou, tajne samo na kutiji.
