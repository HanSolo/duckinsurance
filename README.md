# DuckInsurance

A small, PetClinic-style reference application for **Jakarta EE 11**, built around an insurance domain
(duck owners, ducks, vets, policies, claims), matching the Duck-Clinic scheme. It targets **Payara 7** by default; the code only uses standard APIs
(plus one `glassfish-web.xml` for context root and role mapping), so other EE 11 runtimes should work too.

## Run it

```bash
docker compose up --build        # builds the WAR and starts Payara 7
# or, without Docker (JDK 21+, Maven, Payara Micro 7 jar):
mvn package
java -jar payara-micro.jar --deploy target/duckinsurance.war
```

Open <http://localhost:8080> and sign in:

| User       | Password   | Role     | Can do                                   |
|------------|------------|----------|------------------------------------------|
| `admin`    | `admin`    | ADMIN    | everything                               |
| `adjuster` | `adjuster` | ADJUSTER | view, submit claims, approve / reject    |
| `customer` | `customer` | CUSTOMER | view, submit claims                      |

Data lives in the server's default H2 datasource and is re-created (with seed data) on every start.

## Feature map: where do I see X?

| Specification        | Where                                   | What it shows |
|----------------------|-----------------------------------------|---------------|
| Persistence 3.2      | `domain/`, `META-INF/persistence.xml`   | entities, `java.time`, schema generation |
| Data 1.0             | `repo/`                                 | `@Repository`, `@Find`, `@Query` (JDQL), `countBy…` |
| REST 4.0             | `rest/`                                 | JSON-B, record request bodies, `@RolesAllowed`, exception mapper |
| CDI 4.1              | `event/`, `audit/`, `config/DataSeeder` | `Startup` event, sync + async events, `AFTER_SUCCESS` observer, interceptor binding |
| Concurrency 3.1      | `config/ConcurrencyConfig`, `event/FraudCheck`, `config/PolicyMaintenance` | virtual-thread executor, `@Asynchronous(runAt = @Schedule)` |
| Transactions 2.0     | `service/`                              | `@Transactional` services |
| Security 4.0         | `security/`                             | `HttpAuthenticationMechanism` (session for Faces, Basic for REST), `IdentityStore` |
| Validation 3.1       | `domain/`, `rest/Requests`              | constraints on entities and records |
| Faces 4.1            | `src/main/webapp/`                      | Facelets template, view-scoped beans, `f:ajax`, `viewParam` |

Try this flow: sign in as `customer`, submit a claim, watch the **fraud risk** go from "checking…" to a score
(a virtual thread started after the transaction committed), then sign in as `adjuster` and approve it.
The dashboard's activity log shows the interceptor and the asynchronous notification.

A 15-minute walkthrough with a story is in [DEMO.md](DEMO.md).

## REST API

All endpoints under `/api`, HTTP Basic or session cookie:

```bash
curl -u admin:admin http://localhost:8080/api/customers
curl -u admin:admin http://localhost:8080/api/policies/quote -H 'Content-Type: application/json' \
     -d '{"duckId":1,"type":"VET_CARE","coverageAmount":5000}'
curl -u customer:customer http://localhost:8080/api/claims -H 'Content-Type: application/json' \
     -d '{"policyId":1,"vetId":1,"description":"Sir Quacksalot swallowed a bread bag clip","amount":600,"incidentDate":"2026-10-01"}'
curl -u adjuster:adjuster http://localhost:8080/api/claims/3/decision -H 'Content-Type: application/json' \
     -d '{"approve":true,"note":"ok"}'
```

Resources: `customers` (the owners), `customers/{id}/ducks`, `customers/{id}/policies`, `ducks`, `vets`, `policies`, `policies/quote`, `policies/{id}/cancel`,
`claims`, `claims/{id}/decision`, `stats`.

## Design choices (and known simplifications)

- One WAR, layered packages instead of microservices: easier to present, services are still separate CDI beans.
- Policies and claims reference their parent by plain id instead of JPA relationships, to keep JSON and Faces simple.
- Demo accounts and plain-text passwords are for demonstration only.
- Not yet verified against a running server: this project was written without being able to build or deploy it.
  Expect to fix small things on the first run.

## Next step: MCP

The REST API is the natural integration point. An MCP server can map each operation to a tool
(`search_owners`, `list_ducks`, `quote_policy`, `submit_claim`, `decide_claim`, …) and authenticate with HTTP Basic.
Adding MicroProfile OpenAPI annotations (`@Operation`, `@Parameter`) would give the tools good descriptions for free.
