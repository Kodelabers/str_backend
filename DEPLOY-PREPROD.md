# Deploy — predprodukcija (`s-str-02.infodom.hr`, profil `preprod`)

Predprodukcija **preuzima mjesto bivše dev okoline**: isti host, isti portovi (FE **8085**,
BE **8086**), isti checkout. Razlike prema dev-u su tri:

| | dev (bivše) | preprod |
| :--- | :--- | :--- |
| Baza | `str2` @ `…:5431` | **`eturizam` @ `…:5431`** (produkcijski dump), `currentSchema=str_rn` + `SET ROLE str_owner` |
| NIAS | ugašen (`NIAS_SAML_ENABLED=false`) | **produkcijski** `https://nias.gov.hr/metadata`, novi certifikat |
| eGOP | uključen (`http://egop2builder/EAI_MINT`) | **ugašen** (`EGOP_ENABLED=false`) |

| | |
| :--- | :--- |
| Kutija | `s-str-02.infodom.hr` (SSH; tvoj račun, npr. `mhangi`) |
| Profil | `preprod` (`application-preprod.properties`) |
| Frontend | `str-frontend-preprod` → **:8085** |
| Backend | `str-backend-preprod` → **:8086** |
| Compose | `docker-compose.preprod.yml` (u repou) + `.env.preprod` (na kutiji, nije u gitu) |
| Grana | `develop` |
| Build | build-on-box (`Dockerfile` radi `mvn package`; frontend Dockerfile radi vite build) |

## Cijena ovog deploya: gubi se eGOP testna okolina

`s-str-02` je **jedina** kutija s koje je eGOP dohvatljiv (`http://egop2builder/EAI_MINT`; CDU ga
ne vidi). Gašenjem dev stacka eGOP se prestaje moći testirati. `docker-compose.yml`, `.env.dev` i
`application-dev.properties` ostaju netaknuti, pa je povratak jednostavan:

```bash
cd /home/vviskov/str-deploy/str_backend
sudo docker compose -f docker-compose.preprod.yml down
sudo docker compose --env-file .env.dev up -d --build backend frontend
```

Ne mogu raditi istovremeno — dijele portove 8085/8086.

## Raspored na kutiji

Isti checkout kao dev (repo je već kloniran, GitHub creds keširane, `str_frontend` je susjedni
dir koji compose gleda kao build context):

```
/home/vviskov/str-deploy/
  str_backend/     ← git repo + docker-compose.yml (dev) + docker-compose.preprod.yml (preprod)
    .env.dev       ← dev tajne (ostaju, ne diraju se)
    .env.preprod   ← preprod tajne (nije u gitu)
  str_frontend/    ← build context za frontend
/home/vviskov/str-secrets/           ← montira se u /secrets:ro
  nias-sp.jks      ← stari (dev, testni NIAS) — ostaje
  nias-prod.p12    ← NOVI: produkcijski NIAS certifikat
```

## Pristup i dozvole (bitno)

- Repo je **`vviskov:vviskov`**, ti (npr. `mhangi`) nisi u toj grupi → git i `.env.preprod` radiš
  **kao vviskov**: `sudo -iu vviskov` (login shell → njegov HOME i GitHub creds).
- **Docker je zaključan na `ssporer`** (ni `vviskov` nije u docker grupi) → `docker` ide **preko
  `sudo`**. Ti si u `wheel`, pa `sudo` radi (traži lozinku).

## Koegzistencija — NE diraj tuđe

Na kutiji se vrti i zaseban stack **`str2-*`** (`str2-str-external-app` :8081,
`str2-str-internal-app` :8082, `str2-postgres-db` :5431, prometheus) te `jti-*` i `sdep-*`.
Diraš samo `str-*` kontejnere. Baza `eturizam` je na istom Postgresu (:5431) kao `str2`, ali je
druga baza; naš Liquibase upravlja **samo** shemom `str_rn`.

---

## 0. Prije svega — pročitaj novi certifikat (lokalno)

`nias.saml.entity-id` **mora** biti Subject DN našeg certifikata: NIAS ne čita SP metadatu, nego
ima hardkodiranu konfiguraciju vezanu uz certifikat, pa je certifikat identitet servisa.

```powershell
& "C:\Program Files\Java\jdk-21.0.10\bin\keytool.exe" -list -v `
  -keystore "$env:USERPROFILE\Downloads\keystore.p12" -storetype PKCS12 -storepass "<lozinka>"
```

**Već odrađeno 09.09.2026.** — keystore ima 4 unosa, ali samo **jedan `PrivateKeyEntry`**
(ostala tri su CA certifikati u lancu, i jedan istekli `eovlastenjaprod` trustedCertEntry koji
nam ne služi ničemu). Vrijednosti su upisane u `.env.preprod.example`:

| | |
| :--- | :--- |
| `NIAS_KEY_ALIAS` | `interniturizam (fina rdc 2020)` |
| `NIAS_ENTITY_ID` | `CN=InterniTurizam, L=ZAGREB, OID.2.5.4.97=HR87892589782, O=MINISTARSTVO TURIZMA I SPORTA, C=HR` |
| Izdavatelj | `CN=Fina RDC 2020` — **produkcijski** FINA CA (CDU-ov cert je s „Fina Demo CA 2020") |
| Vrijedi do | **08.11.2026.** |

Alias mora biti točan — `NiasSamlConfig` ga koristi i za certifikat i za privatni ključ, a
pogrešan alias daje `null` i kontekst pada na dizanju. Ponovi gornji `keytool` ako se keystore
ikad zamijeni.

### ⚠️ Ovo NIJE STR-ov vlastiti certifikat

Identitet je i dalje **posuđeni „InterniTurizam"** (isti Subject DN i isti OIB kao na CDU); nov
je samo CA — produkcijski `Fina RDC 2020` umjesto demo. Što to znači:

- **Prijava bi trebala raditi.** AuthnRequest nosi `AssertionConsumerServiceURL` i putuje preko
  preglednika, pa assertion sjedne na naš ACS. Produkcijski CA je ono što produkcijskom NIAS-u
  treba da prihvati potpis.
- **Odjava ne može raditi.** NIAS ne čita SP metadatu — ima hardkodiranu konfiguraciju vezanu uz
  certifikat i logout callback šalje na URL registriran uz taj cert, tj. InterniTurizamu. Ne
  trošiti vrijeme na debug; treba vlastiti FINA certifikat + zasebna registracija STR-a na
  NIAS-u (InfoDom / MINTS).
- **`STR_TIJELO_OIB` ostaje prazan.** `HR87892589782` je OIB posuđenog identiteta, ne MINTS-ov
  potvrđeni — akti bi ga inače nosili kao OIB tijela.
- **Rok: 08.11.2026.** Vlastiti certifikat tražiti prije toga, inače prijava prestane raditi.

## 1. Zeleno svjetlo + kod na `develop`

Kutija je dijeljena — kratko potvrdi s **vviskovom** da je slobodno i da zna da dev/eGOP okolina
ide dolje.

Preprod se deploya s grane `develop`. Provjeri da su izmjene mergeane (nova grana → PR →
`develop`; nikad push na `develop` ni PR na `main`).

## 2. Preflight — VPN i dohvatljivost

Sve tri stavke ruše start ako padnu — provjeri ih **prije** nego oboriš dev.

### 2a. Na InfoDom VPN, pa do kutije

Kutija `s-str-02.infodom.hr` je na InfoDomovoj mreži i **nije dostupna s interneta**. Treba
InfoDom **Sophos SSL VPN** (račun tipa `mhangi`). To je *druga* tajna od eGOP/NTLM lozinke i od
državnog VPN-a kojim se ide na CDU — ne miješati.

```bash
# s laptopa, nakon što je VPN gore:
nslookup s-str-02.infodom.hr
ssh mhangi@s-str-02.infodom.hr
```

Za `s-str-02` **nema** SSH aliasa u `~/.ssh/config` (tamo je samo `cdu` → 172.20.8.158), pa ide
puno ime hosta. Ako `ssh` visi, VPN nije gore ili je pao; ako `nslookup` ne razriješi a VPN je
gore, DNS ide mimo tunela.

> **Full-tunnel:** dok si na InfoDom VPN-u laptop zna izgubiti javni internet. To ne blokira
> deploy (build i `git pull` idu **s kutije**), ali blokira `git push` s laptopa — zato prvo
> pushaj granu, pa se spajaj na VPN.

### 2b. Vidi li kutija produkcijski NIAS?

```bash
curl -sS -o /dev/null -w 'NIAS metadata: %{http_code}\n' https://nias.gov.hr/metadata
```

Očekuj **200**; vješanje ili `000` = firewall. Ovo nije kozmetika: `NiasSamlConfig` dohvaća IdP
metadatu **pri dizanju konteksta**, pa nedohvatljiv `nias.gov.hr` znači da aplikacija uopće ne
krene — točno onako kako je `dev` padao na `niastst.fina.hr`. Ako je izlaz zatvoren, digni
okolinu s `NIAS_SAML_ENABLED=false` u `.env.preprod` (radi bez prijave) i traži otvaranje
prema `nias.gov.hr:443`.

### 2c. Baza: shema, changelog, članstvo u roli

**`psql` NIJE instaliran na kutiji**, a `apt install postgresql-client` traži admina — ne
instalirati samovoljno na dijeljenoj kutiji. Tri načina bez toga, po redu preferencije.

#### A. S laptopa (preferirano — ništa se ne instalira)

Dok je InfoDom VPN gore, port 5431 je dohvatljiv po imenu hosta (isto onako kako se spaja i
aplikacija). Na Windowsu je `psql` u `C:\Program Files\PostgreSQL\17\bin`.

Jedna komanda odgovara na cijelo pitanje — replicira **točno** ono što radi aplikacijski JDBC
URL, uključujući `options`:

```powershell
psql "postgresql://<user>@s-str-02.infodom.hr:5431/eturizam?options=-c%20role%3Dstr_owner" -c "select current_user, session_user"
```

Prođe li i vrati `current_user = str_owner`, konekcija sa `SET ROLE` radi. Padne li s
`permission denied to set role`, user nije član role.

Detaljnije:

```powershell
psql "postgresql://<user>@s-str-02.infodom.hr:5431/eturizam" `
  -c "\dn str_rn" `
  -c "select count(*) from str_rn.databasechangelog" `
  -c "select rolname from pg_roles where pg_has_role(current_user, oid, 'member') order by 1"
```

Zadnji upit ispisuje **sve** role kojih je user član — informativnije od `pg_has_role(...)`
boolean-a, i odmah se vidi kako se rola točno zove ako nije `str_owner`. Lozinku ne stavljati u
`$env:PGPASSWORD`; pustiti psql da pita.

#### B. Na kutiji, kroz postojeći Postgres kontejner

Postgres na :5431 je kontejner `str2-*` stacka, pa `psql` postoji *u njemu*:

```bash
sudo docker ps --format '{{.Names}}' | grep -i postgres        # nađi točno ime
sudo docker exec -i <ime-kontejnera> psql -U <user> -d eturizam \
  -c "select rolname from pg_roles where pg_has_role(current_user, oid, 'member') order by 1"
```

Iznutra ide preko lokalnog socketa, pa lozinka često nije potrebna (`trust`/`peer`).
**Samo read-only upiti** — to je tuđi stack, ne restartati ga i ne dirati mu podatke.

#### C. Zadnja opcija — pustiti Liquibase da kaže

Nije preflight (dev stack u tom trenutku već leži), ali je definitivno. Nakon `up` u logu:

| Zapis | Znači |
| :--- | :--- |
| `permission denied to set role "str_owner"` | user nije član role → tražiti da ga se doda |
| `FATAL: database "eturizam" does not exist` | dump nije na tom serveru |
| Liquibase pada na `default-schema` / `str_rn` ne postoji | `CREATE SCHEMA str_rn AUTHORIZATION str_owner;` |
| `Started StrBackendApplication` | sve troje je bilo u redu |

#### Kako čitati rezultat

- **`str_rn` ne postoji** → Liquibase pada; na `preprod` profilu shemu nitko ne kreira
  (`LocalDatabaseConfig` radi samo na `local`/`mock`). Riješi s
  `CREATE SCHEMA str_rn AUTHORIZATION str_owner;` pa ponovi.
- **`databasechangelog` postoji** → Liquibase samo validira checksume i primijeni zaostatak.
  Changeseti su nepromjenjivi nakon primjene; izmjena postojećeg = `ValidationFailedException`.
- **`SET ROLE` ne prolazi** → konekcija s `options=-c role=str_owner` pada **već na handshakeu**
  (ne na prvom upitu). Traži da se usera doda u rolu; **ne** skidaj `options` iz URL-a jer bi
  zapisi išli u pogrešno vlasništvo.

### 2d. Prava na vanjske sheme — izmjereno 09.09.2026.

Preflight na `eturizam` @ `s-str-02:5431` dao je ovo (user `shorttermrental`, član samo role
`str_owner`, `SET ROLE str_owner` radi):

| Shema | Owner | `USAGE` za `str_owner` | `CREATE` | Tablica (`pg_tables`) |
| :--- | :--- | :--- | :--- | :--- |
| `str_rn` | `str_owner` (naša) | ✅ | ✅ | 0 — **shema je prazna** |
| `str` | `tustart_owner` | ✅ (`str_owner=U/tustart_owner`) | ✗ (i treba tako — read-only) | 144 |
| | | | | *tablična prava: `SELECT` ✅ na `facility`/`subject`/`country`, `UPDATE` ❌* |
| `rpj_dgu` | `gis_owner` | ❌ | ✗ | 8 |
| `eturizam_test` | `gis_owner` | ❌ | ✗ | 9 |

**`str_rn` je prazan.** „Dump produkcije" je produkcija **eTurizma** (`str`), ne STR-ovih
podataka — naša shema tamo nikad nije postojala. Liquibase zato vrti **cijeli changelog od
nule**, a registar na preprodu starta **bez ijednog registracijskog broja**. To mijenja
očekivanja za testiranje: `select count(*) from str_rn.registration_number` je 0, i tako treba
biti. `CREATE` na `str_rn` je potvrđen, pa Liquibase prolazi.

#### `str` je čitljiv, ali nije zapisiv

`USAGE` na shemi sam po sebi ne daje `SELECT` na tablice, pa je i to izmjereno:
`has_table_privilege` vraća `t` za `str.facility`, `str.subject` i `str.country` — popis objekata
eTurizma, OIB lookup iznajmljivača i padajući izbornik država rade.

`UPDATE` na `str.facility` je **`f`**. To je odredište `FacilityRegistrationNumberWriteBack`-a
(upis izdanog RB-a natrag u eTurizam za objekt koji je došao kroz tuStart handoff). Nije
blokada — taj razred namjerno guta greške i logira `facility_writeback_failed`, a RB ostaje
izdan i valjan. Praktična posljedica: na preprodu se **tuStart handoff ne može testirati
end-to-end** jer RB ne dolazi natrag u `str.facility`.

Ako je taj tok u opsegu testiranja, od `tustart_owner`-a treba i:

```sql
GRANT UPDATE (registration_number) ON str.facility TO str_owner;
```

Grant je namjerno sužen na jedan stupac — write-back ne dira ništa drugo. Ako tok nije u
opsegu, ovo se preskače i u logu se ignorira `facility_writeback_failed`.

#### ⚠️ Blokada: nema pristupa adresnim registrima

`nspacl` za `rpj_dgu` i `eturizam_test` je **prazan** — nema nikakvih grantova osim implicitnih
ownerovih (ni `developers`). `to_regclass('rpj_dgu.zupanije')` pada s
`ERROR: permission denied for schema rpj_dgu`. Cijela adresna kaskada registracijskog formulara
čita odande:

| Entitet | Tablica |
| :--- | :--- |
| `CountyEntity` | `rpj_dgu.zupanije` |
| `MunicipalityEntity` | `rpj_dgu.gradovi_i_opcine` |
| `SettlementEntity` | `rpj_dgu.naselja` |
| poštanski broj | `rpj_dgu.postanski_brojevi` |
| `StreetEntity` | `eturizam_test.ar_ulice` |
| `HouseNumberEntity` | `eturizam_test.ar_address` |

**Aplikacija se digne normalno** — ništa od toga ne dira startup — i padaju tek padajući
izbornici za adresu. Tiha greška koja se otkriva na prvom testu formulara, pa je ovdje
zapisana.

Rješenje nije u našoj konfiguraciji (nema alternativne role: `shorttermrental` je član samo
`str_owner`), nego grant od `gis_owner`. **Precedent postoji** — `tustart_owner` je za svoju
shemu `str` dao točno to, pa se traži isto, a ne šira prava:

```sql
-- izvršava gis_owner (ili superuser); samo SELECT, te su sheme za nas read-only
GRANT USAGE  ON SCHEMA rpj_dgu       TO str_owner;
GRANT USAGE  ON SCHEMA eturizam_test TO str_owner;
GRANT SELECT ON ALL TABLES IN SCHEMA rpj_dgu       TO str_owner;
GRANT SELECT ON ALL TABLES IN SCHEMA eturizam_test TO str_owner;

-- da i buduće tablice budu čitljive bez ponovnog granta
ALTER DEFAULT PRIVILEGES FOR ROLE gis_owner IN SCHEMA rpj_dgu       GRANT SELECT ON TABLES TO str_owner;
ALTER DEFAULT PRIVILEGES FOR ROLE gis_owner IN SCHEMA eturizam_test GRANT SELECT ON TABLES TO str_owner;
```

Ponovna provjera nakon granta:

```powershell
psql "postgresql://shorttermrental@s-str-02.infodom.hr:5431/eturizam?options=-c%20role%3Dstr_owner" `
  -c "select has_schema_privilege('rpj_dgu','USAGE') as rpj, has_schema_privilege('eturizam_test','USAGE') as etur" `
  -c "select count(*) from rpj_dgu.zupanije" `
  -c "select count(*) from eturizam_test.ar_ulice"
```

**Deploy ne mora čekati grant.** Bez njega rade prijava (NIAS), javna verifikacija, pregled
registra i akti; ne radi samo unos nove registracije preko formulara. Ako se ide bez granta,
to treba reći testerima unaprijed, inače će prijaviti „formular ne radi" kao bug.

#### Korisni upiti koje treba znati

- `information_schema.tables` je **filtriran po privilegijama** i broji i poglede — nije mjera
  postojanja. Za pravo stanje ide `pg_tables` (samo tablice, bez filtriranja). Odatle i razlika
  `str`: 154 u `information_schema` vs 144 u `pg_tables` = 10 pogleda.
- `to_regclass('shema.tablica')` vraća `NULL` umjesto greške kad tablice nema — ali **pada** ako
  na shemi nema `USAGE`. Za sondiranje prava koristiti `has_schema_privilege(...)`, koja čita
  katalog i ne traži `USAGE`.
- `nspacl` + `pg_get_userbyid(nspowner)` daju točan zahtjev za DBA-a (kome je što već dano),
  umjesto traženja širokih prava.


## 3. Keystore na kutiju

Prijenos ide **s laptopa preko VPN-a**; keystore nikad ne ulazi u git (`.gitignore` blokira
`*.p12`).

```powershell
# s laptopa (PowerShell), VPN gore:
scp "$env:USERPROFILE\Downloads\keystore.p12" mhangi@s-str-02.infodom.hr:/tmp/nias-prod.p12
```

Pa na kutiji — `install` u jednom koraku postavlja vlasnika i prava, da datoteka ne ostane
čitljiva svima:

```bash
sudo install -o vviskov -g vviskov -m 640 /tmp/nias-prod.p12 /home/vviskov/str-secrets/nias-prod.p12
rm /tmp/nias-prod.p12
ls -l /home/vviskov/str-secrets/
```

Provjeri da je datoteka prenesena neoštećeno — usporedi checksum s onim izmjerenim lokalno
09.09.2026.:

```bash
sha256sum /home/vviskov/str-secrets/nias-prod.p12
# očekuj: f3284f082fd69ecae0de3041af21260eb15261a587dab7df08c9b2f80dfcaf90
# velicina: 11132 bajta
```

Ovako se lozinka ne pojavljuje ni u `ps` ni u shell historyju kutije. Sadržaj keystorea je
ionako već provjeren lokalno (korak 0), a da se privatni ključ stvarno učitava potvrdit će
startup u koraku 6 — ako alias ili lozinka ne odgovaraju, kontekst pada na dizanju.

Stari `nias-sp.jks` (dev, testni NIAS) **ostaje** — ne briši ga, treba dev okolini kad se vraća
na eGOP.

## 4. `git pull` + `.env.preprod` (kao vviskov)

```bash
sudo -iu vviskov
cd ~/str-deploy/str_backend
git pull --ff-only origin develop
git log -1 --format='%h %ci %s'          # potvrdi da je na zadnjem develop mergeu
ls docker-compose.preprod.yml .env.preprod.example    # obje moraju postojati nakon pulla

cp .env.preprod.example .env.preprod
nano .env.preprod
```

Upisuje se **pet** vrijednosti; ostalo je u predlošku već popunjeno i ne dira se.

| Ključ | Odakle | Napomena |
| :--- | :--- | :--- |
| `PREPROD_DB_USERNAME` | s CDU kutije | `ssh cdu; grep CDU_DB_USERNAME ~/str-rn/str_backend/.env.cdu` (vjerojatno `shorttermrental`) |
| `PREPROD_DB_PASSWORD` | s CDU kutije | `ssh cdu; grep CDU_DB_PASSWORD ~/str-rn/str_backend/.env.cdu`; rezerva: `.env.cdu.bak.*` ili `docker exec str-backend-cdu env \| grep CDU_DB` |
| `NIAS_KEYSTORE_PASSWORD` | Simon / FINA | lozinka za `nias-prod.p12` |
| `DRAFT_ENC_KEY` | generiraj | `openssl rand -base64 32` — **nova** vrijednost; skice iz dumpa su ovdje nebitne |
| `CAPTCHA_HMAC_KEY` | generiraj | `openssl rand -base64 32` — generiraj jednom i **ne** rotiraj |

`NIAS_ENTITY_ID` i `NIAS_KEY_ALIAS` su **već popunjeni** u predlošku (vidi korak 0) — ne treba
ih tipkati; pogrešno prepisan DN je najčešći način da NIAS ne prepozna servis.

**Prazna vrijednost NIJE isto što i nepostavljena** — ključ koji ne postavljaš zakomentiraj.
Najgadnija posljedica te razlike je `CAPTCHA_HMAC_KEY`: nepostavljen obori start s jasnom
greškom, a **prazan pusti start** i tek `GET /api/captcha/challenge` vrati 500, pa su sva 4
javna formulara mrtva bez ijedne greške u startup logu.

Datoteka `.env.preprod` **mora postojati** — bez nje `docker compose up` puca na `env_file`.

Na kraju frontend, pa natrag na svoj račun:

```bash
cd ~/str-deploy/str_frontend
git pull --ff-only origin develop
exit
```

### 4b. URL-ovi — što je efektivno i kad se dira

URL-ovi **nisu** u `.env.preprod`; dolaze iz defaulta u `application-preprod.properties` i iz
`environment:` bloka u `docker-compose.preprod.yml`. Efektivno stanje:

| Namjena | Vrijednost |
| :--- | :--- |
| IdP metadata | `https://nias.gov.hr/metadata` |
| SSO odredište (AuthnRequest) | `https://nias.gov.hr/sso-http` — **iz metadate, ne konfigurira se** |
| SLO odredište | `https://nias.gov.hr/ssout-http` (+ SOAP `…/ssout-soap`) — isto iz metadate |
| Naš ACS | `http://s-str-02.infodom.hr:8086/login/saml2/sso/nias` |
| Naš SLO (HTTP) | `http://s-str-02.infodom.hr:8086/logout/saml2/slo/nias` |
| Naš SLO (SOAP) | `http://s-str-02.infodom.hr:8086/logout/saml2/soap/nias` |
| Nakon prijave | `http://s-str-02.infodom.hr:8085/registration-number` |
| Nakon neuspjele prijave | `http://s-str-02.infodom.hr:8085/?nias_error=true` |
| Nakon odjave | `http://s-str-02.infodom.hr:8085/` |
| CORS / frontend base | `http://s-str-02.infodom.hr:8085` |
| Frontend → backend (`VITE_API_URL`) | `http://s-str-02.infodom.hr:8086` (build arg, ne runtime) |

Dirati ih treba **samo** ako NIAS uz certifikat ima registrirane druge — tada se u
`.env.preprod` odkomentiraju `NIAS_ACS_URL` / `NIAS_SLO_URL` / `NIAS_*_REDIRECT_URL` i upišu te
vrijednosti. Mijenja li se port ili host, mijenja se i `VITE_API_URL` u
`docker-compose.preprod.yml`, jer je to **build-time** argument — sam restart ga ne mijenja,
treba `--build`.

## 5. Oboriti dev, dignuti preprod

Portovi 8085/8086 se ne mogu dijeliti, pa je redoslijed obavezan.

```bash
cd /home/vviskov/str-deploy/str_backend
sudo docker compose --env-file .env.dev down
sudo docker ps --format '{{.Names}}' | grep -E '^str-(backend|frontend)$' || echo "dev je dolje"

sudo docker compose -f docker-compose.preprod.yml --env-file .env.preprod up -d --build
```

`docker-compose.preprod.yml` ima `name: str-preprod`, pa je zaseban compose projekt i ne petlja
se s dev projektom u istom direktoriju.

## 6. Provjera

```bash
sudo docker ps --format '{{.Names}}  {{.Status}}  {{.Ports}}' | grep preprod
sudo docker logs str-backend-preprod 2>&1 | tail -60
sudo docker logs -f str-backend-preprod                # live (Ctrl+C za izlaz)
```

U logu **očekuj**:

- `The following 1 profile is active: "preprod"`
- Liquibase bez ijednog `context="local"` changeseta
- `Loading eGOP MOCK!`
- `Started StrBackendApplication`

U logu se **ne smije** pojaviti:

| Zapis | Znači |
| :--- | :--- |
| `NonEuTestLessorSeeder` | profil je pogrešan (`dev`, a ne `preprod`) — seeder je `@Profile({"local","mock","dev"})` |
| `043-seed-dev-activity` / `120-seed-cdu-activity` | Liquibase kontekst je pogrešan; demo seedovi ulaze u produkcijski dump |
| `egop_endpoints base=` ili `EgopRetryJob` | eGOP nije ugašen |
| `app.captcha.hmac-key must be overridden` | `CAPTCHA_HMAC_KEY` nije postavljen (vrijedi ugrađeni default) |
| `Could not resolve placeholder 'NIAS_ENTITY_ID'` | korak 0 nije odrađen |
| `UnrecoverableKeyException` ili NPE na keystoreu | pogrešan `NIAS_KEY_ALIAS` ili lozinka keystorea |
| `permission denied to set role "str_owner"` | user nije član role (preflight 2c) |

Funkcionalna provjera:

```bash
curl -s http://s-str-02.infodom.hr:8086/api/captcha/challenge | head -c 200; echo
curl -s http://s-str-02.infodom.hr:8086/saml2/service-provider-metadata/nias | head -c 600; echo
```

SP metadata mora nositi **naš** `entityID` (Subject DN iz certifikata) i ACS na
`http://s-str-02.infodom.hr:8086/login/saml2/sso/nias`.

Da je Liquibase odradio i da su vanjski podaci pravi:

```sql
-- naša shema: ~60 tablica nakon prvog runa (prije njega je str_rn bio PRAZAN)
select count(*) from pg_tables where schemaname = 'str_rn';

-- registar starta PRAZAN: dump je produkcija eTurizma, ne STR-ovih podataka.
-- 0 je ispravan rezultat, ne znak da nešto ne radi.
select count(*) from str_rn.registration_number;

-- eTurizam objekti — pravi podaci (str shema, 144 tablice u dumpu)
select count(*) from str.facility;

-- adresni registar: RADI SAMO NAKON GRANTA od gis_owner (vidi §2d).
-- Bez granta: ERROR: permission denied for schema rpj_dgu — očekivano, nije bug.
select count(*) from rpj_dgu.zupanije;
```

## 7. Iz browsera

```
http://s-str-02.infodom.hr:8085
```

„Prijava putem eGrađana" → redirect na `https://nias.gov.hr/sso-http` → nakon prijave povratak na
`/registration-number`.

### NIAS preko HTTP-a — poznat rizik

Okolina je na **plain HTTP-u**, a NIAS na naš ACS šalje **cross-site POST**
(`nias.gov.hr` → `s-str-02.infodom.hr`). Spring drži AuthnRequest u `HttpSession`, pa sesijski
cookie mora doći uz taj POST:

- **Ne** postavljaj `server.servlet.session.cookie.same-site=none` (kao na CDU) — `SameSite=None`
  zahtijeva `Secure`, a `Secure` cookie preko HTTP-a browser odbacuje, pa prijava zajamčeno ne bi
  radila. Profil ga zato namjerno ne postavlja.
- S nepostavljenim atributom browseri primjenjuju `Lax`, a Chrome uz „Lax+POST" iznimku (cookie
  mlađi od 2 min) takav POST propušta. Firefox i Safari se ponašaju drukčije.
- Padne li prijava na `InResponseTo` ili „saved request not found" → **to je ovaj problem**, a ne
  konfiguracija URL-ova. Rješenje je HTTPS pred okolinom (kao CDU gateway), ne petljanje po
  cookie atributima.

### Ako NIAS odbije prijavu ili odjavu

NIAS ignorira URL-ove koje pošaljemo i callback vraća na one **registrirane uz certifikat**. Ako
prijava/odjava ne prođe iako je metadata učitana i SP metadata ispravna, to je registracijski
gap — pitanje za **Simona**, ne bug u kodu. Tada se u `.env.preprod` odkomentiraju `NIAS_ACS_URL`
i `NIAS_SLO_URL` i upišu vrijednosti koje je NIAS registrirao.

## Napomene

- **eGOP ugašen ≠ ništa se ne urudžbira.** `RnIssuedListener` i `RnLifecycleFilingListener` nisu
  vezani na `EGOP_ENABLED`, pa tok prolazi kroz `EgopClientMock` i upisuje `MOCK-` prefiksiranu
  KLASU/URBROJ u `submission.egop_klasa` / `filing_number` te redove u `str_rn.egop_pismeno`.
  Prefiks postoji upravo da se lažni brojevi razlikuju od pravih.
- **Cron poslovi mutiraju kopiju od prvog dana.** `SuspensionDeadlineJob` (`0 0 1 * * *`, cron
  nije konfigurabilan) prevodi `SUSPENSION_PROPOSED` → `SUSPENDED` za sve prave RB-ove kojima je
  rok prošao, što okida render akta i mock urudžbiranje. `DraftCleanupJob` briše skice starije od
  30 dana, `AccommodationActivityPurgeJob` čisti aktivnost. `WithdrawnRnRetentionJob` je samo
  detekcija (upisuje `RETENTION_DUE`, ne briše). Sve ostaje unutar kopije; **ugašena e-pošta je
  ono što sprječava vanjski efekt** — zato `APP_MAIL_ENABLED=false` ne pali bez izričite odluke.
- **Full-tunnel VPN:** dok si na InfoDom VPN-u laptop zna izgubiti javni internet. Deploy
  svejedno radi jer `git pull` ide s kutije, ne s laptopa.

## Update-only (drugi put nadalje)

```bash
ssh mhangi@s-str-02.infodom.hr
sudo -iu vviskov -- bash -c 'cd ~/str-deploy/str_backend && git pull --ff-only origin develop'
sudo -iu vviskov -- bash -c 'cd ~/str-deploy/str_frontend && git pull --ff-only origin develop'
cd /home/vviskov/str-deploy/str_backend
sudo docker compose -f docker-compose.preprod.yml --env-file .env.preprod up -d --build
sudo docker compose -f docker-compose.preprod.yml logs -f backend
```
