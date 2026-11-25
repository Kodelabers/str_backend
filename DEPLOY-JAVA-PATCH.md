# Deploy — security patch Jave (`eclipse-temurin:21.0.13.1_2`)

Redoslijed: **CDU test** (`str-test-eturizam.gov.hr`), pa **CDU predprodukcija**
(`str-preprod-eturizam.gov.hr`). Mijenja se samo Java u slici backenda; frontend se ne dira.

| | |
| :--- | :--- |
| Verzija | OpenJDK `21.0.13.1+2` (Adoptium GA 18.11.2026.), u Docker tagu `21.0.13.1_2` |
| Slika | `eclipse-temurin:21.0.13.1_2-jre-jammy` (CDU okoline grade iz jammy) |
| Gdje je pinana | `ARG JAVA_VERSION` u `Dockerfile` i `Dockerfile.artifact`; na CDU testu server-only `Dockerfile.cdu` |
| Zašto ovim redom | CDU test ne vidi Docker Hub, pa sliku povlači CDU predprodukcija i prenosi se preko laptopa |

Za neki budući patch postupak je isti: zamijeni `21.0.13.1_2` novim tagom (u tagu `+` postaje `_`) i prije
commita provjeri `git grep -n "<STARI_TAG>"` (opće pravilo je u `DEPLOY-CDU.md`, „Nadogradnja Jave").

---

## 0. Prije deploya — kod u `develop`

```powershell
git -C C:\Users\MladenHangi\str_backend status --short    # samo datoteke patcha
git -C C:\Users\MladenHangi\str_backend diff
```

Prijedlog commit poruke:

```
chore(docker): pin Java runtime na 21.0.13.1+2 (OpenJDK security patch)

- ARG JAVA_VERSION u Dockerfile i Dockerfile.artifact umjesto plutajućeg 21-jre
- DEPLOY-CDU: nadogradnja Jave na CDU testu (docker save/load s predprodukcije)
- keytool primjeri pinani na istu verziju
- DEPLOY-JAVA-PATCH.md: redoslijed deploya, CDU test pa CDU predprodukcija
```

Commit, push, PR u `develop`, merge. CDU testu kod ne treba (`Dockerfile.cdu` je samo na serveru), ali
predprodukciji treba `Dockerfile.artifact` iz `develop`-a, pa merge mora biti gotov **prije koraka C**.

---

## A. Slika s CDU predprodukcije (samo preuzimanje, bez deploya)

Državni VPN gore.

```powershell
ssh cdu-preprod "docker pull eclipse-temurin:21.0.13.1_2-jre-jammy && docker image inspect --format '{{.Id}}' eclipse-temurin:21.0.13.1_2-jre-jammy"
```

**Zapiši ispisani `sha256:…` Id** — provjerava se u B1.

```powershell
ssh cdu-preprod "docker save eclipse-temurin:21.0.13.1_2-jre-jammy | gzip > ~/temurin-21.0.13.1_2-jre-jammy.tar.gz && ls -lh ~/temurin-21.0.13.1_2-jre-jammy.tar.gz"
scp cdu-preprod:~/temurin-21.0.13.1_2-jre-jammy.tar.gz $env:TEMP\
scp $env:TEMP\temurin-21.0.13.1_2-jre-jammy.tar.gz cdu:/tmp/
```

Prijenos ide kroz datoteku, a ne pipeom: PowerShell 5.1 `>` kvari binarni stream.

---

## B. CDU test (`str-test-eturizam.gov.hr`)

Jar i frontend ostaju isti. Backend ne radi oko minutu, između `down` i `up -d` — javi testerima.

> **Uvijek apsolutna putanja.** Alias `cdu` spaja se kao `mhangi`, a deploy direktorij je
> `/home/vviskov/str-rn`. `cd ~/str-rn/...` bi otišao u krivi direktorij (vidi „Tko deploya i čime" u
> `DEPLOY-CDU.md`).

### B0. Stanje prije (`ssh cdu`)

```bash
cd /home/vviskov/str-rn/str_backend
df -h / | tail -1                                    # treba ~0,5 GB slobodno
docker exec str-backend-cdu java -version            # zapiši staru verziju
docker exec str-backend-cdu sha256sum /app/app.jar   # zapiši — jar se ne smije promijeniti
grep -n '^ARG\|^FROM' Dockerfile.cdu                 # očekuj: FROM eclipse-temurin:21-jre-jammy
```

### B1. Učitaj sliku i provjeri da je ista

```bash
gunzip -c /tmp/temurin-21.0.13.1_2-jre-jammy.tar.gz | docker load
docker image inspect --format '{{.Id}}' eclipse-temurin:21.0.13.1_2-jre-jammy   # MORA biti Id iz koraka A
```

### B2. Izmijeni `Dockerfile.cdu` i pregradi backend

```bash
cp Dockerfile.cdu Dockerfile.cdu.bak.$(date +%F)
sed -i 's#^FROM eclipse-temurin:21-jre-jammy$#ARG JAVA_VERSION=21.0.13.1_2\nFROM eclipse-temurin:${JAVA_VERSION}-jre-jammy#' Dockerfile.cdu
grep -n '^ARG\|^FROM' Dockerfile.cdu
```

Ispis mora biti `ARG JAVA_VERSION=21.0.13.1_2` pa `FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy`. Ako je
`FROM` u B0 bio drukčiji, `sed` ništa ne mijenja — te dvije linije upiši ručno (`nano Dockerfile.cdu`).

```bash
STARI=$(docker-compose --env-file .env.cdu -f docker-compose.cdu.yml images -q backend); echo "$STARI"
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml build --no-cache backend   # stari kontejneri i dalje rade
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml down                       # compose v1: obavezno prije up
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml up -d
```

`down` prije `up` je obavezan zbog greške compose v1 (`KeyError: 'ContainerConfig'`, `DEPLOY-CDU.md` §7).

### B3. Provjera

```bash
docker exec str-backend-cdu java -version            # mora pisati 21.0.13.1 (Temurin-21.0.13.1+2)
docker exec str-backend-cdu sha256sum /app/app.jar   # isti hash kao u B0
docker logs --since 5m str-backend-cdu 2>&1 | grep -E 'Started StrBackendApplication|startup_|ERROR' | head -30
```

Zatim §8 iz `DEPLOY-CDU.md` i prijava putem NIAS-a u pregledniku (Ctrl+F5).

### B4. Čišćenje

```bash
docker rmi "$STARI"; rm /tmp/temurin-21.0.13.1_2-jre-jammy.tar.gz
```

Briše se samo naša stara slika backenda. Globalni `docker image prune` / `docker builder prune` ne
koristiti — kutija je dijeljena. **Stari `eclipse-temurin:21-jre-jammy` ne brisati:** ne može se ponovo
povući, a treba za povratak.

### B5. Povratak

```bash
cd /home/vviskov/str-rn/str_backend
cp Dockerfile.cdu.bak.<datum> Dockerfile.cdu
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml build --no-cache backend
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml down
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml up -d
```

Pusti CDU test da radi (prijava, izdavanje broja, dokumenti) prije koraka C.

---

## C. CDU predprodukcija (`str-preprod-eturizam.gov.hr`)

Deploy ide pod tvojim računom i u tvoj home (`DEPLOY-CDU-PREPROD.md` §B2), pa je `~` ondje ispravan.
Compose je v2 — `down` ne treba. Slika je već povučena u koraku A. Postupak je redeploy iz
`DEPLOY-CDU-PREPROD.md` §C9: patch je u `Dockerfile.artifact`, pa repo na kutiji mora dobiti novi `develop`.

### C0. Što ide s ovim deployem (lokalno, nakon mergea)

```powershell
$BE = "C:\Users\MladenHangi\str_backend"
git -C $BE fetch origin
git -C $BE log --oneline <zadnji-deployani-sha>..origin/develop
git -C $BE diff <zadnji-deployani-sha> origin/develop -- .env.cdupreprod.example
```

Svi navedeni commitovi idu na predprodukciju zajedno s patchem. Novi ključ iz `.env.cdupreprod.example`
dopiši i u `~/str-rn/str_backend/.env.cdupreprod` na kutiji.

### C1. Prije VPN-a — jar i bundle iz `develop`-a

`$MVN` i `JAVA_HOME` postavi kao u `DEPLOY-CDU-PREPROD.md` §C9, korak 0.

```powershell
git -C $BE checkout develop; git -C $BE pull
& $MVN -f $BE\pom.xml clean package -DskipTests
git -C $BE bundle create C:\Users\MladenHangi\be-upd.bundle origin/develop
```

Frontend se ne mijenja — korak 3 iz §C9 preskoči, osim ako je `develop` frontenda donio nešto novo.

### C2. Na VPN-u — sigurnosna kopija, prijenos, repo

```powershell
ssh cdu-preprod "git -C ~/str-rn/str_backend log -1 --format='%h %cs %s'"
ssh cdu-preprod "cd ~/str-rn/str_backend/target && cp -f str-backend-0.0.1-SNAPSHOT.jar str-backend-PRETHODNI.jar && ls -l"
scp $BE\target\str-backend-0.0.1-SNAPSHOT.jar cdu-preprod:~/str-rn/str_backend/target/
scp C:\Users\MladenHangi\be-upd.bundle cdu-preprod:~/
ssh cdu-preprod "git -C ~/str-rn/str_backend fetch ~/be-upd.bundle origin/develop:refs/remotes/upd/develop && git -C ~/str-rn/str_backend checkout --detach upd/develop && git -C ~/str-rn/str_backend log -1 --format='%h %cs %s'"
```

Prvi ispis zapiši kao **povratni sha**, zadnji je novi „zadnji deployani sha". Provjera da je patch stigao:

```powershell
ssh cdu-preprod "grep -n '^ARG\|^FROM' ~/str-rn/str_backend/Dockerfile.artifact; grep BACKEND_DOCKERFILE ~/str-rn/str_backend/.env.cdupreprod"
```

Očekuj `ARG JAVA_VERSION=21.0.13.1_2`, `FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy` i
`BACKEND_DOCKERFILE=Dockerfile.artifact`.

### C3. Podizanje

```powershell
ssh cdu-preprod "cd ~/str-rn/str_backend && docker compose -f docker-compose.cdupreprod.yml --env-file .env.cdupreprod up -d --build"
```

### C4. Provjera

```powershell
ssh cdu-preprod "docker exec str-backend-cdupreprod java -version"
ssh cdu-preprod "docker logs --since 5m str-backend-cdupreprod 2>&1 | grep -E 'Started StrBackendApplication|startup_|ERROR' | head -30"
ssh cdu-preprod "curl -sI http://localhost:8085 | head -1; curl -s -o /dev/null -w 'captcha %{http_code}\n' http://localhost:8085/api/captcha/challenge"
```

Java mora pisati `21.0.13.1`, startup bez ERROR-a (očekivane vrijednosti u `DEPLOY-CDU-PREPROD.md` §C7).
Zatim preglednik (Ctrl+F5) i NIAS prijava.

### C5. Čišćenje

```powershell
ssh cdu-preprod "rm ~/temurin-21.0.13.1_2-jre-jammy.tar.gz ~/be-upd.bundle"
```

### C6. Povratak

```powershell
ssh cdu-preprod "git -C ~/str-rn/str_backend checkout --detach <povratni-sha> && cd ~/str-rn/str_backend/target && cp -f str-backend-PRETHODNI.jar str-backend-0.0.1-SNAPSHOT.jar && cd ~/str-rn/str_backend && docker compose -f docker-compose.cdupreprod.yml --env-file .env.cdupreprod up -d --build"
```

Stara slika `21-jre-jammy` ostaje na kutiji, pa povratak ne treba Docker Hub. Liquibase changeseti koje je
novi `develop` primijenio ostaju primijenjeni (vidi §C9, „Ako nova verzija ne valja").

---

## Napomena — `s-str-02` (InfoDom predprodukcija, profil `preprod`)

Ondje se gradi na kutiji iz `Dockerfile`, koji sada koristi `eclipse-temurin:21.0.13.1_2-jre`. Nakon mergea
dovoljan je uobičajeni postupak iz `DEPLOY-PREPROD.md` (korak 4 `git pull`, korak 5 `up -d --build`); kutija
vidi Docker Hub i novu sliku povuče sama. Provjera: `docker exec str-backend-preprod java -version`.
`Dockerfile` daje sliku na Ubuntu noble, a CDU okoline jammy — tako je bilo i prije patcha.
