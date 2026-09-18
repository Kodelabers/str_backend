# CDU predprodukcija — `str-preprod-eturizam.gov.hr` (PLAN)

> **Status 18.09.2026.: recon kutije je odrađen, okolina još nije deployana.** Build strategija
> je time odlučena (build-on-box), a otvoreno je ostalo troje: DB kredencijali i potvrda
> sheme/role, lozinka produkcijskog NIAS keystorea i potvrda gateway pravila. Kad okolina
> proradi, ovaj dokument prelazi u oblik kakav ima `DEPLOY-CDU.md` (koraci + zamke).

Nova okolina je **druga kutija na istom državnom VPN-u** kao CDU test. Nije nastavak
InfoDomove predprodukcije (`s-str-02`) — s njom dijeli samo riječ „predprodukcija".

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
| Docker Hub | `registry-1.docker.io/v2/` → `401`, `docker pull nginx:alpine` → **OK** | **build-on-box je moguć** |
| GitHub SSH (22) | **zatvoren** (bez odgovora) | deploy ključ preko SSH-a **otpada** → §B6 |
| `mvn` / `npm` / `java` na hostu | **nema ih** | nebitno: build ide u kontejneru |
| Cacheirani imageovi | `eclipse-temurin:11-jre`, `str-external`, `str-internal` | naš base (**21**) i `maven`/`node` povlače se pri prvom buildu |
| Disk | 17 GB ukupno, **8,5 GB slobodno** | dovoljno, ali prvi build povuče ~2–3 GB → vidi zamke |
| NIAS produkcijski | `https://nias.gov.hr/metadata` vraća **punu, potpisanu metadatu** (`CN=niasap`, SSO `/sso-http`, Fina RDC 2020) | najvažniji preflight prošao — bez njega aplikacija ne bi ni startala |
| Baza | TCP `172.20.8.212:5432` **dohvatljiv s kutije** | ostaje samo autentikacija i prava |

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
| Build | lokalno + `scp` (nema mvn/npm/DockerHub) | **build-on-box (potvrđeno)** | build-on-box |

---

# A. Pitanja — što još blokira

## A1. Darko Bosnar / DBA (baza `172.20.8.212`) — najveći ostatak

1. **Kako se točno zove shema i rola?** Uputa je došla s `currentSchema=xxx` i `role=xxx_owner`.
   Na CDU testu je URL **bez** `currentSchema` i **bez** `SET ROLE`, na InfoDom preprodu je
   `str_rn` + `str_owner`. „Ista priča kao test" pokriva korisnika i lozinku, ali ne i ovo.
2. **Je li aplikacijski user član role?** Bez članstva konekcija pada **već na handshakeu**, ne
   na prvom upitu.
3. **Postoji li shema već?** Na `cdupreprod` profilu je **nitko ne kreira** —
   `LocalDatabaseConfig` radi samo na `local`/`mock`. Ako je nema:
   `CREATE SCHEMA <shema> AUTHORIZATION <rola>;`
4. **Što točno noćni reset radi** — drop/restore cijele baze `eturizam`, ili samo njihovih shema
   (`str`, `rpj_dgu`, `eturizam_test`)? **Ovo mijenja cijeli §C3.**
   - briše li i `str_rn` (naša shema, naši podaci i `databasechangelog`)?
   - **može li se `str_rn` izuzeti iz reseta?** Najjeftinije rješenje za sve.
5. **U koje vrijeme i koliko traje** reset? Treba nam prozor za jutarnji oporavak.
6. **Preživljavaju li grantovi reset?** Ako se sheme recreiraju iz dumpa, ACL-ovi dolaze iz dumpa
   i naši grantovi nestaju svake noći.
7. **Grantovi koji nam trebaju** (isti zahtjev kao na InfoDom preprodu, gdje je ovo bila blokada
   — `DEPLOY-PREPROD.md` §2d). Bez njih se **aplikacija digne**, a padne samo adresna kaskada:
   ```sql
   GRANT USAGE  ON SCHEMA rpj_dgu, eturizam_test TO <rola>;
   GRANT SELECT ON ALL TABLES IN SCHEMA rpj_dgu       TO <rola>;
   GRANT SELECT ON ALL TABLES IN SCHEMA eturizam_test TO <rola>;
   -- plus str shema: SELECT na facility, subject, country
   ```
8. **Je li u `str` shemi produkcijski dump** (prave adrese i e-mailovi)? Odgovor određuje smije
   li se ikad upaliti e-pošta i smije li se okolina koristiti za prezentaciju.
9. Treba li `GRANT UPDATE (registration_number) ON str.facility` — bez toga se **tuStart handoff
   ne može testirati end-to-end**.

## A2. Simon / InfoDom (NIAS)

10. ~~Koji NIAS?~~ **Odgovoreno: produkcijski.** Dohvatljivost s kutije je izmjerena (metadata se
    učitava). Nosi se produkcijski keystore `nias-prod.p12` (Fina RDC 2020), **ne** CDU-ov demo.
    **Fali još samo lozinka keystorea.**
11. **Je li nova domena registrirana uz certifikat?** NIAS ne čita našu SP metadatu — ima
    hardkodiranu konfiguraciju vezanu uz certifikat. Reći testerima unaprijed: prijava bi trebala
    raditi (ACS putuje u AuthnRequestu), **odjava ne može** dok STR nema vlastiti certifikat.
12. **Certifikat istječe 08.11.2026.** — tražiti vlastiti STR certifikat, uz registraciju obje
    domene.

## A3. Mrežni tim / CDU

13. **Je li gateway pravilo postavljeno?** Treba potvrda da `443 → 172.20.8.143:8085` radi.
    Certifikat je riješen (wildcard `*.gov.hr`).
14. Što znači `16:8085` iz njihove tablice? Pretpostavka je interni port 8085; ne nagađamo.
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

U repou (grana `feat/cdu-preprod-okolina`):

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

`~/str-rn/` (tj. `/home/mhangi/str-rn`), tajne u `~/str-rn/secrets/` s `chmod 600`.

Ovo je bitna razlika prema CDU testu, gdje je deploy u **tuđem** home-u (`/home/vviskov/str-rn`)
kroz grupu `kodelab-d`. Odatle dolaze `Permission denied` na `scp`-u, nečitljivi modovi za nginx
i onaj bijeli ekran. Ovdje toga nema: vlastiti home, vlastite datoteke, `docker` bez `sudo`.

## B3. Build ide NA KUTIJI

Docker Hub je dohvatljiv i `docker pull` prolazi, pa se koriste **postojeći `Dockerfile`-ovi iz
oba repoa** (maven i vite u kontejneru). `BACKEND_DOCKERFILE`/`FRONTEND_DOCKERFILE` ostaju
nepostavljeni.

Time otpada cijeli lanac s CDU testa: lokalni build, `scp` artefakata, ručni `chmod`, i
posljedično najčešći kvar te okoline. `Dockerfile.artifact` ostaje u repou kao **rezerva** ako
kutija ikad ostane bez pristupa registryju; FE thin varijanta se tada radi zasebno (u
`str_frontend` repou ne postoji).

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
  secrets/         ← nias-prod.p12, chmod 600
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

Što se dogodi kad baza nestane pod aplikacijom koja radi:

| Posljedica | Zašto |
| :--- | :--- |
| `str_rn` nestane | shemu na ovom profilu **nitko ne kreira** (`LocalDatabaseConfig` je `local`/`mock`) |
| `databasechangelog` nestane | idući start vrti **cijeli changelog od nule** → registar je ujutro prazan |
| Hikari drži mrtve konekcije | kontejner je „Up", ali svaki upit puca dok se ne restarta |
| Sesije nestanu (`str_rn.spring_session`) | svi prijavljeni testeri su odjavljeni |
| Skice nestanu | `DRAFT_ENC_KEY` ostaje isti (u `.env`), ali podaci ne |
| Grantovi možda nestanu | ako se sheme recreiraju iz dumpa, ACL dolazi iz dumpa (→ A1/6) |

Redoslijed jutarnjeg oporavka (`tools/cdupreprod-nightly.sh`, cron **nakon** njihovog prozora):

```
1. čekaj da baza prihvaća konekcije      (retry — ne pretpostavljaj da je reset gotov)
2. psql -f cdupreprod-bootstrap.sql      (CREATE SCHEMA IF NOT EXISTS + ispis prava)
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
- Ako Darko potvrdi da se `str_rn` **može izuzeti** iz reseta (A1/4), cijela faza se svede na
  „restart ujutro" i podaci testera preživljavaju. **Prvo tražiti to.**

## C4. Repo rad — ✅ napravljeno

Grana `feat/cdu-preprod-okolina` (PR na `develop`; nikad push na `develop`, nikad PR na `main`).

**Zamka:** `VITE_API_URL` ide **bez** `/api` sufiksa — `backendApi.ts` uzima `VITE_API_URL` kao
`baseURL`, a putanje već počinju s `/api/…`. U repou postoji i suprotan primjer
(`docker-compose.cdu.yml` ima `/api`), ali to je referentna datoteka koja se na CDU ne izvršava.

## C5. NIAS keystore i gateway

- [ ] `keytool -list -v` **lokalno** prije prijenosa — alias i Subject DN idu u `.env`; pogrešan
      alias daje `null` privatni ključ i kontekst pada na dizanju.
- [ ] keystore na kutiju (bez lozinke u `ps` i historyju):
      ```bash
      scp nias-prod.p12 cdu-preprod:/tmp/
      install -m 600 /tmp/nias-prod.p12 ~/str-rn/secrets/nias-prod.p12 && rm /tmp/nias-prod.p12
      ```
- [ ] lozinka keystorea od Simona → `NIAS_KEYSTORE_PASSWORD` u `.env.cdupreprod`
- [ ] tražiti registraciju ACS/SLO za novu domenu:
      `https://str-preprod-eturizam.gov.hr/login/saml2/sso/nias` i `…/logout/saml2/slo/nias`
- [ ] potvrda gateway pravila (A3/13)

**Zamka:** ako registracija ne stigne na vrijeme, okolina se **svejedno diže** s
`NIAS_SAML_ENABLED=false` — radi sve osim prijave eGrađanima. Bolji ishod od čekanja.

## C6. Prvi deploy — build na kutiji

```bash
cd ~/str-rn/str_backend
cp .env.cdupreprod.example .env.cdupreprod
nano .env.cdupreprod      # 5 vrijednosti: DB user/pass, DRAFT_ENC_KEY, CAPTCHA_HMAC_KEY, NIAS_KEYSTORE_PASSWORD
#   openssl rand -base64 32   ← za oba ključa, generirati jednom i ne rotirati

docker compose -f docker-compose.cdupreprod.yml --env-file .env.cdupreprod up -d --build
docker compose -f docker-compose.cdupreprod.yml logs -f backend
```

Prvi build traje najdulje — povlače se `maven`, `node`, `eclipse-temurin:21-jre` i `nginx:alpine`
(na kutiji je cacheiran samo temurin **11**). Sljedeći su znatno brži.

**Zamka:** `.env.cdupreprod` **mora postojati** prije `up` — bez nje compose puca na `env_file`.
**Zamka:** ključ koji ne postavljaš **zakomentiraj**, ne ostavljaj prazan. Prazna vrijednost je
postavljena vrijednost i pregazi Spring default.

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

Zatim izvana: `curl -sS -o /dev/null -w '%{http_code}\n' https://str-preprod-eturizam.gov.hr`.
Ako i dalje puca veza uz ispravan TLS, gateway pravilo nije postavljeno (A3/13) — a ne aplikacija.

Iz browsera **uvijek Ctrl+F5**: statika ima `immutable, 7 dana`, pa preglednik inače servira
stari `index.html` koji traži hash kojeg u novom buildu više nema.

## C8. Automatizacija + primopredaja

- [ ] `docker pull postgres:16-alpine` (preduvjet nightly skripte)
- [ ] cron za `cdupreprod-nightly.sh`, log u datoteku
- [ ] tri jutra zaredom provjeriti log prije nego se okolina proglasi stabilnom
- [ ] testerima napisati **što nakon reseta nestaje** (RB-ovi, skice, sesije) — inače to dolazi
      kao prijavljeni bug
- [ ] ovaj dokument prepisati iz plana u postupak + dodati `Update-only` odjeljak

---

# D. Zamke

Prvo one koje na **ovoj** okolini vrijede:

| Simptom | Uzrok | Rješenje |
| :--- | :--- | :--- |
| Ujutro svaki upit puca, kontejner „Up" | Hikari drži konekcije na resetiranu bazu | restart backenda u nightly skripti |
| Puna migracija svaki dan, registar prazan | reset briše `str_rn` i `databasechangelog` | očekivano; tražiti izuzimanje `str_rn` iz reseta |
| `permission denied for schema rpj_dgu` (servis radi) | grant nestao s resetom | grant u bootstrap skriptu ili u njihov reset |
| `permission denied to set role` | user nije član role | A1/2 |
| `app.captcha.hmac-key is empty or unset` → backend ne starta | `CAPTCHA_HMAC_KEY` prazan ili nepostavljen | postaviti ključ; `AltchaService` namjerno obara start umjesto tihog 500 na formularima |
| Prijava radi, odjava ne | posuđeni „InterniTurizam" certifikat | ne debugirati; vlastiti cert + registracija (A2) |
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
