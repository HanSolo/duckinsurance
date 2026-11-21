# Backlog: agent-to-agent demo

State on 2026-11-21: the whole story runs on the three apps (refer, plan, document round-trip, pre-authorization, booking, billing with the fraud check, Don Quixote declined, cancel with release of the reserved coverage). Everything below is what is left, in the order I would do it.

## Done and verified in a run
- First build and run of all three apps, `ClinicTest`, Quarkus version pins.
- Referral Sir Quacksalot: clinic agent, therapy agent, insurer agent, MCP multi round-trip twice (insurer asks for the referral document, clinic asks for a confirmation), booking, billing, fraud check on the claims.
- Don Quixote: the clinic declines by rule `NO_RECENT_VISIT`, the other agents stay silent.
- Cancel after one completed session: booked sessions cancelled, completed one stays billed, coverage released (`release_preauthorization`), status CANCELLED, a new referral for the same duck is accepted and sees the released coverage.
- Hardening of the prompts: all three agents are told which rules the code already checked, after the clinic's model declined a referral for "missing consent" although the code had checked it. To verify over a few runs.

## 1. Authentication between the apps (medium to large)
Today: the insurer accepts `clinic-agent:SECRET` and `therapy-agent:SECRET` (role AGENT). The clinic's and the therapy clinic's MCP and REST endpoints have no authentication, the clinic's agent log and referral endpoints are open.
- Step 1: the same shared bearer secret on the clinics' MCP endpoints and their `/agent` and `/referrals` REST endpoints, so only the agents (and the insurer's demo page) can call them.
- Step 2: per-agent identities and scopes, so that for example only the therapy agent may call `get_referral_document` and only the clinic agent may call `cancel_referral`.
- Step 3: OAuth client credentials. The client already supports it (`oauthClientCredentials`), a token endpoint is needed. Replaces the shared secret.
- Remember: the signed request state binds to the caller's subject (`call.caller().subject()`). With no authentication every caller is anonymous, so step 1 also makes that binding meaningful.

## 2. Cancel, remaining checks and gaps
- Not yet tried: cancel after 0 and after 2 or more completed sessions, cancel of a referral whose sessions are all completed (expected: refused, status COMPLETED).
- Cancelling while the therapy agent is still planning is refused, there is no race handling.
- Only the vet's button cancels. An owner-initiated cancel (the owner calls the therapy clinic directly) would need the therapy clinic to tell the clinic and the insurer by itself.
- A cancelled booking could be offered to the next waiting duck, not built.

## 3. The 30-second connection break
Every call to `request_preauthorization` that includes the model's decision breaks after exactly 30 seconds (`EOF reached while reading`), while the insurer still finishes and stores its answer. The therapy agent works around it by polling `get_preauthorization` every 5 seconds for at most 4 minutes. The workaround holds, but the cause is unknown.
- Find what closes the connection (a servlet async timeout of 30 s, a Payara listener setting, or the MCP library). The insurer's log around the break and the Payara HTTP listener settings are the places to look.
- The polling only works for answers that are stored (APPROVED, PARTIAL, DENIED). A NEEDS_INFO answer is not stored and would wait the full four minutes.
- Longer term: MCP Tasks (below) would replace both the polling and the status callbacks.

## 4. Smaller known limits
- Agent state (logs, referrals, plans, pre-authorizations) is in memory only. A restart of one app loses its part. Persist it if the demo is to survive restarts.
- Ducks are matched by owner last name plus duck name. Use a shared owner key (for example the owner's e-mail, which the clinic does not store) when that gets confusing.
- "Complete next session" may complete a session before its start time (demo shortcut).
- Tool answers that can take minutes (planning) use a status tool plus a callback. MCP Tasks would fit better, Quarkus MCP 2.0.x does not support them (planned for 2.1.0, unverified).
- No automated tests for the agents. Add unit tests for the hard limits (`PreauthAgent.parse`, the clamps in `TherapyAgent`, `ReferralAgent.parse`) and for the cancel rule.
- The models can still decide differently from run to run, for example the insurer may deny a plan that the therapy agent considers fitting. The policies steer this, they cannot guarantee it. If a run goes wrong, read the agent log: lines marked `[rules]` were decided by the code, `[agentic-ai]` by the model.
- The model's reason sometimes repeats the price cap. The policy tells it not to, the code adds the cap note itself.
- Policies are a first proposal. Adjust the numbers in the three `policies/*.md` files and the matching classes (`PreauthPolicy`, `ClinicPolicy`, `TherapyAgent` constants) together.

## 5. Cosmetic
- DuckClinic startup shows 12 harmless H2 warnings (`CREATE SEQUENCE` ... `Syntax error in SQL statement`). Payara's deployer runs `schema.sql` once against its built-in H2 default datasource, the real DuckDB connection works. Pre-existing, `persistence.xml` is unchanged.
- The therapy clinic's `application.properties` still uses the deprecated `quarkus.hibernate-orm.database.generation`.
- Maven prints restricted-method warnings with newer JDKs. Setting `MAVEN_OPTS="--enable-native-access=ALL-UNNAMED"` silences them.
- The description document (claude.ai doc "Duck agents: how the three institutions talk to each other") has to be kept in step with the code when a tool or step changes.
