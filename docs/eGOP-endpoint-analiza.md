# eGOP integracija — analiza endpointa

> **Izvori:** `MINT — eGOP_specifikacija_integracijskih_web_servisa`, v1.11, 07.10.2025. (OBR-7508, 75 str.)
> + `eGOP-wsdl.zip` (`ServicePredmet.wsdl`, `ServiceSubjekt.wsdl`, `ServicePismeno.wsdl`, `ServiceMDM.wsdl`).
> **Verzija:** 1.1 · **Status:** izvor istine za eGOP integraciju.
> Zamjenjuje `docs/eGOP-integracija.md` i `docs/eGOP-otvorena-pitanja.md` (nacrti v0.1 pisani bez uvida u WSDL).
>
> ⚠️ **AŽURIRANO 24.07.2026. — §17.** InfoDom je isporučio **referentni klijent** (source code) koji
> nadjačava dio ove analize: odluka #1 je pala (koristi se ServiceMDM i legacy metode, ne „PoUredbi"),
> auth je NTLM, šifrarnici se dohvaćaju sa MDM-a po nazivu. Sekcije §5 (izbor PoUredbi metoda),
> §11 (blockeri A i B) i §15 čitati kroz prizmu §17. Integracija je implementirana u
> `com.str.backend.egop` (port referentnog klijenta + `EgopFilingService`).

---

## 1. Sažetak i opseg

Pregledano je **svih 75 stranica** specifikacije i **sva četiri WSDL-a**. Cilj: utvrditi koji se
endpoint koristi, na kojem mjestu u STR toku, i zašto baš taj — te što nam nedostaje prije prve linije koda.

### 1.1 Što eGOP modelira

eGOP10 je MINT-ova platforma za uredsko poslovanje. Modelira **predmete** (klasifikacijski označeni spisi),
**pismena** (akti/podnesci unutar predmeta), **subjekte** (stranke u registru subjekata),
**dokumente** (binarni sadržaj uz pismeno) i **priloge**.

STR se integrira tako da za svaki izdani registracijski broj otvori predmet, kreira **ulazno pismeno**
tipa „zahtjev za registraciju" i uz njega priloži generirani PDF. Sve daljnje upravne radnje
(rješavanje, izlazno pismeno) ostaju u eGOP-u kod MINT-ovih službenika.

### 1.2 Odluke koje definiraju opseg ove analize

| # | Odluka | Posljedica |
| :-- | :--- | :--- |
| 1 | ~~**Koriste se isključivo metode dokumentirane u spec-u**~~ → **NADJAČANO (24.07., §17)** | InfoDomov vlastiti referentni klijent koristi `ServiceMDM` (šifrarnici!) i legacy metode `KreirajSubjekta`/`KreirajPismeno2`/`KreirajDokumentZaPismeno`. Zabrana sa spec str. 5 očito ne vrijedi za metode koje sam InfoDom isporuči u klijentu. Blocker „šifrarnici e-mailom" je nestao — dohvaćaju se s MDM-a pri startu. |
| 2 | ~~**Samo EU put**~~ → **NADJAČANO** | Mailom naručitelja od 22.07.2026.: *„kreirati neupravni predmet … za svaki zahtjev **(EU i non-EU)**"*. Urudžbiranje ide za sve. Podjela EU/non-EU ostaje, ali samo kao **kanal dostave** (KP eGrađana ↔ e-mail), ne kao uvjet za eGOP. Vidi §14.2. `KreirajSubjektaProsirenoStranci` time vjerojatno ulazi u opseg → Q17. |
| 3 | Novi dokument, stari označeni kao superseded | Stari nacrti ostaju u repou kao trag razmišljanja. |

> **Napomena uz odluku 1.** Odluka je konzervativna i namjerno takva — pravno je čista i ne oslanja se
> na ništa što MINT nije potpisao. Cijena je konkretna i navedena je u §2.2 i §11: bez `ServiceMDM`
> nemamo nijedan šifrarnik, pa je **prvi uspješan poziv blokiran dok MINT ne odgovori e-mailom**.

---

## 2. Inventar metoda

### 2.1 Pokrivenost

WSDL izlaže **233 operacije**; spec dokumentira **41 (≈18 %)**.

| Servis | Operacija u WSDL-u | Dokumentirano u spec-u | Koristimo |
| :--- | ---: | ---: | ---: |
| `ServicePredmet` | 46 | 9 | 2 (+1 rezerva) |
| `ServiceSubjekt` | 19 | 5 | 2 |
| `ServicePismeno` | 124 | 27 (14 legacy + 13 „PoUredbi") | 1 (+3 rezerve) |
| `ServiceMDM` | 44 | **0** | 0 |
| **Ukupno** | **233** | **41** | **5 (+5 rezerva)** |

### 2.2 Što WSDL nudi, a mi po odluci #1 ne diramo

Navedeno da bi se znalo što se dobiva ako MINT naknadno odobri proširenje opsega.

**`ServiceMDM` (44 operacije, nula u spec-u).** Kompletan šifrarnik servis. Spec ga navodi samo u
listi URL-ova (str. 6 i 7) i ne opisuje nijednu metodu. Relevantne operacije koje bi *same*
riješile sva naša pitanja o šifrarnicima (§11):

| MDM operacija | Vraća | Riješilo bi |
| :--- | :--- | :--- |
| `DohvatiVrstePredmetaActive` | `VrstePredmetaInfo{idvrste, nazivVrstePredmeta, rokZaRjesavanje, idDosjea}` | `vrstaPredmeta` |
| `DohvatiVrstePismenaActive` | `VrstePismenaInfo{idVrstePismena, nazivVrstePismena}` | `vrstaPismena` |
| `ListaKnjiga` | `KnjigaInfo{idknjige, nazivknjige, idstupnjatajnosti}` | `upisnaKnjiga` |
| `DohvatiUstrojActive` | `UstrojInfo{idorgjedinice, oznakajedinica, idnadorgjedinice, nazivorgjedinice}` | `nadleznaOrgJedinica` |
| `ListaVrstePoslovnihSubjekata` | `TipOsobeInfo{oznaka, naziv, …}` | `tipOsobe` |
| `DohvatiUlogaSubjektaUPredmetimaAll` | `UlogeSubjekataUPredmetimaInfo{iduloge, nazivUloge}` | `idUlogePartnera` |
| `ListaUrbrojeva` | `UrbrojeviInfo{idurbroja, broj, naziv}` | `urBroj` |
| `ListaDrzave` | `Sifarnici{id, vrijednost}` | `isoKodDrzave` |
| `DohvatiZaposlenikByUserName` | `ZaposleniciInfo{…, idorgjediniceZaposlenika, sifraStvarateljaAkta}` | provjera nadležnosti računa (§5.0) |

**`ServicePredmet.KreirajPredmetPismenoSubjektPoUredbi`.** Jedan atomičan poziv koji kreira subjekt
+ predmet + pismeno i vraća sve identifikatore odjednom (`UredskaGodina`, `RbrPredmeta`,
`KlasifikacijskaOznaka`, `Jop`, `UrBroj`, `SubjektOznaka`, `JedinstvenaOznakaPismena`,
`JedinstveniIdentifikatorPismena`, `BrojcanaOznaka`). Prima i `vanjskiIdentifikatorEntiteta : string` —
polje koje bi prirodno primilo naš `submission_id` kao idempotency ključ. Time bi otpao cijeli
state machine iz §8.2 i tok bi se sveo na 2 poziva umjesto 4.

**Nekonzistentnost u samom spec-u.** Na str. 23, u opisu `KreirajSubjektaProsireno`, spec kaže:

> „Provjeru da li neki poslovni subjekt postoji u eGOP10 platforme kao eksterni vršimo metodom
> **DohvatiPodatkeSubjektaEksterni**."

Ta metoda postoji u WSDL-u, ali **nije dokumentirana** nigdje u spec-u. Spec dakle upućuje na metodu
koju vlastita pravila zabranjuju koristiti. Treba prijaviti MINT-u (pitanje Q9 u §12).

---

## 3. Transport i sigurnost

### 3.1 Pristupne adrese (spec str. 6–7)

| Servis | Test | Produkcija |
| :--- | :--- | :--- |
| Pismeno | `https://egopeaitest.mint.hr/ServicePismeno.asmx` | `https://egopeai.mint.hr/ServicePismeno.asmx` |
| Predmet | `https://egopeaitest.mint.hr/ServicePredmet.asmx` | `https://egopeai.mint.hr/ServicePredmet.asmx` |
| Subjekt | `https://egopeaitest.mint.hr/ServiceSubjekt.asmx` | `https://egopeai.mint.hr/ServiceSubjekt.asmx` |
| MDM | `https://egopeaitest.mint.hr/ServiceMDM.asmx` | `https://egopeai.mint.hr/ServiceMDM.asmx` |

WSDL se dobiva dodavanjem `?WSDL`. Fizički server (MINT interno): `MINTS-SHAPP1 (192.168.30.80)`.

### 3.2 Transport (iz WSDL-a — nije u spec-u)

- `targetNamespace` = `http://www.infodom.hr/egov` (isti za sva četiri servisa)
- `soapAction` = `http://www.infodom.hr/egov/<NazivMetode>`
- **document/literal**; svaki servis izlaže **i SOAP 1.1 i SOAP 1.2** binding (`…Soap` i `…Soap12` port)
- ASMX (.NET) — HTTP status je **200 i kod poslovne greške**; greška je u tijelu odgovora

> **⚠️ Gotcha — adresa u WSDL-u je neupotrebljiva.**
> ```xml
> <wsdl:port name="ServicePredmetSoap" binding="tns:ServicePredmetSoap">
>   <soap:address location="http://egop2builder/EAI_MINT/ServicePredmet.asmx"/>
> </wsdl:port>
> ```
> Sva četiri WSDL-a pokazuju na interni build hostname `egop2builder` preko **plain HTTP-a**.
> Endpoint se **mora** eksplicitno overrideati na adrese iz §3.1. Generirani klijent koji koristi
> default adresu iz WSDL-a neće se ni spojiti.

### 3.3 Autentifikacija (spec str. 5)

> „Autentifikacijski mehanizmi su implementirani oslanjajući se na **Windows/Basic authentication**
> Internet Information Servica Windows Servera (IIS)."

Spec ne kaže koji od ta dva je stvarno uključen na `egopeaitest.mint.hr`. Razlika je bitna:
Basic → trivijalno; NTLM → treba `httpclient-win` / JCIFS; Kerberos → treba SPN i keytab.
**Blokira prvi poziv** — pitanje Q2 u §12.

### 3.4 Autorizacija (spec str. 5)

eGOP ima vlastiti role-based model: administrator eGOP platforme dodjeljuje računu ovlasti
**po pojedinoj metodi pojedinog servisa**. Manjak ovlasti → `OperationSucceeded = false` + `ErrorCode -700`.

Ovlasti koje trebamo za naš tehnički račun:

- `Subjekt.DohvatiPodatkeSubjekta`, `Subjekt.KreirajSubjektaProsireno`
- `Predmet.KreirajPredmet2`, `Predmet.DohvatiPodatkePredmeta`, `Predmet.PostaviSubjektaNaPredmetu`
- `Pismeno.KreirajPismenoPoUredbi`, `Pismeno.KreirajDokumentZaPismenoPoUredbi`,
  `Pismeno.DohvatiPodatkePismenaPoUredbi`, `Pismeno.KreirajPrilogPoUredbi`,
  `Pismeno.KreirajDokumentZaPrilogPoUredbi`

### 3.5 Format korisničkog imena (spec str. 74)

U **svim** metodama `userName` / `userNameZaposlenika` ide u obliku `DOMENA\activedirectoryusername`.
Bez prefiksa domene → `ErrorCode -100` („Korisnik ne postoji!").

---

## 4. Model odgovora i grešaka

### 4.1 `BaseInfo` (spec str. 8)

Sve metode osim `DohvatiSubjektIdZaUsername` vraćaju skup koji nasljeđuje `BaseInfo`:

```
BaseInfo
├── OperationSucceeded : boolean
└── Errors             : ArrayOfErrorStatus     ← WSDL naziv; spec ga zove "ErrorStatus"
    └── ErrorStatus
        ├── ErrorCode    : int
        └── ErrorMessage : string
```

**HTTP 200 ne znači uspjeh.** Klijent *uvijek* prvo čita `OperationSucceeded`, pa tek onda konzumira
ostatak payloada.

### 4.2 Kodovi grešaka (spec str. 74–75)

| ErrorCode | Poruka / značenje | Tretman |
| :--- | :--- | :--- |
| `-1` | „Došlo je do neočekivane pogreške…" | Tehnička → retry |
| `-100` | „Korisnik ne postoji!" | Konfiguracijska (krivi `DOMENA\user`) → ne retry, alert |
| `-240` | „Ne postoji vrsta predmeta!" | Kriva `vrstaPredmeta` šifra → ne retry, alert |
| `-300` | „Poslovni subjekt ne postoji!" | Poslovna → ne retry |
| `-450` | „Rješavatelj ne postoji" | Konfiguracijska → ne retry, alert |
| `-700` | Narušeno **sigurnosno** pravilo (dinamički tekst) | Nedostaje ovlast → ne retry, alert |
| `-701` | Narušeno **poslovno** pravilo (dinamički tekst) | Npr. predmet zatvoren → ne retry |

`-700` i `-701` su generički kontejneri uvedeni u eGOP10 — semantika je u `ErrorMessage`.
Poruka se **logira i prikazuje u alertu, ali se ne parsira** (dinamički tekst, nije ugovor).

### 4.3 Iznimka: `DohvatiSubjektIdZaUsername` (spec str. 29)

Jedina metoda koja **ne** vraća `BaseInfo`, nego goli `int`:
`> 0` = `oznakaSubjekta`; `-1` operater ne postoji; `-2` korisnik aplikacije ne postoji;
`-3` nema prava; `-4` nema podrazumijevajuću oznaku subjekta; `-5` interna greška servisa.

---

## 5. Endpointi koje KORISTIMO (6)

> **Ispravak nakon uvida u Knjigu testiranja:** izvorno je ovdje bilo 5 metoda. Šesta —
> `ServicePismeno.KreirajIzlaznoPismenoPoUredbi` — dodana je jer Knjiga traži izlazne akte
> („potvrda o RB", Nalog, Dopis, obavijesti) urudžbirane u isti predmet. Obrazloženje i popis
> akata su u §13.4(b); potpis metode je u spec-u str. 52–55 i identičan je ulaznoj varijanti
> uz izostanak `subjekt*` parametara (stvaratelj se određuje iz `userName`).

### 5.0 Preduvjet koji vrijedi za cijelu skupinu

Spec, uvjeti za `KreirajPismeno*` (str. 30, ponovljeno na str. 50):

> „• AD full username (to jest službenik) koji kreira pismeno **mora biti nadležan za navedeni predmet**
> (rješavatelj, surješavatelj, načelnik ustrojstvene jedinice kojoj pripada predmet, djelatnik pisarnice)
> • Predmet ne smije biti zatvoren • … arhiviran • … storniran • … izvorno riješen"

**Ovo je najozbiljniji otvoreni rizik integracije.** Nije dovoljno da naš servisni račun ima *ovlast
na metodu* (§3.4) — mora biti i **nadležan nad konkretnim predmetom**. Ako predmet otvorimo s
`nadleznaOrgJedinica` u kojoj naš račun nije načelnik ni djelatnik pisarnice, `KreirajPredmet2` (korak 2)
proći će, a `KreirajPismenoPoUredbi` (korak 3) pasti s `-700`/`-701` — dakle kvar se manifestira
*tek na trećem pozivu*, s već otvorenim praznim predmetom u eGOP-u. Pitanje Q1 u §12.

---

### 5.1 `ServiceSubjekt.DohvatiPodatkeSubjekta` (spec str. 27–28)

**Gdje:** korak 1 toka — prije svakog kreiranja subjekta.

**Zašto:** eGOP nema idempotency ključeve ni „upsert" semantiku. Ovo je **jedina zaštita od dupliciranja
iznajmljivača** u registru subjekata. Bez ovog poziva svaki retry koji je pao nakon
`KreirajSubjektaProsireno` stvara drugi zapis za isti OIB.

```
DohvatiPodatkeSubjekta(
    userName        : string   DA
    oznakaSubjekta  : int      ⎫ jedan od tri
    mbJmbg          : string   ⎬ je obavezan
    oib             : string   ⎭
) → SubjektInfo : BaseInfo {
    oznakaSubjekta, adresa, mbjmbg, OIB, naziv, oznakaVrste,
    postanskiBroj, tipOsobe, urBroj, kratkiNaziv, nazivDrzave
}
```

Zovemo s `oib = lessor.lessorOib`. Ako vrati subjekt → koristi `oznakaSubjekta`, preskoči 5.2.

> ⚠️ Spec (str. 27) parametar zove **`subjektOznaka`**; WSDL ga zove **`oznakaSubjekta`**. Vidi §10.
> ⚠️ Ova metoda pretražuje **cijeli** registar. Provjeru „postoji li kao *eksterni* subjekt" radi
> `DohvatiPodatkeSubjektaEksterni`, koja nije dokumentirana (§2.2) pa je po odluci #1 ne koristimo.
> Praktična posljedica: ne možemo razlikovati eksternog od internog subjekta s istim OIB-om.

---

### 5.2 `ServiceSubjekt.KreirajSubjektaProsireno` (spec str. 23–25)

**Gdje:** korak 1 toka — samo ako 5.1 nije našao subjekt.

**Zašto baš ova, a ne `KreirajSubjekta`:** dva razloga, oba iz spec-a str. 23.

1. **Prošireni set podataka.** `KreirajSubjekta` prima adresu kao **jedan slobodni string** (`Adresa`),
   dok `KreirajSubjektaProsireno` prima strukturirano — `ulica`, `kucnibroj`, `naselje`, `postanskiBroj` —
   plus `telefon`, `email`, `ziroracun`. STR ima adresu iznajmljivača već razloženu na komponente
   (`LessorEntity.street` / `.streetNumber` / `.place`), pa bi je za `KreirajSubjekta` trebalo konkatenirati
   i time izgubiti strukturu.
2. **Dopušta isti OIB.** Spec: *„omogućuje kreiranje poslovnog subjekta sa istim OIBom ukoliko taj
   poslovni subjekt ne postoji kao eksterni poslovni subjekt eGOP10 platforme."* Bitno za slučaj kad je
   iznajmljivač ujedno i MINT-ov službenik — kao fizička osoba podnosi zahtjev vlastitom ministarstvu.

```
KreirajSubjektaProsireno(
    userName      : string   DA        mbJmbg    : string   NE
    tipOsobe      : string   DA ⚠️     naziv     : string   DA
    oib           : string   NE        telefon   : string   NE
    email         : string   NE        ziroracun : string   NE
    ulica         : string   NE        kucnibroj : string   NE
    naselje       : string   NE        postanskiBroj : string  DA ⚠️
    isoKodDrzave  : int      NE (nillable)
    urBroj        : string   NE
) → SubjektBasicInfo : BaseInfo { oznakaSubjekta : int }
```

`oznakaSubjekta` je **primarni identifikator subjekta** prema eGOP-u; čuvamo ga (§9).

Mapiranje — vidi §9.2. Dva polja označena ⚠️ su problem: `tipOsobe` je šifrarnik koji nemamo,
a `postanskiBroj` je po spec-u obavezan, a **STR ga za iznajmljivača uopće ne sprema**.

---

### 5.3 `ServicePredmet.KreirajPredmet2` (spec str. 11–13)

**Gdje:** korak 2 toka.

**Zašto baš `KreirajPredmet2`, a ne `KreirajPredmet`:** jedina razlika je povratni skup. `KreirajPredmet`
vraća `PredmetBasicInfo{uredskaGodina, rbrPredmeta}`, a `KreirajPredmet2` vraća `PredmetBasicInfo2` koji
uz to nosi i **`klasifikacijskaOznaka`**. Bez toga bi nakon svakog otvaranja predmeta trebao još jedan
poziv `DohvatiPodatkePredmeta` samo da bismo dobili klasu za prikaz korisniku — dakle dupli round-trip
i dodatna točka kvara, bez ikakve koristi.

```
KreirajPredmet2(
    userName            : string    DA
    upisnaKnjiga        : string    DA ⚠️  — dozvoljeno: NP, UP/I, UP/II
    vrstaPredmeta       : string    DA ⚠️  — eGOP šifra; određuje dosje predmeta
    nadleznaOrgJedinica : int       NE ⚠️  ⎫ ako se ne preda rjesavatelj,
    rjesavatelj         : string    NE     ⎭ onda je nadleznaOrgJedinica obavezna
    subjektOznaka       : int       ⎫
    subjektOIB          : string    ⎬ jedan od tri je obavezan
    subjektMBJmbg       : string    ⎭
    nazivPredmeta       : string    NE
    datumOtvaranja      : dateTime  NE — ako se ne pošalje, uzima se server time
) → PredmetBasicInfo2 : BaseInfo { uredskaGodina : int, rbrPredmeta : int, klasifikacijskaOznaka : string }
```

**Trajni identifikator predmeta je par `(uredskaGodina, rbrPredmeta)`** — sve ostale Predmet metode
identificiraju predmet tom kombinacijom. `klasifikacijskaOznaka` je human-readable derivat.

Vrijednosti koje šaljemo:

- `subjektOznaka` ← iz koraka 1 (jeftinije za eGOP nego ponovno razrješavanje po OIB-u)
- `nazivPredmeta` ← *„Zahtjev za registracijski broj — {naziv objekta}, {naselje}"*
- `datumOtvaranja` ← šaljemo eksplicitno, da ne ovisimo o vremenu na MINT-ovom serveru

⚠️ Tri parametra su blokirana šifrarnicima koje nemamo (`upisnaKnjiga`, `vrstaPredmeta`,
`nadleznaOrgJedinica`) — §11.

---

### 5.4 `ServicePismeno.KreirajPismenoPoUredbi` (spec str. 50–52)

**Gdje:** korak 3 toka.

**Zašto „PoUredbi", a ne legacy `KreirajPismeno` / `KreirajPismeno2`:** povratni skup. Legacy metode
vraćaju samo `jop` (interni int ID), odnosno `jop + urBroj`. „PoUredbi" varijanta vraća
**`jedinstveniIdentifikatorPismena` (GUID)** — po Uredbi o uredskom poslovanju to je službeni
cross-system identifikator akta, čija je svrha (citat iz spec-a, str. 52) *„provjera izvornosti,
cjelovitosti i sljedivosti pri razmjeni podataka između informacijskih sustava uredskog poslovanja"*.
Legacy generacija (dodana 2018.) nadopunjena je „PoUredbi" metodama u v1.9 spec-a (08.05.2023.)
upravo „sukladno novoj zakonskoj regulativi". Vezati se na legacy shemu znači vezati se na format
koji izlazi iz upotrebe i nemati zakonski priznat identifikator akta.

**Zašto ne `KreirajIzlaznoPismenoPoUredbi`:** ta metoda kreira izlazno pismeno i stvaratelja akta
određuje iz `username`. Nama treba **ulazno** pismeno — akt koji stranka podnosi tijelu.
`KreirajPismenoPoUredbi` odlučuje o smjeru automatski: *„Ukoliko predani stvaratelj akta predstavlja
ustrojstvenu jedinicu ili službenika organizacije — metoda će kreirati izlazno pismeno. U svim drugim
slučajevima metoda će kreirati **ulazno** pismeno."* (spec str. 50). Pošto je naš stvaratelj akta
iznajmljivač (vanjski subjekt), dobivamo ulazno pismeno bez dodatnog parametra.

```
KreirajPismenoPoUredbi(
    userName        : string    DA
    rbrSpisa        : int       NE ⚠️  — redni broj predmeta u koji se ulaže
    uredskaGodina   : short ⚠️  NE
    vrstaPismena    : string    DA ⚠️  — eGOP šifra
    subjektOznaka   : int       ⎫
    subjektMbJmbg   : string    ⎬ jedan od tri — stvaratelj akta
    subjektOIB      : string    ⎭
    nazivPismena    : string    DA
    datumNastanka   : dateTime  NE
) → PismenoBasicInfoUredba : BaseInfo {
    jedinstvenaOznakaPismena        : string   // "brojčana oznaka tijela - klasa - rbr pismena u predmetu"
    jedinstveniIdentifikatorPismena : string   // GUID
    jop                             : int      // eGOP interni ID
    brojcanaOznaka                  : string
}
```

> ⚠️ **`rbrSpisa` i `uredskaGodina` su po spec-u „NE" (neobavezni), ali ih uvijek šaljemo.**
> Bez njih pismeno nije vezano ni za jedan predmet — nastaje nepridruženo pismeno, što nije ono što želimo.
> `rbrSpisa` ← `rbrPredmeta` iz koraka 2.
> ⚠️ `uredskaGodina` je ovdje **`short`**, a u `KreirajPredmet2` je `int`. Vidi §10.

**Što čuvamo:** sva četiri polja odgovora. `jop` je najkraći ključ za daljnje pozive,
GUID je formalni identifikator po Uredbi, `jedinstvenaOznakaPismena` je ono što se prikazuje korisniku.

---

### 5.5 `ServicePismeno.KreirajDokumentZaPismenoPoUredbi` (spec str. 59–61)

**Gdje:** korak 4 toka — prilaganje PDF-a.

**Zašto:** jedini dokumentirani način da se binarni sadržaj priloži *samom pismenu* (a ne prilogu).
STR-ov PDF zahtjeva **jest** taj akt, pa ide kao dokument pismena, ne kao prilog.

```
KreirajDokumentZaPismenoPoUredbi(
    userName                        : string        DA
    jedinstveniIdentifikatorPismena : string        ⎫ jedan od dva
    jop                             : int           ⎭ je obavezan
    extension                       : string        DA
    attachment                      : base64Binary  DA
) → DokumentInfoUredba : BaseInfo   // ostali atributi nisu popunjeni pri kreiranju
```

**Šaljemo GUID, ne `jop`.** Oba su tehnički prihvatljiva, ali GUID je identifikator po Uredbi i
stabilan cross-system ključ; `jop` je interni eGOP int koji nema značenje izvan platforme.

`extension` = `"pdf"` (bez točke — pretpostavka, treba potvrda; pitanje Q7 u §12).
`attachment` = Base64 sadržaja PDF-a.

---

## 6. Endpointi u REZERVI (5)

Ne koriste se u prvoj fazi, ali su predviđeni i tražimo ovlasti za njih.

| Metoda | Spec | Kada bi se koristila |
| :--- | :--- | :--- |
| `Predmet.DohvatiPodatkePredmeta` | str. 19–20 | Sanity provjera nakon `KreirajPredmet2`; dohvat `statusPredmeta` za sinkronizaciju. Vraća `PredmetInfo` s `klasa`, `rjesavatelj`, `statusPredmeta`, `nadleznaUstrojstvenaJedinica`. |
| `Pismeno.DohvatiPodatkePismenaPoUredbi` | str. 57–59 | Nightly sync statusa pismena u STR. Jedino mjesto gdje dolazimo do **`urBroj`** — `KreirajPismenoPoUredbi` ga ne vraća (vraća `brojcanaOznaka`). |
| `Predmet.PostaviSubjektaNaPredmetu` | str. 16–17 | Ako se pokaže da uz glavnog subjekta treba evidentirati i dodatne sudionike. Traži `idUlogePartnera` šifru koju nemamo. |
| `Pismeno.KreirajPrilogPoUredbi` | str. 63–65 | Ako STR forma ikad dobije upload privitaka (npr. dokaz vlasništva). |
| `Pismeno.KreirajDokumentZaPrilogPoUredbi` | str. 67–68 | Par uz prethodnu — prilaže binarni sadržaj na već kreirani prilog. |

---

## 7. Endpointi koje NE KORISTIMO (30), s razlogom

### 7.1 `ServicePredmet` — 6 metoda

| Metoda | Spec | Zašto ne |
| :--- | :--- | :--- |
| `KreirajPredmet` | str. 9–10 | Nadomješten `KreirajPredmet2`; ne vraća klasifikacijsku oznaku (§5.3). |
| `OdrediRjesavatelja` | str. 13 | Dodjela rješavatelja je MINT-ov interni posao; STR ne odlučuje tko rješava. |
| `ZatvoriPredmet` | str. 14 | Zatvaranje predmeta je upravna radnja službenika, ne posljedica STR događaja. |
| `StornirajPredmet` | str. 15 | Isto — storniranje je odluka službenika. STR ne poništava predmete. |
| `ObrisiSubjektaNaPredmetu` | str. 18–19 | STR nikad ne uklanja sudionike iz eGOP predmeta. |
| `PonovoOtvori` | str. 21 | Ponovno otvaranje zatvorenog predmeta radi pisarnica. |

### 7.2 `ServiceSubjekt` — 3 metode

| Metoda | Spec | Zašto ne |
| :--- | :--- | :--- |
| `KreirajSubjekta` | str. 22–23 | Nadomješten `KreirajSubjektaProsireno` (§5.2). |
| `AzurirajPodatkeSubjekta` | str. 25–26 | **STR nije izvor istine za subjekt.** STR drži *immutable snapshot* iznajmljivača po registraciji (`LessorEntity`, identitetske kolone `updatable = false`), dok je eGOP subjekt *mutable singleton po OIB-u*. Pisanje našeg snapshota preko eGOP zapisa pregazilo bi podatke koje su unijeli drugi sustavi ili službenici. Nesklad ta dva modela je otvoreno pitanje Q6 u §12. |
| `DohvatiSubjektIdZaUsername` | str. 28–29 | Vraća oznaku subjekta *dodijeljenu službeniku*. Relevantno samo pri kreiranju **izlaznih** pismena, gdje je stvaratelj akta MINT-ov službenik. STR radi isključivo ulazna pismena. |

### 7.3 `ServicePismeno` — legacy generacija, svih 14

`KreirajPismeno` (str. 30–32) · `KreirajPismeno2` (str. 32–34) · `KreirajIzlaznoPismeno` (str. 34–36) ·
`PostaviSubjektaNaPismenu` (str. 37–38) · `DohvatiPodatkePismena` (str. 38–39) ·
`KreirajDokumentZaPismeno` (str. 40–41) · `ObrisiDokumentZaPismeno` (str. 41–42) ·
`DohvatiDokumentZaPismeno` (str. 42–43) · `KreirajPrilog` (str. 43–44) · `ObrisiPrilog` (str. 44–45) ·
`KreirajDokumentZaPrilog` (str. 45–46) · `ObrisiDokumentPriloga` (str. 46–47) ·
`DohvatiListuPrilogaPismena` (str. 47–48) · `DohvatiDokumentZaPrilog` (str. 49–50)

**Razlog za cijelu skupinu:** identificiraju pismeno isključivo preko `jop` i ne poznaju
`jedinstveniIdentifikatorPismena` (GUID). Nadomještene su „PoUredbi" varijantama u v1.9 spec-a
(08.05.2023., *„Nadopuna metoda grupe Web Service »Pismeno« sukladno novoj zakonskoj regulativi"*).
Miješanje dviju generacija na istom pismenu nema smisla — biramo jednu, i to onu usklađenu s Uredbom.

### 7.4 `ServicePismeno` — „PoUredbi" koje ne trebamo, 7 metoda

| Metoda | Spec | Zašto ne |
| :--- | :--- | :--- |
| ~~`KreirajIzlaznoPismenoPoUredbi`~~ | str. 52–55 | **⚠️ ISPRAVAK — ovu metodu ipak koristimo.** Isključena je uz obrazloženje „rješenje piše službenik", što ne vrijedi za neupravni automatski postupak. Knjiga testiranja (TC-STR-1.2-001) traži izlazni akt „potvrda o RB" već pri registraciji, plus sve akte suspenzije/povlačenja. Vidi §13.4(b) i tablicu akata. |
| `PostaviSubjektaNaPismenoPoUredbi` | str. 55–57 | Postavlja **primatelja** akta. Kod ulaznog pismena primatelj je samo tijelo (MINT) i eGOP ga postavlja sam. Spec upozorava i da poziv s `isGlavni = TRUE` **regenerira urudžbeni broj** — nuspojava koju ne želimo. |
| `ObrisiDokumentZaPismenoPoUredbi` | str. 61–62 | STR nikad ne briše dokumente iz eGOP-a. |
| `DohvatiDokumentZaPismenoPoUredbi` | str. 62–63 | STR već ima izvorni PDF u `submission.pdf_content`; nema potrebe dohvaćati ga natrag. |
| `ObrisiPrilogPoUredbi` | str. 66 | Isto — bez brisanja. |
| `ObrisiDokumentPrilogaPoUredbi` | str. 69–70 | Isto. |
| `DohvatiListuPrilogaPismenaPoUredbi` | str. 70–71 | U prvoj fazi ne šaljemo priloge, pa nema što listati. |
| `DohvatiDokumentZaPrilogPoUredbi` | str. 71–73 | Isto. |

**Kontrola zbroja:** 6 (§5) + 5 (§6) + 30 (§7) = **41** dokumentirana metoda. ✔

---

## 8. Integracijski tok za STR

### 8.1 Redoslijed

Polazni uvjet: registracijski broj je izdan (`RnStatus.ACTIVE`) i objavljen `RnIssuedEvent`.

```
┌─ 1 ─ SUBJEKT ────────────────────────────────────────────────────┐
│  DohvatiPodatkeSubjekta(oib = lessor.lessorOib)                  │
│    └─ nađen?  → uzmi oznakaSubjekta                              │
│    └─ nije?   → KreirajSubjektaProsireno(...) → oznakaSubjekta   │
└──────────────────────────────┬───────────────────────────────────┘
                               ▼
┌─ 2 ─ PREDMET ────────────────────────────────────────────────────┐
│  KreirajPredmet2(upisnaKnjiga, vrstaPredmeta,                    │
│                  nadleznaOrgJedinica, subjektOznaka, ...)        │
│    → uredskaGodina, rbrPredmeta, klasifikacijskaOznaka           │
└──────────────────────────────┬───────────────────────────────────┘
                               ▼
┌─ 3 ─ PISMENO ────────────────────────────────────────────────────┐
│  KreirajPismenoPoUredbi(rbrSpisa = rbrPredmeta,                  │
│                         uredskaGodina, vrstaPismena,             │
│                         subjektOznaka, nazivPismena, ...)        │
│    → jop, jedinstveniIdentifikatorPismena (GUID),                │
│      jedinstvenaOznakaPismena, brojcanaOznaka                    │
└──────────────────────────────┬───────────────────────────────────┘
                               ▼
              ╔════════════════════════════════════╗
              ║  TEK SADA renderiraj PDF —         ║
              ║  urudžbeni broj postoji od koraka 3║
              ╚════════════════┬═══════════════════╝
                               ▼
┌─ 4 ─ DOKUMENT uz ulazno pismeno ─────────────────────────────────┐
│  KreirajDokumentZaPismenoPoUredbi(GUID, "pdf", base64(pdf))      │
└──────────────────────────────┬───────────────────────────────────┘
                               ▼
┌─ 5 ─ IZLAZNO PISMENO ────────────────────────────────────────────┐
│  KreirajIzlaznoPismenoPoUredbi(rbrSpisa = rbrPredmeta,           │
│      uredskaGodina, vrstaPismena = <Obavijest o dodjeli RB>,     │
│      nazivPismena, datumNastanka)                                │
│    → jop, GUID, jedinstvenaOznakaPismena, urBroj                 │
└──────────────────────────────┬───────────────────────────────────┘
                               ▼
              ╔════════════════════════════════════╗
              ║  renderiraj PDF obavijesti         ║
              ╚════════════════┬═══════════════════╝
                               ▼
┌─ 6 ─ DOKUMENT uz izlazno pismeno ────────────────────────────────┐
│  KreirajDokumentZaPismenoPoUredbi(GUID₂, "pdf", base64(pdf₂))    │
└──────────────────────────────┬───────────────────────────────────┘
                               ▼
┌─ 7 ─ PERSIST u str_rn.submission ────────────────────────────────┐
│  egop_subjekt_oznaka, egop_uredska_godina, egop_rbr_predmeta,    │
│  egop_klasifikacijska_oznaka, egop_pismeno_jop,                  │
│  egop_pismeno_guid, egop_pismeno_oznaka, egop_sync_status=SYNCED │
└──────────────────────────────┬───────────────────────────────────┘
                               ▼
        ┌─ izvan eGOP-a ─────────────────────────────────┐
        │  dostava obavijesti: KP eGrađana (NIAS)        │
        │                    / e-mail (non-EU)           │
        └────────────────────────────────────────────────┘
```

**Izdavanje RB-a je dakle 6 poziva** (7 ako iznajmljivač još nije u registru subjekata), ne tri.
Uzrok razlike: (a) subjekt mora postojati prije predmeta i (b) **prilaganje PDF-a je zaseban poziv**,
nije parametar kreiranja pismena.

> **Redoslijed PDF-a nije proizvoljan.** PDF nosi oznaku pismena
> (`jedinstvenaOznakaPismena` = „brojčana oznaka tijela – klasa – redni broj pismena"), a ona u
> eGOP-u nastaje tek kreiranjem pismena. PDF se zato renderira **između** kreiranja pismena i
> prilaganja dokumenta — u koracima 3→4 i 5→6. Vidi §9.1: postojeći kod to radi obrnuto.
> Konačna potvrda ovisi o predlošcima koje naručitelj još nije dostavio (§14.4) — ako predložak
> ne sadrži oznaku, PDF se smije generirati unaprijed.

### 8.2 Atomarnost i idempotencija

eGOP nema XA transakcije ni idempotency ključeve (`vanjskiIdentifikatorEntiteta` postoji samo na
composite metodi koju po odluci #1 ne koristimo). Koraci 1–4 su četiri neovisna SOAP poziva.
Posljedice ponovljenog poziva:

| Metoda | Dvostruki poziv → | Zaštita |
| :--- | :--- | :--- |
| `KreirajSubjektaProsireno` | vjerojatno `-701`, ali moguć i duplikat | Obavezan `DohvatiPodatkeSubjekta` prije |
| `KreirajPredmet2` | **otvara drugi predmet** | STR state machine |
| `KreirajPismenoPoUredbi` | **kreira drugo pismeno u istom predmetu** | STR state machine |
| `KreirajDokumentZaPismenoPoUredbi` | nepoznato — overwrite ili greška | Pitanje Q8 u §12 |

**Naš idempotency mehanizam je stanje u bazi**, ne nešto što eGOP nudi:

```
NEW → SUBJEKT_OK → PREDMET_OK → PISMENO_OK → SYNCED
                                     ↘ FAILED (ručna intervencija)
```

Svaki korak se commita u zasebnoj transakciji zajedno s dobivenim identifikatorima. Reconciliation
job obrađuje zapise s `egop_sync_status NOT IN (SYNCED, FAILED)` i nastavlja **od zadnjeg
uspješnog koraka** — npr. pad između 3 i 4 znači ponovni poziv samo `KreirajDokumentZaPismenoPoUredbi`
s već pohranjenim GUID-om, bez ponovnog otvaranja predmeta.

### 8.3 Asinkronost

Frontend ne čeka eGOP. Registracijski broj je valjan neovisno o ishodu dostave — što je već
implementirano u `RnIssuedListener` (`@TransactionalEventListener(AFTER_COMMIT)`, greške se logiraju
a ne rollbackaju). eGOP polja se dopunjuju na postojećem `submission` zapisu kad sinkronizacija prođe.

---

## 9. Mapiranje na STR shemu i postojeći kod

### 9.1 Postojeći `EgopClient` je na krivom modelu

`src/main/java/com/str/backend/registries/EgopClient.java:24-26`:

```java
FilingNumber reserveFilingNumber();                        // ← ne postoji u eGOP-u
FilingConfirmation submitFiling(String filingNumber, byte[] pdf);
```

**U eGOP-u nema operacije „rezerviraj urudžbeni broj".** Klasa, urudžbeni broj i
`jedinstvenaOznakaPismena` nastaju kao *posljedica* kreiranja predmeta i pismena — nema ih se gdje
unaprijed zatražiti. Sučelje se mora redizajnirati da odražava tok iz §8.1.

Isti problem u pozivnom mjestu, `RnIssuedListener.java:100-108`:

```java
EgopClient.FilingNumber filing = egopClient.reserveFilingNumber();   // 1. broj
String filingNumber = filing.formatted();
byte[] pdf = renderPdf(event, accommodation, lessor, filingNumber);  // 2. PDF s brojem
egopClient.submitFiling(filingNumber, pdf);                          // 3. slanje
```

Redoslijed 1→2→3 je neizvediv protiv pravog eGOP-a; ispravan je 8.1 (predmet → pismeno → PDF → dokument).

`StubEgopClient` fabricira `KLASA: 334-01/25-01/<seq>, URBROJ: 529-06/25-1` — ostaje kao
local/mock implementacija, ali uz novo sučelje.

**U ovom koraku kod se ne mijenja** — ovo je nalaz za implementacijski task.

### 9.2 `LessorEntity` → `KreirajSubjektaProsireno`

| eGOP parametar | Izvor u STR-u | Napomena |
| :--- | :--- | :--- |
| `oib` | `lessor.lessorOib` | Filter EU/non-EU je upravo `lessorOib != null` |
| `naziv` | `firstName + " " + lastName`, ili `legalEntityName` ako `isLegalEntityOwner` | |
| `ulica` | `lessor.street` | |
| `kucnibroj` | `lessor.streetNumber` | |
| `naselje` | `lessor.place` | |
| `telefon` | `lessor.phoneNumber` (fallback `mobileNumber`) | |
| `email` | `lessor.email` | |
| `mbJmbg` | — | STR nema MB/JMBG iznajmljivača |
| `ziroracun` | — | STR ne prikuplja |
| `postanskiBroj` | **NEDOSTAJE** | Po spec-u str. 24 obavezan (`DA`). `LessorEntity` ima `street`/`streetNumber`/`place`/`county`, ali **nema poštanski broj**. Objekt ga ima (`event.postalCode()`), iznajmljivač nema. Treba ili proširiti model, ili razriješiti iz `place` preko `rpj_dgu.postanski_brojevi`. |
| `tipOsobe` | **NEDOSTAJE** | Šifrarnik. Mapiranje „fizička osoba / obrt / d.o.o." → eGOP šifra; STR ima `isLegalEntityOwner` boolean kao polazište. |
| `isoKodDrzave` | `lessor.countryOfResidenceId`? | Nije potvrđeno da je STR-ov `country_id` = ISO numerički kod. |
| `urBroj` | **NEDOSTAJE** | Šifrarnik urudžbenih brojeva. |

### 9.3 Predložene kolone na `str_rn.submission`

Trenutno (`SubmissionEntity.java:30-58`): `filing_number VARCHAR(64)`, `document_link VARCHAR(500)`,
`pdf_content BYTEA`, `lessor_id`, `authority_id`, `status`, `filing_date`.

Novi Liquibase changeset (bez brisanja postojećih kolona):

```sql
ALTER TABLE str_rn.submission
  ADD COLUMN egop_subjekt_oznaka         INT,
  ADD COLUMN egop_uredska_godina         INT,
  ADD COLUMN egop_rbr_predmeta           INT,
  ADD COLUMN egop_klasifikacijska_oznaka VARCHAR(64),
  ADD COLUMN egop_pismeno_jop            INT,
  ADD COLUMN egop_pismeno_guid           VARCHAR(64),
  ADD COLUMN egop_pismeno_oznaka         VARCHAR(128),
  ADD COLUMN egop_sync_status            VARCHAR(32) NOT NULL DEFAULT 'NEW',
  ADD COLUMN egop_synced_at              TIMESTAMP,
  ADD COLUMN egop_last_error             TEXT;

CREATE UNIQUE INDEX uk_submission_egop_predmet
  ON str_rn.submission (egop_uredska_godina, egop_rbr_predmeta)
  WHERE egop_uredska_godina IS NOT NULL;
```

Napomene:

- **`filing_number` ostaje STR-ov broj**, ne eGOP-ov. To su dvije neovisne stvari: STR-ov broj
  prikazujemo korisniku odmah, eGOP klasifikacijska oznaka stiže tek nakon sinkronizacije.
- `document_link` (*„URI dokumenta pohranjenoga u eGOP sustavu"*) ima **krivu semantiku** — eGOP ne
  vraća nikakav URL. Trenutno se puni s `"egop://" + filingNumber` (`RnIssuedListener.java:106`),
  što je izmišljena shema. Postupno se napušta; drop tek kad se potvrdi da nigdje ne piše.
- `egop_pismeno_guid` je `VARCHAR`, ne `UUID` — spec ga tipizira kao `string` i nigdje ne jamči
  kanonski UUID format.
- `egop_subjekt_oznaka` stoji na **submissionu**, ne na iznajmljivaču: `oznakaSubjekta` je doduše
  globalna po iznajmljivaču, ali držanjem na submissionu izbjegavamo pisanje u immutable snapshot.

---

## 10. Odstupanja spec ↔ WSDL

> Provjereno skriptirano nad svih 41 dokumentiranih metoda: za svaki parametar iz WSDL-a traženo je
> pojavljivanje kao zaseban redak u tablici parametara na pripadajućim stranicama spec-a.

Spec **nije pouzdan** za nazive i tipove parametara. Zatečena odstupanja:

| Metoda / tip | Spec kaže | WSDL stvarno |
| :--- | :--- | :--- |
| `KreirajPredmet` | — | **`idstupnjaTajnosti : int`** — 11. parametar kojeg spec **uopće ne spominje**; riječ „tajnost" ne pojavljuje se nijednom u 75 stranica. `KreirajPredmet2` ga *nema*. |
| `DohvatiPodatkeSubjekta` | `subjektOznaka` | **`oznakaSubjekta`** — spec si na istoj stranici (27) proturječi: proza kaže „definiran je atributom **oznakaSubjekta**", tablica kaže `subjektOznaka`. WSDL potvrđuje prozu. |
| `DohvatiSubjektIdZaUsername` | `userName`, `userNameKorisnikaAplikacije` | **`username`, `usernameKorisnikaAplikacije`** — jedina od 41 metode koja odstupa (vidi ispod) |
| `KreirajPredmet2` | `subjektMbJmbg` | **`subjektMBJmbg`** |
| `KreirajPismenoPoUredbi` | `subjektMbJmbg` | `subjektMbJmbg` — *drukčije kapitalizirano nego u Predmet servisu* |
| `KreirajPismenoPoUredbi` | `uredskaGodina : Int` | **`short`** |
| `PismenoInfoUredba` | `uredskaGodina : Int` | **`short`** |
| `BaseInfo` | `operationSucceeded`, lista `ErrorStatus` | **`OperationSucceeded`** (veliko O), **`Errors : ArrayOfErrorStatus`** — vidi napomenu ispod |
| `KreirajSubjektaProsireno` | redoslijed `telefon, ziroracun, email`; „Kućni broj" | `telefon, email, ziroracun`; **`kucnibroj`** (spec u tablici koristi ljudsku oznaku s razmakom i dijakritikom, ne identifikator) |
| `PredmetInfo` | `Rjesavatelj` naveden dvaput | jedan `rjesavatelj` |
| `KreirajSubjektaProsireno` | `postanskiBroj` obavezan (`DA`) | `minOccurs="0"` — obaveznost je poslovna, ne shemska |
| razni `int` / `dateTime` označeni „NE" | „neobavezan" | `minOccurs="1" nillable="true"` → **element se mora poslati, kao `xsi:nil="true"`**, ne izostaviti |

**Napomena o `Errors` vs `ErrorStatus`:** oba naziva su na svoj način točna, na različitim razinama.
Struktura je `BaseInfo.Errors : ArrayOfErrorStatus`, a `ArrayOfErrorStatus` sadrži ponovljive elemente
`ErrorStatus`. Spec imenuje unutarnji element, WSDL vanjsko polje. U generiranom Java kodu pristup je
`resp.getErrors().getErrorStatus()` — dvorazinski, što se lako promaši ako se ide po spec-u.

**Naziv auth parametra — sustavna provjera.** Od 41 dokumentirane metode **40 koristi `userName`**,
a točno jedna (`DohvatiSubjektIdZaUsername`) koristi `username`. Spec ga u većini tablica piše kao
`Username` (veliko U), što nije točno **ni za jednu** metodu. Kod document/literal SOAP-a krivo
kapitaliziran element se ne veže na parametar — server ga vidi kao `null` i vraća `-100`
(„Korisnik ne postoji!"), što izgleda kao problem s računom, a zapravo je tipfeler.

**Zaključak:** klijent se generira iz WSDL-a (`cxf-codegen-plugin`, Apache CXF `wsdl2java`),
**nikad ručno po spec-u**. Spec se čita za *semantiku i poslovna pravila*; WSDL je ugovor.

Posljednja stavka u tablici zaslužuje naglasak: value-type parametri su `nillable`, pa se
„neobavezno" ne postiže izostavljanjem elementa nego eksplicitnim `xsi:nil`. Generirani JAXB kod
to hvata kao `Integer`/`XMLGregorianCalendar` wrapper — pisanjem klijenta na ruku vrlo lako se
pogriješi i dobije `-1`.

### 10.1 Strukturni nalazi iz WSDL-a (vrijedi za sva četiri servisa)

Provjereno pretragom nad sva četiri WSDL-a; svi nalazi su *odsutnosti*, i svaki ima praktičnu posljedicu.

| Nalaz | Broj pojava | Zašto je bitno |
| :--- | ---: | :--- |
| `<soap:header>` | **0** | Nema WS-Security ni bilo kakvog SOAP zaglavlja. Autentikacija je **isključivo na HTTP razini** (IIS). Ne treba nam WSS4J ni potpisivanje poruka — samo HTTP auth na `HttpComponentsMessageSender`-u. |
| `<wsdl:fault>` | **0** | **Nijedna metoda ne deklarira SOAP fault.** Generirani klijent stoga *nikad neće baciti exception* na poslovnu grešku — sve se vraća kao HTTP 200 s `OperationSucceeded=false`. Provjera uspješnosti mora biti eksplicitna u kodu; `try/catch` ne hvata ništa osim mrežnih kvarova. |
| `xop`/MTOM | **0** | Privitci idu **inline kao Base64 u SOAP tijelu**, bez MTOM optimizacije. Napuhavanje od ~33 % je neizbježno i nema streaminga — cijeli PDF je u memoriji i u jednoj XML poruci. Zato je pitanje maksimalne veličine (Q11) stvarno, ne teoretsko. |
| `<s:enumeration>` za šifrarnike | **0** | WSDL ne ograničava nijednu šifru — `vrstaPredmeta`, `vrstaPismena`, `tipOsobe`, `upisnaKnjiga` su svi obični `xs:string` bez ijednog dopuštenog popisa. **WSDL nam ne pomaže oko šifrarnika ni najmanje.** (Jedina enumeracija u sva četiri fajla je `ObrtniServiceSpolEnum{F,M}` u Subjekt servisu, za Obrtni registar.) Upravo zato postoji `ServiceMDM` — i zato nas odluka #1 ostavlja bez ijednog izvora šifara osim e-maila. |
| `<http:binding>` | **0** | Samo SOAP; nema HTTP GET/POST bindinga. Ne postoji „brzi curl test" bez SOAP omotnice. |
| `attachment` tip | 6 metoda, sve `s:base64Binary` | Jednolično. Spec mjestimično piše „Binary array", mjestimično „Base64 Binary" — na žici je uvijek isto. |

**Potvrda uz §6:** `PismenoBasicInfoUredba` (odgovor na `KreirajPismenoPoUredbi`) ima točno četiri
polja — `jedinstvenaOznakaPismena`, `jedinstveniIdentifikatorPismena`, `jop`, `brojcanaOznaka` —
i **nema `urBroj`**. Time je potvrđeno da je `DohvatiPodatkePismenaPoUredbi` jedini način da dođemo
do urudžbenog broja.

### 10.2 Provenijencija WSDL-ova

Datoteke iz `eGOP-wsdl.zip`, sve s istim timestampom **22.07.2026. 14:34** (dakle svježe izvučene):

| Datoteka | Veličina | SHA-256 (prvih 16) |
| :--- | ---: | :--- |
| `ServicePredmet.wsdl` | 152 647 B | `1ea3b4029fa6b804` |
| `ServiceSubjekt.wsdl` | 89 200 B | `f7c87d0592d0b0a8` |
| `ServicePismeno.wsdl` | 428 239 B | `11f0a1f35c310ef6` |
| `ServiceMDM.wsdl` | 130 245 B | `894c159110a3d90b` |

Hashovi su zabilježeni da se pri sljedećoj isporuci može utvrditi je li se ugovor promijenio —
spec nema mehanizam najave breaking changeova (Q13).

---

## 11. Što nam fali (blokira prvi poziv)

Poredano po tome što prije blokira.

| # | Nedostaje | Blokira | Posljedica ako pogriješimo |
| :-- | :--- | :--- | :--- |
| 1 | **Nadležnost servisnog računa nad predmetom** (§5.0) | korak 3 | `KreirajPredmet2` prođe, `KreirajPismenoPoUredbi` padne `-700`/`-701` → prazan predmet u eGOP-u |
| 2 | **Autentifikacijski mehanizam** — Basic / NTLM / Kerberos (§3.3) | svaki poziv | Ne možemo se ni spojiti |
| 3 | **Kredencijali za test okruženje** | svaki poziv | — |
| 4 | `vrstaPredmeta` šifra | korak 2 | `-240` „Ne postoji vrsta predmeta!" |
| 5 | `vrstaPismena` šifra | korak 3 | odbijeno |
| 6 | `upisnaKnjiga` — potvrda da je **`NP`** ispravno (vidi §13) | korak 2 | krivi upisnik |
| 7 | `nadleznaOrgJedinica` — šifrarnik + mapping po županiji | korak 2 | obavezno ako ne šaljemo `rjesavatelj` |
| 8 | `tipOsobe` šifra (fizička / obrt / d.o.o.) | korak 1 | odbijeno |
| 9 | `postanskiBroj` iznajmljivača — **nemamo ga u modelu** (§9.2) | korak 1 | spec ga traži kao obavezan |
| 10 | `idUlogePartnera` šifra | rezerva | samo ako aktiviramo `PostaviSubjektaNaPredmetu` |
| 11 | Max veličina attachmenta | korak 4 | Base64 napuhuje ~33 %; IIS default ≈ 4 MB |
| 12 | Retry semantika `KreirajDokumentZaPismenoPoUredbi` | reconciliation | overwrite ili duplikat — mijenja dizajn retryja |

Stavke 4–8 i 10 su sve šifrarnici koje bi `ServiceMDM` vratio sam. Po odluci #1 ih tražimo e-mailom.

---

## 12. Pitanja za MINT

> Formulirano tako da se može poslati kakvo jest.

**Q1 — Nadležnost servisnog računa.** Specifikacija (str. 30 i 50) traži da AD korisnik koji kreira
pismeno bude *nadležan za predmet* (rješavatelj, surješavatelj, načelnik ustrojstvene jedinice ili
djelatnik pisarnice). Naš servisni račun otvara predmet automatski i odmah u njega ulaže ulazno
pismeno. Kako to riješiti — treba li račun dobiti ulogu djelatnika pisarnice, ili predmet otvaramo
s `rjesavatelj` postavljenim na taj isti račun? Postoji li preporučeni obrazac za sustave koji
podnose zahtjeve u ime vanjskih stranaka?

**Q2 — Autentifikacija.** Specifikacija (str. 5) navodi „Windows/Basic authentication" na IIS-u.
Što je konkretno uključeno na `egopeaitest.mint.hr` i na produkciji — HTTP Basic, NTLM ili Kerberos?

**Q3 — Kredencijali i pristup.** Molimo AD račun za test okruženje, popis ovlaštenih metoda za taj
račun (predložene u §3.4 ovog dokumenta), te informaciju postoji li IP allowlist na MINT strani.

**Q4 — Šifrarnici.** Nazive vrsta imamo iz maila od 22.07.2026.; trebamo **eGOP šifre** za njih, i
potvrdu **postoje li već definirane u eGOP-u** ili ih administrator tek treba kreirati:
- `vrstaPredmeta` za neupravni predmet „Izdavanje Registracijskog broja"
- `vrstaPismena` za svih 7 vrsta iz §14.3 (2 ulazne + 5 izlaznih)
- `tipOsobe` (fizička osoba / obrt / d.o.o.), `idUlogePartnera`, te popis `nadleznaOrgJedinica`
  s mapiranjem po županiji.

**Q5 — `upisnaKnjiga`.** Naša interna dokumentacija (Knjiga testiranja STR v4, preko
`docs/STR-USPOREDBA-KNJIGA-TESTIRANJA.md`) opisuje eGOP predmet kao **neupravni**, dakle
`upisnaKnjiga = NP`. Molimo potvrdu MINT-ove pisarnice da je to ispravno za registraciju objekta
u kratkoročnom najmu, odnosno da se ne radi o `UP/I` (prvostupanjski upravni postupak).

**Q6 — Subjekt kao mutable singleton.** STR drži nepromjenjiv snapshot iznajmljivača po registraciji;
eGOP subjekt je mutable po OIB-u. Ako iznajmljivač promijeni adresu i podnese novi zahtjev, tko
ažurira eGOP subjekt — mi kroz `AzurirajPodatkeSubjekta`, ili službenik ručno? I: novi zahtjev
istog iznajmljivača otvara **novi predmet** ili se dodaje **pismeno na postojeći**?

**Q7 — `extension` format.** Za `KreirajDokumentZaPismenoPoUredbi` šaljemo `"pdf"` — bez točke,
mala slova. Potvrda?

**Q8 — Retry na dokumentu.** Ako se `KreirajDokumentZaPismenoPoUredbi` pozove dvaput za isto pismeno,
prepisuje li drugi poziv prvi dokument, ili vraća grešku? Bitno za dizajn ponovnih pokušaja.

**Q9 — `DohvatiPodatkeSubjektaEksterni`.** Specifikacija na str. 23 upućuje na tu metodu kao način
provjere postoji li subjekt kao eksterni, ali je nigdje ne dokumentira — a str. 5 zabranjuje
korištenje nedokumentiranih metoda. Molimo pojašnjenje: smijemo li je koristiti, i može li se
dokumentirati u sljedećoj reviziji?

**Q10 — `ServiceMDM`.** Servis je naveden u pristupnim adresama (str. 6–7), ali nijedna njegova metoda
nije opisana. Postoji li zasebna specifikacija? Ako da, molimo je — MDM bi nam riješio sve iz Q4
bez ručnog održavanja šifara na našoj strani.

**Q11 — Veličina privitka.** Koje je ograničenje veličine za `attachment` (Base64) na eGOP strani?

**Q12 — SLA i održavanja.** Očekivana vremena odgovora i planirani prozori nedostupnosti — trebamo
za podešavanje timeouta i circuit breakera.

**Q13 — Revizije specifikacije.** Postoji li obavijest o novim verzijama i najava breaking changeova?
Tko je danas kontakt za pitanja o specifikaciji (u dokumentu su navedeni Antun Divald i Krešimir Kavran)?

**Q14 — Izlazna pismena iz STR-a.** Naša Knjiga testiranja opisuje postupak kao *neupravni,
automatski, bez intervencije službenika*, s ulaznim aktom „zahtjev za registraciju" i **izlaznim
aktom „potvrda o RB"**, te traži da se sva ulazna i izlazna komunikacija urudžbira u isti predmet
(potvrda o RB, Dopis o namjeri, Nalog za suspenziju/povlačenje, obavijesti). Potvrđujete li da STR
ta izlazna pismena kreira sam, kroz `KreirajIzlaznoPismenoPoUredbi`, i da servisni račun treba
ovlast i nadležnost i za tu metodu? Trebamo i zasebne `vrstaPismena` šifre za svaku vrstu akta
iz tablice u §13.4.

**Q15 — Jedan predmet po registracijskom broju.** Mailom je potvrđeno da svi akti idu u „isti
neupravni predmet". Smije li taj predmet ostati otvoren neodređeno dugo, ili ga se u nekom trenutku
zatvara (`ZatvoriPredmet`) pa ponovno otvara (`PonovoOtvori`) pri svakoj promjeni statusa?
Podsjetnik: `KreirajPismeno*` odbija ulaganje u zatvoren, arhiviran, storniran ili izvorno riješen
predmet (spec str. 30, 50).

**Q16 — Potpunost i nazivlje liste akata.** Mail navodi 7 vrsta pismena (§14.3), ali:
- Knjiga testiranja spominje još dva akta koje mail ne navodi — „obavijest o reaktivaciji"
  (TC-STR-2.2-001) i obavijest o rezultatu nasumične SDEP provjere (TC-STR-4.2-001/5).
  Idu li i oni u predmet?
- Nazivi se razlikuju između Knjige i maila: „Dopis o namjeri suspenzije" ↔ „Obavijest o prijedlogu
  suspenzije", „Nalog za suspenziju" ↔ „Obavijest o suspenziji", „potvrda o RB" ↔ „Obavijest o
  dodjeli registracijskog broja". Radi li se o istim aktima pod drugim nazivom, ili su to različite
  vrste pismena? O tome ovisi koliko `vrstaPismena` šifara tražimo.

**Q18 — Potvrda mapiranja akt → metoda.** U prilogu je popis od 7 vrsta pismena i 1 vrste predmeta
koje traži vaš mail (§14.3). Molimo da za **svaki** akt navedete koju integracijsku metodu očekujete
i pripadnu šifru:
- Koristimo li „PoUredbi" generaciju metoda (`KreirajPismenoPoUredbi`,
  `KreirajIzlaznoPismenoPoUredbi`, `KreirajDokumentZaPismenoPoUredbi`) ili legacy?
- Za izlazna pismena (obavijesti): `KreirajIzlaznoPismenoPoUredbi`, gdje se stvaratelj akta izvodi
  iz `userName`, ili `KreirajPismenoPoUredbi` s ustrojstvenom jedinicom kao stvarateljem?
  O tome ovisi brojčana oznaka u urudžbenom broju.
- Smijemo li koristiti metode koje postoje u WSDL-u a nisu u specifikaciji v1.11 — konkretno
  `KreirajPredmetPismenoSubjektPoUredbi` (kreira subjekt + predmet + pismeno u jednom pozivu),
  `KreirajUlaznoPismenoPoUredbi`, `KreirajSubjektaProsirenoStranci` i `ServiceMDM` za šifrarnike?

**Q17 — Strani subjekti u registru.** Sada kada se predmet otvara i za non-EU iznajmljivače,
treba li ih upisivati kroz `KreirajSubjektaProsirenoStranci` (traži `vrstaIdDokumenta`,
`brojIDDokumenta`, `isoKodDrzaveIzdavanja`) umjesto obične `KreirajSubjektaProsireno`?
STR za non-EU iznajmljivača nema OIB nego strani porezni broj, datum rođenja i državu prebivališta.
Napomena: `KreirajSubjektaProsirenoStranci` postoji u WSDL-u, ali **nije dokumentirana u spec-u** —
pa vrijedi i pitanje smijemo li je koristiti (isto kao Q9).

---

## 13. Gdje je definirano *kada* STR uopće koristi eGOP

Ovo je zasebno pitanje od svega dosad. Sve gore opisuje **kako** se eGOP zove; ova sekcija je o tome
**gdje piše da ga moramo zvati i u kojim trenucima**.

### 13.1 U specifikaciji — nigdje, i to namjerno

Spec str. 5: *„Web Servisi su generički i primjenjivi su za sve sustave koji se naslanjaju na uredsko
poslovanje."* Dokument opisuje transport i ugovor; **nema nijedne rečenice o STR-u**. Ne kaže koja je
vrsta predmeta naša, kada se otvara, što ga okida ni koji akti u njega idu. To je očekivano — spec je
zajednički za sve MINT-ove sustave.

### 13.2 U Knjizi testiranja — riječ „eGOP" ne postoji

*Knjiga testiranja STR v4* (`knjiga_testiranja_STR_v4.docx`, verzija sadržaja 3.0, 17.06.2026.,
17 testnih slučajeva na 12 UC-ova) je izvor poslovne logike. Provjereno strojno nad punim tekstom:

| Pojam | Pojavljivanja |
| :--- | ---: |
| **`eGOP`** | **0** |
| `klasifikacijska oznaka` / `klasa` | **0** |
| `pisarnica` | 1 |
| `urudžbiranje` | 1 |
| `neupravni` | 3 |
| `predmet` | 5 |
| `KP` (komunikacijski pretinac) | 14 |

**Nema nijednog UC-a ni TC-a za eGOP.** Cijeli zahtjev stoji u **tri rečenice**, sve tri u polju
„Napomena", nijedna ne imenuje sustav:

> **TC-STR-1.1-001:** *„**Neupravni postupak** — odvija se automatski, bez intervencije službenika.
> **Integracija s pisarnicom automatska.**"*

> **TC-STR-1.2-001:** *„**Neupravni automatski postupak. Ulazni akt: zahtjev za registraciju.
> Izlazni akt: potvrda o RB.**"*

> **TC-STR-2.1-001:** *„Sukladno čl. 6. STR Uredbe. **Sva ulazna/izlazna komunikacija urudžbiranjem
> u isti neupravni predmet.**"*

Uz to, `predmet` se spominje i u koracima: *„otvoriti predmet zahtjeva za registraciju"* (TC-2.1-001/1),
*„Dopis je vidljiv u TuStart kao **novi akt u predmetu zahtjeva**"* (TC-2.1-001/2),
*„Akt je vidljiv u TuStart u predmetu zahtjeva za registracijom"* (TC-2.1-001/4), te
*„obavijest … vidljiva u predmetu zahtjeva za registracijom"* (TC-4.2-001/5).

### 13.3 U našem repou — četiri tvrdnje, dvije netočne

| Izvor | Tvrdi | Ocjena |
| :--- | :--- | :--- |
| `docs/STR-USPOREDBA-KNJIGA-TESTIRANJA.md:101` | „**eGOP urudžbiranje** (neupravni predmet)" | **Točno** — vjerna interpretacija Knjige |
| `RnDocumentService.java:27` | „urudžbiranje u isti **neupravni** predmet (eGOP)" | **Točno**, neovisno potvrđuje |
| `docs/DOKUMENTACIJA.md:202` | „eGOP / Sudski registar · Lookup zastupnika pravne osobe · **HTTP REST**" | **Netočno** — eGOP nije Sudski registar, ne služi za lookup zastupnika, i nije REST nego SOAP |
| `.claude/skills/str-validation-engine/SKILL.md:39` | GO-3 legality flag „sourced from **eGOP** / building registry" | **Netočno** — legalnost dolazi iz MPGI/registra zgrada |

Ime „eGOP" dakle nigdje ne dolazi iz poslovne dokumentacije — ono je **naš** naziv za sustav koji
Knjiga zove „pisarnica".

### 13.4 Tri posljedice koje mijenjaju dizajn

**(a) Upisnik je `NP`, ne `UP/I`.** Prethodni nacrt i prva verzija ovog dokumenta pretpostavljali su
`UP/I` jer „radi se o upravnom postupku". Knjiga na dva mjesta izrijekom kaže **neupravni postupak**
(TC-1.1-001, TC-1.2-001). Pretpostavka je bila kriva; ispravljeno u §11 i Q5.

**(b) Registracija stvara DVA pismena, ne jedno.** TC-STR-1.2-001 je eksplicitan:
*„Ulazni akt: zahtjev za registraciju. **Izlazni akt: potvrda o RB**."* Uz to, postupak je
*„automatski, bez intervencije službenika"* — dakle **izlazni akt generira i urudžbira STR**, ne čovjek.

To obara isključenje `KreirajIzlaznoPismenoPoUredbi` iz §7.4, gdje je odbačena uz obrazloženje
„rješenje piše MINT-ov službenik". Za neupravni automatski postupak to ne vrijedi.
**Ispravljen broj metoda: 6 koristimo, 5 rezerva, 30 ne koristimo** (i dalje 41).

**(c) eGOP se koristi kroz cijeli životni ciklus, i za ulazne i za izlazne akte.** TC-2.1-001:
*„Sva ulazna/izlazna komunikacija urudžbiranjem u isti neupravni predmet."* Predmet je dugovječan
i vezan uz registracijski broj. Popis akata izveden iz Knjige:

| Trenutak | Akt | Smjer | Metoda |
| :--- | :--- | :--- | :--- |
| Registracija | Zahtjev za registraciju | ulazni | `KreirajPismenoPoUredbi` |
| Registracija | Potvrda o RB | **izlazni** | `KreirajIzlaznoPismenoPoUredbi` |
| Suspenzija | Dopis o namjeri suspenzije | izlazni | `KreirajIzlaznoPismenoPoUredbi` |
| Suspenzija | Nalog za suspenziju | izlazni | `KreirajIzlaznoPismenoPoUredbi` |
| Suspenzija | Obavijest o suspenziji (s Nalogom) | izlazni | `KreirajIzlaznoPismenoPoUredbi` |
| Prigovor (TC-2.2-001/2) | Prigovor s ispravljenom dokumentacijom | **ulazni** | `KreirajPismenoPoUredbi` |
| Reaktivacija | Obavijest o reaktivaciji | izlazni | `KreirajIzlaznoPismenoPoUredbi` |
| Povlačenje | Nalog za povlačenje | izlazni | `KreirajIzlaznoPismenoPoUredbi` |
| Povlačenje | Obavijest o povlačenju (s Nalogom) | izlazni | `KreirajIzlaznoPismenoPoUredbi` |
| SDEP provjera (TC-4.2-001/5) | Obavijest o rezultatu nasumične provjere | izlazni | `KreirajIzlaznoPismenoPoUredbi` |

Svi ulaze u **isti** predmet, dakle s `rbrSpisa`/`uredskaGodina` iz ranije pohranjenog predmeta.
Nova metoda za „dodaj pismeno u postojeći predmet" nije potrebna — to su iste dvije metode s
drugim `rbrSpisa`. Identifikator predmeta mora biti dohvatljiv iz RB-a: `RnEntity` ima
`submissionId` (`RnEntity.java:27`), pa je put `rn → submission → egop_*` prohodan i shema iz §9.3
je dovoljna.

> ⚠️ Uz to, `KreirajIzlaznoPismenoPoUredbi` traži **istu nadležnost** kao ulazna varijanta
> (spec str. 52–53, isti popis uvjeta). Nalaz iz §5.0 time postaje još važniji — ne pogađa jedan
> poziv nego svaki akt u životnom ciklusu.

### 13.5 eGOP nije kanal dostave — to je KP

Ovo je konceptualna zamjena koju treba razriješiti. U Knjizi **svaka** isporuka korisniku ide u
**KP (komunikacijski pretinac)**, potpisana **ePečatom MINTS** — 14 spominjanja KP-a, nijedno
spominjanje e-maila kao kanala. eGOP („pisarnica") je isključivo **urudžbiranje**, tj. evidencija
akta u spisu. To su dva ortogonalna sustava i oba su potrebna.

Naš kod ih spaja u jedno. `RnIssuedListener` (javadoc, `RnIssuedListener.java:20-28`) opisuje se kao
*„Routes the issued RN to **either** eGOP (EU lessor) **or** e-mail (non-EU lessor)"* — dakle tretira
eGOP kao *kanal dostave* i bira između njega i e-maila po državljanstvu iznajmljivača.

Po Knjizi to ne stoji:

- **Urudžbiranje se ne bira** — svaki predmet se urudžbira, neovisno o tome tko je iznajmljivač.
  Podjela EU/non-EU nema uporište ni u jednom TC-u.
- **Dostava ide u KP za sve**, ne e-mailom. E-mail put u kodu je improvizacija dok KP ne postoji
  (u backlogu vođen kao BX1).

Posljedica za odluku #2 iz §1.2 („samo EU put"): ta je odluka donesena uz pretpostavku da je eGOP
kanal dostave koji non-EU korisnici ne koriste. Ako je eGOP urudžbiranje, pretpostavka pada —
**urudžbirali bismo svaku registraciju**. Odluku treba preispitati; ne mijenjam je jednostrano.
→ pitanje Q15.

### 13.6 Što i dalje nedostaje

- **Idejno projektno rješenje (IPR).** Knjiga se na njega poziva kao na izvor svih UC-ova
  (*„Svaki testni slučaj vezan je isključivo na UC-ove definirane u poglavlju 5.2. dokumenta
  Idejno projektno rješenje"*). Knjiga daje samo testne korake; **IPR bi trebao sadržavati stvarni
  opis postupka**, uključujući vrstu predmeta i vrstu pismena. To je sada jedini preostali interni
  dokument koji bi mogao odgovoriti na Q4.
- `docs/STR-BACKEND-VERIFIKACIJA-REZULTAT.md` se navodi kao izvor u
  `STR-NEDOSTAJUCE-FUNKCIONALNOSTI.md:3`, ali ne postoji u repou.
- **Knjiga ne spominje klasifikacijsku oznaku ni urudžbeni broj** (0 pojavljivanja). Ne postoji
  poslovni zahtjev da se ti podaci prikazuju korisniku — što znači da `filing_number` na
  `submission` nema uporište u dokumentaciji, a PDF koji ga otiskuje
  (`SubmissionPdfGenerator.java:83`) možda uopće ne treba to polje.

### 13.7 Zamka u postojećem kodu

`RnService.java:34-35` definira mapu s komentarom
*„Maps str.county.name → **EGOP organization ID** used in RN generation"*:

```java
private static final Map<String, Integer> COUNTY_EGOP_ORG_IDS = Map.ofEntries(
        Map.entry("Zagrebačka županija", 2), … Map.entry("Grad Zagreb", 22));
```

**To nisu eGOP organizacijske jedinice.** Vrijednosti su obična abecedna numeracija hrvatskih
županija (2–22), a koriste se isključivo kao `countyCode` u `RegistrationNumber.generate(...)`
(`RnService.java:87,93,178`) — dakle za sastavljanje HR+18 registracijskog broja. S eGOP-om nemaju
nikakve veze.

Stvarni `nadleznaOrgJedinica` ID-evi dolaze iz `ServiceMDM.DohvatiUstrojActive`
(`UstrojInfo.idorgjedinice`) i **nemamo ih**. Naziv konstante je zavaravajući i tko implementira
eGOP lako ih proslijedi u `KreirajPredmet2` — predlaže se preimenovanje u `COUNTY_CODES`.

---

## 14. Mail naručitelja, 22.07.2026. — potvrđeni opseg

Najautoritativniji izvor poslovnog opsega koji imamo. Nadjačava i Knjigu testiranja (§13.2) i naše
interne dokumente gdje se razilaze, jer je noviji i eksplicitno propisuje što STR mora raditi.

### 14.1 Ključna promjena konteksta

> *„očito je nastao nesporazum … oslonio sam se da će eTurizam 1 putem postojećih standardnih
> mehanizama kreirati predmete i pismena u uredskom poslovanju (eGOP, btw. InfoDom proizvod).
> **Kako to nije moguće, STR će morati:**"*

Time je razriješeno pitanje koje je u prvom nacrtu bilo označeno kao „blokira cijelu arhitekturu":
**predmet otvara STR, ne pisarnica i ne eTurizam 1.** Potvrđen je i proizvođač — InfoDom, što se
poklapa s `targetNamespace = http://www.infodom.hr/egov` u sva četiri WSDL-a.

### 14.2 Propisani model — potvrde

| Zahtjev iz maila | Status u ovoj analizi |
| :--- | :--- |
| „kreirati **neupravni** predmet" | ✅ potvrđuje §13.4(a) — `upisnaKnjiga = NP`, ne `UP/I` |
| „za svaki zahtjev **(EU i non-EU)**" | ✅ potvrđuje §13.5 — **odluka #2 iz §1.2 je nadjačana**, eGOP ide za sve |
| „**prvo ulazno** pismeno … **drugo (izlazno)** pismeno" | ✅ potvrđuje §13.4(b) — registracija stvara dva pismena |
| „(isti neupravni predmet)" | ✅ potvrđuje §13.4(c) — jedan dugovječan predmet po RB-u |
| „dostavljati u **KP** eGrađana … odnosno na **mail** za non-EU" | ✅ potvrđuje §13.5 — dostava je odvojena od urudžbiranja |

**Podjela EU/non-EU postoji, ali na krivom mjestu u našem kodu.** Ona se odnosi na **kanal dostave**
(KP eGrađana za NIAS korisnike, e-mail za non-EU), a **ne** na to hoće li se predmet urudžbirati.
`RnIssuedListener.java:68-73` danas tom podjelom bira *između eGOP-a i e-maila*; ispravno je:
urudžbiranje uvijek, a e-mail/KP kao zaseban korak nakon njega.

### 14.3 Vrste akata — nazivi koje smo dobili

Mail prvi put daje **nazive** vrsta. Šifre (`vrstaPredmeta` / `vrstaPismena`) i dalje nemamo —
i to nije cjepidlačenje:

- Spec oba parametra opisuje kao „eGOP **šifra** vrste" (str. 11, 51), ne naziv.
- MDM model vodi **id i naziv kao odvojena polja**: `VrstePredmetaInfo{idvrste, nazivVrstePredmeta}`,
  `VrstePismenaInfo{idVrstePismena, nazivVrstePismena}` — u poziv ide id.
- Nije isključeno da je šifra *slučajno* jednaka nazivu (`idvrste` je string), ali to nitko nije
  potvrdio, a promašena šifra znači `-240`.

Naziv iz maila je ipak **ključ za razrješenje**: uz pristup MDM-u (`DohvatiVrstePredmetaActive`,
`DohvatiVrstePismenaActive`) šifre bismo pronašli **sami, sparivanjem po nazivu** — bez ljudskog
round-tripa. Alternativno, jednim probnim pozivom na testu (`vrstaPredmeta = doslovni naziv`)
empirijski se vidi prolazi li naziv kao šifra.

**Vrsta predmeta:** „**Izdavanje Registracijskog broja**" (neupravni)

| # | Vrsta pismena | Smjer | Okidač u STR-u |
| :-- | :--- | :--- | :--- |
| 1 | Zahtjev za registracijski broj | ulazno | registracija |
| 2 | Obavijest o dodjeli registracijskog broja | izlazno | registracija (odmah nakon 1) |
| 3 | Obavijest o opozivu | izlazno | opoziv od strane iznajmljivača |
| 4 | Obavijest o prijedlogu suspenzije | izlazno | pokretanje suspenzije |
| 5 | Obavijest o suspenziji | izlazno | stvarna suspenzija |
| 6 | Obavijest o povlačenju | izlazno | povlačenje od strane tijela |
| 7 | Prigovor na prijedlog suspenzije | **ulazno** | iznajmljivač podnosi prigovor |

Dakle **2 ulazna + 5 izlaznih**. Time je konačno potvrđeno da `KreirajIzlaznoPismenoPoUredbi`
ulazi u opseg (§5, §13.4(b)).

### 14.4 Svako pismeno nosi vlastiti PDF

> *„Riječ kreirati znači pozivati odgovarajući API kojemu se predaju parametri **i prilaže PDF kojega
> treba kreirati za svaku vrstu pismena prema predlošku** kojega ću dostaviti tijekom dana."*

Sedam vrsta pismena → sedam PDF predložaka. Trenutno stanje u kodu:

| Vrsta | Postoji generator? |
| :--- | :--- |
| Zahtjev za registracijski broj | ✅ `SubmissionPdfGenerator` |
| Obavijest o dodjeli RB | ✅ `documents/hr/dodjela.txt` |
| Obavijest o opozivu | ✅ `documents/hr/opoziv.txt` |
| Obavijest o prijedlogu suspenzije | ✅ `documents/hr/prijedlog-suspenzije.txt` |
| Obavijest o suspenziji | ✅ `documents/hr/suspenzija.txt` |
| Obavijest o povlačenju | ✅ `documents/hr/povlacenje.txt` |
| Prigovor na prijedlog suspenzije | ✅ `documents/hr/prigovor.txt` |

> **Ažurirano 26.07.2026.** Svih 7 predložaka je izrađeno po strukturi čl. 98. ZUP-a —
> `StrDocumentType` je sada jedini izvor naziva vrsta pismena (ranije su živjeli na tri mjesta:
> `application.properties`, `EgopFilingService` i `EgopClientMock`). Nazivi iz Knjige
> („Dopis o namjeri", „Nalog za…") zadržani su samo kao URL aliasi. Detalji: `docs/ZUP-predlosci.md`.
> Predlošci koje je InfoDom najavio mailom nisu stigli; kad dođu, mijenja se sadržaj `.txt`
> datoteka, ne kod.

`RnDocumentType` (`rn/RnDocumentType.java`) ima tri tipa nazvana po Knjizi testiranja
(„Dopis o namjeri", „Nalog za…"), a mail ih zove „Obavijest o…". Vjerojatno je riječ o istim aktima
pod drugim imenom, ali pošto **naziv određuje `vrstaPismena` šifru**, razliku treba razriješiti
prije implementacije → Q16.

### 14.5 Gdje je dokumentacija koju Simon spominje

> *„Sve navedene akcije će se realizirati pozivom API-a za koji će **Simon danas dostaviti
> dokumentaciju** i bit ćemo na raspolaganju."*

Datoteke u `eGOP-wsdl.zip` nose timestamp **22.07.2026. 14:34** — isti dan kad je mail poslan.
To je gotovo sigurno ta isporuka. Zaključak: **nema nekog trećeg, nepoznatog API-ja** — riječ je o
eGOP SOAP servisima analiziranima u ovom dokumentu, a WSDL-ovi su Simonova dopuna spec-u v1.11
(listopad 2025.).

Praktično važno: *„bit ćemo na raspolaganju"* znači da postoji otvoren kanal prema InfoDomu za
pitanja Q1–Q16 iz §12.

### 14.6 Što mail NIJE riješio

- **Šifre.** Imamo nazive vrsta, ne i eGOP kodove. Uz to nije rečeno **postoje li te vrste već
  definirane u eGOP-u** ili ih administrator tek treba kreirati. → Q4 (preformuliran).
- **Nadležnost servisnog računa** (§5.0) — i dalje najveći rizik, mail je ne spominje. → Q1.
- **Autentifikacija**, `nadleznaOrgJedinica`, `tipOsobe` — nedirnuto.
- **PDF predlošci** — *„dostaviti tijekom dana"*; provjeriti jesu li stigli.
- **Dva akta iz Knjige koja mail ne spominje:** „obavijest o reaktivaciji" (TC-STR-2.2-001) i
  „obavijest o rezultatu nasumične SDEP provjere" (TC-STR-4.2-001/5). Lista od 7 vrsta možda nije
  potpuna. → Q16.

### 14.7 Posljedica za model statusa u kodu

Mail traži **različita** pismena za opoziv (iznajmljivač) i povlačenje (tijelo):
„Obavijest o opozivu" vs „Obavijest o povlačenju". Naš model ih ne razlikuje —
oba prolaze kroz `RnTrigger.WITHDRAWAL` → `RnStatus.WITHDRAWN`
(`LessorRnActionService.withdrawOwn` odnosno `RnService.withdraw`).

Iz trenutnog stanja se dakle **ne može zaključiti koji akt generirati**. Potrebno je razdvojiti
okidače, npr. `REVOCATION` (opoziv iznajmljivača, TC-STR-1.3-001) uz postojeći `WITHDRAWAL`
(povlačenje tijela, TC-STR-2.1-002). Status ostaje `WITHDRAWN` u oba slučaja — dijeli se samo okidač,
pa je promjena u `RnStatus.canTransitionTo` minimalna.

---

## 15. Spremnost po točkama iz maila

Odgovor na pitanje „znamo li što i kako trebamo integrirati". Razdvojeno namjerno:
**ŠTO** = znamo li metodu i oblik poziva · **KAKO** = možemo li ga danas stvarno izvršiti.

| # | Točka iz maila | Metoda | ŠTO | KAKO |
| :-- | :--- | :--- | :--: | :--: |
| 0 | *(preduvjet)* upis iznajmljivača u registar subjekata | `DohvatiPodatkeSubjekta` → `KreirajSubjektaProsireno` | ✅ | ❌ |
| 1 | neupravni predmet „Izdavanje Registracijskog broja" | `KreirajPredmet2` | ✅ | ❌ |
| 2 | ulazno pismeno „Zahtjev za registracijski broj" | `KreirajPismenoPoUredbi` + `KreirajDokumentZaPismenoPoUredbi` | ✅ | ❌ |
| 3 | izlazno „Obavijest o dodjeli registracijskog broja" | `KreirajIzlaznoPismenoPoUredbi` + dokument | ✅ | ❌ |
| 4 | „Obavijest o opozivu" | `KreirajIzlaznoPismenoPoUredbi` + dokument | ✅ | ❌ |
| 5 | Obavijesti: prijedlog suspenzije / suspenzija / povlačenje | `KreirajIzlaznoPismenoPoUredbi` ×3 | ✅ | ❌ |
| 6 | ulazno „Prigovor na prijedlog suspenzije" | `KreirajPismenoPoUredbi` + dokument | ✅ | ❌ |
| 7 | dostava: KP eGrađana (NIAS) / e-mail (non-EU) | — | 🟡 | ❌ / ✅ |

**Nijedna točka nije izvediva danas.** Metodološki je sve razriješeno — ne treba nam više nijedan
novi endpoint niti dodatna analiza spec-a. Blokada je u ulaznim podacima.

### 15.1 Tri zajednička blokera — pogađaju sve točke

| Bloker | Što točno nedostaje | Pitanje |
| :--- | :--- | :--- |
| **A. Pristup** | AD servisni račun (`DOMENA\korisnik`) + lozinka + potvrda mehanizma (Basic / NTLM / Kerberos) | Q2, Q3 |
| **B. Šifre** | `vrstaPredmeta` (1), `vrstaPismena` (7), `tipOsobe`, `nadleznaOrgJedinica` — **imamo nazive, nemamo kodove** | Q4 |
| **C. Nadležnost** | servisni račun mora biti nadležan za predmet, inače pada svaki `KreirajPismeno*` | Q1 |

Bloker **C** je podmukao: pogađa tek treći poziv u nizu, nakon što su subjekt i predmet već upisani
— dakle prvi neuspjeli pokušaj ostavlja smeće u eGOP-u.

Konkretno, koliko parametara ne možemo popuniti:

| Poziv | Parametara ukupno | Ne možemo popuniti |
| :--- | ---: | :--- |
| `KreirajSubjektaProsireno` | 14 | `userName`, `tipOsobe`, `postanskiBroj`, `urBroj` |
| `KreirajPredmet2` | 10 | `userName`, `vrstaPredmeta`, `nadleznaOrgJedinica`/`rjesavatelj` |
| `KreirajPismenoPoUredbi` | 9 | `userName`, `vrstaPismena` |

> `postanskiBroj` je jedini bloker koji **nije** na MINT-u — `LessorEntity` ga jednostavno nema
> (§9.2). Rješava se kod nas.

### 15.2 Što nedostaje na našoj strani, po točkama

| # | Naš nedostatak |
| :-- | :--- |
| 0 | `postanskiBroj` ne postoji na `LessorEntity`; za non-EU nemamo OIB → vjerojatno `KreirajSubjektaProsirenoStranci` (Q17) |
| 1 | `egop_*` kolone ne postoje u shemi (§9.3); `EgopClient` je na nepostojećem modelu (§9.1) |
| 2 | PDF postoji (`SubmissionPdfGenerator`), ali predložak nije potvrđen; redoslijed generiranja je obrnut (§9.1) |
| 3 | **PDF generator ne postoji** |
| 4 | **PDF generator ne postoji**; kod ne razlikuje opoziv od povlačenja — oba su `RnTrigger.WITHDRAWAL` (§14.7) |
| 5 | 3 generatora postoje u `RnDocumentType`, ali pod nazivima iz Knjige, ne iz maila (§14.4). **„Prijedlog suspenzije" kao faza ne postoji** — `RnService.suspend()` ide odmah u `SUSPENDED`, nema međustanja u kojem se čeka ispravak |
| 6 | **Prigovor ne postoji uopće** — ni endpoint, ni entitet, ni status. Pretraga `prigovor\|objection\|appeal` po `src/main` vraća nula pogodaka |
| 7 | **KP klijent ne postoji** i nemamo njegovu specifikaciju (backlog BX1). E-mail put radi (`EmailService`) |

### 15.3 Što se može raditi odmah, bez čekanja

Sve navedeno ne ovisi ni o jednom odgovoru MINT-a:

1. **Generirati SOAP klijent** iz WSDL-a (`cxf-codegen-plugin`) — ne treba kredencijale.
   Endpoint se overridea (§3.2).
2. **Redizajnirati `EgopClient`** da odgovara stvarnom toku (§9.1) i ispraviti redoslijed PDF-a.
3. **Liquibase changeset** za `egop_*` kolone (§9.3).
4. **Razdvojiti okidače** opoziv ↔ povlačenje (§14.7) — mala izmjena, otključava točku 4.
5. **Dodati `postanskiBroj`** iznajmljivaču ili ga razriješiti iz `place` preko
   `rpj_dgu.postanski_brojevi`.
6. **Dvofazna suspenzija** — uvesti fazu „prijedlog suspenzije" prije prelaska u `SUSPENDED`
   (točka 5 maila i TC-STR-2.1-001 to traže; danas ne postoji).
7. **Prigovor** — endpoint + model (točka 6). Čista poslovna logika, bez vanjskih ovisnosti.
8. **Kostur 3 nedostajuća PDF generatora** + usklađivanje 3 postojeća — puni sadržaj tek kad
   stignu predlošci.
9. **Konfiguracija i otpornost** — `EgopProperties`, timeouti, retry, circuit breaker,
   `egop_sync_status` state machine (§8.2).

Stavke 4, 6 i 7 su čiste STR funkcionalnosti koje ionako nedostaju prema Knjizi testiranja —
eGOP ih samo čini vidljivima.

### 15.4 Popis po endpointima — koji za koju točku i što fali

Sve točke iz maila pokriva **6 eGOP endpointa**. Dva nedostatka vrijede za *sve* i ne ponavljaju se
u tablicama ispod:

- **Pristup** — `userName` (`DOMENA\račun`) + lozinka + mehanizam autentifikacije → Q2, Q3
- **Nadležnost** — servisni račun mora biti nadležan za predmet; bez toga padaju svi
  `KreirajPismeno*` pozivi → Q1

| Točka iz maila | Endpoint |
| :--- | :--- |
| 0 · preduvjet: iznajmljivač u registru | `Subjekt.DohvatiPodatkeSubjekta` → `Subjekt.KreirajSubjektaProsireno` |
| 1 · neupravni predmet | `Predmet.KreirajPredmet2` |
| 2 · ulazno „Zahtjev za registracijski broj" | `Pismeno.KreirajPismenoPoUredbi` |
| 3 · izlazno „Obavijest o dodjeli RB" | `Pismeno.KreirajIzlaznoPismenoPoUredbi` |
| 4 · „Obavijest o opozivu" | `Pismeno.KreirajIzlaznoPismenoPoUredbi` |
| 5 · Obavijesti o prijedlogu susp. / susp. / povlačenju | `Pismeno.KreirajIzlaznoPismenoPoUredbi` ×3 |
| 6 · ulazno „Prigovor na prijedlog suspenzije" | `Pismeno.KreirajPismenoPoUredbi` |
| *(uz 2–6)* prilaganje PDF-a | `Pismeno.KreirajDokumentZaPismenoPoUredbi` |
| 7 · dostava | **nije eGOP** — KP klijent (ne postoji) / `EmailService` |

---

**1 · `ServiceSubjekt.DohvatiPodatkeSubjekta`** — točka 0 · 1 poziv po registraciji
`userName, oznakaSubjekta, mbJmbg, oib` → `SubjektInfo`

- Fali (MINT): — *(ništa osim globalnog pristupa)*
- Fali (mi): za non-EU nemamo ni OIB ni MB, pa subjekta ne možemo ni potražiti → Q17

> **Jedini endpoint koji je inače spreman.** Za EU iznajmljivača poziv se može sastaviti u cijelosti.

---

**2 · `ServiceSubjekt.KreirajSubjektaProsireno`** — točka 0 · 0–1 poziv (samo ako subjekt ne postoji)
`userName, mbJmbg, tipOsobe, naziv, oib, telefon, email, ziroracun, ulica, kucnibroj, naselje, postanskiBroj, isoKodDrzave, urBroj` → `SubjektBasicInfo{oznakaSubjekta}`

- Fali (MINT): **`tipOsobe`** šifra (fizička / obrt / d.o.o.) · `urBroj` šifrarnik *(neobavezan)*
- Fali (mi): **`postanskiBroj`** — spec ga traži kao obavezan, `LessorEntity` ga nema (§9.2) ·
  `isoKodDrzave` — nije potvrđeno da je naš `country_id` ISO numerički kod ·
  za non-EU vjerojatno treba `KreirajSubjektaProsirenoStranci`, koja **nije dokumentirana** → Q17

---

**3 · `ServicePredmet.KreirajPredmet2`** — točka 1 · 1 poziv po registraciji
`userName, upisnaKnjiga, vrstaPredmeta, nadleznaOrgJedinica, rjesavatelj, subjektOznaka, subjektOIB, subjektMBJmbg, nazivPredmeta, datumOtvaranja` → `PredmetBasicInfo2{uredskaGodina, rbrPredmeta, klasifikacijskaOznaka}`

- Fali (MINT): **`vrstaPredmeta`** šifra za „Izdavanje Registracijskog broja" — bez nje `-240` ·
  **`nadleznaOrgJedinica` ili `rjesavatelj`** — barem jedno je obavezno, nemamo nijedno
- Fali (mi): `egop_*` kolone za pohranu odgovora (§9.3)

> `upisnaKnjiga = "NP"` je **jedina šifra koju znamo** — vrijednost je u spec-u str. 11, potvrđena
> mailom („neupravni predmet").

---

**4 · `ServicePismeno.KreirajPismenoPoUredbi`** — točke 2 i 6 · 1–2 poziva
`userName, rbrSpisa, uredskaGodina(short), vrstaPismena, subjektOznaka, subjektMbJmbg, subjektOIB, nazivPismena, datumNastanka` → `PismenoBasicInfoUredba{jedinstvenaOznakaPismena, jedinstveniIdentifikatorPismena, jop, brojcanaOznaka}`

- Fali (MINT): **2× `vrstaPismena`** šifra — „Zahtjev za registracijski broj" i
  „Prigovor na prijedlog suspenzije"
- Fali (mi): **prigovor ne postoji u aplikaciji** — ni endpoint, ni entitet, ni status (§15.2) ·
  PDF predložak za prigovor

> Odgovor **ne sadrži `urBroj`** — ako nam treba, potreban je naknadni
> `DohvatiPodatkePismenaPoUredbi` (§6).

---

**5 · `ServicePismeno.KreirajIzlaznoPismenoPoUredbi`** — točke 3, 4, 5 · 1–5 poziva
`userName, rbrSpisa, uredskaGodina(short), vrstaPismena, nazivPismena, datumNastanka` → `PismenoInfoUredba`

- Fali (MINT): **5× `vrstaPismena`** šifra (dodjela RB, opoziv, prijedlog suspenzije, suspenzija,
  povlačenje) · potvrda da STR uopće smije kreirati izlazna pismena → Q14
- Fali (mi): generatori za „Obavijest o dodjeli RB" i „Obavijest o opozivu" **ne postoje** ·
  3 postojeća u `RnDocumentType` nose nazive iz Knjige, ne iz maila (§14.4) ·
  **faza „prijedlog suspenzije" ne postoji** — `RnService.suspend()` ide odmah u `SUSPENDED` ·
  **opoziv i povlačenje se ne razlikuju** — oba su `RnTrigger.WITHDRAWAL` (§14.7)

> Nema `subjekt*` parametara — stvaratelj akta se izvodi iz `userName`, tj. akt nastaje u ime MINT-a.
> Odgovor nasljeđuje `PismenoBasicInfoUredba`, pa daje GUID **i `urBroj` odmah** — za razliku od
> ulazne varijante.

---

**6 · `ServicePismeno.KreirajDokumentZaPismenoPoUredbi`** — uz svaku točku 2–6 · 2–7 poziva
`userName, jedinstveniIdentifikatorPismena, jop, extension, attachment(base64Binary)` → `DokumentInfoUredba`

- Fali (MINT): potvrda formata `extension` („pdf") → Q7 · maksimalna veličina privitka → Q11 ·
  ponašanje pri ponovljenom pozivu (prepisuje ili puca) → Q8
- Fali (mi): **3 od 7 PDF generatora ne postoje** (dodjela RB, opoziv, prigovor), **3 postoje pod
  nazivima iz Knjige** pa ih treba uskladiti (§14.4) · **predlošci nisu stigli** („dostaviti tijekom dana",
  22.07.) · redoslijed generiranja PDF-a treba ispraviti (§9.1)

---

**7 · Dostava — izvan eGOP-a** — točka 7
KP eGrađana za NIAS korisnike · e-mail za non-EU

- Fali (MINT/druga strana): **KP klijent ne postoji i nemamo njegovu specifikaciju** (backlog BX1)
- Fali (mi): odvojiti dostavu od urudžbiranja u `RnIssuedListener` (§14.2) — danas bira *između*
  eGOP-a i e-maila umjesto da radi oboje

E-mail put radi (`EmailService`).

### 15.5 Koliko je izbor endpointa siguran

Pošteno razdvojeno, jer nije sve jednako čvrsto.

**Najvažnija ograda: mail imenuje poslovne akte, ne endpointe.** Nitko nam nije rekao koje metode
zvati. Mail kaže *„Sve navedene akcije će se realizirati pozivom API-a za koji će Simon dostaviti
dokumentaciju"* — a ta dokumentacija (spec v1.11 + WSDL-ovi) je **generička** i nigdje ne spominje
STR (§13.1). Mapiranje akt → metoda je dakle **naš zaključak**, ne nešto što je itko potvrdio.

#### Što je čvrsto — nametnuto modelom

Model eGOP-a (predmet → pismeno → dokument, §1.1) ne ostavlja izbora oko *vrste* poziva:

| Potrebno | Dokumentirane opcije | Komentar |
| :--- | :--- | :--- |
| upisati subjekta | `KreirajSubjekta`, `KreirajSubjektaProsireno` | samo te dvije |
| otvoriti predmet | `KreirajPredmet`, `KreirajPredmet2` | samo te dvije |
| kreirati pismeno | `KreirajPismeno`, `KreirajPismeno2`, `KreirajPismenoPoUredbi`, `KreirajIzlaznoPismeno`, `KreirajIzlaznoPismenoPoUredbi` | pet |
| priložiti PDF | `KreirajDokumentZaPismeno`, `KreirajDokumentZaPismenoPoUredbi` | samo te dvije |

Da će trebati po jedan poziv iz svake skupine — to je sigurno. **Koji točno iz skupine — to je izbor.**

#### Četiri odluke koje sam donio, s alternativama

| # | Odluka | Alternativa | Rizik |
| :-- | :--- | :--- | :--- |
| 1 | **„PoUredbi" umjesto legacy generacije** — jer vraća GUID propisan Uredbom (§5.4) | cijela legacy skupina radi isto | **Srednji.** Argument je jak, ali nitko iz MINT-a nije potvrdio koju generaciju očekuju. Ako njihova praksa je legacy, mijenja se 4 od 6 poziva. |
| 2 | **`KreirajIzlaznoPismenoPoUredbi` za točke 3–5** | `KreirajPismenoPoUredbi` sa subjektom koji *je* ustrojstvena jedinica — spec str. 50 kaže da tada metoda **sama** kreira izlazno pismeno | **Najveći.** Vidi ispod. |
| 3 | `KreirajPredmet2` umjesto `KreirajPredmet` | `KreirajPredmet` + naknadni `DohvatiPodatkePredmeta` | Nizak — samo jedan round-trip razlike. |
| 4 | `KreirajSubjektaProsireno` umjesto `KreirajSubjekta` | `KreirajSubjekta` s adresom kao jednim stringom | Nizak-srednji — gubi se strukturirana adresa. |

**Odluka 2 zaslužuje objašnjenje.** Postoje dva načina da nastane izlazno pismeno:

- `KreirajIzlaznoPismenoPoUredbi` — stvaratelj akta se izvodi iz **`userName`** (naš servisni račun)
- `KreirajPismenoPoUredbi` — stvaratelj je **predani subjekt**; ako je to ustrojstvena jedinica ili
  službenik, spec str. 50 kaže da metoda automatski radi *izlazno* pismeno

Razlika nije kozmetička: **brojčana oznaka stvaratelja akta ulazi u urudžbeni broj** (spec str. 30).
Dakle o izboru ovisi kako će urudžbeni broj izgledati. Koja je varijanta ispravna je pitanje uredskog
poslovanja, ne tehnike — i na njega ne možemo odgovoriti sami.

#### Što sam svjesno isključio, a možda ne bih trebao

Po odluci #1 iz §1.2 koristimo samo metode dokumentirane u spec-u. Te su odluke donesene **prije**
maila naručitelja. Sada kada je opseg propisan, a InfoDom *„na raspolaganju"*, vrijedi ih preispitati:

| Metoda (nedokumentirana) | Što bi riješila |
| :--- | :--- |
| `KreirajPredmetPismenoSubjektPoUredbi` | **točke 0 + 1 + 2 u jednom atomarnom pozivu**, uz `vanjskiIdentifikatorEntiteta` kao idempotency ključ — otpao bi cijeli state machine iz §8.2 |
| `KreirajUlaznoPismenoPoUredbi` | izrijekom kreira **ulazno** pismeno umjesto oslanjanja na automatsko zaključivanje smjera (točke 2, 6) |
| `KreirajSubjektaProsirenoStranci` | non-EU iznajmljivač (Q17) — bez nje točku 0 za non-EU ne znamo riješiti |
| `KreirajPrilogIPridruziDokumentPoUredbi` | prilog + dokument u jednom pozivu umjesto dva |
| `ServiceMDM.*` | **sve šifre** iz Q4, bez čekanja na e-mail |

#### Kako ovo zatvoriti

Umjesto da tražimo potvrdu naše liste, ispravnije je pitanje okrenuti: poslati InfoDomu **popis
akata iz maila** (§14.3) i tražiti da oni kažu koju metodu za koji akt očekuju, uz šifre. Time
odgovor na Q4 i na ovu nesigurnost dolazi u jednom koraku → Q18.

### 15.6 Obaveznost polja po pozivu

Izvučeno usporedbom spec tablica („Obaveznost") i WSDL sheme (`minOccurs` / `nillable`).

#### Ključni nalaz: shema ne provodi nijedno poslovno pravilo

| | Kako spec označava | Kako WSDL označava |
| :--- | :--- | :--- |
| `string` polje označeno **DA** | obavezno | `minOccurs="0"` — **smije se izostaviti** |
| `int`/`dateTime` označen **NE** | neobavezno | `minOccurs="1" nillable="true"` — **mora biti u XML-u**, ali kao `xsi:nil` |

Dakle **XSD validacija proći će i s praznom porukom**. Sve poslovne obaveznosti eGOP provjerava tek
u izvođenju i vraća ih kao `-240`, `-300`, `-700`, `-701` — uz HTTP 200 (§4). Iz toga:

- **Spec tablica „Obaveznost" je jedini izvor istine o obaveznosti** — i to je proza, ne shema.
- Generirani klijent (`wsdl2java`) ispravno emitira `xsi:nil="true"` za `null` vrijednosti
  value tipova, pa je zamka relevantna samo ako se SOAP piše ručno.

#### Po pozivu — obavezna polja i imamo li ih

Legenda: **DA** = spec traži · **[1 od N]** = uvjetna skupina, barem jedno · *(ostalo neobavezno)*

**`DohvatiPodatkeSubjekta`**

| Polje | Spec | Imamo? |
| :--- | :--- | :--- |
| `userName` | DA | ❌ pristup |
| `oznakaSubjekta` \| `mbJmbg` \| `oib` | [1 od 3] | ✅ EU (`oib`) · ❌ non-EU (nemamo nijedno) |

**`KreirajSubjektaProsireno`**

| Polje | Spec | Imamo? |
| :--- | :--- | :--- |
| `userName` | DA | ❌ pristup |
| `tipOsobe` | DA | ❌ šifra |
| `naziv` | DA | ✅ |
| `postanskiBroj` | DA | ❌ **nema ga na `LessorEntity`** |

> Tri od četiri obavezna polja nedostaju. `oib` je ovdje **neobavezan** (NE) — što znači da eGOP
> dopušta subjekt bez OIB-a; korisno za non-EU, ali onda subjekta ne možemo naći kroz lookup.

**`KreirajPredmet2`**

| Polje | Spec | Imamo? |
| :--- | :--- | :--- |
| `userName` | DA | ❌ pristup |
| `upisnaKnjiga` | DA | ✅ `"NP"` |
| `vrstaPredmeta` | DA | ❌ šifra |
| `nadleznaOrgJedinica` \| `rjesavatelj` | [1 od 2] | ❌ nemamo nijedno |
| `subjektOznaka` \| `subjektOIB` \| `subjektMBJmbg` | [1 od 3] | ✅ EU |

**`KreirajPismenoPoUredbi`**

| Polje | Spec | Imamo? |
| :--- | :--- | :--- |
| `userName` | DA | ❌ pristup |
| `vrstaPismena` | DA | ❌ šifra |
| `nazivPismena` | DA | ✅ |
| `subjektOznaka` \| `subjektMbJmbg` \| `subjektOIB` | [1 od 3] | ✅ iz koraka 1 |
| `rbrSpisa`, `uredskaGodina` | **NE** | ✅ — *ali ih uvijek šaljemo* |

> `rbrSpisa`/`uredskaGodina` su formalno neobavezni. Bez njih pismeno **nije vezano ni za jedan
> predmet** — nastaje nepridruženo pismeno. Za nas su de facto obavezni.

**`KreirajIzlaznoPismenoPoUredbi`**

| Polje | Spec | Imamo? |
| :--- | :--- | :--- |
| `userName` | DA | ❌ pristup |
| `vrstaPismena` | DA | ❌ šifra |
| `nazivPismena` | DA | ✅ |
| `rbrSpisa`, `uredskaGodina` | **NE** | ✅ — *ali ih uvijek šaljemo* |

> Nema `subjekt*` parametara uopće — stvaratelj se izvodi iz `userName`.

**`KreirajDokumentZaPismenoPoUredbi`**

| Polje | Spec | Imamo? |
| :--- | :--- | :--- |
| `userName` | DA | ❌ pristup |
| `jedinstveniIdentifikatorPismena` \| `jop` | [1 od 2] | ✅ iz prethodnog poziva |
| `extension` | DA | 🟡 `"pdf"` — format nepotvrđen (Q7) |
| `attachment` | DA | 🟡 čeka predloške |

#### Zbroj — koliko vrijednosti stvarno nedostaje

Isključivši `userName` (jedan kredencijal koji rješava svih šest poziva), obaveznih polja koja
ne možemo popuniti ima **pet vrsta**:

| Vrijednost | Gdje | Tko rješava |
| :--- | :--- | :--- |
| `vrstaPredmeta` (1 šifra) | `KreirajPredmet2` | InfoDom |
| `vrstaPismena` (7 šifara) | oba `Kreiraj*Pismeno*` | InfoDom |
| `tipOsobe` (1 šifra) | `KreirajSubjektaProsireno` | InfoDom |
| `nadleznaOrgJedinica` **ili** `rjesavatelj` | `KreirajPredmet2` | InfoDom / MINT |
| `postanskiBroj` iznajmljivača | `KreirajSubjektaProsireno` | **mi** |

Ostala obavezna polja (`naziv`, `nazivPismena`, `upisnaKnjiga`, `extension`, identifikatori iz
prethodnih poziva) možemo popuniti danas.

### 15.7 Detaljna razrada — točka po točku iz maila

Za svaku točku: koji API i zašto baš taj · što točno šaljemo (izvor u STR-u) · što fali · što možemo
odmah. Dva globalna nedostatka (`userName` + nadležnost računa, §15.1 A i C) vrijede svugdje i ne
ponavljaju se.

---

#### Točka 0 (implicitni preduvjet) — iznajmljivač u registru subjekata

**API:** `DohvatiPodatkeSubjekta`, pa `KreirajSubjektaProsireno` samo ako ne postoji.
**Zašto:** `KreirajPredmet2` traži postojeći subjekt (inače `-300`); eGOP nema upsert, pa je lookup
prije kreiranja jedina zaštita od duplikata (§5.1). Prosirena varijanta zbog strukturirane adrese
i dopuštanja istog OIB-a (§5.2).

| Šaljemo | Izvor | Status |
| :--- | :--- | :--- |
| `oib` (lookup i create) | `lessor.lessorOib` | ✅ EU · ❌ non-EU (nema OIB → Q17) |
| `tipOsobe` | mapping iz `lessor.legalEntityOwner` | ❌ šifra |
| `naziv` | `firstName + lastName` / `legalEntityName` | ✅ |
| `ulica`, `kucnibroj`, `naselje` | `street`, `streetNumber`, `place` | ✅ |
| `postanskiBroj` (obavezan!) | — | ❌ **nema na `LessorEntity`** |
| `telefon`, `email` | `phoneNumber`/`mobileNumber`, `email` | ✅ |
| `isoKodDrzave` | `countryOfResidenceId`? | 🟡 nije potvrđeno da je ISO kod |

**Fali (InfoDom):** `tipOsobe` šifra · non-EU postupak (Q17).
**Fali (mi):** `postanskiBroj` · potvrda ISO kodova.
**Možemo odmah:** dodati `postanskiBroj` (ili izvesti iz `place` + `rpj_dgu.postanski_brojevi`) ·
provjeriti podudaraju li se naši `country_id` s ISO 3166 numeričkim kodovima.

---

#### Točka 1 — neupravni predmet „Izdavanje Registracijskog broja"

**API:** `KreirajPredmet2`.
**Zašto:** jedina dokumentirana alternativa je `KreirajPredmet`, koja ne vraća klasifikacijsku
oznaku pa traži dodatni dohvat (§5.3).

| Šaljemo | Izvor | Status |
| :--- | :--- | :--- |
| `upisnaKnjiga` | konstanta `"NP"` (mail: „neupravni") | ✅ |
| `vrstaPredmeta` | šifra za „Izdavanje Registracijskog broja" | ❌ šifra |
| `nadleznaOrgJedinica` **ili** `rjesavatelj` | ? | ❌ nemamo nijedno |
| `subjektOznaka` | iz točke 0 | ✅ |
| `nazivPredmeta` | npr. „Izdavanje registracijskog broja — {accommodation.name}, {city}" | ✅ |
| `datumOtvaranja` | trenutak izdavanja RB-a | ✅ |

**Čuvamo:** `uredskaGodina`, `rbrPredmeta`, `klasifikacijskaOznaka` → `egop_*` kolone (§9.3).
**Fali (InfoDom):** `vrstaPredmeta` šifra + postoji li vrsta uopće u eGOP-u · org. jedinica ili
rješavatelj (i posljedično nadležnost, Q1).
**Fali (mi):** `egop_*` kolone ne postoje.
**Možemo odmah:** Liquibase changeset · dogovor formata `nazivPredmeta`.

---

#### Točka 2 — ulazno pismeno „Zahtjev za registracijski broj" + PDF

**API:** `KreirajPismenoPoUredbi`, zatim `KreirajDokumentZaPismenoPoUredbi`.
**Zašto:** stvaratelj akta je iznajmljivač (vanjski subjekt) → metoda automatski kreira **ulazno**
pismeno (spec str. 50). „PoUredbi" zbog GUID-a (§5.4). PDF je zaseban poziv — kreiranje pismena
nema parametar za privitak.

| Šaljemo | Izvor | Status |
| :--- | :--- | :--- |
| `rbrSpisa`, `uredskaGodina` | iz točke 1 (formalno NE, de facto obavezno) | ✅ |
| `vrstaPismena` | šifra „Zahtjev za registracijski broj" | ❌ šifra |
| `subjektOznaka` | iz točke 0 (stvaratelj = iznajmljivač) | ✅ |
| `nazivPismena` | „Zahtjev za registracijski broj — {naziv objekta}" | ✅ |
| `datumNastanka` | trenutak submita (`submission.filingDate`) | ✅ |
| → `extension`, `attachment` | `"pdf"`, Base64 PDF-a zahtjeva | 🟡 |

**Fali (InfoDom):** `vrstaPismena` šifra · potvrda `extension` formata (Q7) · max veličina (Q11) ·
retry semantika dokumenta (Q8).
**Fali (mi):** predložak nepotvrđen — `SubmissionPdfGenerator` postoji, ali otiskuje „Urudžbeni
broj" koji u ovom trenutku toka još ne postoji; redoslijed treba obrnuti (§9.1).
**Možemo odmah:** ispraviti redoslijed PDF-a · uskladiti generator s predloškom čim stigne.

---

#### Točka 3 — izlazno pismeno „Obavijest o dodjeli registracijskog broja" + PDF

**API:** `KreirajIzlaznoPismenoPoUredbi`, zatim `KreirajDokumentZaPismenoPoUredbi`.
**Zašto:** stvaratelj akta je MINT, ne iznajmljivač — izlazna varijanta izvodi stvaratelja iz
`userName` i nema subjekt parametara. Alternativa (obični `KreirajPismenoPoUredbi` s ustrojstvenom
jedinicom kao subjektom) mijenja izvor brojčane oznake u urudžbenom broju → Q18.

| Šaljemo | Izvor | Status |
| :--- | :--- | :--- |
| `rbrSpisa`, `uredskaGodina` | iz točke 1 | ✅ |
| `vrstaPismena` | šifra „Obavijest o dodjeli registracijskog broja" | ❌ šifra |
| `nazivPismena` | „Obavijest o dodjeli registracijskog broja {rn}" | ✅ |
| `datumNastanka` | trenutak izdavanja | ✅ |
| → `attachment` | PDF obavijesti | ❌ |

**Odgovor daje i `urBroj` odmah** (nasljeđuje `PismenoInfoUredba`) — za razliku od ulaznog pismena.
**Fali (InfoDom):** šifra · potvrda da STR smije kreirati izlazna pismena (Q14) · predložak.
**Fali (mi):** **PDF generator ne postoji.**
**Možemo odmah:** kostur generatora · okidanje iz postojećeg `RnIssuedEvent` toka.

---

#### Točka 4 — „Obavijest o opozivu" (opoziv iznajmljivača)

**API:** `KreirajIzlaznoPismenoPoUredbi` + dokument, u **postojeći** predmet.
**Zašto:** obavijest je akt MINT-a prema iznajmljivaču — izlazno pismeno; predmet se ne otvara nov
(mail: „isti neupravni predmet").

| Šaljemo | Izvor | Status |
| :--- | :--- | :--- |
| `rbrSpisa`, `uredskaGodina` | `rn → submission → egop_*` | 🟡 vidi rizik |
| `vrstaPismena` | šifra „Obavijest o opozivu" | ❌ šifra |
| `nazivPismena`, `datumNastanka` | RN + trenutak opoziva | ✅ |
| → `attachment` | PDF obavijesti o opozivu | ❌ |

> **Rizik na putu do predmeta:** `rn.submission_id` je **nullable**
> (`005-registration-number.xml` bez `nullable=false`; changeset 045 potvrđuje da mock RN-ovi
> postoje bez njega). Za svaki RN bez submissiona ili bez `egop_*` vrijednosti lifecycle akt nema
> u što biti uložen — reconciliation mora to detektirati.

**Fali (InfoDom):** šifra · predložak.
**Fali (mi):** **kod ne razlikuje opoziv od povlačenja** — oba su `RnTrigger.WITHDRAWAL`
(`LessorRnActionService.withdrawOwn` i `RnService.withdraw` → isti trigger), pa se ne zna koji akt
generirati (§14.7) · PDF generator ne postoji.
**Možemo odmah:** novi trigger `REVOCATION` · kostur generatora.

---

#### Točka 5 — Obavijesti: prijedlog suspenzije / suspenzija / povlačenje

**API:** `KreirajIzlaznoPismenoPoUredbi` + dokument, ×3, sve u isti predmet.
**Zašto:** kao točka 4 — akti MINT-a.

| Akt | Okidač u STR-u danas | PDF generator |
| :--- | :--- | :--- |
| Obavijest o prijedlogu suspenzije | **ne postoji faza** — `RnService.suspend()` ide odmah u `SUSPENDED` | 🟡 `DOPIS_NAMJERE` (drugi naziv) |
| Obavijest o suspenziji | `RnService.suspend(trigger, deadline)` | 🟡 `NALOG_SUSPENZIJA` (drugi naziv) |
| Obavijest o povlačenju | `RnService.withdraw(reason)` | 🟡 `NALOG_POVLACENJE` (drugi naziv) |

**Fali (InfoDom):** 3 šifre · 3 predloška · razjašnjenje nazivlja Knjiga ↔ mail (Q16).
**Fali (mi):** **dvofazna suspenzija ne postoji** — treba stanje/korak „prijedlog" prije `SUSPENDED`
(i tu se prirodno veže postojeći `suspensionDeadline` kao rok za ispravak) · isti rizik
`rn → submission` kao točka 4.
**Možemo odmah:** dvofazna suspenzija (čista STR logika, traži je i TC-STR-2.1-001) ·
preimenovanje/proširenje `RnDocumentType`.

---

#### Točka 6 — ulazno pismeno „Prigovor na prijedlog suspenzije"

**API:** `KreirajPismenoPoUredbi` (stvaratelj = iznajmljivač → ulazno) + dokument, u isti predmet.
**Zašto:** prigovor podnosi iznajmljivač — ulazni akt, kao točka 2.

| Šaljemo | Izvor | Status |
| :--- | :--- | :--- |
| `rbrSpisa`, `uredskaGodina` | `rn → submission → egop_*` | 🟡 isti rizik |
| `vrstaPismena` | šifra „Prigovor na prijedlog suspenzije" | ❌ šifra |
| `subjektOznaka` | `egop_subjekt_oznaka` | ✅ (kad postoji) |
| → `attachment` | PDF prigovora | ❌ |

**Fali (InfoDom):** šifra · predložak.
**Fali (mi):** **prigovor ne postoji u aplikaciji** — ni endpoint, ni entitet, ni status (0 pogodaka
u kodu, §15.2) · ovisi o dvofaznoj suspenziji iz točke 5 (prigovor se podnosi na *prijedlog*).
**Možemo odmah:** cijela funkcionalnost prigovora — endpoint, entitet, veza na dvofaznu suspenziju.

---

#### Točka 7 — dostava: KP eGrađana (NIAS) / e-mail (non-EU)

**API:** **nije eGOP.** KP je zaseban sustav; e-mail je `EmailService`.

**Fali (naručitelj/MINT):** KP specifikacija i pristup — klijent ne postoji, spec nemamo (BX1).
**Fali (mi):** `RnIssuedListener` danas **bira između** eGOP-a i e-maila po EU/non-EU
(`RnIssuedListener.java:68-73`); ispravno je urudžbirati uvijek, a EU/non-EU odlučuje samo
kanal dostave (§14.2).
**Možemo odmah:** refaktor listenera na „urudžbiraj → pa dostavi" · e-mail grana već radi.

---

#### Zbirno — što od svega možemo napraviti već danas

| Zahvat | Otključava točke |
| :--- | :--- |
| `postanskiBroj` na iznajmljivaču | 0 |
| Liquibase `egop_*` kolone + `egop_sync_status` | 1–6 |
| SOAP klijent iz WSDL-a + redizajn `EgopClient` | sve |
| Ispravak redoslijeda PDF-a | 2, 3 |
| Trigger `REVOCATION` (opoziv ≠ povlačenje) | 4 |
| Dvofazna suspenzija | 5, 6 |
| Funkcionalnost prigovora | 6 |
| Refaktor `RnIssuedListener` (urudžba ≠ dostava) | 3, 7 |
| Kosturi 3 PDF generatora + usklađivanje 3 postojeća | 3, 4, 5, 6 |

---

## 16. Sažetak preporuka

1. **Klijent generirati iz WSDL-a** (`cxf-codegen-plugin`), ne pisati po spec-u — §10.
2. **Endpoint URL overrideati** na adrese iz §3.1; WSDL-ova adresa je interni build hostname — §3.2.
3. **Uvijek prvo `OperationSucceeded`**, pa tek onda payload — §4.1.
4. **Redoslijed: predmet → pismeno → PDF → dokument.** PDF se renderira tek kad postoji urudžbeni
   broj — §8.1, §9.1.
5. **Idempotencija je naša**, kroz `egop_sync_status`; eGOP je ne nudi — §8.2.
6. **Ne miješati legacy i „PoUredbi" metode** — §7.3.
7. **PDF sadržaj nikad u log** (veličina i hash su dovoljni).
8. Prije prve linije koda dobiti odgovore na **Q1–Q4** — sve ostalo je paralelizabilno.

---

## 17. Referentni klijent (24.07.2026.) — nadjačava dio analize

Nakon calla 23.07. InfoDom je isporučio **source code referentnog klijenta**
(`hr.infodom.str.integration.egop` — pisan baš za STR; Spring Boot 2 / Gradle /
`com.github.bjornvester.wsdl2java`). WSDL-ovi u isporuci su **bit-identični**
(SHA-256 match) onima iz `eGOP-wsdl.zip`, pa WSDL analiza (§10) i dalje vrijedi.
Sve dolje navedeno dolazi iz koda, ne iz dokumentacije.

### 17.1 Što je klijent razriješio

| Otvoreno pitanje | Odgovor iz koda |
| :--- | :--- |
| **Auth mehanizam (blocker A, Q2)** | **NTLM** — Apache HttpClient 4.x `NTCredentials` + `NTLMSchemeFactory`; **SOAP 1.2**; Spring WS `WebServiceTemplate` + `Jaxb2Marshaller`; connect timeout 3 s; interceptor briše `Content-Length` header. Fale samo **vrijednosti** kredencijala (`@Value` property-ji, nisu u isporuci). |
| **Šifrarnici (blocker B, Q4)** | Dohvaćaju se sa **ServiceMDM-a pri startu aplikacije** (`DohvatiVrstePredmetaActive`, `DohvatiVrstePismenaActive`, `DohvatiUstrojActive`, `ListaVrstePoslovnihSubjekata`, `DohvatiVrstePrilogaActive`) kao mape **naziv → šifra**. `EgopNaziv.resolveId()` normalizira nazive (trim/dijakritici/lowercase). Nazivi pod navodnicima iz maila 22.07. **jesu ključevi**. |
| **Odluka #1 (samo dokumentirane metode)** | **Pala.** Klijent koristi ServiceMDM + legacy `KreirajSubjekta` (ne `Prosireno`), `KreirajPismeno2` (*„jer on jedini vrati UrBroj"* — vraća `jop` + `UrBroj` u istom pozivu), `KreirajDokumentZaPismeno` (preko `jop` int, ne GUID), `KreirajPrilogIPridruziDokument`, `DohvatiPodatkePismena3`. §5 izbor „PoUredbi" varijanti je time nadjačan. |
| **Obrazac „provjeri pa kreiraj"** | Potvrđen u kodu: greške **-300** (subjekt ne postoji) i **-100** (predmet ne postoji) tretiraju se kao **validni odgovori** (`EgopNotErrorCodes`), ne iznimke. |
| **Nadležnost (blocker C, Q1)** | Klijent sadrži `OdrediRjesavatelja` — vjerojatno predviđeni mehanizam da servisni račun postane nadležan nad predmetom koji sam otvori. **Još nepotvrđeno od InfoDoma.** |
| **Upisna knjiga (Q5)** | Enum `VrstaUpisneKnjige {NP, UP/I, UP/II}` — potvrđuje NP kao izbor za STR. |
| **Dva identiteta** | `username`/`password` za HTTP NTLM **+** `app-domain`/`app-username` za `userName` polje u payloadu (`DOMENA\user`). |
| **Ustroj (Q6)** | Mock u klijentu otkriva realne ID-eve: SAMOSTALNI SEKTOR TURISTIČKE INSPEKCIJE=426, MINISTARSTVO TURIZMA=559, Pododsjek pisarnice=6… |

### 17.2 Implementacija u STR-u (ovaj repo)

Port klijenta + poslovni tok su implementirani (Boot 3.3 / jakarta / Maven / constructor injection):

- **`pom.xml`** — `cxf-codegen-plugin` generira JAXB iz WSDL-ova u `src/main/resources/egop/`
  u pakete `hr.infodom.egov.{mdm,subjekt,predmet,pismeno}` (identično referentnom klijentu);
  `spring-boot-starter-web-services` + `httpclient` 4.5.14 (NTLM).
- **`com.str.backend.egop`** — port: `EgopConfig` (NTLM + SOAP 1.2 + 4 `WebServiceTemplate`),
  `EgopClient`/`EgopClientImpl`/`EgopClientMock` (mock aktivan kad je
  `hr.infodom.str.integration.egop.enabled=false`, default), `EgopCodebooks` (šifrarnici pri
  startu; **odstupanje od reference:** vrste subjekata mapirane naziv→šifra kao i ostali
  šifrarnici, uz merge duplikata naziva), `EgopNaziv`, `EgopDates` (CXF generira
  `XMLGregorianCalendar`), exceptioni i codebook enumi.
- **`EgopFilingService`** — tok registracije: `ensureSubjekt` (lookup po OIB-u / `mbJmbg` za
  non-EU → create; oznaka se trajno sprema na `lessor.egop_subjekt_oznaka`) → `ensurePredmet`
  (provjera spremljenog identiteta → `KreirajPredmet2` NP + `OdrediRjesavatelja` best-effort;
  identitet na `submission.egop_{uredska_godina,rbr_predmeta,klasa}`) → ulazno pismeno
  `KreirajPismeno2` → **tek sada** PDF s KLASOM/URBROJEM → `KreirajDokumentZaPismeno` →
  izlazno pismeno (obavijest o dodjeli; do isporuke predložaka prilaže se PDF zahtjeva).
  Napredak: `egop_sync_status` (NEW → SUBJEKT_OK → PREDMET_OK → PISMENO_OK → SYNCED | FAILED),
  po jedan red u `str_rn.egop_pismeno` za svaki urudžbirani akt (Liquibase `051-egop-integration`).
- **`RnIssuedListener`** — **i EU i non-EU idu kroz eGOP** (mail 22.07.); EU/non-EU podjela
  ostaje samo za dostavu (non-EU dodatno mail s PDF-om). Stari `registries.EgopClient`
  (`reserveFilingNumber` model) i `StubEgopClient` su obrisani.
- Nazivi vrsta su **konfigurabilni** (`str.egop.vrsta-predmeta`, `str.egop.vrsta-pismena-zahtjev`,
  `str.egop.vrsta-pismena-dodjela`, `str.egop.nadlezna-org-jedinica`) s defaultima iz maila.

### 17.3 Razriješeno nakon isporuke (potvrda 24.07.)

- **Smjer pismena (ulazno/izlazno)** — nije naša briga; ne šalje se u pozivu, eGOP ga sam
  određuje (konfiguracija vrste pismena). `Smjer` na `egop_pismeno` je samo interna evidencija.
- **Dostava obavijesti** — **KP eGrađana za EU rješava eGOP strana** nakon urudžbiranja; STR radi
  samo non-EU dostavu e-mailom (implementirano u `RnIssuedListener`).

### 17.4 Što je i dalje otvoreno prema InfoDomu

1. ~~**Vrijednosti kredencijala**~~ — **stiglo 26.07.2026.** InfoDom je dao svoj dijeljeni test račun
   (`student1` / NTLM) uz aplikacijski identitet `INFODOM\strservis`; app-domain/app-username su sada
   defaulti u `application.properties`, a NTLM par ide isključivo kroz `EGOP_*` env varijable
   (`.env.cdu.example`). `enabled` i dalje ostaje `false` dok se ne potvrdi dohvatljivost
   `egopeaitest.mint.hr` s CDU kutije. **Otvoreno:** tražiti *namjenski STR servisni račun* — dijeljeni
   račun znači da naši predmeti nastaju u InfoDomovom tenantu, pripisani njihovom korisniku, a rotacija
   lozinke kod njih nas lomi bez najave.
2. **Non-EU subjekt bez OIB-a** — je li `mbJmbg` = strani porezni broj ispravan put; šifrarnik za
   `isoKodDrzave` (MDM `ListaDrzave`?).
3. **`OdrediRjesavatelja`** — je li to predviđeni način stjecanja nadležnosti servisnog računa.
   InfoDom (26.07.): *„rješavatelj mora biti neka osoba s username-om; probajte da je to sve strservis,
   pa možda radi"*. Vrijednost je zato konfigurabilna (`str.egop.rjesavatelj`, prazno = servisni račun),
   a ishod poziva se logira na INFO/ERROR — pad se vidi tek kasnije, kod kreiranja pismena.
4. **Vrste u test okruženju** — jesu li „Izdavanje Registracijskog broja" + 7 vrsta pismena unesene;
   točni nazivi (Knjiga testiranja ↔ mail razlike, §14.4).
5. **PDF predlošci** (7 kom) — najavljeni 22.07. „tijekom dana", još nisu stigli. Do tada izlazno
   pismeno nosi PDF zahtjeva (TODO u `EgopFilingService`).
6. **`postanskiBroj` iznajmljivača** — `lessor` ga ne pohranjuje. Za PDF/adresu objekta koristi se
   `accommodation.postal_code` (changeset 052). Poštanski broj *iznajmljivača* (za adresu subjekta)
   i dalje nije u modelu — čeka proširenje forme.

### 17.5 Robusnost i code-review fixevi (26.07.2026.)

Nakon detaljne evaluacije implementacije popravljeno:

**Bugovi naslijeđeni iz referentnog klijenta:**
- `dohvatiPredmet` — casta odgovor direktno u `PredmetBasicInfo3` (`ClassCastException`); stvarni odgovor
  je wrapper `DohvatiPredmetIdResponse`. **Prijaviti InfoDomu** da im je referentni klijent tu pokvaren.
- **NTLM domena se nikad nije slala** — `NTCredentials(user, pass, null, null)` ne parsira `DOMENA\korisnik`
  iz stringa. `EgopConfig` sada splitta username i predaje domenu zasebnim argumentom (moguć uzrok 401 u testu).
- Nema read timeouta — dodан socket timeout 30 s (obješeni eGOP je blokirao registracijski thread zauvijek).
- MDM šifrarnici s duplim nazivima (npr. „Trgovačko društvo" pod 2 šifre) rušili start aplikacije —
  `Collectors.toMap` merge `(a,b)->a`.

**Otpornost dostave:**
- `EgopRetryJob` (`@Scheduled`, aktivan samo uz `enabled=true`) ponovno urudžbira `FAILED`/nedovršene;
  `submission.egop_sync_attempts` (cap 10) sprječava beskonačni retry. Config `str.egop.retry.*`.
- `EgopRegistrationDispatcher` — zajednička orkestracija (listener + retry job), rekonstruira sve iz baze
  po `submissionId`.
- Idempotentno prilaganje PDF-a — `egop_pismeno.document_attached` + `attachPdfOnce` (retry ne duplira dokument).
- ~~Pesimistički lock `findByIdForUpdate` u `ensureSubjekt`~~ — **ukinut 26.07.**, vidi §17.6.

**Odbačeno svjesno:** `@Async` dostava — pukla „PDF odmah dostupan nakon registracije" ugovor
(`pdf_endpoint`, `PdfLocalExportTest`). Listener ostaje sinkroni `AFTER_COMMIT`; async bi tražio odvajanje
PDF pohrane od eGOP filinga (odgođeno).

**Ostalo:** WSDL-ovi premješteni u `src/main/wsdl/egop/` (van JAR-a); `nazivPredmeta` čitljiv (ime+OIB umjesto UUID).

### 17.6 Otvrdnjavanje prije go-livea (26.07.2026.)

Druga evaluacija, nakon dispatchera i retry joba. Devet nalaza, svi popravljeni.

**Blokeri za paljenje `EGOP_ENABLED=true`:**

- **Duplicirani mail non-EU iznajmljivaču.** Dostava se slala na kraju *svakog* `dispatch()`, a retry job
  dispatcha do 10 puta → do 10 identičnih mailova. Sada `submission.rn_email_sent_at` (changeset 053),
  oznaka se postavlja tek nakon uspješnog slanja.
- **Transakcija otvorena kroz cijeli SOAP lanac.** `dispatch()` je bio `REQUIRES_NEW` i unutar sebe radio
  ~7 SOAP poziva, uz pesimistički lock na iznajmljivaču — do ~3,5 min otvorene transakcije koja drži lock
  i konekciju. Sada sva perzistencija ide kroz **`EgopFilingStore`**, gdje svaki upis ima vlastitu kratku
  transakciju; SOAP se događa *između* njih. Lock je zamijenjen uvjetnim UPDATE-om
  `LessorRepository.assignEgopSubjektIfAbsent` (utrku rješava jednako dobro, bez dugog locka).
- **Dvostruko urudžbiranje istog pismena.** `ensurePismeno` radi „SELECT pa insert", a indeks je bio
  ne-unique. Dodan `uq_egop_pismeno_submission_vrsta` (053) + hvatanje `DataIntegrityViolationException`
  uz ponovno čitanje. *Ključ `(submission_id, vrsta_pismena_naziv)` vrijedi dok su akti jednokratni —
  s ponovljivim aktima životnog ciklusa treba `act_ref`.*

**Ostalo:**

- **Tiho `"\\"` kao app-username** — `application.properties` je sve `EGOP_*` ključeve definirao s praznim
  defaultom, pa `${...:STR}` fallbackovi u kodu nikad nisu odradili. Sada `EgopProperties`
  (`@ConfigurationProperties`) s jednim `qualifiedAppUsername()` i `requireComplete()` koji uz `enabled=true`
  pukne na startu s popisom nedostajućih ključeva.
- **Nema backoffa** — 10 pokušaja na cronu od 2 min izgorjelo bi za 20 minuta i submission bi trajno ostao
  `FAILED`. Dodan `egop_next_attempt_at` (053) + `EgopRetryPolicy` (eksponencijalno, `PT2M` → `PT2H`),
  plus `egop_retry_exhausted` ERROR log kad se pokušaji potroše.
- **Šifrarnici više ne ruše start** — `str.egop.codebooks.fail-fast` (na CDU `false`, jer ta kutija vrti i
  NIAS test). Mape su uvijek ne-null, neuspio refresh zadržava prethodne vrijednosti, `ensureLoaded()` +
  `refresh-cron` same izliječe prolazni ispad bez restarta.
- **Timeouti konfigurabilni i kraći** — 5 s connect / 15 s read (`EGOP_CONNECT_TIMEOUT_MS`,
  `EGOP_READ_TIMEOUT_MS`), jer urudžbiranje i dalje ide sinkrono na request threadu.
- **Mock KLASA/URBROJ dobili prefiks `MOCK-`** — mock je `matchIfMissing = true`, pa je tipfeler u
  `EGOP_ENABLED` tiho punio `submission.egop_klasa` vrijednostima koje izgledaju stvarno.
- **`RnIssuedEvent` sužen** na `(submissionId, rn)` — dispatcher ionako sve čita iz baze da bi isti put
  radio i za retry.

**Redoslijed za prvi živi test:** `curl -skI https://egopeaitest.mint.hr/ServiceMDM.asmx?wsdl`
s CDU kutije (očekuj 401 ili 200; vješanje = firewall)
→ `EGOP_ENABLED=true` → pratiti startup logove šifrarnika → jedna registracija → očitati
`SELECT egop_sync_status, egop_sync_error FROM str_rn.submission ORDER BY created_at DESC LIMIT 5`.
Ključni nepoznanik je `OdrediRjesavatelja` (§17.4 t.3) — pad se manifestira tek na `KreirajPismeno2`.

---

### 17.7 ZUP predlošci (26.07.2026.)

Sedam vrsta pismena dobilo je predloške po strukturi čl. 98. ZUP-a. Puni opis:
**`docs/ZUP-predlosci.md`**. Ovdje samo ono što dira eGOP integraciju:

- **Izlazno pismeno više ne nosi kopiju PDF-a zahtjeva.** TODO iz `EgopFilingService:122` je skinut:
  „Obavijest o dodjeli" se renderira iz vlastitog predloška, s **vlastitim** URBROJ-em. Ranije se
  istoj obavijesti prilagao dokument zahtjeva — dakle s krivim urudžbenim brojem i krivim naslovom.
- **`Function<String, byte[]>` → `EgopDocumentSupplier`** s dvije metode. Jedan callback nije mogao
  poslužiti obama pismenima jer svako dobiva svoj URBROJ, a URBROJ mora biti otisnut u dokumentu koji
  se tom pismenu prilaže.
- **Nazivi vrsta pismena su sada na `StrDocumentType`**, ne u `@Value` propertyjima. Naziv je ključ
  eGOP šifrarnika i mora se poklapati znak za znak; držati ga na tri mjesta bilo je pitanje vremena.
  `str.egop.vrsta-pismena-*` propertyji ostaju radi izmjene bez rebuilda ako InfoDom promijeni naziv.
- **Pad rendera obavijesti ne ruši urudžbiranje** — zahtjev je u tom trenutku već uložen i valjan,
  pa se obavijesti prilaže PDF zahtjeva uz ERROR u logu.

Uz to, van eGOP-a: `GET /api/rn/{rn}/documents/{tip}` je **zatvoren**. Akt po čl. 98. st. 2 nosi OIB
stranke, a endpoint je dotad padao pod `anyRequest().permitAll()`.

---

### 17.8 Akti životnog ciklusa u isti predmet (26.07.2026.)

Do sada se urudžbirala **samo registracija**. Sada i akti koji nastaju iz promjene statusa RB-a
idu u **isti** neupravni predmet.

| Trenutak | Vrsta pismena | Smjer | Okidač u kodu |
| :--- | :--- | :--- | :--- |
| Registracija | Zahtjev za registracijski broj | ulazno | `EgopRegistrationDispatcher` |
| Registracija | Obavijest o dodjeli registracijskog broja | izlazno | isto |
| Opoziv | Obavijest o opozivu registracijskog broja | izlazno | prijelaz → `WITHDRAWN`, actor `LESSOR:` |
| Suspenzija | Obavijest o suspenziji registracijskog broja | izlazno | prijelaz → `SUSPENDED` |
| Povlačenje | Obavijest o povlačenju registracijskog broja | izlazno | prijelaz → `WITHDRAWN` po sl. dužnosti |
| Reaktivacija | Obavijest o reaktivaciji registracijskog broja | izlazno | prijelaz → `ACTIVE`, **iza zastavice** |
| Prijedlog suspenzije | Obavijest o prijedlogu suspenzije | izlazno | ❌ nema dvofazne suspenzije |
| Prigovor | Prigovor na prijedlog suspenzije | ulazno | ❌ tok ne postoji |

**Tok jednog akta** (`RnLifecycleFilingListener` → `EgopAktDispatcher` → `EgopFilingService.fileAct`):

1. renderira se PDF **iz stanja u trenutku događaja**,
2. akt se zapiše u `egop_pismeno` sa statusom `NEW` — „kreiramo lokalno pa šaljemo",
3. `ensureSubjekt` → `ensurePredmet` → `KreirajPismeno2` → `KreirajDokumentZaPismeno`.

Korak 3 ponavlja subjekt i predmet namjerno: ako je registracijsko urudžbiranje trajno palo,
predmet u eGOP-u **ne postoji**, a `KreirajPismeno2` ga ne stvara. To je pravilo „preduvjet da
predmet postoji" — vrijedi za svaki akt, ne samo za prvi.

**Zašto se PDF snima, a ne renderira ponovo pri retryju.** `RnEntity.applyStatus` briše
`suspension_deadline` pri izlasku iz `SUSPENDED`, a zadnji zapis revizijskog traga se mijenja sa
svakim novim prijelazom. Ponovni render bi poslao **drugi dokument pod istim urudžbenim brojem**.

**Zašto `act_ref`.** Ključ `(submission_id, vrsta_pismena_naziv)` iz changeseta 053 vrijedio je
dok su akti bili jednokratni. Ciklus suspenzija → reaktivacija → suspenzija daje dvije
„Obavijest o suspenziji" nad istim submissionom. `act_ref` je `log_id` prijelaza; registracijski
akti nose konstantu `REGISTRACIJA`. NOT NULL je bitan — NULL se u unique ključu ne uspoređuje.

**Zašto akt ima vlastiti status i retry.** `submission.egop_sync_status` prati registraciju.
Suspenzija urudžbirana šest mjeseci kasnije ne smije dirati taj state machine; pad jednog ne
smije značiti ponovno slanje drugog.

**Reaktivacija je iza `str.egop.urudzbiraj-reaktivaciju` (ugašeno).** Ta vrsta nije među 7 iz
maila 22.07. i nema šifru — slanje bi palo na razrješavanju vrste i vrtjelo se do iscrpljenja.
Obavijest e-poštom ide neovisno.

#### Dostava (točka 7 zadatka)

| Kanal | Stanje |
| :--- | :--- |
| KP eGrađana (NIAS korisnici) | ❌ nema klijenta ni specifikacije — BX1. **Otvoreno prema InfoDomu: šalje li eGOP sam u KP kad nastane izlazno pismeno?** Ako da, s naše strane nema posla. |
| E-pošta (non-EU) | ✅ radi; akt ide u privitku |

Poruka razlikuje dva slučaja jer bi inače lagala: non-EU iznajmljivač nema pretinac, pa mu je
e-pošta **kanal dostave** (čl. 94. st. 4) i rokovi teku od nje; korisniku s OIB-om poruka kaže da
se akt dostavlja u pretinac i da rokovi teku odande — **ne** tvrdi da je dostava već obavljena,
jer dok KP ne postoji to ne bi bilo točno.

#### Neriješeno

- **KP dostava** — vidi gore.
- **Gubitak akta pri padu između commita i listenera.** Akt se zapisuje u AFTER_COMMIT; ako
  proces padne točno između, prijelaz je u `registration_number_log` a akta nema. Isti stupanj
  trajnosti kao postojeći registracijski put. Zatvorilo bi ga usklađivanje log ↔ akt.
- **NTLM iz Dockera** neprovjeren (natuknica „sad smo u Dockeru, možda će bit problema s
  produkcijom") — ide u isti smoke test kao §17.6.
