# PI Security Copilot

A web application that helps organizations discover likely personal data (PI) in existing applications and databases, map where the data is used, and prepare controlled encryption migrations.

## Technology Stack

Everyone on the team should use the same major versions listed here. Mixing major versions (for example Angular 21 core with Angular Material 19) breaks the build.

### Frontend (`frontend/`)

| Package                    | Version  | Notes                                  |
|----------------------------|----------|----------------------------------------|
| Angular (core, router, …)  | 21.2.x   | Standalone components                  |
| Angular CLI / `@angular/build` | 21.2.x | Builder: `@angular/build:application` |
| Angular Material / CDK     | 21.2.x   | Theme uses the M2 API (`mat.m2-*`)     |
| NgRx (store, effects, signals, …) | 21.1.x | NgRx 22 requires Angular 22 — do not upgrade alone |
| TypeScript                 | 5.9.x    | Angular 21 requires `>=5.9 <6.1`       |
| RxJS                       | 7.8.x    |                                        |
| zone.js                    | 0.15.x   |                                        |

### Backend (`backend/`)

| Component        | Version        |
|------------------|----------------|
| Java             | 25 (LTS)       |
| Spring Boot      | 3.5.0          |
| Flyway           | 11.2.0         |
| springdoc-openapi| 2.8.3          |
| Lombok           | 1.18.42        |
| MapStruct        | 1.6.3          |

### Infrastructure

| Component  | Version            |
|------------|--------------------|
| PostgreSQL | 16 (`postgres:16-alpine`) |
| Keycloak   | 26.0 (optional)    |

## Prerequisites

Install these tools before you start:

| Tool           | Required version         | Check with        |
|----------------|--------------------------|-------------------|
| JDK            | 25                       | `java -version`   |
| Maven          | 3.9.x                    | `mvn -v`          |
| Node.js        | 22 LTS (22.12 or newer)  | `node -v`         |
| npm            | 11.x                     | `npm -v`          |
| Docker Desktop | Any recent version (for PostgreSQL) | `docker compose version` |
| Angular CLI    | 21.x, global install optional | `ng version` |

Notes:

- **npm 11 is required.** npm 10.9.x fails on this project with `Cannot read properties of null (reading 'edgesOut')`. Upgrade with `npm install -g npm@11`.
- **Maven must run on JDK 25.** Check the `Java version:` line in `mvn -v`. If it shows another JDK (e.g. 17), set `JAVA_HOME` to your JDK 25 folder and reopen the terminal.
- The Angular CLI does not need to be installed globally. `npx ng …` and `npm start` use the version pinned in the project.

## Local Development

### 1. Start PostgreSQL

Create `docker/.env`. It is not committed, so each developer creates their own:

```env
POSTGRES_DB=picopilot
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
```

These values match the backend's default `DB_USERNAME` / `DB_PASSWORD`. If you choose different values, set `DB_USERNAME` and `DB_PASSWORD` to match before you start the backend.

```bash
docker compose -f docker/docker-compose.yml up -d postgres
```

### 2. Start the backend (port 8080)

```bash
cd backend
mvn spring-boot:run
```

There is no Maven wrapper (`mvnw`) in the repository yet, so use your installed Maven 3.9.x. Flyway creates the schema automatically on first start.

### 3. Start the frontend (port 4200)

```bash
cd frontend
npm ci          # installs exact versions from package-lock.json
npm start       # same as: npx ng serve
```

Open `http://localhost:4200`. The dev server sends `/api/*` requests to `http://localhost:8080` (see `frontend/proxy.conf.json`).

### Default local login

Local authentication is enabled by default (`LOCAL_AUTH_ENABLED=true`), so Keycloak is not needed for development.

| Username | Password   |
|----------|------------|
| `admin`  | `admin123` |

Change these with `LOCAL_ADMIN_USERNAME` / `LOCAL_ADMIN_PASSWORD`. **Never use the defaults outside local development.**

## Ports

| Service     | Port |
|-------------|------|
| Frontend    | 4200 |
| Backend API | 8080 (`/api/v1`, health at `/actuator/health`) |
| PostgreSQL  | 5432 |
| Keycloak    | 8180 (optional) |

## Environment Variables (backend)

| Variable                | Default                                  | Purpose                         |
|-------------------------|------------------------------------------|---------------------------------|
| `DB_USERNAME`           | `postgres`                               | Database user                   |
| `DB_PASSWORD`           | `postgres`                               | Database password               |
| `LOCAL_AUTH_ENABLED`    | `true`                                   | Built-in login (disable in prod)|
| `LOCAL_ADMIN_USERNAME`  | `admin`                                  | Local admin user                |
| `LOCAL_ADMIN_PASSWORD`  | `admin123`                               | Local admin password            |
| `LOCAL_AUTH_JWT_SECRET` | dev-only value                           | Signing key for local tokens    |
| `GOOGLE_CLIENT_ID`      | empty (Google button hidden)             | Google Sign-In                  |
| `LOCAL_ADMIN_EMAILS`    | empty                                    | Google accounts given PLATFORM_ADMIN |
| `CORS_ORIGINS`          | `http://localhost:4200,…`                | Allowed browser origins         |
| `OIDC_ISSUER_URI`       | `http://localhost:8180/realms/picopilot` | Keycloak issuer                 |
| `GITHUB_APP_ID`, `GITHUB_INSTALLATION_ID`, `GITHUB_PRIVATE_KEY_REF` | empty | GitHub connector |

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `'ng' is not recognized…` | Use `npx ng serve` or `npm start`, or install the CLI globally: `npm install -g @angular/cli@21` |
| `This command is not available when running the Angular CLI outside a workspace` | Run the command inside `frontend/`, where `angular.json` lives |
| `Port 4200 is already in use` | Another dev server is running. Stop it, or run `npx ng serve --port 4201`. On Windows, find the process with `Get-NetTCPConnection -LocalPort 4200` |
| npm error `reading 'edgesOut'` | Upgrade to npm 11 (`npm install -g npm@11`). Then delete `node_modules` and run `npm ci` |
| `ERESOLVE` peer dependency errors | Someone mixed major versions. Keep all `@angular/*` packages on 21.x and all `@ngrx/*` packages on 21.x |
| Maven: `release version 25 not supported` | Maven is running on an older JDK. Point `JAVA_HOME` to JDK 25 |
| Backend can't connect to the database | Check that the Postgres container is running and that `DB_USERNAME` / `DB_PASSWORD` match `docker/.env` |

## Upgrading Dependencies

- Upgrade Angular with `npx ng update @angular/core@<ver> @angular/cli@<ver>`. Upgrade `@angular/material` / `@angular/cdk` and `@ngrx/*` to the matching major in the same change.
- Commit `frontend/package-lock.json`. Teammates should run `npm ci`, not `npm install`, so they get the same versions.
- When you change a version, update the tables in this README in the same commit.

## Documentation

- [Frontend Architecture](docs/frontend-architecture.md)

## Project Structure

```
pi-copilot/
├── backend/                    # Spring Boot REST API
│   ├── src/main/java/com/picopilot/
│   │   ├── config/            # Security, DB, async config
│   │   ├── identity/          # Auth, workspace, RBAC
│   │   ├── project/           # Project and connector management
│   │   ├── scanner/           # Scan orchestration, workers
│   │   ├── discovery/         # Rule engine, parsers, AI adapter
│   │   ├── inventory/         # PI findings, evidence, review
│   │   ├── encryption/        # Key provider abstraction
│   │   ├── migration/         # Migration plans, PR generation
│   │   ├── backfill/          # Batch backfill worker
│   │   ├── featureflag/       # Feature flag and cutover
│   │   ├── audit/             # Audit events, reports
│   │   └── common/            # Shared utilities, DTOs
│   └── src/main/resources/
│       └── db/migration/      # Flyway migrations
├── frontend/                   # Angular application
│   ├── angular.json           # Angular workspace / build config
│   ├── proxy.conf.json        # Dev-server proxy to backend
│   └── src/app/
│       ├── core/              # Auth, guards, interceptors
│       ├── shared/            # Shared components, pipes
│       ├── features/          # Feature components (lazy-loaded)
│       └── store/             # NgRx state management
├── docker/                     # Docker Compose and configs
└── docs/                       # Architecture and guides
```

## Security

- OAuth2/OIDC authentication with MFA support
- Role-based access control (RBAC)
- Tenant/workspace isolation
- Secrets stored in external secret manager; never in database or source code
- TLS encryption for all network traffic
- Audit trail for all security-sensitive actions

## License

Proprietary — all rights reserved.
