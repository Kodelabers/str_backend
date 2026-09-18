#!/usr/bin/env bash
# Jutarnji oporavak CDU predprodukcije nakon noćnog reseta baze.
#
# ŠTO SE DOGODI NOĆU: baza 172.20.8.212/eturizam se resetira. Ako reset briše i shemu `str_rn`,
# s njom nestaju naše tablice, sesije i Liquibaseov `databasechangelog`. Backend to ne primijeti:
# kontejner ostaje "Up", Hikari drži konekcije na nepostojeće objekte i SVAKI upit puca dok se
# aplikacija ne restarta. Zato redoslijed nije proizvoljan:
#
#   1. čekaj da baza uopće prihvaća konekcije   (reset možda još traje)
#   2. kreiraj shemu ako je nema                (na ovom profilu je nitko drugi ne kreira)
#   3. restartaj backend                        (Liquibase ponovno izgradi shemu iz changeloga)
#   4. čekaj "Started StrBackendApplication"
#   5. smoke test
#
# Instalacija na kutiji (kao VLASTITI korisnik — `mhangi` je u grupi `docker`, sudo ne treba):
#   chmod +x ~/str-rn/str_backend/tools/cdupreprod-nightly.sh
#   crontab -e   →   30 4 * * *  $HOME/str-rn/str_backend/tools/cdupreprod-nightly.sh
#                    ^ vrijeme NAKON njihovog reset prozora — TRAŽI GA OD DBA prije postavljanja;
#                      dok se ne zna, cron se NE postavlja (skripta bi krenula usred reseta).
#
# Skripta je idempotentna — smije se pokrenuti i ručno, i dvaput.
set -euo pipefail

# --- konfiguracija -------------------------------------------------------------------------
# Putanje se izvode iz mjesta same skripte (`<repo>/tools/`), pa rade za bilo kojeg korisnika i
# bilo koji checkout. Deploy ide u VLASTITI home (~/str-rn), ne u /srv — kutiji se pristupa
# osobnim korisnikom, ne rootom.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.cdupreprod.yml}"
ENV_FILE="${ENV_FILE:-.env.cdupreprod}"
BOOTSTRAP_SQL="${BOOTSTRAP_SQL:-${PROJECT_DIR}/tools/cdupreprod-bootstrap.sql}"
BACKEND_CONTAINER="${BACKEND_CONTAINER:-str-backend-cdupreprod}"
FRONTEND_PORT="${FRONTEND_PORT:-8085}"
# NE u /var/log: skripta se vrti kao običan korisnik i ondje nema pravo pisanja, a `log()` piše
# kroz `tee -a` — uz `set -euo pipefail` prvi bi zapis srušio cijeli oporavak prije ijedne radnje.
LOG_FILE="${LOG_FILE:-$(dirname "$PROJECT_DIR")/nightly.log}"

# libpq parametri (JDBC URL iz .env-a se NE može dati psql-u — drugi format).
DB_HOST="${DB_HOST:-172.20.8.212}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-eturizam}"

DB_WAIT_SECONDS="${DB_WAIT_SECONDS:-900}"      # koliko najdulje čekamo da reset završi
START_WAIT_SECONDS="${START_WAIT_SECONDS:-300}" # koliko najdulje čekamo dizanje konteksta

log() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG_FILE"; }
fail() { log "NEUSPJEH: $*"; exit 1; }

cd "$PROJECT_DIR" || fail "nema direktorija $PROJECT_DIR"
[ -f "$ENV_FILE" ] || fail "nema $ENV_FILE (bez njega compose puca na env_file)"

# Kredencijali se čitaju ciljano, redak po redak. `set -a; . .env` ovdje NE VALJA: JDBC URL sadrži
# `&` (…?currentSchema=str_rn&options=…), koji bi shell protumačio kao pokretanje u pozadini.
read_env() { sed -n "s/^$1=//p" "$ENV_FILE" | head -1; }
DB_USER="$(read_env CDUPREPROD_DB_USERNAME)"
DB_PASSWORD="$(read_env CDUPREPROD_DB_PASSWORD)"
[ -n "$DB_USER" ] || fail "CDUPREPROD_DB_USERNAME nije postavljen u $ENV_FILE"

# docker compose v2 (razmak) ili v1 (crtica) — CDU kutije znaju imati stari v1.
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  fail "nema ni 'docker compose' ni 'docker-compose'"
fi

# psql na kutiji NE POSTOJI (izmjereno 18.09.: nema ni mvn/npm/java), pa rezerva nije rezerva
# nego glavni put: psql iz postgres imagea. Docker Hub je s ove kutije dohvatljiv (provjereno
# `docker pull nginx:alpine`), pa se image po potrebi i povuče — jednokratno, pri prvom radu.
if command -v psql >/dev/null 2>&1; then
  run_psql() { PGPASSWORD="$DB_PASSWORD" psql "postgresql://${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}" -v ON_ERROR_STOP=1 "$@"; }
elif docker image inspect postgres:16-alpine >/dev/null 2>&1 \
     || docker pull postgres:16-alpine >/dev/null 2>&1; then
  run_psql() { docker run --rm --network host -e PGPASSWORD="$DB_PASSWORD" -v "${PROJECT_DIR}/tools:/tools:ro" \
      postgres:16-alpine psql "postgresql://${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}" -v ON_ERROR_STOP=1 "$@"; }
  BOOTSTRAP_SQL="/tools/$(basename "$BOOTSTRAP_SQL")"
else
  fail "nema psql-a ni postgres imagea — shemu nakon reseta netko mora kreirati (vidi tools/cdupreprod-bootstrap.sql)"
fi

log "=== jutarnji oporavak: start ==="

# --- 1. čekaj bazu -------------------------------------------------------------------------
# Namjerno provjera, a ne `sleep`: reset ne traje uvijek jednako, a dizanje aplikacije usred
# reseta znači pad na Liquibaseu i restart petlju (`restart: unless-stopped`).
deadline=$(( $(date +%s) + DB_WAIT_SECONDS ))
until run_psql -qtAc 'select 1' >/dev/null 2>&1; do
  [ "$(date +%s)" -lt "$deadline" ] || fail "baza ne prihvaća konekcije ni nakon ${DB_WAIT_SECONDS}s"
  log "baza još nije spremna, čekam…"
  sleep 30
done
log "baza prihvaća konekcije"

# --- 2. shema ------------------------------------------------------------------------------
# Skripta NE kreira shemu — nemamo CREATE na bazi (izmjereno 18.09., vrijedi i uz SET ROLE).
# Ona samo provjerava i puca ako sheme nema. Taj pad je namjeran i ovdje se mora zaustaviti
# cijeli oporavak: restart backenda u bazu bez sheme znači pad na Liquibaseu i restart petlju.
log "provjera sheme…"
if ! run_psql -f "$BOOTSTRAP_SQL" 2>&1 | tee -a "$LOG_FILE"; then
  log "PREKID: shema str_rn nije dostupna, a ne možemo je kreirati."
  log "        Backend NIJE restartan — ostaje na staroj konekciji umjesto da uđe u restart petlju."
  log "        Traži od DBA da str_rn izuzme iz noćnog reseta (vidi tools/cdupreprod-bootstrap.sql)."
  exit 1
fi

# --- 3. restart backenda -------------------------------------------------------------------
# Restart, ne `up -d`: image se ne rebuilda (kod se nije mijenjao), ali se ruše mrtve konekcije i
# Liquibase ponovno izgradi shemu iz changeloga.
log "restart backenda…"
$DC -f "$COMPOSE_FILE" --env-file "$ENV_FILE" restart backend >>"$LOG_FILE" 2>&1

# --- 4. čekaj dizanje konteksta ------------------------------------------------------------
deadline=$(( $(date +%s) + START_WAIT_SECONDS ))
until docker logs --since 10m "$BACKEND_CONTAINER" 2>&1 | grep -q 'Started StrBackendApplication'; do
  [ "$(date +%s)" -lt "$deadline" ] || {
    log "--- zadnjih 40 redaka loga ---"
    docker logs --tail 40 "$BACKEND_CONTAINER" 2>&1 | tee -a "$LOG_FILE"
    fail "backend se nije digao u ${START_WAIT_SECONDS}s"
  }
  sleep 10
done
log "backend je gore"

# --- 5. smoke ------------------------------------------------------------------------------
# Startup dijagnostika odgovara na cijeli preflight odjednom; ERROR ovdje znači da nešto ne radi
# iako je servis "Up" (najčešće: nema USAGE na rpj_dgu → adresna kaskada u formularu je mrtva).
docker logs --since 10m "$BACKEND_CONTAINER" 2>&1 | grep 'startup_' | tee -a "$LOG_FILE" || true
if docker logs --since 10m "$BACKEND_CONTAINER" 2>&1 | grep -q 'startup_schema_missing\|startup_schema_unreadable'; then
  log "POZOR: nedostaju prava na vanjskim shemama — grantovi nisu preživjeli reset (vidi bootstrap SQL)"
fi

captcha_code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${FRONTEND_PORT}/api/captcha/challenge" || echo 000)"
front_code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${FRONTEND_PORT}/" || echo 000)"
log "smoke: fronta=${front_code} captcha=${captcha_code}"
[ "$captcha_code" = "200" ] || log "POZOR: /api/captcha/challenge nije 200 — javni formulari su mrtvi (provjeri CAPTCHA_HMAC_KEY)"

log "=== jutarnji oporavak: gotovo ==="
