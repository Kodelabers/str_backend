# Non-EU Lessor Registration — Implementation Reference

## Što radimo i zašto

STR backend podržava dva tipa iznajmljivača:
- **EU iznajmljivači** — autentificiraju se putem eIDAS/NIAS, nemaju username/password u bazi
- **Non-EU iznajmljivači** — nemaju eIDAS pristup; Valentina je dodala username/password login sistem (`AuthController`, `LessorPrincipal`, `LessorUserDetailsService`)

Trenutno postoji samo jedan hardkodirani test-lessor (`NonEuTestLessorSeeder`). **Cilj ove implementacije je self-service registracija** — non-EU iznajmljivač šalje formu s osobnim podacima i slikama isprave, backend kreira zapis i generira kredencijale.

**Endpoint:** `POST /api/registerLessor` (multipart/form-data)

---

## Ulazni podaci (iz forme)

| Form field | Tip | Mapira na |
|---|---|---|
| `ime` | String | `lessor.first_name` |
| `prezime` | String | `lessor.last_name` |
| `datumRodjenja` | LocalDate | `lessor.date_of_birth` (nova kolona) |
| `porezniBroj` | String | `lessor.tax_number` (nova kolona) |
| `zemljaPrebivalistaId` | Integer | `lessor.country_of_residence_id` (nova kolona) |
| `stalnaAdresa` | String | `lessor.street` (cijela adresa u jedno polje) |
| `vrstaIsprave` | String | `lessor_document.document_type` |
| `brojIsprave` | String | `lessor_document.document_number` |
| `email` | String | `lessor.email` |
| `telefon` | String | `lessor.mobile_number` (opcionalno) |
| `ispravaPrednja` | MultipartFile | `lessor_document.front_image` (BYTEA) |
| `ispravaStraznja` | MultipartFile | `lessor_document.back_image` (BYTEA, opcionalno) |

**Napomena za adresu:** `lessor` tablica ima `street`, `street_number`, `place`, `county` koji su za EU/HR adrese. Za non-EU, cijela adresa ide u `street`; ostale kolone postaju nullable migracijom.

**Napomena za credentials:** forma ne šalje lozinku. Backend generira username (iz email prefixa) + random lozinku (12 znakova, `SecureRandom`), vraća ih u 201 responsu. Korisnik ih vidi jednom.

---

## Arhitektura

```
LessorController
    └── LessorRegistrationService
            ├── LessorRepository          (findByEmail, findByUsername, save)
            └── LessorDocumentRepository  (save)
                    └── LessorDocumentEntity  (nova tablica str_rn.lessor_document)
```

**Zašto odvojena `lessor_document` tablica?**  
Slike isprave mogu biti nekoliko MB-a po zapisu. Da su BYTEA kolone direktno na `lessor` tablici, svaki `SELECT` na lessoru (login, auth/me, lookups) povlačio bi slike. S odvojenom tablicom query planeri ne diraju slike dok ih eksplicitno ne tražiš.

---

## Dependency chain — redoslijed implementacije

```
Faza 1: Schema (Liquibase)
    032-lessor-non-eu-fields.xml
    033-lessor-document.xml
    db.changelog-master.xml  ← oba upisati ovdje

Faza 2: Entiteti i repozitoriji
    LessorEntity.java          ← nova polja + factory metoda
    LessorDocumentEntity.java  ← novi entitet (ovisi o 033 migraciji)
    LessorDocumentRepository   ← novi repo (ovisi o LessorDocumentEntity)
    LessorRepository           ← dodati findByEmail (ovisi o LessorEntity)

Faza 3: DTOs
    LessorRegistrationRequest.java   ← neovisno
    LessorRegistrationResponse.java  ← neovisno

Faza 4: Servis
    LessorRegistrationService.java   ← ovisi o Fazi 2 i 3

Faza 5: Controller
    LessorController.java            ← ovisi o Fazi 3 i 4
```

---

## Faza 1: Liquibase migracije

### `032-lessor-non-eu-fields.xml`

Dodaje nove kolone i čini adresna polja nullable u `str_rn.lessor`:

```xml
<changeSet id="032" author="...">
    <!-- Nove kolone -->
    <addColumn tableName="lessor" schemaName="str_rn">
        <column name="date_of_birth" type="DATE"/>
        <column name="country_of_residence_id" type="INTEGER"/>
        <column name="tax_number" type="VARCHAR(64)"/>
    </addColumn>

    <!-- street_number, place, county postaju nullable -->
    <dropNotNullConstraint tableName="lessor" schemaName="str_rn"
        columnName="street_number" columnDataType="VARCHAR(16)"/>
    <dropNotNullConstraint tableName="lessor" schemaName="str_rn"
        columnName="place" columnDataType="VARCHAR(128)"/>
    <dropNotNullConstraint tableName="lessor" schemaName="str_rn"
        columnName="county" columnDataType="VARCHAR(128)"/>
</changeSet>
```

### `033-lessor-document.xml`

Nova tablica `str_rn.lessor_document`:

```xml
<changeSet id="033" author="...">
    <createTable tableName="lessor_document" schemaName="str_rn">
        <column name="document_id" type="UUID">
            <constraints primaryKey="true" nullable="false"/>
        </column>
        <column name="lessor_id" type="UUID">
            <constraints nullable="false"
                foreignKeyName="fk_lessor_document_lessor"
                referencedTableSchemaName="str_rn"
                referencedTableName="lessor"
                referencedColumnNames="lessor_id"
                deleteCascade="true"/>
        </column>
        <column name="document_type" type="VARCHAR(32)">
            <constraints nullable="false"/>
        </column>
        <column name="document_number" type="VARCHAR(64)">
            <constraints nullable="false"/>
        </column>
        <column name="front_image" type="BYTEA">
            <constraints nullable="false"/>
        </column>
        <column name="back_image" type="BYTEA"/>
        <column name="uploaded_at" type="TIMESTAMPTZ">
            <constraints nullable="false"/>
        </column>
    </createTable>
</changeSet>
```

Dodati oba u `db/changelog/db.changelog-master.xml` u ovom redoslijedu, s kontekstom ako je potreban.

---

## Faza 2: Entiteti i repozitoriji

### `LessorEntity.java` izmjene

**Fajl:** `src/main/java/com/str/backend/lessor/LessorEntity.java`

Dodati polja (sva `updatable = false` jer su identity podaci):
```java
@Column(name = "date_of_birth", updatable = false)
private LocalDate dateOfBirth;

@Column(name = "country_of_residence_id", updatable = false)
private Integer countryOfResidenceId;

@Column(name = "tax_number", updatable = false)
private String taxNumber;
```

`street_number`, `place`, `county` — provjeriti imaju li `nullable = false` u `@Column`; ako da, ukloniti to.

Nova factory metoda (pored postojećih `create()` i `createNonEu()`):
```java
public static LessorEntity createNonEuRegistration(
        String firstName, String lastName,
        String street, String email,
        String username, String passwordHash,
        LocalDate dateOfBirth, Integer countryOfResidenceId,
        String taxNumber, String mobileNumber) {

    LessorEntity e = new LessorEntity();
    e.lessorId = UUID.randomUUID();
    e.firstName = firstName;
    e.lastName = lastName;
    e.street = street;
    // street_number, place, county → null (nullable nakon migracije 032)
    e.email = email;
    e.username = username;
    e.passwordHash = passwordHash;
    e.dateOfBirth = dateOfBirth;
    e.countryOfResidenceId = countryOfResidenceId;
    e.taxNumber = taxNumber;
    e.mobileNumber = mobileNumber;
    e.applicationStatus = SubmissionStatus.INITIATED;
    Instant now = Instant.now();
    e.createdAt = now;
    e.updatedAt = now;
    return e;
}
```

### `LessorDocumentEntity.java` (novi)

**Fajl:** `src/main/java/com/str/backend/lessor/LessorDocumentEntity.java`  
**Pattern:** identičan ostalim entitetima — `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, bez `@Data`/`@Builder`, static factory.

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "lessor_document", schema = "str_rn")
public class LessorDocumentEntity {

    @Id
    @Column(name = "document_id", updatable = false, nullable = false)
    private UUID documentId;

    @Column(name = "lessor_id", updatable = false, nullable = false)
    private UUID lessorId;

    @Column(name = "document_type", updatable = false, nullable = false)
    private String documentType;

    @Column(name = "document_number", updatable = false, nullable = false)
    private String documentNumber;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "front_image", updatable = false, nullable = false)
    private byte[] frontImage;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "back_image", updatable = false)
    private byte[] backImage;

    @Column(name = "uploaded_at", updatable = false, nullable = false)
    private Instant uploadedAt;

    public static LessorDocumentEntity create(UUID lessorId, String documentType,
                                               String documentNumber,
                                               byte[] frontImage, byte[] backImage) {
        LessorDocumentEntity d = new LessorDocumentEntity();
        d.documentId = UUID.randomUUID();
        d.lessorId = lessorId;
        d.documentType = documentType;
        d.documentNumber = documentNumber;
        d.frontImage = frontImage;
        d.backImage = backImage;
        d.uploadedAt = Instant.now();
        return d;
    }
}
```

**Važno:** `@JdbcTypeCode(SqlTypes.VARBINARY)` — isti tip kao `SubmissionEntity.pdfContent` nakon bugfixa. `MATERIALIZED_BLOB` šalje OID (bigint) na PostgreSQL `bytea` kolonu — ne koristiti.

### `LessorDocumentRepository.java` (novi)

```java
@Repository
public interface LessorDocumentRepository extends JpaRepository<LessorDocumentEntity, UUID> {}
```

### `LessorRepository.java` izmjena

Dodati:
```java
Optional<LessorEntity> findByEmail(String email);
```

---

## Faza 3: DTOs

### `LessorRegistrationRequest.java`

**Fajl:** `src/main/java/com/str/backend/lessor/LessorRegistrationRequest.java`

Ne koristiti record — multipart/form-data binding s `@ModelAttribute` zahtijeva setters.

```java
@Getter
@Setter
public class LessorRegistrationRequest {

    @NotBlank @Size(max = 128)
    private String ime;

    @NotBlank @Size(max = 128)
    private String prezime;

    @NotNull
    private LocalDate datumRodjenja;

    @NotBlank @Size(max = 64)
    private String porezniBroj;

    @NotNull
    private Integer zemljaPrebivalistaId;

    @NotBlank @Size(max = 500)
    private String stalnaAdresa;

    @NotBlank @Size(max = 32)
    private String vrstaIsprave;

    @NotBlank @Size(max = 64)
    private String brojIsprave;

    @NotBlank @Email @Size(max = 255)
    private String email;

    @Size(max = 32)
    private String telefon;

    @NotNull
    private MultipartFile ispravaPrednja;

    private MultipartFile ispravaStraznja;
}
```

### `LessorRegistrationResponse.java`

```java
public record LessorRegistrationResponse(
    UUID lessorId,
    String username,
    String temporaryPassword
) {}
```

---

## Faza 4: `LessorRegistrationService.java`

**Fajl:** `src/main/java/com/str/backend/lessor/LessorRegistrationService.java`

```java
@Service
@Transactional
public class LessorRegistrationService {

    // Injectirati via constructor: LessorRepository, LessorDocumentRepository, PasswordEncoder

    public LessorRegistrationResponse register(LessorRegistrationRequest req) throws IOException {

        // 1. Email duplikat check
        if (lessorRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email već registriran");
        }

        // 2. Username generacija
        String username = generateUniqueUsername(req.getEmail());

        // 3. Password generacija
        String plaintextPassword = generateRandomPassword();
        String hash = passwordEncoder.encode(plaintextPassword);

        // 4. Kreiranje lessor entiteta
        LessorEntity lessor = LessorEntity.createNonEuRegistration(
            req.getIme(), req.getPrezime(),
            req.getStalnaAdresa(), req.getEmail(),
            username, hash,
            req.getDatumRodjenja(), req.getZemljaPrebivalistaId(),
            req.getPorezniBroj(), req.getTelefon()
        );
        lessorRepository.save(lessor);

        // 5. Kreiranje dokumenta
        byte[] back = req.getIspravaStraznja() != null
            ? req.getIspravaStraznja().getBytes() : null;
        LessorDocumentEntity doc = LessorDocumentEntity.create(
            lessor.getLessorId(),
            req.getVrstaIsprave(), req.getBrojIsprave(),
            req.getIspravaPrednja().getBytes(), back
        );
        lessorDocumentRepository.save(doc);

        return new LessorRegistrationResponse(lessor.getLessorId(), username, plaintextPassword);
    }

    private String generateUniqueUsername(String email) {
        // prefix emaila, lowercase, samo [a-z0-9._-]
        String base = email.split("@")[0]
            .toLowerCase()
            .replaceAll("[^a-z0-9._-]", "");
        if (base.isBlank()) base = "user";

        String candidate = base;
        int suffix = 2;
        while (lessorRepository.findByUsername(candidate).isPresent()) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private String generateRandomPassword() {
        // 12 znakova iz [A-Za-z0-9]
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }
}
```

**`PasswordEncoder` bean** je već definiran u `SecurityConfig` — injectirati ga standardno.

---

## Faza 5: `LessorController.java`

**Fajl:** `src/main/java/com/str/backend/lessor/LessorController.java`

```java
@RestController
@RequestMapping("/api")
@Validated
public class LessorController {

    private final LessorRegistrationService registrationService;

    public LessorController(LessorRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping(value = "/registerLessor",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LessorRegistrationResponse register(
            @Valid @ModelAttribute LessorRegistrationRequest req) throws IOException {
        return registrationService.register(req);
    }
}
```

`SecurityConfig` ima `anyRequest().permitAll()` — **nema izmjene security konfiguracije**.

---

## Ključne napomene za implementatora

1. **`@JdbcTypeCode` vrijednost za BYTEA** — uvijek koristiti `SqlTypes.VARBINARY`. `SqlTypes.MATERIALIZED_BLOB` šalje PostgreSQL Large Object OID (bigint) umjesto bytea — postoji aktivan bug s tim u `SubmissionEntity` koji je upravo fixan.

2. **`@ModelAttribute` vs `@RequestBody`** — za multipart forme s fajlovima koristi `@ModelAttribute`. `@RequestBody` ne zna parsirati `multipart/form-data`.

3. **Nullability u `LessorEntity`** — provjeri postoji li `nullable = false` u `@Column` anotacijama na `streetNumber`, `place`, `county`. Ako postoji, ukloniti — te kolone su nullable u bazi nakon migracije 032.

4. **`PasswordEncoder` scope** — bean je definiran u `SecurityConfig` kao `@Bean`. Može se injectirati direktno u servis; ne kreirati novu instancu u servisu.

5. **IOException propagacija** — `MultipartFile.getBytes()` baca `IOException`. Controller metoda mora deklarirati `throws IOException` ili ga treba omotati u `UncheckedIOException`. `GlobalExceptionHandler` ne hendla `IOException` eksplicitno — sigurnije je `throws IOException` na controller i service metodama dok nema explicit handling.

6. **`@Table(schema = "str_rn")`** — svi entiteti koji pišu u `str_rn` moraju imati eksplicitnu schema deklaraciju (CLAUDE.md constraint).

---

## Verifikacija

```bash
mvn compile           # nema kompajlerskih grešaka
mvn test              # postojeći testovi prolaze (nema regresija)
```

Manualni happy-path test:
```
POST /api/registerLessor  (multipart/form-data)
→ 201 { lessorId, username, temporaryPassword }

POST /api/auth/login { username, temporaryPassword }
→ 200 + Set-Cookie: SESSION=...

GET /api/auth/me
→ 200 { lessorId, username, firstName, lastName, email }
```

Edge case provjere:
- Isti email → 409 Conflict
- `ispravaPrednja` nedostaje → 400 Bad Request
- Nevalidan `email` format → 400 Bad Request
- `datumRodjenja` krivog formata → 400 Bad Request
