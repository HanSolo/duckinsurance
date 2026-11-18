# DuckInsurance demo script (about 15 minutes)

**Story:** It is a stormy Tuesday at DuckInsurance, a small insurer for ducks. You follow three people
(an admin, a duck owner and a claims adjuster) and show Jakarta EE 11 at work underneath each step.

## Before you start (5 minutes, do this before the audience arrives)

1. **Start the app:** `docker compose up --build` in the project folder. The first build takes a few minutes. It is ready when the log shows the application as deployed. Open <http://localhost:8080>.
2. **Sign in:** `admin/admin`, `customer/customer`, `adjuster/adjuster`. Use two browser profiles (or a private window) so you can switch between roles quickly.
3. **Only for step 8 (Assistant):** Ollama must run on your machine with the model pulled (`ollama pull qwen3.8:27b`, version 0.14 or newer). On Linux start it with `OLLAMA_HOST=0.0.0.0 ollama serve`, so the container can reach it; Docker Desktop needs nothing.
4. **Check:** open **Assistant**. Both dots at the top should be green (model reachable, MCP endpoint reachable). Ask one question beforehand: the first answer loads the model and takes a while.
5. **Reset:** data is re-created on every restart (`docker compose restart`), so you can always go back to the start.

The model follows the Duck-Clinic: **owners** have **ducks**, **vets** treat them. A **policy** insures one duck
(types: Vet care, Duck life, Pond liability, Coop & pond), a **claim** belongs to a policy and may name the treating vet.

---

## 1. Morning: the admin looks at the business (2 min)

Sign in as **admin**. The dashboard shows owners, ducks, active policies, premium volume and open claims.

- Point at the **specification table** at the bottom: "This page is also the map of the talk."
- Point at the **spec chips** in the page header: every page names what it shows.
- Sidebar footer: server and Java version. "Payara 7, Jakarta EE 11, Java 21."
- Click through **Owners**, **Ducks**, **Vets** quickly: "Same scheme as the Duck-Clinic."

*Under the hood:* seed data comes from `config/DataSeeder`, which reacts to the CDI 4.1 `Startup` event.

## 2. A new owner, a new duck, and the rules that protect us (3 min)

**Owners**, then **New owner** (admin only).

1. Submit the empty form. Bean Validation messages appear. "Same annotations protect the REST API."
2. Create **Donald Duck**, `donald@duckpond.example`, born 9 June 1934, Duckburg.
3. Create **Huey Duck**, `huey@duckpond.example`, born 2012, Duckburg.

**Ducks**, then **Register a duck**:

4. Owner Donald, name **Daisy**, breed Pekin, born about 6 years ago.
5. Owner Huey, name **Dewey**, breed Mallard, born last year.

**Policies**, then **Quote and issue a policy**:

6. Daisy, *Vet care*, coverage 5000, **Calculate premium**: 5,000 x 3% x 0.95 (Pekin) x 1.30 (age 5 to 7) = **€185.25 / year**. Issue it.
7. Dewey, any type, **Issue policy**. Refused: "Owners under 18 cannot hold a policy. Please ask a grown-up duck owner." "A business rule in a CDI service. The REST API answers the same with HTTP 409."

*Under the hood:* `service/PremiumCalculator` is a plain CDI bean (coverage x type rate x breed risk x age factor). `PolicyService` is `@Transactional`.
Entities go through Jakarta Persistence 3.2 and **Jakarta Data** repositories (`repo/`): no DAO code.

## 3. The duck has a bad day (3 min)

Sign out, sign in as **customer**. Note what is missing: no *New owner* form, no *Cancel* buttons.
(Simplification to mention up front: a customer can file against any policy, there is no ownership check.)

Open **Claims** and file a claim:

- Policy: the *Vet care* policy of **Bill** (owner Priya Nair)
- Vet: Dr. Pip Pekin
- Amount: 450
- Incident date: about five days ago
- Description: *Cut a foot on a broken bottle at the pond edge*

The claim shows **checking...** in the Fraud risk column. Click **Refresh** after a second. A score appears (low, green).

"What happened in that second?" The claim was saved and committed. **Only then** a CDI observer
(`@Observes(during = AFTER_SUCCESS)`) handed the work to a **virtual-thread executor** (Jakarta Concurrency 3.1),
which computed the score and moved the claim to UNDER_REVIEW. The HTTP request never waited.

Now look at the list: **Ducky McDuckface**'s claim, *Noise complaint*, **€40,000** against a €50,000 liability policy.
Risk score 100, red. "Short description, huge amount. The adjuster will want to look at that one."

*Under the hood:* `event/FraudCheck`, `config/ConcurrencyConfig`, `service/ClaimService.runFraudCheck`.

## 4. The adjuster decides (2 min)

Sign out, sign in as **adjuster**. Claims now shows **Approve / Reject** buttons and a decision note field.

1. Filter by status *UNDER_REVIEW*.
2. Approve **Don Quixote**'s wing fracture claim (vet Dr. Mabel Mallard) with the note *Covered, invoice verified*.
3. Reject Ducky's *Noise complaint* with the note *Not an insured event, and the amount is disproportionate*.

"Two roles, one screen, different powers." The buttons are hidden for customers, **and** the service refuses the call even if someone bypasses the UI (next step).
Also point at the two historic decisions already in the list: Sir Quacksalot's fishing hook (approved) and Admiral Beak's burst pipe (rejected).

## 5. The same business logic, as an API (2 min)

```bash
# A customer tries to decide a claim: forbidden
curl -i -u customer:customer -X POST http://localhost:8080/api/claims/3/decision \
     -H 'Content-Type: application/json' -d '{"approve":true,"note":"sneaky"}'

# A quote for Ducky McDuckface (duck id 6), no side effects
curl -u customer:customer http://localhost:8080/api/policies/quote \
     -H 'Content-Type: application/json' \
     -d '{"duckId":6,"type":"LIABILITY","coverageAmount":50000}'

# Ducks of one owner, and the vets
curl -u customer:customer "http://localhost:8080/api/ducks?ownerId=1"
curl -u customer:customer http://localhost:8080/api/vets

# The numbers behind the dashboard
curl -u admin:admin http://localhost:8080/api/stats
```

- First call returns **403** (`@RolesAllowed` on the REST resource). Read a real claim number off the Claims page if needed.
- The quote shows the **young Mandarin** surcharge: 50,000 x 0.4% x 1.25 (Mandarin) x 1.40 (under 1 year) = **€350.00**.
- One security mechanism serves both audiences: session cookie for the browser, HTTP Basic for programs (`security/AppAuthMechanism`, Jakarta Security 4.0).

## 6. The activity log tells the story (1 min)

Sign in as admin, open the dashboard, click **Refresh** in *Live activity*. You see who did what:
`PolicyService.issue ok`, `ClaimService.decide ok`, the failed `issue` for Dewey, and lines from `notifier`.

- `admin`, `customer`, `adjuster` entries come from a **CDI interceptor** (`@Audited`).
- `notifier` entries come from an **asynchronous event observer** (`@ObservesAsync`).
- Nobody wrote logging code in the services.

## 7. Closing and teaser (1 min)

"Everything you saw is standard Jakarta EE 11 on Payara 7: Persistence, Data, REST, CDI, Concurrency, Security,
Validation, Transactions and Faces. No framework beyond the platform."

Teaser: "Everything we just did is a REST call with a clear contract. Next we put an **MCP server** in front of it,
so an LLM can list ducks, quote a policy or file a claim, with the same roles and rules."

---

## 8. Optional: the same app, driven by a local LLM (3 min)

Needs Ollama running on the host with the model pulled (see the README). Open **Assistant** in the sidebar (or run `node scripts/duckchat.mjs`) and ask:

1. *Which ducks does Anna Keller have, and what do their policies cover?* The model searches owners, lists ducks and policies.
2. *What would a Pond liability policy for 50000 euro cost for Ducky McDuckface?* It calls `quote_policy` and answers with the €350.00 you showed in step 5.
3. *Approve claim 3.* Signed in as `customer` the tool answers "Only adjusters can decide claims". Sign in as `adjuster` and it works: the assistant uses your role. Open the "tool calls" detail under an answer to show the MCP calls.

4. *Issue a LIABILITY policy with 20000 euro coverage for Ducky McDuckface.* The answer is not final: a yellow **Please confirm** card appears (MCP elicitation). Click *No* and nothing is written; ask again and click *Yes*. The signed state makes sure the confirmed arguments are the executed ones.

Watch the text appear word by word: the page streams the answer while Ollama writes it.

"No cloud, no API key: a model on this laptop, the same services, the same roles."

---

## Cheat sheet

The REST API is described at `/openapi` (sidebar: OpenAPI ↗).


| Want to show | Where |
|--------------|-------|
| Startup seeding | `config/DataSeeder` |
| Jakarta Data repositories | `repo/Customers`, `Ducks`, `Vets`, `Policies`, `Claims` |
| Validation | `domain/Customer`, `domain/Duck`, `rest/Requests` |
| Async after commit, virtual threads | `event/FraudCheck`, `config/ConcurrencyConfig` |
| Scheduled job (02:00 nightly) | `config/PolicyMaintenance` |
| Interceptor | `audit/AuditInterceptor` |
| Auth | `security/AppAuthMechanism`, `security/DemoIdentityStore` |
| MCP tools | `mcp/DuckTools`, `rest/RestApplication` (registers `/api/mcp`) |
| MCP client + chat | `chat/*`, `app/chat.xhtml` |

**If something goes wrong on stage:** restart the container (`docker compose restart`) to reset all data.
Claim numbers in the REST examples depend on the seed order, so read them off the Claims page first.
