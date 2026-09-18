# CDU predprodukcija — `str-preprod-eturizam.gov.hr` (PLAN)

> **Status: plan, ništa još nije izvršeno.** Dokument je radni — dio vrijednosti je `<?>` i
> popunjava se kako stižu odgovori (§A). Kad okolina proradi, ovaj dokument prelazi u oblik
> kakav ima `DEPLOY-CDU.md` (koraci + zamke).

Nova okolina je **druga kutija na istom državnom VPN-u** kao CDU test. Nije nastavak
InfoDomove predprodukcije (`s-str-02`) — s njom dijeli samo riječ „predprodukcija".

## Što je poznato iz upute (17.09.2026.)

| | |
| :--- | :--- |
| Kutija | `172.20.8.143`, korisnik `root`, lozinka kod **Darka Bosnara** |
| Pristup | „isti način kao i na test s certifikatom" → isti SSH ključ (`~/.ssh/id_ed25519`), državni VPN |
| Baza | `jdbc:postgresql://172.20.8.212:5432/eturizam?currentSchema=<?>&options=-c%20role%3D<?>_owner` |
| Lozinka baze | „ista kao na str2 testu i na pretprodukciji u Infodomu" |
| Reset baze | **svake noći**, planirano — „imajte spremne skripte da se izvršavaju na svaki reset" |
| Javni URL | `https://str-preprod-eturizam.gov.hr` → `85.209.13.25` → interno `…:8085` |

Provjereno danas s laptopa: `str-preprod-eturizam.gov.hr` i `str-test-eturizam.gov.hr`
**oba** resolvaju na `85.209.13.25`, dakle gateway ih razlikuje po `Host`/SNI, ne po IP-u.
TCP do `172.20.8.143:22` ne prolazi — ali ne prolazi ni do poznato ispravne test kutije
`172.20.8.158:22`, pa je to nalaz o **ugašenom VPN-u**, ne o novoj kutiji.

## Tri okoline jedna uz drugu

| | CDU test (radi) | **CDU preprod (nova)** | InfoDom preprod |
| :--- | :--- | :--- | :--- |
| Kutija | `172.20.8.158` | `172.20.8.143` | `s-str-02.infodom.hr` |
| VPN | državni | državni | InfoDom Sophos |
| Korisnik | `mhangi` (tuđi home, grupa `kodelab-d`) | **`root`** (nema problema s pravima) | `mhangi` + `sudo -iu vviskov` |
| Baza | `172.20.8.196:5432/eturizam` | `172.20.8.212:5432/eturizam` | `s-str-02:5431/eturizam` |
| Reset baze | ne | **svake noći** | ne |
| HTTPS | da (gateway) | **da (gateway)** | ne (plain HTTP) |
| Captcha | uključena | **uključena** (ima HTTPS) | ugašena (nema HTTPS) |
| NIAS | `niastst.fina.hr`, demo cert | **`nias.gov.hr`, produkcijski cert** (odlučeno) | `nias.gov.hr`, prod cert |
| eGOP | ugašen | **ugašen** (odlučeno) | ugašen |
| Profil | `cdu` | **`cdupreprod` (novi)** | `preprod` |
| Build | lokalno + `scp` (nema mvn/npm/DockerHub) | odlučuje recon (§C1) | build-on-box |

---

# A. Pitanja — bez odgovora se ne kreće

Grupirano po tome **koga se pita**. Sve ostalo u planu je izvedivo bez čekanja.

## A1. Darko Bosnar / DBA (baza `172.20.8.212`)

1. **Kako se točno zove shema i rola?** U uputi piše `currentSchema=xxx` i `role=xxx_owner`.
   Na CDU testu i InfoDom preprodu je `str_rn` + `str_owner`. Je li ovdje isto?
2. **Koji je aplikacijski user?** (na CDU testu `shorttermrental`). Je li član role — bez
   članstva konekcija pada **već na handshakeu**, ne na prvom upitu.
3. **Postoji li shema već, ili je moramo kreirati?** Na `cdupreprod` profilu je **nitko ne
   kreira** — `LocalDatabaseConfig` radi samo na `local`/`mock`. Ako je nema:
   `CREATE SCHEMA <shema> AUTHORIZATION <rola>;`
4. **Što točno noćni reset radi** — drop/restore cijele baze `eturizam`, ili samo njihovih
   shema (`str`, `rpj_dgu`, `eturizam_test`)? **Ovo mijenja cijeli §C3.**
   - briše li i `str_rn` (naša shema, naši podaci i `databasechangelog`)?
   - **može li se `str_rn` izuzeti iz reseta?** To je najjeftinije rješenje za sve.
5. **U koje vrijeme i koliko traje** reset? Treba nam prozor za jutarnji oporavak.
6. **Preživljavaju li grantovi reset?** Ako se sheme recreiraju iz dumpa, ACL-ovi dolaze iz
   dumpa i naši grantovi nestaju svake noći.
7. **Grantovi koji nam trebaju** (isti zahtjev kao na InfoDom preprodu, gdje je ovo bila
   blokada — vidi `DEPLOY-PREPROD.md` §2d). Bez njih se **aplikacija digne**, a padne samo
   adresna kaskada u registracijskom formularu:
   ```sql
   GRANT USAGE  ON SCHEMA rpj_dgu, eturizam_test TO <rola>;
   GRANT SELECT ON ALL TABLES IN SCHEMA rpj_dgu       TO <rola>;
   GRANT SELECT ON ALL TABLES IN SCHEMA eturizam_test TO <rola>;
   -- plus str shema: SELECT na facility, subject, country
   ```
8. **Je li u `str` shemi produkcijski dump** (prave adrese i e-mailovi iznajmljivača)?
   Odgovor određuje smije li se na okolini ikad upaliti e-pošta i smije li se prezentirati.
9. Treba li nam `GRANT UPDATE (registration_number) ON str.facility` — bez toga se **tuStart
   handoff ne može testirati end-to-end** (RB se izda, ali se ne upiše natrag u eTurizam).

## A2. Simon / InfoDom (NIAS)

10. ~~Koji NIAS na ovoj okolini?~~ **Odgovoreno: produkcijski** (`https://nias.gov.hr/metadata`).
    Posljedice, sve već ugrađene u `application-cdupreprod.properties`:
    - nosi se **produkcijski keystore** (`nias-prod.p12`, Fina RDC 2020) — isti koji koristi
      InfoDom predprodukcija, **ne** CDU-ov demo `keystore.p12` (Fina Demo CA);
    - treba **lozinka keystorea** (Simon / FINA) — jedina NIAS tajna koja nam još fali;
    - kutija **mora vidjeti `nias.gov.hr:443`**, jer se metadata čita pri dizanju konteksta;
      ako ne vidi, aplikacija uopće ne krene (preflight u §C1).
11. **Je li nova domena registrirana uz certifikat?** NIAS ne čita našu SP metadatu — ima
    hardkodiranu konfiguraciju vezanu uz certifikat. Posljedica koju treba reći testerima
    unaprijed: prijava bi trebala raditi (ACS putuje u AuthnRequestu), **odjava ne može** dok
    STR nema vlastiti certifikat i vlastitu registraciju po okolini.
12. **Certifikat istječe 08.11.2026.** Ako preprod ide na produkcijski NIAS, to je za manje od
    dva mjeseca — traži li se vlastiti STR certifikat sad, uz registraciju obje domene?

## A3. Mrežni tim / CDU

13. **Je li gateway pravilo već postavljeno?** U mailu piše „URL bi trebao bit". Treba potvrda
    da `443 → 172.20.8.143:8085` stvarno radi i da postoji **TLS certifikat za novo ime**
    (isti javni IP kao test → razlikuju se po SNI).
14. Što znači `16:8085` u tablici? Pretpostavka je interni port 8085; ne nagađamo.
15. Ima li kutija izlaz prema internetu (GitHub, Docker Hub) ili je odsječena kao test kutija?
    → odlučuje build strategiju (§C1).

## A4. Naručitelj / MINTS

16. ~~Čemu okolina služi?~~ **Odgovoreno: ponaša se kao CDU testna okolina.** Znači:
    `EGOP_MOCK_FILING_PREFIX=` (urudžbeni brojevi na PDF-u izgledaju kao pravi),
    `RN_DOCUMENTS_ZAHTJEV_VISIBLE=false`, `APP_CADASTRAL_ENABLED=true`, captcha uključena.
    Ostaje otvoreno samo: **jesu li u bazi pravi podaci iznajmljivača** (A1/8) — o tome ovisi
    smije li se okolina koristiti za prezentaciju i smije li e-pošta ikad na `true`.
17. Tko su testeri i **treba li im interni pristup**? Uloge se razrješavaju po OIB-u iz baze —
    na okolini koja se resetira to znači da popis mora biti dio reset skripte.
18. Ostaje li CDU test gore paralelno? (Pretpostavka: da — dvije okoline, dvostruki deploy.)

---

# B. Odluke koje predlažem

## B1. Novi Spring profil `cdupreprod`, ne „reuse" profila `preprod`

`application-preprod.properties` je vezan uz InfoDom: defaulti su `s-str-02` DB URL, plain
HTTP (`request-store=database`, bez `secure` cookieja, bez `forward-headers`), produkcijski
NIAS i `S-SMTP-01.infodom.hr`. Nova okolina je **bliža CDU testu** (HTTPS iza gatewaya, jedan
origin, captcha radi). Model je zato `application-cdu.properties`, ne `preprod`.

Novi profil je jeftin: `@Profile` se u kodu pojavljuje **dva puta** (`NonEuTestLessorSeeder` na
`local/mock/dev`, `StartupDiagnostics` na `!test`), sve ostalo ide kroz `${ENV:...}`.

Novo u repou (grana `feat/cdu-preprod-okolina` → PR na `develop`) — **napisano, čeka PR**:

| Datoteka | Sadržaj | |
| :--- | :--- | :--- |
| `src/main/resources/application-cdupreprod.properties` | model = `cdu` profil; produkcijski NIAS; DB i URL-ovi kroz env | ✅ |
| `docker-compose.cdupreprod.yml` | `name: str-cdupreprod`, `env_file`, jedan origin, captcha i eGOP prekidači | ✅ |
| `.env.cdupreprod.example` | tajne + komentari (struktura `.env.preprod.example`) | ✅ |
| `Dockerfile.artifact` | thin image za kutiju bez mavena — `COPY target/*.jar` + `chmod` u imageu | ✅ |
| `.dockerignore` | iznimka `!target/*.jar` — bez nje thin build pada, `target` je bio isključen | ✅ |
| `.gitattributes` | `*.sh`/`*.sql` = LF — Git for Windows inače daje CRLF i shebang na kutiji puca | ✅ |
| `tools/cdupreprod-bootstrap.sql` | `CREATE SCHEMA` + ispis prava — pušta se **na svaki reset** | ✅ |
| `tools/cdupreprod-nightly.sh` | jutarnji oporavak: čekaj bazu → bootstrap → restart → smoke (§C3) | ✅ |
| `CLAUDE.md` | tablica profila (pisalo je „five profiles", a bilo ih je sedam) | ✅ |
| `DEPLOY-CDU-PREPROD.md` | ovaj dokument | ✅ |

Nijedna datoteka ne dira postojeće okoline: novi profil, novi compose projekt (`name:`), nove
skripte. Jedina zajednička datoteka je `.dockerignore`, gdje dodani redak samo **prestaje
isključivati** jar iz build konteksta.

**Build strategija još nije odlučena** (čeka recon, §C1), pa compose podržava obje:
`BACKEND_DOCKERFILE` / `FRONTEND_DOCKERFILE` u `.env.cdupreprod` prebacuju s build-on-box na
thin image. Za frontend thin varijanta **još ne postoji u `str_frontend` repou** — na CDU testu
živi samo na serveru; ako recon pokaže da treba, radi se u tom repou zasebno.

Liquibase: `spring.liquibase.contexts=cdupreprod` — dakle **bez** `local`, `dev` i `cdu`
seedova. `120-seed-cdu-activity` se veže na postojeće `ACTIVE` RB-ove; na bazi koja se resetira
svake noći njih nema, pa bi ionako ubacio nula redaka.

## B2. Deploy kao `root`, izvan tuđeg home-a

CDU test nas je koštao pola dana na pravima datoteka jer je deploy u `/home/vviskov/str-rn`
kroz grupu `kodelab-d`. Ovdje imamo `root` → **`/srv/str-preprod/`**, bez grupnih akrobacija.
Tajne u `/srv/str-preprod/secrets/`, `chmod 600`.

## B3. Ono što je na CDU testu ostalo „samo na serveru" ovaj put ide u repo

`Dockerfile.cdu` i serverski `docker-compose.cdu.yml` na testu **nisu u gitu** — zbog toga se
17.09. dijagnosticiralo po krivim datotekama. Na novoj okolini to ne ponavljamo: thin
Dockerfile (`COPY jar` / `COPY build/` + `RUN chmod -R a+rX`) ide u repo pod jasnim imenom, a
compose ga referencira. Ako recon pokaže da kutija ima mvn/npm/Docker Hub, koristi se postojeći
build-on-box `Dockerfile` i thin varijanta ne treba.

## B4. Captcha UKLJUČENA (za razliku od InfoDom preproda)

ALTCHA traži sigurni kontekst (Web Crypto). Ova okolina je iza HTTPS-a, pa oba prekidača idu na
`true`: `APP_CAPTCHA_ENABLED=true` (backend) **i** `VITE_CAPTCHA_ENABLED=true` (FE build arg,
default je `true`). `CAPTCHA_HMAC_KEY` je obavezan — nepostavljen obori start, a **prazan pusti
start** i tek `GET /api/captcha/challenge` vrati 500, pa su sva 4 javna formulara mrtva bez
ijedne greške u startup logu.

## B5. E-pošta i eGOP ugašeni do izričite odluke

`APP_MAIL_ENABLED=false`, `EGOP_ENABLED=false`. eGOP se pali samo ako recon pokaže da je
`https://egopeaitest.mint.hr` s ove kutije dohvatljiv (§C1) — i tek uz odluku naručitelja.
Napomena: ugašen eGOP **ne znači** da se ništa ne urudžbira; `EgopClientMock` i dalje dodjeljuje
KLASU/URBROJ.

---

# C. Faze

## C0. Pristup (blokira sve ostalo)

- [ ] Državni VPN gore (danas je dolje — ni test kutija se ne vidi).
- [ ] Lozinka za `root@172.20.8.143` od Darka Bosnara.
- [ ] Potvrditi da je naš SSH ključ prihvaćen („isti način kao test").
- [ ] SSH alias u `~/.ssh/config`:
  ```
  Host cdu-preprod
      HostName 172.20.8.143
      User root
      IdentityFile ~/.ssh/id_ed25519
      IdentitiesOnly yes
  ```

**Zamka:** `cdu` alias se spaja kao `mhangi`, novi kao `root`. Kopirati blok, ne prepravljati
postojeći — jedan pogrešan `scp` na krivu kutiju i debugira se pogrešna okolina.

## C1. Recon kutije (jedan prolaz — VPN je skup)

Cilj: doznati **buildamo li na kutiji ili nosimo artefakte**, i što je već ondje.

```bash
ssh cdu-preprod
docker --version; docker compose version 2>/dev/null; docker-compose --version 2>/dev/null
docker images                      # ima li eclipse-temurin:21-jre* i nginx:alpine u cacheu
docker ps -a                       # vrti li se već nešto tuđe (portovi!)
ss -lntp | grep -E '8085|8080|8086'
df -h /; free -m; nproc
which git mvn npm java
timeout 5 curl -sSI https://registry-1.docker.io/v2/ | head -1     # Docker Hub?
timeout 5 curl -sSI https://github.com | head -1                    # GitHub?
timeout 5 curl -sS -o /dev/null -w 'nias-test: %{http_code}\n' https://niastst.fina.hr/metadata
timeout 5 curl -sS -o /dev/null -w 'nias-prod: %{http_code}\n' https://nias.gov.hr/metadata
timeout 5 curl -sk -o /dev/null -w 'egop: %{http_code}\n' "https://egopeaitest.mint.hr/ServiceMDM.asmx?wsdl"
timeout 5 bash -c '</dev/tcp/172.20.8.212/5432' && echo "DB port OK"
crontab -l; ls /etc/cron.d         # postoji li već njihova reset automatizacija
```

**Zamka:** NIAS metadata se dohvaća **pri dizanju konteksta**. Ako kutija ne vidi odabrani
NIAS, aplikacija uopće ne krene — točno onako kako je `dev` padao na `niastst.fina.hr`. Zato je
ovo preflight, ne provjera poslije deploya.

**Izlaz faze:** odluka build-on-box vs. lokalni build + `scp`, i odgovor na A3/15.

## C2. Baza — preflight i grantovi

`psql` vjerojatno nije na kutiji (na `s-str-02` ga nije bilo). Tri puta, istim redom kao u
`DEPLOY-PREPROD.md` §2c: (A) s laptopa preko VPN-a, (B) kroz postojeći Postgres kontejner ako
ga ima, (C) pustiti Liquibase da kaže.

```powershell
# s laptopa, VPN gore — replicira TOČNO ono što radi aplikacijski JDBC URL:
psql "postgresql://<user>@172.20.8.212:5432/eturizam?options=-c%20role%3D<rola>" -c "select current_user, session_user"
```

```powershell
psql "postgresql://<user>@172.20.8.212:5432/eturizam" `
  -c "\dn" `
  -c "select rolname from pg_roles where pg_has_role(current_user, oid, 'member') order by 1" `
  -c "select has_schema_privilege('rpj_dgu','USAGE') as rpj, has_schema_privilege('eturizam_test','USAGE') as etur, has_schema_privilege('str','USAGE') as str" `
  -c "select count(*) from pg_tables where schemaname='str_rn'"
```

Za dublju provjeru postoji `tools/CduProvjera.java` (pisan za CDU test): u jednom prolazu vrti
sve native upite i mjeri popunjenost stupaca. Prilagodba je promjena hosta u argumentima.

**Zamke:**
- `information_schema.tables` je filtriran po privilegijama i broji poglede → za stvarno stanje
  ide `pg_tables`.
- `to_regclass('rpj_dgu.zupanije')` **puca** ako nema `USAGE`; za sondiranje prava koristiti
  `has_schema_privilege`.
- Lozinku ne stavljati u `$env:PGPASSWORD` ni u argument — ostaje u historyju i u popisu procesa.

**Izlaz faze:** `tools/cdupreprod-bootstrap.sql` s točnim imenima shema/rola i popis grantova
koje treba tražiti od vlasnika (`gis_owner`, `tustart_owner`) — grantove **ne pišemo mi**.

## C3. Noćni reset — srce ove okoline

Ovo nema nijedna postojeća okolina i zato je najveći dio posla. Što se dogodi kad baza nestane
pod aplikacijom koja radi:

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
2. psql -f cdupreprod-bootstrap.sql      (CREATE SCHEMA IF NOT EXISTS + grantovi koje smijemo)
3. docker compose -f docker-compose.cdupreprod.yml restart backend
4. čekaj "Started StrBackendApplication" u logu, s timeoutom
5. smoke: /api/captcha/challenge + jedan upit koji čita str i rpj_dgu
6. zapiši ishod u /var/log/str-preprod-nightly.log
```

**Zamke:**
- **Ne dizati aplikaciju usred reset prozora.** `restart: unless-stopped` + pad na Liquibaseu =
  restart petlja. Zato korak 1 nije `sleep`, nego provjera.
- Liquibase lock: ako se aplikacija sruši usred migracije, u `databasechangeloglock` ostaje
  zaključan red. Kod nas je scenarij blaži (tablica se ionako briše resetom), ali ako reset
  **ne** dira `str_rn`, provjera lock-a mora u skriptu.
- Skripta mora biti **idempotentna** — pušta se i ručno, i po dva puta.
- Ako Darko potvrdi da se `str_rn` **može izuzeti** iz reseta (A1/4), cijela faza se svede na
  „restart ujutro" i podaci testera preživljavaju. **Prvo tražiti to.**

## C4. Repo rad — može odmah, ne čeka VPN

Grana → PR na `develop` (nikad push na `develop`, nikad PR na `main`).

1. `application-cdupreprod.properties` po uzoru na `cdu`: HTTPS cookie (`secure`,
   `same-site=none`), `forward-headers-strategy=framework`, `liquibase.contexts=cdupreprod`,
   svi URL-ovi kroz `${ENV:https://str-preprod-eturizam.gov.hr/...}`.
2. `docker-compose.cdupreprod.yml`: **`name: str-cdupreprod` je obavezan** (inače compose izvodi
   ime projekta iz direktorija i petlja se s drugim stackom), `env_file: .env.cdupreprod`,
   FE na `8085:80`, BE samo `expose` (jedan origin preko nginxa u FE kontejneru).
3. `.env.cdupreprod.example` — struktura `.env.preprod.example`, s naglaskom da **prazna
   vrijednost nije isto što i nepostavljena**.
4. `tools/cdupreprod-bootstrap.sql` + `tools/cdupreprod-nightly.sh`.
5. Dopuniti tablicu profila u `CLAUDE.md` (sad ih je pet, bit će šest) i `docs/DOKUMENTACIJA.md`.

**Zamka:** `VITE_API_URL` ide **bez** `/api` sufiksa — `backendApi.ts` ima
`baseURL = VITE_API_URL`, a putanje već počinju s `/api/…`. U repou postoji i suprotan primjer
(`docker-compose.cdu.yml` ima `/api`), ali to je referentna datoteka koja se na CDU ne izvršava.
Provjera poslije builda:
```powershell
Select-String -Path .\build\assets\*.js -Pattern "str-preprod-eturizam" | Select-Object -First 1
```

## C5. NIAS i gateway

- [ ] Odgovor na A2/10 → odabrani keystore na kutiju: `scp` u `/tmp`, pa
      `install -o root -g root -m 600 /tmp/<file> /srv/str-preprod/secrets/`
- [ ] `keytool -list -v` **lokalno** prije prijenosa — alias i Subject DN idu u `.env`;
      pogrešan alias daje `null` privatni ključ i kontekst pada na dizanju.
- [ ] Tražiti registraciju ACS/SLO za novu domenu:
      `https://str-preprod-eturizam.gov.hr/login/saml2/sso/nias` i `…/logout/saml2/slo/nias`
- [ ] Potvrda gateway pravila i TLS certifikata za novo ime (A3/13).

**Zamka:** ako registracija ne stigne na vrijeme, okolina se **svejedno diže** s
`NIAS_SAML_ENABLED=false` — radi sve osim prijave eGrađanima. To je bolji ishod od čekanja.

## C6. Prvi deploy

Ovisno o ishodu C1, jedan od dva puta:

**(a) Kutija ima mvn/npm/Docker Hub** → kao InfoDom preprod: `git pull` na kutiji, pa
`docker compose -f docker-compose.cdupreprod.yml --env-file .env.cdupreprod up -d --build`.

**(b) Kutija je odsječena (kao CDU test)** → lokalni build + `scp` artefakata + thin Dockerfile
na kutiji. Koraci 1-8 iz `DEPLOY-CDU.md`, uz dvije razlike: deploy je kao `root` (otpadaju sve
zamke s grupom `kodelab-d`) i `chmod -R a+rX` je **u imageu**, ne ručni korak.

## C7. Verifikacija

```bash
docker ps
docker logs str-backend-cdupreprod 2>&1 | grep startup_
curl -I http://localhost:8085
```

Blok `startup_*` odgovara na cijeli preflight odjednom:

| Linija | Mora pisati |
| :--- | :--- |
| `startup_config` | `profili=[cdupreprod]`, `liquibase_contexts=cdupreprod` |
| `startup_db current_user=… session_user=…` | `SET ROLE` je prošao (dva različita imena) |
| `startup_schema_ok tablica=str_rn.registration_number` | `redova=0` je **ispravno** nakon reseta |
| `startup_schema_ok tablica=str.facility` | pravi eTurizam podaci |
| `startup_schema_missing shema=rpj_dgu` (ERROR) | grant nije stigao → formular neće raditi |
| `startup_captcha hmac_key=postavljen` | `PRAZAN`/`UGRAĐENI DEFAULT` = `.env` nije primijenjen |
| `startup_mail enabled=false` | mora biti `false` |
| `startup_nias_urls acs=… slo=…` | usporediti s onim što je NIAS registrirao |

Iz browsera: `https://str-preprod-eturizam.gov.hr`, **uvijek Ctrl+F5** (statika ima
`immutable, 7 dana`, pa se inače servira stari `index.html` i vidi se bijeli ekran).

## C8. Automatizacija + primopredaja

- [ ] cron za `cdupreprod-nightly.sh`, log u `/var/log/`
- [ ] tri jutra zaredom provjeriti log prije nego se okolina proglasi stabilnom
- [ ] testerima napisati **što nakon reseta nestaje** (RB-ovi, skice, sesije) — inače to dolazi
      kao prijavljeni bug
- [ ] ovaj dokument prepisati iz plana u postupak + dodati `Update-only` odjeljak

---

# D. Zamke — naslijeđene i nove

| Simptom | Uzrok | Rješenje |
| :--- | :--- | :--- |
| Ujutro svaki upit puca, kontejner „Up" | Hikari drži konekcije na resetiranu bazu | restart backenda u nightly skripti |
| Puna migracija svaki dan, registar prazan | reset briše `str_rn` i `databasechangelog` | očekivano; tražiti izuzimanje `str_rn` iz reseta |
| `permission denied for schema rpj_dgu` (servis radi) | grant nestao s resetom | grant u bootstrap skriptu ili u njihov reset |
| `permission denied to set role` | user nije član role | A1/2 |
| `app.captcha.hmac-key is empty or unset` → backend ne starta | `CAPTCHA_HMAC_KEY` prazan ili nepostavljen | postaviti ključ (`openssl rand -base64 32`); `AltchaService` namjerno obara start umjesto da pusti tihi 500 na formularima |
| Prijava radi, odjava ne | posuđeni „InterniTurizam" certifikat | ne debugirati; vlastiti cert + registracija (A2) |
| Bijeli ekran, nginx log `Permission denied` | modovi datoteka iz Windows `scp`-a | `chmod -R a+rX` **u imageu** |
| `KeyError: 'ContainerConfig'` | `docker-compose` v1 1.29.2 | `down` prije `up -d --build` |
| Fronta gađa `localhost:8080` | `VITE_API_URL` nije zapečen u build | `Select-String` provjera nakon builda |
| `bad interpreter: /usr/bin/env bash^M` | `.sh` donesen s Windowsa s CRLF završecima | `dos2unix`, ili skriptu uzeti `git pull`-om na kutiji (Linux checkout daje LF); `.gitattributes` to čuva |
| `COPY target/*.jar`: „no source files were specified" | `.dockerignore` isključuje `target` | iznimka `!target/*.jar` (već dodana) |
| Deploy otišao na krivu kutiju | dva slična SSH aliasa | `cdu` = test/`mhangi`, `cdu-preprod` = novi/`root` |

# E. Definicija gotovog

1. `https://str-preprod-eturizam.gov.hr` vraća aplikaciju preko HTTPS-a.
2. `startup_*` blok bez ijednog ERROR-a (uključujući `rpj_dgu` i `eturizam_test`).
3. Registracijski formular prolazi end-to-end: adresna kaskada → RB → PDF.
4. Prijava eGrađanima radi, ili je svjesno ugašena uz zapisan razlog.
5. Okolina preživi **tri uzastopna noćna reseta** bez ručne intervencije.
6. Postupak je u ovom dokumentu, konfiguracija u repou, tajne samo na kutiji.
