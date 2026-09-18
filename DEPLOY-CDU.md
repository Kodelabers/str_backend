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
STR_TIJELO_NAZIV=MINISTARSTVO TURIZMA I SPORTA
STR_TIJELO_OIB=
STR_TIJELO_ADRESA=Prisavlje 14
STR_TIJELO_MJESTO=Zagreb
STR_TIJELO_USTROJ=Uprava za turizam
STR_TIJELO_PROPIS=
STR_POTPISNIK_IME=
STR_POTPISNIK_FUNKCIJA=Voditelj postupka
STR_EPECAT_ENABLED=false
STR_DOCUMENTS_RELOAD=false

# --- eGOP ---
EGOP_AKTI_BEZ_SIFRE=reaktivacija,prijedlog-suspenzije,obustava-suspenzije

# --- obavijesti e-poštom (ugašeno dok nema SMTP-a dohvatljivog s kutije) ---
APP_MAIL_ENABLED=false
```

**Ključ koji ne postavljaš zakomentiraj, nemoj ostaviti prazan** — prazna vrijednost je
postavljena vrijednost i pregazi default iz `application.properties`. Iznimka su ključevi
čiji je default ionako prazan (`STR_TIJELO_OIB`, `STR_TIJELO_PROPIS`, `STR_POTPISNIK_IME`).

Postavke koje CDU drži u `application-cdu.properties` (ne treba ih u `.env.cdu`, ali je dobro
znati da postoje): `str.rn.documents.zahtjev-visible=false`, prazan `str.egop.mock.filing-prefix`
i `app.cadastral.enabled=true`.

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
docker logs str-frontend-cdu 2>&1 | tail -20     # NE smije biti "Permission denied"
```

Najbrža potvrda da je fronta stvarno poslužena, a ne samo `index.html`:

```bash
curl -sI http://localhost:8085/assets/$(docker exec str-frontend-cdu ls /usr/share/nginx/html/assets | grep '\.js$') | head -1
```

Mora vratiti `HTTP/1.1 200 OK`. Ako vrati `404`, pogledaj `docker logs str-frontend-cdu` — kad
ondje piše `stat() ... failed (13: Permission denied)`, preskočen je korak 6.

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
