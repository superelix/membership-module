# Setup

Step-by-step instructions to get the Membership Module running from a clean machine. For what the
project *is*, see [README.md](README.md); for how it behaves once running, see
[WORKFLOW.md](WORKFLOW.md).

---

## 1. Prerequisites

| Tool | Required version | Check |
|---|---|---|
| Java (JDK) | 21+ | `java -version` |
| Docker Desktop | any recent version, daemon running | `docker info` |
| Gradle | not required — the project ships its own wrapper (`./gradlew`) | — |

You do **not** need to install PostgreSQL, Liquibase, or Gradle yourself — Postgres runs in Docker,
Liquibase ships as a project dependency and runs automatically on boot, and the Gradle wrapper
downloads the correct Gradle version on first use.

If Java 21 isn't installed, the simplest fix is via the Gradle-managed toolchain — running
`./gradlew` for the first time will auto-provision a matching JDK if none is found, so step 1 can
be a soft requirement in practice. If you'd rather install it directly: `brew install
openjdk@21` (macOS) or your platform's equivalent.

---

## 2. Start Docker Desktop

The app needs a running Postgres container, which needs a running Docker daemon.

```bash
open -a Docker        # macOS — starts Docker Desktop if not already running
```

Wait for it to finish starting, then confirm:

```bash
docker info
```

If this errors with `Cannot connect to the Docker daemon`, Docker Desktop is still starting —
wait a few seconds and retry.

---

## 3. Start PostgreSQL

From the project root:

```bash
cd ~/IdeaProjects/membership-module
docker compose up -d
```

This starts a single `postgres:16-alpine` container (`membership-module-postgres`) with a named
volume for persistence, exposed on the default port `5432`, with credentials already wired into
the app's config (`membership`/`membership`/`membership` — local dev only, not a real secret).

Confirm it's healthy before moving on:

```bash
docker compose ps
```

You want to see `Up ... (healthy)` in the `STATUS` column. If it says `(health: starting)`, wait a
few seconds and check again.

---

## 4. Run the app

```bash
./gradlew bootRun
```

On startup, the app will:
1. Connect to the Postgres container from step 3.
2. Run all Liquibase migrations automatically (creates all 12 tables on a fresh database — nothing
   to run manually).
3. Seed demo data (`Plan`s, `Tier`s with their criteria/benefits, and one pre-qualified `GOLD`
   demo member, `demo-gold-member`) — this seeding is idempotent, so restarting the app against the
   same database won't double-seed.
4. Start listening on `http://localhost:8080`.

You'll know it's ready when the log shows:
```
Started MembershipModuleApplication in N.NNN seconds
```

---

## 5. Verify it's working

In a separate terminal:

```bash
curl -s http://localhost:8080/api/v1/plans
```

Expected: a JSON list of two plans (`MONTHLY`, `YEARLY`). If you get a connection error, the app
isn't listening yet — check the terminal from step 4 for errors.

For a fuller smoke test (subscribe, checkout, tier progress), see the **Quick start** section in
[README.md](README.md), or the full walkthrough in [WORKFLOW.md](WORKFLOW.md).

---

## 6. Run the automated test suite

```bash
./gradlew clean build
```

This runs all 44 tests. You do **not** need the docker-compose Postgres running for this — the
test suite uses [Testcontainers](https://testcontainers.com) to spin up its own throwaway Postgres
container automatically for the duration of the test run, entirely independent of the container
from step 3. (Docker itself must still be running, per step 2.)

---

## 7. Stopping / resetting

```bash
# Stop the app: Ctrl+C in the terminal running `bootRun`

# Stop Postgres, keep its data for next time
docker compose down

# Stop Postgres and wipe all data (next `bootRun` starts from a genuinely empty database)
docker compose down -v

# Check container status any time
docker compose ps
```

---

## Opening in IntelliJ IDEA

The project is a standard Gradle project and imports as-is:

1. **File → Open**, select the `membership-module` directory.
2. IntelliJ will detect `build.gradle` and offer to import as a Gradle project — accept.
3. Wait for the initial Gradle sync (downloads dependencies on first import).
4. Run/debug `MembershipModuleApplication` directly from the IDE, or keep using `./gradlew
   bootRun` from a terminal — either works, but Docker Compose (step 3 above) must be running
   first either way.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Cannot connect to the Docker daemon` | Docker Desktop not running | `open -a Docker`, wait, retry |
| `docker compose up` fails with a port conflict on `5432` | Another Postgres (local install or another project's container) is already using port 5432 | Stop the other process, or change the host-side port mapping in `docker-compose.yml` (e.g. `"5433:5432"`) and update `spring.datasource.url` in `application.properties` to match |
| App fails to start with a connection-refused error to Postgres | Container not up yet, or not healthy | `docker compose ps` — wait for `(healthy)`, retry `bootRun` |
| Port `8080` already in use | Another process (maybe a previous unstopped run of this same app) is bound to it | Find and stop it: `lsof -i :8080`, or set `server.port` in `application.properties` |
| Liquibase fails with a checksum/validation error after pulling new changes | A changelog file was edited after already being applied to your local database | For local dev only: `docker compose down -v` to wipe and start clean (never do this against a real environment) |
| `./gradlew: Permission denied` | Wrapper script lost its executable bit (can happen after certain zip extractions) | `chmod +x gradlew` |
| Tests fail with a Docker-related error | Testcontainers needs Docker running, same as step 2 | Confirm `docker info` succeeds, retry `./gradlew clean build` |

If something's still stuck after these, check the app's own log output first — errors during
startup are almost always self-explanatory (missing Docker, bad connection string, migration
conflict).
