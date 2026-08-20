# V-Core backend

Java 21 and Spring Boot modular monolith for V-Core. PostgreSQL is the source of truth; Redis is limited to ephemeral infrastructure concerns.

## Run dependencies

From the repository root:

```powershell
docker compose up -d postgres redis
```

## Run the API

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```bash
cd backend
./mvnw spring-boot:run
```

The API listens on `http://localhost:8080`. Liveness is available at `/actuator/health/liveness`, readiness at `/actuator/health/readiness`, and aggregate health at `/actuator/health`.

## Verify

Docker must be running because integration tests use disposable PostgreSQL and Redis containers.

```powershell
.\mvnw.cmd verify
```

Schema changes are forward-only Flyway migrations in `src/main/resources/db/migration`. Never use Hibernate schema generation outside disposable experiments.
