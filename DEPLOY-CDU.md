# CDU deploy — str-test-eturizam.gov.hr

Server: `172.20.8.158` (CDU intranet, **državni VPN obavezan** — on odsijeca ostatak interneta).
Javno: `https://str-test-eturizam.gov.hr`.

CDU server ima docker + osnovne base imageove (`eclipse-temurin:21-jre-jammy`, `nginx:alpine`)
već cachirane, ali NEMA maven/npm i nema pristupa Docker Hubu za nove pulls. Zato buildamo
artefakte lokalno i preko `scp` prebacujemo na server; docker tamo samo `COPY`-a artefakte u
thin imageove.

> **Prije prvog deploya pročitaj „Zamke" na dnu.** Četiri od pet kvarova koje smo prošli
> 17.09.2026. nemaju veze s kodom — svi su prava na datotekama, stari `docker-compose` ili
> zaostalo smeće u build kontekstu. Ondje su simptomi doslovno kako izgledaju u terminalu.

## Tko deploya i čime

Kutija ima više korisnika; direktorij je u **tuđem home-u** (`/home/vviskov/str-rn`), a ostali
pišu kroz grupu `kodelab-d`. To je izvor većine problema ispod.

```
~/.ssh/config
Host cdu
    HostName 172.20.8.158
    User mhangi              # ← alias se spaja kao mhangi, NE kao vviskov
```

Ako u nekom starijem zapisu vidiš `scp ... vviskov@172.20.8.158:`, to je deploy **vlasnikovim**
računom. Oba rade, ali ostavljaju datoteke različitih vlasnika — i upravo tako nastaje kvar
„jučer je radilo, danas ne".

## Server layout

```
~/str-rn/                              (= /home/vviskov/str-rn)
  str_backend/
    docker-compose.cdu.yml   (server-only kopija; ima dockerfile: Dockerfile.cdu)
    Dockerfile.cdu           (server-only; eclipse-temurin:21-jre-jammy + COPY jar)
    target/
      str-backend-0.0.1-SNAPSHOT.jar    ← scp destinacija
    .env.cdu                            (secreti + NIAS URL-ovi; server-only)
  str_frontend/
    Dockerfile.cdu           (server-only; nginx:alpine + COPY build/)
    nginx.conf.template
    nginx-envsubst-filter.envsh
    build/                              ← scp destinacija
```

`Dockerfile.cdu` i server-side `docker-compose.cdu.yml` **ZASAD NISU u repou** — postoje samo na
serveru. To nije kozmetika: repo ima **drugi** `Dockerfile` (node:20-alpine, `npm ci`,
`npm run build`) i **drugi** `docker-compose.cdu.yml` (context `../str_frontend`, VITE build
argumenti). Tko dijagnosticira CDU po repo datotekama, gleda krivu okolinu — dogodilo se
17.09.2026. TODO: reconciliati u PR-u i uklopiti u redovan flow.

Praktična posljedica: na CDU se **VITE varijable ne prosljeđuju kroz compose**. Zapečene su u
lokalnom buildu (korak 2). Zato je ovo upozorenje pri buildu očekivano i **nije** greška:

```
[Warning] One or more build-args [VITE_API_URL VITE_NIAS_ENABLED VITE_USE_MOCK] were not consumed
```

## 1. Lokalno — build BE jar

```powershell
cd C:\Users\MladenHangi\str_backend
mvn package -DskipTests
```

## 2. Lokalno — build FE

```powershell
cd C:\Users\MladenHangi\str_frontend
$env:VITE_API_URL="https://str-test-eturizam.gov.hr"
$env:VITE_USE_MOCK="false"
$env:VITE_NIAS_ENABLED="true"
npm run build
```

**Bitno:** `VITE_API_URL` je BEZ `/api` sufiksa — nginx u frontend containeru rutira bez tog
prefiksa.

Provjera da je adresa stvarno zapečena (inače fronta na CDU gađa `localhost:8080`):

```powershell
Select-String -Path .\build\assets\*.js -Pattern "str-test-eturizam" | Select-Object -First 1
```

## 3. Lokalno — scp artefakti na server

```powershell
scp C:\Users\MladenHangi\str_backend\target\str-backend-0.0.1-SNAPSHOT.jar cdu:~/str-rn/str_backend/target/
scp -r C:\Users\MladenHangi\str_frontend\build\* cdu:/home/vviskov/str-rn/str_frontend/build/
```

Dva detalja koja štede sat vremena:

- **Kopiraj `build\*`, ne `build`.** Kad kopiraš samu mapu, scp joj na kraju pokušava postaviti
  mod i vrijeme (`setstat`), a to smije samo vlasnik — dobiješ
  `remote setstat "...": Permission denied` iako je upis prošao.
- **`scp` ne briše zaostale datoteke.** Stari `index-*.js` se gomilaju. Prije kopiranja:
  `ssh cdu 'rm -rf /home/vviskov/str-rn/str_frontend/build/*'`

## 4. SSH na server

```powershell
ssh cdu
```

## 5. Server — provjeri domenu u compose i env (samo prvi put nakon domenskog rename-a)

Ako server compose/env još drži staru domenu (`str-test.eturizam.gov.hr`), zamijeni:

```bash
sed -i 's/str-test\.eturizam\.gov\.hr/str-test-eturizam.gov.hr/g' ~/str-rn/str_backend/docker-compose.cdu.yml
sed -i 's/str-test\.eturizam\.gov\.hr/str-test-eturizam.gov.hr/g' ~/str-rn/str_backend/.env.cdu
grep str-test ~/str-rn/str_backend/.env.cdu
```

*NIAS SP registracija mora pratiti domenu — provjeri s NIAS timom prije zamjene `.env.cdu` NIAS URLova.*

## 5b. Server — env varijable (jednokratno, uz eGOP/ZUP verziju)

`.env.cdu` **živi samo na serveru**, u `~/str-rn/str_backend/`. Gitignoriran je i nikad ne
dolazi iz repoa — uređuje se preko SSH-a. Nema UI-ja ni secret managera.

Uz uredsko poslovanje dolaze novi ključevi kojih postojeći `.env.cdu` nema. Predložak s
komentarima je u repou (`.env.cdu.example`), a na server ide ovako:

```bash
ssh cdu
cd ~/str-rn/str_backend
cp .env.cdu .env.cdu.bak.$(date +%F)      # uvijek prvo kopija — datoteka nije nigdje drugdje
nano .env.cdu
```

Dopiši na kraj:

```bash
# --- uredsko poslovanje: identitet tijela na aktima (čl. 98. st. 2 ZUP-a) ---
# Bez ovih vrijednosti svaki akt vidljivo ispisuje "[nije konfigurirano: ...]".
# Naziv otvara rečenicu uvoda akta, pa ide velikim POČETNIM slovom i ostatkom malim:
# Ministarstvo turizma i sporta. NE cijeli velikim slovima — zaglavlje i potpis ga sami
# pišu velikim. Mali početak znači da akt počinje malim slovom; StartupDiagnostics
# to prijavi WARN-om startup_documents_naziv_pocinje_malim_slovom.
STR_TIJELO_NAZIV=Ministarstvo turizma i sporta
# OIB i propis o nadležnosti imaju default u application.properties, iz predloška akta koji je
# MINT poslao 11.09.2026. NE postavljati ih ovdje praznima — prazna vrijednost pregazi default.
# STR_TIJELO_OIB=87892589782
STR_TIJELO_ADRESA=Prisavlje 14
STR_TIJELO_MJESTO=Zagreb
STR_TIJELO_USTROJ=Uprava za turizam
# Propis o nadležnosti: default je tekst iz predloška MINT-a; brojevi Narodnih novina još nedostaju.
# STR_TIJELO_PROPIS=
# Propis po vrsti akta (T14): dodjela čl. 46. st. 3., ostali akti čl. 48. st. 2. — default u
# application.properties; postaviti tek kad stignu brojevi Narodnih novina.
# STR_PROPIS_DODJELA=
# STR_PROPIS_POSTUPCI=
STR_POTPISNIK_IME=
# Funkcija se u ugradenom potpisu NE ispisuje (potpis je naziv tijela).
# STR_POTPISNIK_FUNKCIJA=Voditelj postupka
STR_EPECAT_ENABLED=false
STR_DOCUMENTS_RELOAD=false

# --- eGOP ---
# Prazno od 22.10.2026. (MINT je unio sve vrste pismena) — sve se urudžbira.
# T12 (postupak povlačenja) dodaje tri izlazne vrste koje MINT još nije unio: prijedlog-povlacenja,
# obustava-povlacenja, reaktivacija-povlacenja. Na CDU-u je eGOP mock, pa ništa ne treba. Uz
# EGOP_ENABLED=true i bez EGOP_SIFRA_PISMENA_FALLBACK do unosa staviti ih ovdje (zarezom odvojeno).
# Očitovanje i prigovor iznajmljivača (studeni 2026.) su četiri nove ULAZNE vrste: ocitovanje-suspenzije,
# prigovor-suspenzije, ocitovanje-povlacenja, prigovor-povlacenja. Uz mock ide isti tok (pismeno se vidi u
# logu kao egop_podnesak_filed i, s URBROJ-em, u popisu dokumenata RB-a). Uz EGOP_ENABLED=true zadati im ulaznu vrstu
# (EGOP_SIFRA_PISMENA_<SLUG> / EGOP_NAZIV_PISMENA_<SLUG>) ili ih staviti ovdje — fallback je izlazni.
EGOP_AKTI_BEZ_SIFRE=
# Vrsta priloga uz pismena (T17): šifra ili naziv iz MDM šifrarnika vrsta priloga. Na CDU-u je eGOP
# mock pa se ne koristi; uz EGOP_ENABLED=true provjerava se pri startu (startup_egop_prilog_unresolved
# = akti idu bez priloga). Naziv ne ostavljati praznim. Vidi DEPLOY-CDU-PREPROD.md §B5a.2.
#EGOP_SIFRA_PRILOGA=
#EGOP_NAZIV_PRILOGA=Prilog

# --- obavijesti e-poštom (relay servmail.ssc.gov.hr:25 i pošiljatelj su default u application-cdu.properties) ---
APP_MAIL_ENABLED=true
# Obavezno uz true: baza nosi stvarne adrese iznajmljivača. Sve poruke idu ovamo, stvarni
# primatelj je u predmetu „[za: …]". U logu: startup_mail enabled=true … redirect_to=<adresa>.
APP_MAIL_REDIRECT_TO=ime.prezime@kodelab.hr
# Najveća kodirana poruka koju relay prima (default 20MB). Prilozi akta koji s aktom ne stanu samo se
# navode u poruci; poruka koju relay odbije zbog veličine (552) odmah ide s popisom priloga.
#APP_MAIL_MAX_MESSAGE_SIZE=20MB
# Adresa korisničkog pretinca za poveznicu u porukama (apsolutni http(s) URL). Nepostavljeno = bez poveznice.
#APP_MAIL_KP_URL=
```

**Ključ koji ne postavljaš zakomentiraj, nemoj ostaviti prazan** — prazna vrijednost je
postavljena vrijednost i pregazi default iz `application.properties`. Iznimka su ključevi
čiji je default ionako prazan (`STR_POTPISNIK_IME`). **`STR_TIJELO_OIB` i `STR_TIJELO_PROPIS` od
18.09.2026. imaju default** — `.env.cdu` koji ih postavlja praznima treba ispraviti, inače akti i
dalje nose „[nije konfigurirano]".

Postavke koje CDU drži u `application-cdu.properties` (ne treba ih u `.env.cdu`, ali je dobro
znati da postoje): prazan `str.egop.mock.filing-prefix` i `app.cadastral.enabled=true`.

**Zahtjev se od 22.10.2026. prikazuje u popisu dokumenata po RB-u** (uz dodjelu i sve akte
životnog ciklusa) — `str.rn.documents.zahtjev-visible=false` je maknut iz profila. Ako `.env.cdu`
na kutiji još ima `RN_DOCUMENTS_ZAHTJEV_VISIBLE=false`, ukloni ga, inače zahtjev ostaje skriven.

### Serverski compose mora propustiti nove ključeve

Server ima **vlastiti** `docker-compose.cdu.yml` (vidi napomenu u §Server layout). Njegov
`environment:` blok je allowlist — ključ koji nije ondje ne ulazi u kontejner, koliko god
puta ga upisao u `.env.cdu`. Umjesto nabrajanja svakog novog ključa, dodaj servisu jedan
redak:

```bash
nano ~/str-rn/str_backend/docker-compose.cdu.yml
```

```yaml
  backend:
    ...
    env_file:
      - .env.cdu          # <— dodaj; sve iz .env.cdu ulazi u kontejner
    environment:
      ...                 # ostaje kako je; ima prednost nad env_file
```

Provjera da je stvarno stiglo, nakon `up`:

```bash
docker exec str-backend-cdu env | grep -E "STR_TIJELO|EGOP_" | sort
```

Ako `STR_TIJELO_NAZIV` nije na popisu, `env_file` nije primijenjen i akti će nositi oznaku
„nije konfigurirano".

Nakon starta isto piše i u logu, pa ne treba čekati prvi izdani akt:

```bash
docker logs str-backend-cdu 2>&1 | grep startup_documents
```

Očekivano: `oib=postavljen propis=postavljen epecat=false`. Stoji li `PRAZAN (pregazio je
default — provjeri .env)`, ključ je u `.env.cdu` postavljen na prazno i treba ga zakomentirati.

## 6. Server — prava na artefaktima (prije builda!)

`scp` s Windowsa donosi modove koje nginx u kontejneru ne može pročitati — Windows nema POSIX
prava. `COPY` ih prenese u image kakvi jesu, nginx radi kao uid 101 i svaki asset vrati 404.
Fronta se tada digne kao **bijeli ekran**.

```bash
chmod -R a+rX ~/str-rn/str_frontend/build
chmod a+r     ~/str-rn/str_backend/target/str-backend-0.0.1-SNAPSHOT.jar
```

`a+rX` = čitanje svima, a veliko `X` dodaje ulazak samo mapama, ne i datotekama.

Ako datoteke nisu tvoje pa `chmod` padne, isti posao kroz kontejner (radi kao root):

```bash
docker run --rm -v /home/vviskov/str-rn/str_frontend:/ctx nginx:alpine \
  sh -c 'chmod -R a+rX /ctx/build'
```

> **Trajno rješenje** je jedan redak u server-only `Dockerfile.cdu`, odmah iza `COPY build/`:
> ```dockerfile
> RUN chmod -R a+rX /usr/share/nginx/html
> ```
> Time image sam popravlja prava i deploy više ne ovisi o tome kakve ih je scp donio.

## 7. Server — down + rebuild + up

```bash
cd ~/str-rn/str_backend
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml down
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml up -d --build
```

Server ima **stari `docker-compose` v1** (`docker-compose` s crtom, ne `docker compose` sa
razmakom). Poznati bug u v1 1.29.2: `KeyError: 'ContainerConfig'` na `up -d --build` kad postoje
stari containeri. `down` prije `up -d --build` to zaobilazi — **nemoj preskočiti `down`**.

Ako i dalje pada:

```bash
docker rm -f str-frontend-cdu str-backend-cdu
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml up -d --build
```

## 8. Server — provjera

```bash
docker ps                                        # str-backend-cdu + str-frontend-cdu moraju biti Up
curl -I http://localhost:8085                    # očekivan 200 OK
docker exec str-frontend-cdu ls -l /usr/share/nginx/html/assets   # mora biti -rw-r--r--
docker logs str-backend-cdu 2>&1 | tail -30      # očekuj "Started StrBackendApplication"
docker logs str-backend-cdu 2>&1 | grep startup_documents   # identitet tijela na aktima
docker logs str-frontend-cdu 2>&1 | tail -20     # NE smije biti "Permission denied"
docker logs -f str-backend-cdu                   # live praćenje (Ctrl+C za izlaz)
```

Najbrža potvrda da je fronta stvarno poslužena, a ne samo `index.html`:

```bash
curl -sI http://localhost:8085/assets/$(docker exec str-frontend-cdu ls /usr/share/nginx/html/assets | grep '\.js$') | head -1
```

Mora vratiti `HTTP/1.1 200 OK`. Ako vrati `404`, pogledaj `docker logs str-frontend-cdu` — kad
ondje piše `stat() ... failed (13: Permission denied)`, preskočen je korak 6.

### 8b. e-Ovlaštenja (djelovanje u ime pravne osobe)

Uključena su u `application-cdu.properties` (`app.eovlastenja.enabled=true`, FINA **test**).
U `.env.cdu` i `/secrets` ne treba ništa novo: klijentski certifikat je NIAS keystore
(`/secrets/keystore.p12`, isti alias i lozinka), a potpisni certifikat e-Ovlaštenja i CA-ovi su
javni i dolaze u jaru (`src/main/resources/eovlastenja/`). Tok i endpointi: `docs/EOVLASTENJA.md`.

Svi retci ispod su bez osobnih podataka (nema OIB-ova, imena ni vrijednosti `sesija_id`).
Paket `com.str.backend.registries.eovlastenja` je na CDU-u na **DEBUG** (`EOVLASTENJA_LOG_LEVEL`),
da se uz svaki odgovor vidi i njegov oblik; poslije prvih provjera staviti `INFO`.

**1. Start** — klijent se slaže na startu (fail-fast, kao eGOP): neučitljiv keystore ili certifikat
znači da backend **ne krene**.

```bash
docker logs str-backend-cdu 2>&1 | grep -E "eovlastenja(_mtls|_trust|_tls| url=)"
```

| Redak | Očekivano |
| :--- | :--- |
| `eovlastenja_mtls alias=… client_cert=… issuer=… valid_until=…` | `client_cert` = CN=InterniTurizam…, `issuer` = Fina Demo CA 2020 — **ovaj** certifikat e-Ovlaštenja moraju poznavati |
| `eovlastenja_trust anchors=N (jdk=…, extra=3 iz classpath:eovlastenja/ca-bundle.crt)` | `extra=3` (Sectigo R46 + dva Fina Demo CA) |
| `eovlastenja url=https://roapiservistst.fina.hr/… signer=CN=Upravljanje-eOvlastenjimaTst,… signer_valid_until=…` | potpisni certifikat FINA testa |
| `eovlastenja_tls host=roapiservistst.fina.hr accepted_server_name=gov.hr` | testni servis pokazuje certifikat `gov.hr`; bez ovog retka odabir tvrtke pada na `No subject alternative DNS name matching roapiservistst.fina.hr` |

Gašenje bez rebuilda: `APP_EOVLASTENJA_ENABLED=false` u `.env.cdu`, pa korak 7 — odabir tvrtke
tada vraća 503, sve ostalo radi.

**2. Mreža s kutije** (bilo koji HTTP kod znači da ruta i TLS rade; vješanje je firewall/proxy):

```bash
curl -sS -o /dev/null -w '%{http_code}\n' --max-time 15 https://roapiservistst.fina.hr/
```

**3. NIAS prijava** (korak 0.1) — jedna prijava u pregledniku, pa:

```bash
docker logs str-backend-cdu 2>&1 | grep nias_login | tail -1
```

| `sesija_id=` | Značenje |
| :--- | :--- |
| `len=… znakovi=[-, 0-9, A-Z, a-z]` (ili podskup) | stiže i odgovara formatu koji šaljemo |
| `len=… znakovi=[…, +, /, …]` | stiže, ali s posebnim znakovima → proširiti uzorak u `EOvlastenjaRequest` prije testa |
| `nema` | FINA nije uključila autorizaciju za uslugu → svaki odabir tvrtke bit će 503; dalje nema smisla |

**4. Odabir tvrtke** (korak 0.2). Dok fronta nema blok „Djelujem u ime", ide curlom s kolačićem
`SESSION` iz preglednika (DevTools → Application → Cookies):

```bash
curl -s -X POST https://str-test-eturizam.gov.hr/api/nias/acting-subject \
  -H 'Content-Type: application/json' -H 'Cookie: SESSION=<vrijednost>' \
  -d '{"oib":"<OIB tvrtke>"}'
docker logs str-backend-cdu 2>&1 | grep -E "eovlastenja_|acting_subject|registry=EOVLASTENJA" | tail -8
```

| U logu | Značenje |
| :--- | :--- |
| `eovlastenja_call ok ms=… functions=N` + `acting_subject selected functions=N` | **radi** — osoba je zastupnik, tvrtka je u sesiji |
| `eovlastenja_call failed … status=4xx code=…` | FINA je odbila poziv nepotpisanim JSON-om; šifra se tumači kao u retcima ispod |
| `eovlastenja_call rejected reason=NOT_REPRESENTATIVE code=400` / `401` | poziv radi; osoba nije u e-Ovlaštenjima / nema privolu |
| `eovlastenja_call rejected reason=SESSION code=20x` | poziv radi (metoda dopuštena), NIAS sjednica nevažeća — ponovna prijava |
| `eovlastenja_call rejected reason=SUBJECT_NOT_FOUND code=500` | poziv radi; tvrtka s tim OIB-om ne postoji |
| `eovlastenja_config_error code=100` (i `eovlastenja_call failed … status=400 code=100`) | certifikat usluge **nije** registriran za metodu → registracija kod FINA-e. Šifra stiže kao nepotpisan JSON uz HTTP 400 |
| `eovlastenja_navigation ok ms=… companies=N` | popis tvrtki za izbornik (`GET /api/nias/acting-subject/options`) radi |
| `acting_subject options_unavailable cause=…` | popis nije dostupan (uzrok u retku); frontend ostaje na upisu OIB-a, novi pokušaj tek nakon 60 s |
| `eovlastenja_navigation error_body content_type=… body="…"` | FINA je vratila grešku bez prepoznate šifre (`code=-`) — ovdje je početak njezina odgovora (skraćeno, OIB i `sesija_id` maskirani); po njemu se vidi je li to odbijen pristup ili oblik zahtjeva |
| `eovlastenja_navigation_no_companies shape=…` | FINA je vratila stavke, ali nijednu tvrtku s e-Zastupanjem — ili je oblik drugačiji od primjera (usporediti shape) |
| `eovlastenja_navigation failed … code=100` | usluga nema pristup `GetNavigationData` — izbornik ostaje na upisu OIB-a, odabir i dalje radi |
| `message=NIAS prijava ne nosi sesija_id` | v. točku 3 (`sesija_id=nema`) |
| `message=neispravan sesija_id za e-Ovlaštenja` | v. točku 3 (posebni znakovi) |
| `eovlastenja_call failed … cause=SSLHandshakeException: …` | TLS: certifikat nije prihvaćen ili ruta ne postoji — v. točku 5 |
| `eovlastenja_call failed … cause=ConnectException` / `SocketTimeoutException` | mreža/firewall prema `roapiservistst.fina.hr:443` |
| `eovlastenja_call failed … status=403` (ili drugi 4xx/5xx) | server je odbio poziv; tijelo odgovora (npr. IIS „403.7 client certificate required") je u stack traceu `external_registry_error` |
| `message=e-Ovlaštenja: odgovor nije potpisan — nepotpisane šifre grešaka …: [100]` | FINA je vratila grešku bez potpisa; šifra je samo informativna |
| `message=e-Ovlaštenja: provjera XML potpisa nije uspjela: … KeyInfo: [CN=…]` | potpisao je drugi certifikat od pinanog — stavlja se novi u `eovlastenja/` ili `APP_EOVLASTENJA_SIGNER_CERT_PATH` |
| `message=e-Ovlaštenja: odgovor nije ispravan XML (… B, početak: "…")` | stigao je HTML ili drugo, ne XML (proxy, stranica greške) |
| `message=e-Ovlaštenja: … ForRequestId=prazan …` / `osoba …` / `subjekt …` / `naziv …` + `eovlastenja_response_shape …` | odgovor je valjano potpisan, ali drugačije građen od FINA primjera — oblik iz loga usporediti s `TestSignatures.response` |
| `eovlastenja_response_shape …` (DEBUG, uz svaki odgovor) | oblik stvarnog odgovora, za usporedbu s primjerom |

Popis tvrtki koje prijavljena osoba zastupa (isti kolačić):

```bash
curl -s http://localhost:8085/api/nias/acting-subject/options -H 'Cookie: SESSION=<vrijednost>' -w '\nHTTP %{http_code}\n'
```

Zatim, s odabranom tvrtkom, zahtjev za RB ili povlačenje RB-a: prije svake od tih radnji u logu
mora biti `acting_subject reverified` (ili `acting_subject reverify_failed reason=…` — tvrtka je
tada maknuta iz sesije i radnja se ne izvodi).

**4a. Navigacijska traka e-Građani** (izbornik subjekta, `docs/EOVLASTENJA.md`). Uključena u
`application-cdu.properties` (`eusluge-nav-test.gov.hr`); gašenje bez rebuilda:
`APP_NIAS_NAVIGATION_BAR_ENABLED=false` u `.env.cdu` + korak 7. Nakon NIAS prijave na vrhu stranice
mora biti traka e-Građani s **imenom korisnika**; klik na ime → „Izaberite subjekt" → odabir.

```bash
docker logs str-backend-cdu 2>&1 | grep -E "navigation_bar|acting_subject|eovlastenja_call" | tail -8
```

| U logu | Značenje |
| :--- | :--- |
| `navigation_bar unavailable nav_token=nema …` | NIAS ne šalje `nav_token` → traka se ne prikazuje, ostaje izbornik u zaglavlju |
| `navigation_bar change outcome=ok izvor_reg=1` (uz `eovlastenja_call ok` + `acting_subject selected`) | **radi** — odabir iz trake potvrđen i spremljen |
| `outcome=notRepresentative` / `notFound` / `unsupported izvor_reg=2…` | traka je ponudila subjekt koji ne prihvaćamo (punomoć, obrt/OPG) — očekivano |
| `outcome=self` | korisnik je u traci odabrao sebe → djeluje u svoje ime |
| `navigation_bar change invalid_state` + `outcome=invalid` | odabir nije došao iz naše trake u ovoj sesiji (ili FINA ne prenosi naš `state` u `change_entity_url`) |
| nijedan `navigation_bar change` nakon odabira u traci | traka nije pozvala naš `change_entity_url` — FINA ga možda ne prihvaća za našu domenu; pogledati kamo je preglednik otišao (DevTools → Network, *Preserve log*) |

Traka piše „Prijava" umjesto imena: adresa skripte nije iz iste okoline kao NIAS (test ↔ produkcija)
ili FINA traku ne veže uz našu domenu — javiti se FINA-i.

**5. TLS dijagnostika** (samo ako točka 4 pokaže `SSLHandshakeException`). FINA klijentski
certifikat traži tek naknadno (renegotiation), pa se iz same iznimke ne vidi je li ga uopće
zatražila i je li poslan. Privremeno u `.env.cdu`:

```bash
JAVA_TOOL_OPTIONS=-Djavax.net.debug=ssl:handshake
```

pa korak 7 i ponoviti točku 4. U `docker logs` se tada vidi `CertificateRequest` od servera i
`Produced client Certificate message` od nas. Ispis je velik (i za NIAS) — **odmah nakon toga
ukloniti** redak i ponovo korak 7.

### 8c. Zajednička sesija i OIB sustav

Sesije više nisu u `str_rn.spring_session`, nego u zajedničkoj tablici `session.spring_session`.
Tablica je u istoj bazi i drži je str-internal-api. Na OIB sustav (`et2-test-internal-eturizam.gov.hr/str-internal-api`,
iza NIAS-a) šaljemo korisnikov cookie `SESSION`, pa ga taj servis prepoznaje kao prijavljenog
(`SharedSessionCookieInterceptor`). OIB sustav je uključen u `application-cdu.properties` (FO i
PO). Za povratak na stare postavke bez rebuilda, u `.env.cdu` postaviti
`SPRING_SESSION_TABLE=str_rn.spring_session` i `APP_OIB_REGISTRY_ENABLED=false`
(+ `APP_OIB_REGISTRY_LEGAL_ENTITY_ENABLED=false`), pa korak 7.

**Prije prvog deploya**, psql s **našim** DB userom:

```sql
\d session.spring_session                -- stupci kao naš changeset 024 (Spring Session 3.x)
\d session.spring_session_attributes
-- Svako pravo ZASEBNO: has_table_privilege s popisom 'SELECT,INSERT,…' vraća true čim
-- postoji BILO KOJE od njih, pa bi user sa samim SELECT-om prošao.
SELECT t, p, has_table_privilege(t, p) AS ima
FROM unnest(ARRAY['session.spring_session', 'session.spring_session_attributes']) AS t,
     unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE']) AS p
UNION ALL
SELECT 'session', 'USAGE', has_schema_privilege('session', 'USAGE');
```

Svih 9 redaka mora imati `ima = t`. Već jedan `f` znači da se nijedna sesija ne može spremiti i da
**svaki** zahtjev pada s 500. Deploy odjavljuje sve korisnike, jer se mijenja tablica sesija.

**Odmah nakon deploya** obrisati stare sesije. Naš cleanup job čisti samo konfiguriranu tablicu,
pa bi redovi u `str_rn.spring_session` (uz SAML odgovor s OIB-om i imenom) inače ostali zauvijek:

```sql
DELETE FROM str_rn.spring_session;       -- atributi se brišu kaskadno (FK u changesetu 024)
```

Preskočiti ako se planira povratak bez rebuilda (`SPRING_SESSION_TABLE=str_rn.spring_session`)
— nakon povratka korisnici se ionako moraju ponovno prijaviti.

**Na startu** backend sam provjerava prava na tablici sesija:

```bash
docker logs str-backend-cdu 2>&1 | grep startup_session_store
```

| Redak | Značenje |
| :--- | :--- |
| `startup_session_store tablica=session.spring_session prava=[SELECT, INSERT, UPDATE, DELETE]` (i za `_ATTRIBUTES`) | u redu |
| `startup_session_store_missing_privileges … nedostaje=[…]` | **svaki zahtjev pada s 500**. GRANT traži od vlasnika tablice, ili vrati `SPRING_SESSION_TABLE=str_rn.spring_session` |
| `startup_session_store_unreadable` | tablica ne postoji (npr. noćni reset na cdupreprodu) ili nema USAGE na shemi `session` |

Ako se to dogodi dok backend radi, u logu je Tomcatov stack trace s
`permission denied for table spring_session` ili `relation "session.spring_session" does not exist`.

**Nakon deploya** (NIAS prijava u pregledniku):

```bash
docker logs str-backend-cdu 2>&1 | grep -E "oib_registry|shared_session_cookie|nias_login|nias_return_to" | tail -10
```

| Redak | Značenje |
| :--- | :--- |
| `nias_login_redirect return_to=true` | korisnik je vraćen na put iz `?returnTo=` |
| `nias_login_redirect return_to=false` | prijava je završila na `success-redirect-url`. FE nije poslao `returnTo`, ili ga je poslao, a u sesijskom načinu se izgubio na ACS-u |
| `nias_return_to_rejected length=N` | FE je poslao put koji nije prošao provjeru (apsolutni URL, `//`, ne-ASCII…) |
| `shared_session_cookie_missing reason=nema_sesije` | poziv OIB sustavu otišao je bez sesije, pa slijedi 302 i fallback |
| `shared_session_serial_version_tolerated class=… stream=570 local=620` | STR je pročitao prijavu koju je u našu sesiju upisao servis na Spring Securityju 5.7 (str-internal-api, Boot 2.7). To je očekivano i ispravno (`SharedSessionSerializationConfig`). Svaka klasa se javi samo jednom |
| `oib_registry_error … status=500 … HTTP Status 500` (Tomcatova HTML stranica) | servis ne može učitati sesiju koju je napisao STR. Ako je uzrok razlika u verzijama Spring Securityja, kod njih treba isti bean `springSessionConversionService` |
| `oib_registry_lookup kind=FO status=200` | servis je prepoznao sesiju |
| `oib_registry_fallback reason=not_found` | OIB sustav osobu ne zna; ime i adresa dolaze iz `str.subject*` (kao prije) |
| `oib_registry_fallback reason=unavailable` | OIB sustav nije odgovorio ili je vratio 302/5xx; korisnik dobiva podatke iz `str.subject*`. Uzrok je u retku `oib_registry_lookup` prije toga |
| `kind=FO status=302` | servis sesiju **nije** prepoznao. Provjeriti je li red u `session.spring_session` (`SELECT session_id, principal_name FROM session.spring_session ORDER BY last_access_time DESC LIMIT 5`) i jesu li verzije Spring Securityja na obje strane kompatibilne (deserijalizacija `SPRING_SECURITY_CONTEXT`) |
| `SerializationFailedException` u logu | jedna strana ne može pročitati atribut druge. Javiti kolegi s nazivom klase iz iznimke |

Zatim provjeriti da `GET /api/nias/me` i dalje vraća 200, tj. da njihov servis nije zamijenio sesiju.

### 8d. eSeal — kvalificirani e-pečat izlaznih akata

Detalji i otvorena pitanja pružatelju: `docs/ESEAL-SPREMNOST.md`. Prvi deploy je **namjerno
dijagnostički**: pali se na CDU-u da se vidi što servis kaže na naše akte.

**1. Mreža i ključ s kutije i iz kontejnera** (prije paljenja):

```bash
cd /home/vviskov/str-rn/str_backend && set -a && . ./.env.cdu && set +a   # ESEAL_* u ljusku
curl -sS --max-time 20 -X POST "$ESEAL_BASE_URL/api/v1/seals/pdf" -H "X-API-Key: $ESEAL_API_KEY"
# iz kontejnera (ako slika nema curl, dovoljna je provjera s kutije):
docker exec str-backend-cdu sh -c 'curl -sS --max-time 20 -X POST "$ESEAL_BASE_URL/api/v1/seals/pdf" -H "X-API-Key: $ESEAL_API_KEY"'
```

Očekivano: `400` s `"errorCode":"IDEMPOTENCY_KEY_MISSING"` — ruta, TLS i ključ rade (`401` = ključ;
vješanje = firewall; TLS greška = CA lanac servisa nije u JDK truststoreu).

**2. `.env.cdu`:**

```bash
ESEAL_ENABLED=true
ESEAL_BASE_URL=<base url>
ESEAL_API_KEY=<ključ>
ESEAL_PROJECT=<eseal.project>
ESEAL_ALLOW_HTTP=true         # servis je na CDU-u na internoj IP adresi, samo http:// (29.10.2026.)
STR_DOCUMENTS_PDFA=false      # prvi prolaz bez PDF/A, drugi s true (bez rebuilda, samo korak 7)
```

Bez `ESEAL_ALLOW_HTTP=true` backend uz `http://` adresu ne krene (`str.eseal.base-url je http:// …`).
Uz njega u logu stoji `WARN eseal_insecure_http` — API ključ i akti idu bez TLS-a, pa to vrijedi samo
za internu mrežu; za produkciju treba `https://` adresa od pružatelja.

**3. Nakon `up`:**

```bash
docker logs str-backend-cdu 2>&1 | grep -E "startup_eseal|eseal base="
```

Očekivano: `startup_eseal enabled=true … api_key=postavljen … allow_http=true pdfa=false` i `eseal base=… project=…`.
Uz `enabled=true` bez URL-a, ključa ili projekta backend namjerno ne krene (poruka navodi što fali).

**4. Okidači:** nova registracija (obavijest o dodjeli) i radnja nad RB-om (prijedlog suspenzije,
povlačenje, opoziv…). Pečatiraju se samo izlazni akti, od T15 i obavijest o opozivu; podnesci
stranke (zahtjev, zahtjev za opoziv, prigovor) ne.

```bash
docker logs str-backend-cdu 2>&1 | grep -E "eseal_seal|eseal_pismeno|not_sealed" | tail -20
```

| Redak | Značenje |
| :--- | :--- |
| `eseal_seal ishod=ok status=201 … seal_level=B-LT` | akt pečatiran; isti PDF ide u eGOP i KP |
| `ishod=greska status=422 error_code=PDF_NOT_PDFA` | servis traži PDF/A → ponoviti sa `STR_DOCUMENTS_PDFA=true` |
| `error_code=PDF_NO_SEAL_SPACE` / `PDF_POLICY_VIOLATION` / `PDF_UNSUPPORTED_FEATURE` | servis nešto traži od PDF-a što specifikacija ne opisuje — `trace_id` i akt poslati pružatelju |
| `status=401` / `403 PROJECT_NOT_ALLOWED` | ključ ili `ESEAL_PROJECT` |
| `ishod=transport_error … cause=…` | mreža, TLS ili timeout (90 s) |
| `error_code=DIGEST_MISMATCH` / `RESPONSE_INVALID` | odgovor nije prihvaćen kao pečaćeni PDF |
| `eseal_pismeno_failed … novi_kljuc=true` | konačno odbijanje: sljedeći pokušaj renderira akt iznova pod novim ključem |
| `egop_email_deferred` / `rn_lifecycle_mail_deferred reason=not_sealed` | non-EU dostava mailom čeka pečat (MailRetryJob, 5 min) |

Isto stoji i u bazi:

```sql
SELECT vrsta_pismena_naziv, ur_broj, status, sync_error, seal_level, sealed_at, seal_document_guid
FROM str_rn.egop_pismeno ORDER BY created_at DESC LIMIT 10
```

**5. Pečat u PDF-u:** obavijest o dodjeli ili akt životnog ciklusa otvoriti iz popisa dokumenata RB-a
i u Adobe Readeru pogledati panel potpisa. Dok pečata nema, portal pokazuje nepečaćenu verziju.

**Na CDU-u eGOP je mock i nema `EgopRetryJob`:** pali pečat se ne ponavlja sam; urudžba te
registracije ostaje nedovršena (RB je izdan i valjan). Za novi pokušaj — nova radnja. Gašenje:
`ESEAL_ENABLED=false`, korak 7.

### 8e. Žurna verifikacija (T8)

RB izdan za **neverificiran** objekt iz eTurizma (tuStart handoff, `created_by = 'optimit'`) daje
zahtjev u `str.verification_request` i, uz uključene obavijesti, po jednu obavijest u
`str.korisnicka_obavijest` svakom aktivnom internom korisniku nadležne jedinice dokumenta
(`docs/PLAN-T8-T9-ZURNA-VERIFIKACIJA.md`). Na CDU testu `shorttermrental` ima `SELECT`, `INSERT` i
`UPDATE` na obje tablice (V11, 5. 11. 2026.), pa grant ne treba.

**`.env.cdu`** (zadano je sve ugašeno):

```bash
STR_ETURIZAM_VERIFIKACIJA_ENABLED=true
# Obavijesti tek kad eTurizam potvrdi vrstu, objekt_tip i tekst (do tada samo zahtjev):
#STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_ENABLED=true
#STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_VRSTA=VERIFIKACIJA_ZATRAZENA
#STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_OBJEKT_TIP=VERIFIKACIJA_RJESENJA
```

**Okidač:** zahtjev za RB za neverificiran objekt s popisa objekata. Objekt kojem je eTurizam
zahtjev za isti dokument već upisao daje `ZAHTJEV_POSTOJAO` (bez obavijesti). Novi i verificiran
objekt ne ostavljaju trag.

```bash
docker logs str-backend-cdu 2>&1 | grep -E "startup_eturizam_verifikacija|eturizam_verifikacija_" | tail -20
```

| Redak | Značenje |
| :--- | :--- |
| `startup_eturizam_verifikacija enabled=… obavijesti=… nedostaje=[]` | potrebna prava postoje (SELECT/INSERT na zahtjevu; uz obavijesti i SELECT na `str.document`, `str.application_user` te INSERT na obavijesti). Uz `enabled=true` i nepraznu listu je WARN |
| `eturizam_verifikacija_config …` (**ERROR**) | `vrsta` ili `objekt-tip` dulji od 64 znaka — svaki upis obavijesti će pasti |
| `eturizam_verifikacija_utrka rn=…` | eTurizam je upisao zahtjev za isti dokument istodobno; pokušaj se ponavlja jednom i redak završi `ZAHTJEV_POSTOJAO`. Drugi sukob ključa je obični neuspjeh |
| `eturizam_verifikacija_zabiljezeno rn=… document=…` | RB je izdan za neverificiran objekt, outbox redak je nastao (i uz `enabled=false`) |
| `eturizam_verifikacija_upisano … verification_request=… obavijesti=N` | zahtjev je upisan u eTurizam, `N` obavijesti službenicima |
| `eturizam_verifikacija_postojao …` | zahtjev za taj dokument je već postojao; STR ga nije dirao ni obavještavao |
| `eturizam_verifikacija_bez_jedinice` / `_bez_primatelja` (WARN) | zahtjev je upisan, ali obavijesti nema komu poslati |
| `eturizam_verifikacija_bez_dokumenta` (WARN) | objekt nema ispravan `document_id` (u praksi ne stiže — `findOwnership` dokument spaja obaveznim joinom); retka nema |
| `eturizam_verifikacija_preskoceno … razlog=…` | RB je u međuvremenu opozvan/povučen ili objekt verificiran — ništa se ne upisuje, redak `PRESKOCENO` |
| `eturizam_verifikacija_bez_prava …` (**ERROR**, jednom po retku) | nedostaje GRANT; redak čeka `PENDING` bez trošenja pokušaja, ponovo za 2 h |
| `eturizam_verifikacija_retry_aborted …` (WARN) | infrastrukturni neuspjeh (istek, zaključavanje, veza, prava) — ostatak serije čeka sljedeći prolazak |
| `eturizam_verifikacija_previse_primatelja …` (WARN) | jedinica ima više aktivnih internih korisnika od `max-primatelja` (200); obavijest dobiva prvih 200 |
| `eturizam_verifikacija_failed … reason=…` (WARN) | pokušaj je pao (najčešće `permission denied`); retry job ponavlja s backoffom |
| `eturizam_verifikacija_exhausted …` (**ERROR**) | iscrpljeni pokušaji, redak `ABANDONED` — ručna intervencija |

```sql
SELECT 'Z1' AS blok, has_table_privilege('str.verification_request','INSERT') AS zahtjev_insert,
       has_table_privilege('str.korisnicka_obavijest','INSERT') AS obavijest_insert;

SELECT 'Z2' AS blok, e.rn, e.status, e.attempts, e.document_id, e.verification_request_id,
       e.obavijesti_poslano, e.next_attempt_at, e.last_error, e.created_at
FROM str_rn.eturizam_verifikacija e ORDER BY e.created_at DESC LIMIT 20;

SELECT 'Z3' AS blok, vr.*
FROM str.verification_request vr
JOIN str_rn.eturizam_verifikacija e ON e.verification_request_id = vr.id
ORDER BY vr.id DESC LIMIT 20;

SELECT 'Z4' AS blok, o.id, o.application_user_id, o.vrsta, o.objekt_tip, o.objekt_id, o.stvoreno, o.procitano, o.tekst
FROM str.korisnicka_obavijest o
WHERE o.objekt_tip = 'VERIFIKACIJA_RJESENJA'
  AND o.objekt_id IN (SELECT e.verification_request_id FROM str_rn.eturizam_verifikacija e)
ORDER BY o.id DESC LIMIT 50;
```

(`'VERIFIKACIJA_RJESENJA'` je zadani `objekt_tip`; uz `STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_OBJEKT_TIP` upisati tu vrijednost.)

**Povrat `ABANDONED` retka u red** (nakon otklanjanja uzroka, npr. granta): retry job ga uzme u
sljedećem prolasku (`STR_ETURIZAM_VERIFIKACIJA_RETRY_CRON`, zadano svakih 5 min) s punim brojem pokušaja.

```sql
UPDATE str_rn.eturizam_verifikacija
   SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL, updated_at = now()
 WHERE rn = '<RB>' AND status = 'ABANDONED';
```

Redak bez prava (`permission denied`) ne troši pokušaje: ostaje `PENDING`, `last_error` počinje s
`bez_prava:`, a sljedeći pokušaj je za `retry.backoff-max` (2 h). Nakon granta ga se ne mora čekati,
a retke koje je starija verzija zbog prava ipak napustila vraća isti upit za sve odjednom:

```sql
UPDATE str_rn.eturizam_verifikacija
   SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL, updated_at = now()
 WHERE (status = 'ABANDONED' AND last_error LIKE '%permission denied%')
    OR (status = 'PENDING' AND last_error LIKE 'bez_prava:%');
```

Gašenje: `STR_ETURIZAM_VERIFIKACIJA_ENABLED=false`, korak 7. Retci koji nastanu dok je ugašeno
ostaju `PENDING` i obrade se kad se ponovno uključi.

#### Novi objekt uz predano rješenje (plan § 7)

Predaja rješenja uz RB **novog** objekta (obrazac „imam rješenje”) daje outbox redak vrste
`NOVI_OBJEKT_RJESENJE` i, **samo uz uključene obavijesti**, obavijesti službenicima županijske organizacije
nadležne za županiju objekta — bez zahtjeva u `str.verification_request` (novi objekt nema dokument).
Dok su obavijesti ugašene, redak čeka `PENDING`. Prije uključivanja pokrenuti
`docs/sql/t8-novi-objekt-dijagnostika.sql` (N3: broj primatelja po županiji za `IOT_ZUPANIJA`).

| Redak | Značenje |
| :--- | :--- |
| `eturizam_verifikacija_novi_zabiljezeno rn=… decision=… zupanija=…` | rješenje je predano, outbox redak je nastao |
| `eturizam_verifikacija_novi_upisano … obavijesti=N` | `N` obavijesti upisano, redak `OBAVIJESTI_UPISANE` |
| `eturizam_verifikacija_novi_bez_zupanije` / `_novi_bez_primatelja` (WARN) | županija nije prepoznata u `str.county` ili nema službenika; redak zatvoren s 0 obavijesti |
| `eturizam_verifikacija_novi_vec_zabiljezeno` | za RB je obavijest već poslana (ponovna predaja nakon odbijanja) — nova se ne šalje |
| `eturizam_verifikacija_novi_ponovo rn=… decision=… prethodno=…` | obavijest za RB još nije bila poslana, a predano je novo rješenje — redak čeka uz njega |
| `eturizam_verifikacija_preskoceno vrsta=NOVI_OBJEKT_RJESENJE … razlog=rješenje je u međuvremenu obrađeno` | službenik je rješenje prihvatio/odbio prije obrade retka |

```sql
SELECT 'Z5' AS blok, e.rn, e.status, e.zupanija, e.decision_id, e.obavijesti_poslano, e.attempts, e.last_error, e.created_at
FROM str_rn.eturizam_verifikacija e WHERE e.vrsta = 'NOVI_OBJEKT_RJESENJE' ORDER BY e.created_at DESC LIMIT 20;

SELECT 'Z6' AS blok, o.id, o.application_user_id, o.vrsta, o.stvoreno, o.procitano, o.podaci, o.tekst
FROM str.korisnicka_obavijest o
WHERE o.objekt_tip IS NULL AND o.vrsta = 'VERIFIKACIJA_ZATRAZENA'
ORDER BY o.id DESC LIMIT 50;
```

### 8f. Korisnički pretinac preko KP API-ja (T16)

Akti **županijskih službenika** (bez eGOP-a) idu u korisnički pretinac preko eTurizmova KP API-ja
(`str-internal-api/kp-api`), samo fizičkim osobama; pravne osobe i non-EU samo e-poštom. Prijava je
**HTTP Basic** tehničkim korisnikom STR-a (dostavlja eTurizam). Poruka se šalje odmah nakon radnje, a ono
što ne prođe ili nastane u noćnom poslu šalje retry posao svakih 5 minuta. Adresa
(`https://et2-test-internal-eturizam.gov.hr/str-internal-api/kp-api`) i šifra poruke (1741) su zadane u
`application-cdu.properties`.

```bash
STR_KP_API_ENABLED=true
STR_KP_API_USERNAME=<korisnik od eTurizma>
STR_KP_API_PASSWORD=<lozinka od eTurizma>
#STR_KP_API_TIP_PORUKE=1741
#STR_KP_API_BASE_URL=https://et2-test-internal-eturizam.gov.hr/str-internal-api/kp-api
```

| Redak | Značenje |
| :--- | :--- |
| `startup_kp_api enabled=… aktivno=… korisnik=… lozinka=postavljena` | konfiguracija pri startu; WARN `nedostaje=[…]` kad je uključeno, a nešto fali |
| `kp_api_zabiljezeno rn=… poruka=N raspored=…` | akt je dobio N poruka (više kad prilozi prelaze 3 MB); šalju se odmah nakon commita |
| `kp_api_poslano id_poruke=…` | KP je zaprimio poruku |
| `kp_api_konacno … ishod=NEMA_PRETINCA/ODBIJENO/NEISPRAVNO` | konačno, bez ponavljanja; akt je dostavljen e-poštom |
| `kp_api_prijava_odbijena … http=401/403/302 greska=…` (**ERROR**) | KP API je odbio prijavu; `greska` nosi opis iz odgovora (npr. CSRF, uloga). Provjeriti korisnika i lozinku; poruka čeka 5 min, pokušaj se ne broji |
| `kp_api_retry_start poruka=N` / `kp_api_retry_done predano=N` | retry posao predaje poolu poruke koje nisu otišle odmah; ishod svake je u `kp_api_*` retku s `id_poruke` |
| `startup_kp_api_http` (WARN) | adresa je `http://` — lozinka ide nekriptirano; na CDU-u i produkciji mora biti `https://` |
| `kp_api_neuspjeh … sljedeci=…` (WARN) | 502, timeout ili veza; ponavlja se s istim `idPoruke` nakon odgode |
| `kp_api_exhausted …` (**ERROR**) | iscrpljeni pokušaji, poruka nije u pretincu |

```sql
SELECT 'K1' AS blok, p.rn, p.dio, p.status, p.attempts, p.tip_poruke, p.izdavatelj_zu_rb, p.next_attempt_at, p.poslano_at, p.greska, p.created_at FROM str_rn.kp_poruka p ORDER BY p.created_at DESC LIMIT 30;
```

#### Ručna KLASA i URBROJ županijskog službenika (T16)

Kad radnju (prijedlog i obustava suspenzije, reaktivacija, prijedlog i obustava povlačenja, uvažavanje
očitovanja) radi **županijski** službenik, eGOP se ne koristi: službenik upisuje `klasa` i `urbroj`,
izdavatelj na aktu je njegova županija (naziv, OIB i jedinica iz `str.internal_organization` /
`organizational_unit`), bez MINTS-ova e-pečata. Suspenzija ili povlačenje istekom roka (noćni posao,
neuvažavanje očitovanja) uzima KLASU i izdavatelja s prijedloga, a URBROJ + 1. Službenik ministarstva radi
kao dosad (eGOP). Privremeno: adresa i mjesto izdavatelja su prazni, propis nadležnosti je MINTS-ov.

| Redak | Značenje |
| :--- | :--- |
| `rucni_akt_zupanija zu_rb=… izdavatelj='…' klasa=… urbroj=…` | radnju radi županijski službenik — ručni akt |
| `rucni_akt_odbijen zu_rb=… razlog=error.rn.akt.…` | KLASA/URBROJ nisu prošli provjeru (400 službeniku); razlog je ključ poruke |
| `rucni_akt_zanemaren outcome=… klasa=… urbroj=…` (WARN) | sučelje je poslalo brojeve, a backend službenika ne vidi kao županijskog — akt ide kroz eGOP |
| `rucni_akt_bez_izdavatelja` / `_izdavatelj_bez_oib` / `_izdavatelj_bez_naziva` (WARN) | podaci izdavatelja u `str.internal_organization` nepotpuni — akt bez tog podatka |
| `rn_transition … rucni_akt=true` | prijelaz nosi ručne brojeve |
| `rucni_akt_izveden rn=… urbroj_prijedloga=… urbroj=…` | noćni posao / neuvažavanje: brojevi izvedeni iz prijedloga (URBROJ + 1) |
| `rucni_akt_izvod_pao …` (**ERROR**) | prijedlog je ručni, a broj se ne može izvesti — akt je otišao kroz eGOP |
| `document_rucni_akt type=… izdavatelj='…' klasa=… urbroj=…` | render ručnog akta (zaglavlje županije, bez e-pečata) |
| `rucni_akt_zapisan rn=… akt=… klasa=… urbroj=… zu_rb=…` | akt je zapisan (`egop_pismeno.rucno`, `SYNCED`), bez eGOP-a |
| `rucni_akt_bez_kp …` | primatelj nije fizička osoba s OIB-om (ili RB nije pronađen) — akt ide samo e-poštom |
| `rucni_akt_not_recorded rn=… log_id=…` (**ERROR**) | status je promijenjen, a akt nije zapisan — izdati ručno |
| `rucni_akt_kp_not_recorded rn=… akt=…` (**ERROR**) | akt je zapisan i ide e-poštom, ali poruka u pretinac nije zabilježena — poslati ručno |
| `rn_lifecycle_mail_unsealed_act … reason=rucni_akt_zupanije` (WARN) | non-EU dobiva ručni akt nepečaćen (ručni akt nema MINTS-ov pečat) |
| `kp_api_priprema_pala id_poruke=… pismeno=…` (WARN, sa stack traceom) | PDF akta ili prilog nisu pročitani pri slanju u KP |

```sql
SELECT 'R1' AS blok, l.rn, l.to_status, l.trigger_name, l.akt_klasa, l.akt_ur_broj, l.akt_izdavatelj_naziv, l.akt_izdavatelj_zu_rb, l.occurred_at FROM str_rn.registration_number_log l WHERE l.akt_ur_broj IS NOT NULL ORDER BY l.occurred_at DESC LIMIT 30;
```

## 9. Iz browsera

```
https://str-test-eturizam.gov.hr
```

Bez porta — gateway radi SSL offload i rutira 443 → 172.20.8.158:8085.

**Uvijek Ctrl+F5.** Statika ima `Cache-Control: immutable, 7 dana`, pa preglednik inače servira
stari `index.html` koji traži hash kojeg u novom buildu više nema — i vidiš bijeli ekran iako je
na kutiji sve ispravno.

Ako timeout:

- CDU VPN gore?
- `nslookup str-test-eturizam.gov.hr` — resolva li DNS?
- `Test-NetConnection str-test-eturizam.gov.hr -Port 443`

## Update-only (drugi put nadalje)

Koraci 1 → 2 → 3 → 6 → 7 → 8. Koraci 5 i 5b su jednokratni.

---

# Zamke

Sve niže smo prošli 17.09.2026. Simptomi su doslovni ispisi iz terminala.

| Simptom | Uzrok | Rješenje |
|---|---|---|
| `scp: dest open ".../index.html": Permission denied` | Datoteke u `build/` pripadaju drugom korisniku i nisu grupno upisive | Vidi „Prava na `build/`" |
| `scp: remote setstat ".../build": Permission denied` | Kopiraš **mapu**, a nisi joj vlasnik | Kopiraj sadržaj: `build\*` |
| `checking context: can't stat '.../build.old-...'` | U build kontekstu je unos koji ne možeš pročitati | Obriši ga (root kroz docker), ne preimenuj |
| `KeyError: 'ContainerConfig'` | Bug `docker-compose` v1 1.29.2 | `down` prije `up -d --build` |
| Bijeli ekran, u nginx logu `stat() ... (13: Permission denied)` | Modovi datoteka iz Windows scp-a; nginx (uid 101) ne može čitati | Korak 6: `chmod -R a+rX build` pa rebuild |
| `[Warning] ... build-args ... were not consumed` | Očekivano na CDU — thin `Dockerfile.cdu` ne prima VITE argumente | Ništa; vrijednosti dolaze iz lokalnog builda |

## Prava na `build/` — kako čitati ispis

```bash
ls -ld /home/vviskov/str-rn/str_frontend /home/vviskov/str-rn/str_frontend/build
id
```

Primjer stvarnog kvara:

```
drwxrwsr-x  vviskov kodelab-d  .../str_frontend        ← grupa: rwx
drwx---rwx  vviskov kodelab-d  .../str_frontend/build  ← grupa: ---, ostali: rwx
```

Linux provjerava prava po **prvoj klasi koja odgovara**. Ako si član grupe datoteke, vrijede
**grupna** prava i bitovi za „ostale" se više uopće ne gledaju. Član `kodelab-d` zato ovdje
nema pristup, dok bi netko izvan grupe prošao kao „ostali" i imao `rwx`. Članstvo u grupi
može **oduzeti** pristup — to je najčešći uzrok „jučer je radilo".

Provjeri i kad je mod zadnji put mijenjan (`chmod`/`chgrp` mijenjaju **ctime**, ne mtime):

```bash
stat /home/vviskov/str-rn/str_frontend/build | grep Change
```

### Popravak

**Ima li tko sudo ili vlasnika na vezi** — jedna naredba:

```bash
sudo chmod 2775 /home/vviskov/str-rn/str_frontend/build
```

**Bez sudo**, kroz docker (kontejner radi kao root):

```bash
docker run --rm -v /home/vviskov/str-rn/str_frontend:/ctx nginx:alpine \
  sh -c 'chown -R $(id -u):$(id -g) /ctx/build'      # ← ubaci SVOJ uid:gid s hosta (`id -u; id -g`)
```

**Bez ičega od toga** — preimenuj i napravi svoju mapu. Radi jer preimenovanje **unutar iste**
roditeljske mape traži samo pravo pisanja na roditelju:

```bash
cd /home/vviskov/str-rn/str_frontend
mv build build.old            # prolazi
mkdir build && chmod 2775 build
```

⚠️ Zaostalu `build.old` **moraš obrisati**, ne samo ostaviti — inače idući `docker build` pukne
na `checking context: can't stat`. Ne možeš je ni premjestiti drugdje (premještanje mape u drugu
roditeljsku mapu traži pravo pisanja na samoj mapi), pa ide kroz docker:

```bash
docker run --rm -v /home/vviskov/str-rn/str_frontend:/ctx nginx:alpine \
  sh -c 'rm -rf /ctx/build.old'
```

`.dockerignore` ovdje **nije** pouzdan zaobilazak — dodavanje mape na popis ne spašava build, a
redak `build` bi razbio `COPY build/` u `Dockerfile.cdu`.

## Višekorisnički deploy (tim pristup)

Da se prava ne kvare svaki put, vlasnik (`vviskov`) jednom pokrene:

```bash
chgrp -R kodelab-d /home/vviskov/str-rn/str_backend/target /home/vviskov/str-rn/str_frontend/build
chmod -R g+w       /home/vviskov/str-rn/str_backend/target /home/vviskov/str-rn/str_frontend/build
chmod g+s          /home/vviskov/str-rn/str_backend/target /home/vviskov/str-rn/str_frontend/build
```

`g+s` (setgid) drži **grupu** novih datoteka, ali **ne daje pravo pisanja** — uz uobičajeni
`umask 022` nove datoteke su `644` i idući kolega opet ne može prepisati. Zato deploy treba
raditi uz:

```bash
umask 002
```

Ako kolega treba i pokretati `docker-compose` (čitati `.env.cdu`):

```bash
chgrp -R kodelab-d /home/vviskov/str-rn
chmod -R g+rwX /home/vviskov/str-rn
find /home/vviskov/str-rn -type d -exec chmod g+s {} \;
```

Dugoročno je najčišće premjestiti `str-rn` iz osobnog home-a u zajednički direktorij
(npr. `/srv/str-rn`) s grupom `kodelab-d` i setgid-om — tada nema „tuđeg home-a" ni ovih zamki.
