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

## 2. Preflight na kutiji

Sve tri stavke ruše start ako padnu — provjeri ih **prije** nego oboriš dev.

```bash
ssh mhangi@s-str-02.infodom.hr

# a) produkcijski NIAS je dohvatljiv? (metadata se čita pri dizanju konteksta)
curl -sS -o /dev/null -w 'NIAS metadata: %{http_code}\n' https://nias.gov.hr/metadata
#   očekuj 200; vješanje ili 000 = firewall → aplikacija ne bi startala

# b) baza: shema, changelog, članstvo u roli
psql "postgresql://<user>@s-str-02.infodom.hr:5431/eturizam" \
     -c "\dn str_rn" \
     -c "select count(*) from str_rn.databasechangelog" \
     -c "select pg_has_role(current_user,'str_owner','member')"
```

Kako čitati (b):

- **`str_rn` ne postoji** → Liquibase pada; na `preprod` profilu shemu nitko ne kreira
  (`LocalDatabaseConfig` radi samo na `local`/`mock`). Riješi s
  `CREATE SCHEMA str_rn AUTHORIZATION str_owner;` pa ponovi.
- **`databasechangelog` postoji** → Liquibase samo validira checksume i primijeni zaostatak.
  Changeseti su nepromjenjivi nakon primjene; izmjena postojećeg = `ValidationFailedException`.
- **`pg_has_role` vraća `f`** → user nije član `str_owner`, pa konekcija s
  `options=-c role=str_owner` pada **već na handshakeu** (ne na prvom upitu). Traži da ga se doda
  u rolu; ne skidaj `options` iz URL-a jer bi zapisi išli u pogrešno vlasništvo.

## 3. Keystore na kutiju

```powershell
scp "$env:USERPROFILE\Downloads\keystore.p12" mhangi@s-str-02.infodom.hr:/tmp/nias-prod.p12
```

Pa na kutiji:

```bash
sudo install -o vviskov -g vviskov -m 640 /tmp/nias-prod.p12 /home/vviskov/str-secrets/nias-prod.p12
rm /tmp/nias-prod.p12
ls -l /home/vviskov/str-secrets/
```

Stari `nias-sp.jks` (dev, testni NIAS) ostaje — ne briši ga, treba dev okolini.

## 4. `git pull` + `.env.preprod` (kao vviskov)

```bash
sudo -iu vviskov
cd ~/str-deploy/str_backend
git pull --ff-only origin develop
git log -1 --format='%h %ci %s'          # potvrdi da je na zadnjem develop mergeu

cp .env.preprod.example .env.preprod
nano .env.preprod
#   PREPROD_DB_USERNAME=<isti kao CDU>
#   PREPROD_DB_PASSWORD=<ssh cdu; grep CDU_DB_PASSWORD ~/str-rn/str_backend/.env.cdu>
#   DRAFT_ENC_KEY=<openssl rand -base64 32 — NOVA vrijednost; skice iz dumpa su ovdje nebitne>
#   CAPTCHA_HMAC_KEY=<openssl rand -base64 32; generiraj jednom i NE mijenjaj>
#     NE ostavljaj prazan: nepostavljen ključ obori start s jasnom greškom, a PRAZAN pusti
#     start i tek /api/captcha/challenge vrati 500 — vidi komentar u .env.preprod.example
#   NIAS_KEYSTORE_PASSWORD=<lozinka keystorea>
#     (NIAS_ENTITY_ID i NIAS_KEY_ALIAS su već popunjeni u .example — vidi korak 0)

cd ~/str-deploy/str_frontend
git pull --ff-only origin develop
exit                                      # natrag na svoj račun
```

Prazna vrijednost NIJE isto što i nepostavljena — ključ koji ne postavljaš zakomentiraj.
Datoteka `.env.preprod` mora postojati, inače `docker compose up` puca na `env_file`.

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
| `permission denied to set role "str_owner"` | user nije član role (preflight 2b) |

Funkcionalna provjera:

```bash
curl -s http://s-str-02.infodom.hr:8086/api/captcha/challenge | head -c 200; echo
curl -s http://s-str-02.infodom.hr:8086/saml2/service-provider-metadata/nias | head -c 600; echo
```

SP metadata mora nositi **naš** `entityID` (Subject DN iz certifikata) i ACS na
`http://s-str-02.infodom.hr:8086/login/saml2/sso/nias`.

Da su podaci pravi, a ne mock:

```sql
select count(*) from str_rn.registration_number;   -- produkcijski RB-ovi
select count(*) from rpj_dgu.zupanije;             -- pravi registar (lokalni mock ima 4)
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
