# MASTER TEHNIČKA SPECIFIKACIJA: STR BACKEND SUSTAV (v2.2)
**Projekt:** Modul za Kratkoročni Najam (Short-Term Rental - STR)  
**Datum:** 23. travnja 2026.  
**Status:** Spreman za implementaciju (Dev-Ready)  
**Tehnološki okvir:** Java 21, Spring Boot 3.2, PostgreSQL 16

---

## 1. ARHITEKTURALNI KONTEKST
Sustav se temelji na mikroservisnoj logici unutar zajedničke baze s jasnim razdvajanjem ovlasti putem shema:
- **Shema `core` (Read-only):** Dohvat matičnih podataka o vlasnicima i objektima.
- **Shema `str` (Read-write):** Potpuno vlasništvo modula za registraciju, zahtjeve i SDEP aktivnosti.

---

## 2. DETALJNA LOGIKA REGISTRACIJSKOG BROJA (RB)
Registracijski broj je centralni identifikator smještajne jedinice. Mora biti jedinstven, nepromjenjiv i generiran prema strogoj backend logici kako bi se spriječile pogreške i osigurala usklađenost s EU platformama.

### 2.1 Format i struktura
Registracijski broj se sastoji od točno **10 alfanumeričkih znakova**:
1. **Prefiks (2 znaka):** Fiksni kod `HR`.
2. **Numeričko tijelo (8 znamenki):** Generirani niz koji uključuje kontrolni broj.
  - *Primjer:* `HR49201835`

### 2.2 Algoritam generiranja (Backend Logic)
RB se generira asinkrono tek nakon uspješne validacije i digitalnog potpisa.
- **Entropija (7 znamenki):** Sustav generira nasumični niz od 7 znamenki koristeći `ThreadLocalRandom`.
- **Kontrolna znamenka (8. znamenka):** Izračunava se pomoću **Luhn algoritma (Modul 10)** nad prethodnih 7 znamenki.
- **Unique Check & Retry:** Prije spremanja, backend vrši provjeru u tablici `str.rb`. U slučaju kolizije, sustav koristi `@Retryable` mehanizam (do 3 pokušaja).

### 2.3 Životni ciklus (The Issue Trigger)
RB se perzistira u bazu isključivo kada su ispunjena oba uvjeta:
1. Svih **5 Grupnih Obrada (GO-1 do GO-5)** je uspješno završeno.
2. Zaprimljen je **Digitalni potpis** korisnika (callback s NIAS/EIDAS servisa).

---

## 3. MODEL PODATAKA (ERA SHEMA V2)

### 3.1 Tablica `str.rb` (Registracijski Brojevi)
- `id_rb`: Primary Key.
- `registracijski_broj`: VARCHAR(10) UNIQUE (Format: `HR\d{8}`).
- `id_sso`: FK na Smještajni Objekt.
- `status_rb`: ENUM (`AKTIVAN`, `SUSPENDIRAN`, `POVUCEN`, `U_OBRADI`).
- `datum_od`: Timestamp aktivacije.

### 3.2 Tablica `str.sso` (Smještajni Objekt)
Pohranjuje podatke specifične za STR registraciju:
- **Tehnički podaci:** Max broj kreveta, max broj gostiju, kat, broj stana.
- **Pravni statusi:** `Legalizirano` (DA/NE), `Zgrada` (DA/NE), `Stanovi` (DA/NE).
- **Suglasnost suvlasnika:** `Suglasnost` (DA/NE), `Datum_suglasnosti`, `Datum_povlačenja`.
- **Izračunato polje:** `Domaćin` (Boolean, rezultat GO-1).

### 3.3 Tablica `str.zahtjevi`
Prati životni ciklus procesa:
- `Kanal`: NIAS, EIDAS ili STRANAC (ručni unos/OCR).
- `Oznaka vrste zahtjeva`: Upis, Promjena, Opoziv.

---

## 4. VALIDACIJSKI ENGINE (GRUPNE OBRADE - GO)

Validacija se izvodi kroz sekvencijalni `ValidationChain`.

| ID | Naziv | Logika slaganja / Pravilo |
| :--- | :--- | :--- |
| **GO-1** | **Domaćin** | Usporedba JLS prebivališta vlasnika i lokacije objekta. |
| **GO-2** | **Zgrada** | Ako je `Zgrada=DA` i `Stanovi=DA`, sustav aktivira obvezu za GO-4. |
| **GO-3** | **Legalnost** | Provjera polja `Legalizirano`. Ako je `NE`, blokira se izdavanje RB-a. |
| **GO-4** | **Suglasnost** | Provjera postojanja dokumenta i praćenje `Datuma_povlačenja`. |
| **GO-5** | **Kapacitet** | `Unos_kreveta <= Kapacitet_u_rješenju_iz_Core_sheme`. |

---

## 5. KLJUČNI BACKEND TIJEKOVI (WORKFLOWS)

### 5.1 Registracija stranaca (Ekran 1)
Za korisnike bez nacionalnih vjerodajnica:
1. **Upload:** Backend prihvaća preslike osobne iskaznice (front/back).
2. **Verification:** Zahtjev dobiva status `PENDING_APPROVAL`.
3. **Approval:** Nakon provjere referenta, sustav omogućuje potpisivanje i izdavanje RB-a.

### 5.2 SDEP Integracija i Aktivnosti (Ekran 18)
- **Ingestion:** Periodički servis povlači podatke s platformi (Booking/Airbnb).
- **Aggregation:** Podaci o noćenjima spremaju se u `str.aktivnosti_sso` vezano uz Registracijski broj.
- **API:** Endpoint za prikaz i export statistike po razdobljima.

### 5.3 Automatizirana suspenzija (Job 15)
- **Daily Job:** Svaku ponoć sustav provjerava `Datum_povlačenja_suglasnosti`.
- **Action:** Ako je uvjet ispunjen, status RB-a automatski prelazi u `SUSPENDIRAN`.

---

## 6. TEHNIČKA IMPLEMENTACIJA (SENIOR SNIPPET)

### 6.1 Java Logic: RB Generator
```java
public String generateRB() {
    // Generiranje 7 nasumičnih znamenki
    int body = ThreadLocalRandom.current().nextInt(1000000, 9999999);
    
    // Luhn algoritam za 8. znamenku (kontrolni broj)
    int checksum = calculateLuhn(String.valueOf(body));
    
    return "HR" + body + checksum;
}