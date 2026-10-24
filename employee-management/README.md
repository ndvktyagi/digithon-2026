# Employee Management Application

A Java 17 + Spring Boot REST API, PostgreSQL database and Angular UI. This application is intended as the baseline repository for the PrivacyGuard DPDP implementation-agent prototype.

## Features
- Employee create, list, retrieve, update and delete operations
- Request validation and consistent HTTP error responses
- PostgreSQL persistence with Flyway schema migration
- Swagger UI / OpenAPI documentation
- Angular employee directory with search, add, edit and delete
- Synthetic/demo data only; no authentication is included in this MVP

## Prerequisites
- Java 17
- Maven 3.9+
- Docker with Docker Compose (or a local PostgreSQL 16 instance)
- Node.js 20.19+ or 22.12+ and npm (for Angular 20)

## 1. Start PostgreSQL
From the project root:

```bash
docker compose up -d postgres
```

The local development database is `employeedb`, user `employee_app`, password `employee_dev_password`. These credentials are for local development only. Override `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` for other environments. Do not use the development password in production.

## 2. Run the Spring Boot backend

```bash
cd backend
mvn spring-boot:run
```

The backend runs at `http://localhost:8080`.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Employee API: http://localhost:8080/api/employees

Flyway applies `V1__create_employees.sql` automatically. Hibernate validates the schema rather than generating tables.

## 3. Run the Angular frontend
Open a second terminal:

```bash
cd frontend
npm install
npm start
```

Open http://localhost:4200. The UI calls the backend at `http://localhost:8080/api/employees`.

## Sample create request

```json
{
  "firstName": "Asha",
  "lastName": "Sharma",
  "email": "asha.sharma@example.invalid",
  "phone": "9000000001",
  "department": "Engineering",
  "joiningDate": "2024-06-17"
}
```

Use synthetic information only in the demo. The `.invalid` email domain is reserved for examples.

## DPDP-agent demo opportunities
- Personal-data candidates: `first_name`, `last_name`, `email`, `phone`
- Java source: `Employee.java`, `EmployeeRequest.java`, `EmployeeResponse.java`, `EmployeeService.java`, `EmployeeController.java`
- Database schema: `backend/src/main/resources/db/migration/V1__create_employees.sql`
- Retention baseline: no retention policy is implemented yet; this is an intentional gap for the future agent to assess, not by itself proof of a legal violation
- Intentional demo finding: `EmployeeService.create()` logs employee email and phone. This seeded unsafe pattern is included so the future DPDP agent can detect it, propose a redaction/removal patch, and add regression tests. Do not use this demo logging pattern in production.

## Important limitations
This is a local development prototype, not a production-ready employee system. It does not include authentication/authorization, a production secrets strategy, rate limiting, or an approved retention policy. Add these based on the target environment before any real employee data is used.
