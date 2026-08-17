# Deploy — InfoDom dev okolina (`s-str-02`)

Ovo je **jedina okolina s koje je eGOP dohvatljiv** (eGOP test je na internoj InfoDom mreži,
`http://egop2builder/EAI_MINT`). CDU ga ne vidi, pa tamo `EGOP_ENABLED` ostaje `false`.
Za CDU vidi `DEPLOY-CDU.md`.

| | |
| :--- | :--- |
| Kutija | `s-str-02.infodom.hr` (pristup preko ssh; ključ i korisnik — vidi `~/.ssh/config`) |
| Profil | `dev` (`application-dev.properties`) |
| Baza | `s-str-02.infodom.hr:5431/str2`, shema `str_rn` (Liquibase, `contexts=dev,!local`) |
| Frontend | `http://s-str-02.infodom.hr:8085` |
| Backend | `http://s-str-02.infodom.hr:8086` |
| Compose | `docker-compose.yml` (u repou) + `.env.dev` (na kutiji, nije u repou) |
| Grana | `develop` |

## 1. Prvi put: `.env.dev`

```bash
cp .env.dev.example .env.dev    # pa popuniti DRAFT_ENC_KEY i EGOP_PASSWORD
```

Datoteka **mora** postojati — bez nje `docker compose up` puca na `env_file`. Prazna vrijednost
nije isto što i nepostavljena: `KLJUC=` šalje praznu ali postavljenu varijablu, a Spring default
iz `${KLJUC:default}` vrijedi samo kad varijabla ne postoji. Ključ koji ne postavljaš —
zakomentiraj.

## 2. Preflight: je li eGOP dohvatljiv

Dvije provjere, i **obje** su potrebne — s hosta i iz kontejnera:

```bash
# s hosta
nslookup egop2builder
curl -sS -o /dev/null -w '%{http_code}\n' "http://egop2builder/EAI_MINT/ServiceMDM.asmx?wsdl"
# očekuj 401 (NTLM challenge) ili 200; vješanje = firewall

# iz kontejnera (single-label host se u Linux kontejneru često NE razrješava)
docker exec str-backend getent hosts egop2builder
```

Ako je `getent` prazan, odkomentirati `extra_hosts` u `docker-compose.yml` s IP-om iz
`nslookup` i ponovno pokrenuti.

## 3. Deploy

```bash
cd ~/str/str_backend && git pull
docker compose --env-file .env.dev up -d --build
docker compose logs -f backend
```

Kutija **sama builda** image (Dockerfile ima `mvn clean package` fazu) — ne prenosi se JAR,
nego se pusha source i radi `git pull`.

## 4. Što gledati u logu

| Log | Znači |
| :--- | :--- |
| `egop_endpoints base=… mdm=…` | efektivne adrese — potvrda da se puca na pravi eGOP |
| `Loaded eGOP CODEBOOK_… with N entries` | MDM odgovara; `egop_codebook_entry` linije = puni popis (uz `EGOP_CODEBOOKS_LOG_CONTENTS=true`) |
| `egop_codebook_ustroj_korisnika_empty` | `DohvatiUstrojKorisnika` ne vraća ništa → gleda se `Active` |
| `egop_vrste_config` / `egop_vrste_privremene` | koje su šifre vrsta u igri i da su posudbene |
| `egop_odredi_rjesavatelja_failed` | rješavatelj nije prošao — `KreirajPismeno2` će vjerojatno pasti; probati drugi `EGOP_RJESAVATELJ` |
| `egop_filing_ok submission=… klasa=…` | cijeli tok prošao |

Ako aplikacija ne krene s `nedostaju obavezni propertyji: … base-url`, `EGOP_BASE_URL` nije
postavljen — to je namjerno, base URL nema ugrađeni default.

## 5. Provjera nakon registracije

```sql
SELECT submission_id, egop_sync_status, egop_sync_error, egop_klasa, filing_number
FROM str_rn.submission ORDER BY created_at DESC LIMIT 5;

SELECT vrsta_pismena_naziv, egop_vrsta_sifra, egop_vrsta_privremena, ur_broj, document_attached
FROM str_rn.egop_pismeno ORDER BY created_at DESC LIMIT 10;
```

Drugi upit je i **popis za storniranje** kad InfoDom dostavi prave šifre vrsta: sve s
`egop_vrsta_privremena = true` urudžbirano je pod tuđom vrstom. Tada se zamijene `EGOP_SIFRA_*`
u `.env.dev`, postavi `EGOP_SIFRE_PRIVREMENE=false` i restarta — bez izmjene koda.

Detalji o šifrarnicima i odabranim šiframa: `docs/eGOP-endpoint-analiza.md` §17.9.
