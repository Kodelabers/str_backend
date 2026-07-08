# CDU deploy — str-test-eturizam.gov.hr

Server: `172.20.8.158` (CDU intranet, VPN required). Public: `https://str-test-eturizam.gov.hr`.

CDU server ima docker + osnovne base imageove (`eclipse-temurin:21-jre-jammy`, `nginx:alpine`) već cachirane, ali NEMA maven/npm i nema pristupa Docker Hubu za nove pulls. Zato buildamo artefakte lokalno i preko `scp` prebacujemo na server; docker tamo samo `COPY`-a artefakte u thin imageove.

## Server layout

```
~/str-rn/
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

`Dockerfile.cdu` i server-side `docker-compose.cdu.yml` ZASAD NISU u repou — postoje samo na serveru. TODO: reconciliati u PR-u i uklopiti u redovan flow.

## 1. Lokalno — build BE jar

```powershell
cd C:\Projects\STR\str_backend
mvn package -DskipTests
```

## 2. Lokalno — build FE

```powershell
cd C:\Projects\STR\str_frontend
$env:VITE_API_URL="https://str-test-eturizam.gov.hr"
$env:VITE_USE_MOCK="false"
$env:VITE_NIAS_ENABLED="true"
npm run build
```

**Bitno:** `VITE_API_URL` je BEZ `/api` sufiksa — nginx u frontend containeru rutira bez tog prefiksa.

## 3. Lokalno — scp artefakti na server

```powershell
scp C:\Projects\STR\str_backend\target\str-backend-0.0.1-SNAPSHOT.jar vviskov@172.20.8.158:~/str-rn/str_backend/target/
scp -r C:\Projects\STR\str_frontend\build vviskov@172.20.8.158:~/str-rn/str_frontend/
```

## 4. SSH na server

```powershell
ssh vviskov@172.20.8.158
```

## 5. Server — provjeri domenu u compose i env (samo prvi put nakon domenskog rename-a)

Ako server compose/env još drži staru domenu (`str-test.eturizam.gov.hr`), zamijeni:

```bash
sed -i 's/str-test\.eturizam\.gov\.hr/str-test-eturizam.gov.hr/g' ~/str-rn/str_backend/docker-compose.cdu.yml
sed -i 's/str-test\.eturizam\.gov\.hr/str-test-eturizam.gov.hr/g' ~/str-rn/str_backend/.env.cdu
grep str-test ~/str-rn/str_backend/.env.cdu
```

*NIAS SP registracija mora pratiti domenu — provjeri s NIAS timom prije zamjene `.env.cdu` NIAS URLova.*

## 6. Server — down + rebuild + up

```bash
cd ~/str-rn/str_backend
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml down
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml up -d --build
```

Server ima **stari `docker-compose` v1** (`docker-compose` s crtom, ne `docker compose` sa razmakom). Poznati bug u v1 1.29.2: `KeyError: 'ContainerConfig'` na `up -d --build` kad postoje stari containeri. `down` prije `up -d --build` to zaobilazi.

Ako i dalje pada — probaj:

```bash
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml build
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml up -d
```

## 7. Server — provjera

```bash
docker ps                      # str-backend-cdu + str-frontend-cdu moraju biti Up
curl -I http://localhost:8085  # očekivan 200 OK
docker logs -f str-backend-cdu
```

## 8. Iz browsera

```
https://str-test-eturizam.gov.hr
```

Bez porta — gateway radi SSL offload i rutira 443 → 172.20.8.158:8085. Ako timeout, provjeri:

- CDU VPN gore?
- `nslookup str-test-eturizam.gov.hr` — resolva li DNS?
- `Test-NetConnection str-test-eturizam.gov.hr -Port 443`

## Update-only (drugi put nadalje)

Ponoviti korake 1–4 pa na serveru:

```bash
cd ~/str-rn/str_backend
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml down
docker-compose --env-file .env.cdu -f docker-compose.cdu.yml up -d --build
```
