# DuckInsurance demo script (about 15 minutes)

**Story:** It is a stormy Tuesday at DuckInsurance, a small insurer for ducks. You follow three people
(an admin, a duck owner and a claims adjuster) and show Jakarta EE 11 at work underneath each step.

## Before you start (5 minutes, do this before the audience arrives)

1. **Start the app:** `docker compose up --build` in the project folder. The first build takes a few minutes. It is ready when the log shows the application as deployed. Open <http://localhost:8081>.
2. **Sign in:** `admin/admin`, `customer/customer`, `adjuster/adjuster`. Use two browser profiles (or a private window) so you can switch between roles quickly.
3. **Only for step 8 (Assistant):** Ollama must run on your machine with the model pulled (`ollama pull qwen3.8:27b`, version 0.14 or newer). On Linux start it with `OLLAMA_HOST=0.0.0.0 ollama serve`, so the container can reach it; Docker Desktop needs nothing.
4. **Check:** open **Assistant**. Both dots at the top should be green (model reachable, MCP endpoint reachable). Ask one question beforehand: the first answer loads the model and takes a while.
5. **Reset:** data is re-created on every restart (`docker compose restart`), so you can always go back to the start.

The model follows the Duck-Clinic: **owners** have **ducks**, **vets** treat them. A **policy** insures one duck
(types: Vet care, Physiotherapy, Duck life, Pond liability, Coop & pond), a **claim** belongs to a policy and may name the treating vet.

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
curl -i -u customer:customer -X POST http://localhost:8081/api/claims/3/decision \
     -H 'Content-Type: application/json' -d '{"approve":true,"note":"sneaky"}'

# A quote for Ducky McDuckface (duck id 6), no side effects
curl -u customer:customer http://localhost:8081/api/policies/quote \
     -H 'Content-Type: application/json' \
     -d '{"duckId":6,"type":"LIABILITY","coverageAmount":50000}'

# Ducks of one owner, and the vets
curl -u customer:customer "http://localhost:8081/api/ducks?ownerId=1"
curl -u customer:customer http://localhost:8081/api/vets

# The numbers behind the dashboard
curl -u admin:admin http://localhost:8081/api/stats
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

## 9. Optional: an agent checks the claims against the DuckClinic (3 min)

Needs the DuckClinic running with the demo data (`duck-clinic-patch/`). Open **Claims**: the **Fraud agent** indicator is green when the clinic's MCP server is reachable.

1. Submit a vet-care claim for **Don Quixote** (a wing fracture surgery 30 days ago). It gets a quick rule-based score first. Press **Check** on its row, wait a moment and press **Refresh**: the score rises and the reason says that the clinic has no matching visit.
2. Press **Check** on the claim of **Sir Quacksalot** or **Bill**: low score, the reason names the clinic visit that matches.
3. Stop the clinic and press **Check** again: the indicator turns red and the claim keeps its rule-based score, with a note that the agent failed.

**Physical therapy clinic (optional):** needs the Duck Physical Therapy Clinic on port 8082 with `duck-physio-patch/`. The second indicator turns green.

4. Press **Check** on the physiotherapy claim of **Sir Quacksalot** (treadmill sessions): the clinic has an appointment 28 days ago, low score.
5. Press **Check** on the claim of **Duchess** (hydrotherapy): no clinic has a record, so the score is high and a dashed **proposal** appears: book a therapy session in the physical therapy clinic. Log in as an adjuster and press **Book it**; the appointment number is added to the reason, and the booking is visible in the clinic.

"An agent in plain Jakarta EE: a CDI event, four annotated methods, and it talks to another application through MCP."

## 10. Optional: three agents, one referral (4 minutes)

Needs all three applications and the same Ollama model: DuckInsurance (8081), the DuckClinic (8080) and the Duck Physical Therapy Clinic (8082). Each has its own agent, its own policy file and its own hard limits. Open **Agents** in the sidebar (admin or adjuster). Three dots show which applications answer.

1. Press **Start referral for Sir Quacksalot**. The **Clinic Agent** looks at his visits (the fishing hook surgery 40 days ago), asks the model whether physiotherapy makes sense, and hands a referral to the **Therapy Agent**. It shares only the diagnosis and the number of sessions.
2. The Therapy Agent picks a treatment and finds free slots (code), then asks the **Insurance Agent**: "will you pay?". The insurer's rules say a referral document is required, so it asks back **inside the same call** (MCP multi round-trip request, `input_required`). The Therapy Agent does not have the document: it asks the Clinic Agent, which asks back in turn: *do you confirm the release?* The Therapy Agent confirms, the clinic releases the document because the owner consented, and the insurer decides. Point at the three `REQUEST`/`ANSWER` pairs: two questions that were answered in the middle of a call, with no session and no second tool call.
3. The Insurance Agent computes the limits in code (remaining coverage of the PHYSIO policy, at most 6 sessions, at most 75 EUR per session) and lets the model decide inside them. Hydrotherapy costs 85 EUR, so expect **PARTIAL**: all sessions, but 75 EUR each, and the owner pays the difference. A cheaper treatment is approved in full.
4. Only the approved sessions are booked. The Clinic Agent notes the status **SCHEDULED**.
5. Press **Complete next session** a few times. After each session the Therapy Agent bills the insurer, which files a claim (visible under Claims) and starts the fraud agent of step 9. The fraud agent asks the clinics whether the visit happened.
6. **Reset conversation** clears the logs and pre-authorizations, so you can run it again. Visits, appointments and claims stay (see "Known limits" in README).

Grey lines are what an agent considered (thoughts and rules). Coloured edges show which application wrote the line.

**The agent that says no.** Press **Start referral for Don Quixote** (owner Alvarez). His only visit at the DuckClinic is more than a year old, so the Clinic Agent declines by its own rule (`NO_RECENT_VISIT`) without asking the model: the line is marked *[decided by the rules]*, nothing is sent, and the other two agents stay silent. The referral list shows DECLINED. It fits the fraud story of section 9: the same duck has a pending wing fracture claim for which the clinic has no record.

**Cancelling.** The buttons *Complete next session* and *Cancel referral* stay disabled until a referral is really scheduled (the DuckClinic shows SCHEDULED or IN_TREATMENT and the insurer holds an approved pre-authorization with sessions left). Complete one or two sessions, then press **Cancel referral**. The Clinic Agent asks the Therapy Agent to cancel, which cancels the sessions that are still booked, completed ones stay billed. The Therapy Agent then asks the Insurance Agent to release the coverage reserved for the cancelled sessions (`release_preauthorization`). Look at the pre-authorization list on the right: it now says CANCELLED with the number of sessions that were claimed. The referral shows CANCELLED at the DuckClinic, and a new referral for the same duck is possible again.

*Under the hood:* `agent/PreauthAgent` (Jakarta Agentic AI, same pattern as `ClaimFraudAgent`), `mcp/PartnerTools` (the tools the clinics' agents call), `agent/PreauthPolicy` and `resources/policies/insurer-agent-policy.md` (limits in code and in words), `security/AgentAccess` (role AGENT for the clinics' agents), `rest/AgentResource` and `app/agents.xhtml` (the page).

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
