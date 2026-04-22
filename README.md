# STR Backend — Short-Term Rental Microservice

Spring Boot 3 microservice for managing short-term rental **properties**, **guests**, and **bookings**.

## Tech Stack
- Java 21
- Spring Boot 3.3
- Spring Data JPA
- PostgreSQL
- Lombok
- Gradle 8.7

## Getting Started

### Prerequisites
- Java 21+
- PostgreSQL running locally
- Gradle 8.7+ (or use the included wrapper)

### Setup

1. Create the database:
```sql
CREATE DATABASE str_db;
```

2. Update credentials in `src/main/resources/application.properties`:
```properties
spring.datasource.username=your_user
spring.datasource.password=your_password
```

3. Run the application:
```bash
./gradlew bootRun
```

The service starts on **http://localhost:8080**

---

## API Endpoints

### Properties
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/properties` | Create a property |
| GET | `/api/v1/properties` | List all properties |
| GET | `/api/v1/properties/{id}` | Get property by ID |
| GET | `/api/v1/properties/available?checkIn=&checkOut=` | Find available properties |
| PUT | `/api/v1/properties/{id}` | Update property |
| DELETE | `/api/v1/properties/{id}` | Delete property |

### Guests
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/guests` | Register a guest |
| GET | `/api/v1/guests` | List all guests |
| GET | `/api/v1/guests/{id}` | Get guest by ID |
| PUT | `/api/v1/guests/{id}` | Update guest |
| DELETE | `/api/v1/guests/{id}` | Delete guest |

### Bookings
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/bookings` | Create a booking |
| GET | `/api/v1/bookings/{id}` | Get booking by ID |
| GET | `/api/v1/bookings/guest/{guestId}` | Get bookings by guest |
| GET | `/api/v1/bookings/property/{propertyId}` | Get bookings by property |
| PATCH | `/api/v1/bookings/{id}/confirm` | Confirm booking |
| PATCH | `/api/v1/bookings/{id}/cancel` | Cancel booking |
| PATCH | `/api/v1/bookings/{id}/complete` | Complete booking |

---

## Example Requests

### Create a Property
```json
POST /api/v1/properties
{
  "name": "Seaside Villa",
  "description": "Beautiful villa with ocean view",
  "address": "123 Ocean Drive",
  "city": "Split",
  "country": "Croatia",
  "maxGuests": 6,
  "pricePerNight": 200.00
}
```

### Create a Booking
```json
POST /api/v1/bookings
{
  "propertyId": 1,
  "guestId": 1,
  "checkIn": "2025-07-01",
  "checkOut": "2025-07-07",
  "numberOfGuests": 4
}
```

## Running Tests
```bash
./gradlew test
```

## Build JAR
```bash
./gradlew bootJar
```
