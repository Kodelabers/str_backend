# Točna verzija Jave u runtime imageu. Plutajući tag (`21-jre`) bi na svakoj kutiji ostao na
# verziji koju je ondje zatekao lokalni cache, pa okoline tiho divergiraju.
# 21.0.13.1+2 = izvanredni OpenJDK security patch iza listopadskog CPU-a 21.0.13 (Adoptium GA 18.11.2026.).
# U Docker tagu `+` iz verzije postaje `_`. Kod patcha mijenjati zajedno s Dockerfile.artifact
# i server-only Dockerfile.cdu na CDU testu (DEPLOY-CDU.md, „Nadogradnja Jave").
ARG JAVA_VERSION=21.0.13.1_2

# --- Build stage --- (samo kompajlira; Java koja se izvršava dolazi iz runtime stagea niže)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# --- Runtime stage ---
FROM eclipse-temurin:${JAVA_VERSION}-jre
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
