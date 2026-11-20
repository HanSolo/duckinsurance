# Backlog: agent-to-agent demo

Rule: first make the main flow work (Sir Quacksalot, start to last session). Only then work through this list, top to bottom.

## 0. Before anything else: first build and first run
- Build all three, fix compile errors. Known suspects: `mcp/DocumentForm` (text field names of the server library, two lines), `@Resource ManagedExecutorService` in the DuckClinic `ReferralService`, `int`/`double` tool parameters in `PartnerTools`, the Jakarta Agentic AI M1 imports in the DuckClinic.
- Check that `Event.fire` returns only after the agent workflow finished and rethrows its exception (the insurer's `AgentCheck` assumes it).
- Check Ollama `/api/chat` options in the therapy clinic's `Llm` (`think: false`, `format: json`) with `qwen3.8:27b`.
- Run the story once, read the conversation page, fix what the logs show.
- Still unverified from the first handover: Quarkus version pins, `ClinicTest`, the therapy clinic web UI.

## 1. Don Quixote: the "agent says no" path (small)
- Start a referral for Don Quixote (owner Alvarez). Expected: the Clinic Agent declines by rule `NO_RECENT_VISIT` (his only visit is 420 days old), nothing is sent, the referral shows DECLINED.
- Work: a second button on `app/agents.xhtml` (the endpoint already takes `owner` and `duck`), a line in DEMO.md. Check that the therapy clinic's seed has no Alvarez, so a forced referral would be REJECTED there ("owner has to register the duck").
- Optional: connect it to his pending 3400 EUR claim in the insurer's seed data (wing fracture and physiotherapy): the existing fraud check already finds no clinic record.

## 2. Cancel operation (medium)
- Therapy clinic: `ClinicService.cancel`, REST, MCP tool `cancel_appointment`, status CANCELLED already exists in the model.
- Agent: cancelling a referral cancels its remaining booked sessions, tells the clinic agent (`report_referral_status` CANCELLED) and tells the insurer.
- Insurer: new tool `release_preauthorization(referralId)` that gives back the reserved coverage (`PreauthStore.reservation`). Without it a cancelled plan keeps coverage locked.
- Decide: what happens to sessions already billed (claims stay, only the unused sessions are released).
- Page: a "Cancel referral" button.

## 3. Authentication between the apps (medium to large)
Today: the insurer accepts `clinic-agent:SECRET` and `therapy-agent:SECRET` (role AGENT); the clinic's and the therapy clinic's MCP and REST endpoints have no authentication; the clinic's agent log and referral endpoints are open.
- Step 1: the same shared bearer secret on the clinics' MCP endpoints and the `/agent` REST endpoints, so no one but the agents can call them.
- Step 2: per-agent identities and scopes (which agent may call which tool), so that for example only the therapy agent may call `get_referral_document`.
- Step 3: OAuth client credentials. The vendored client already supports it (`oauthClientCredentials`), a token endpoint is needed. Replaces the shared secret.
- Remember: the signed request state binds to the caller's subject (`call.caller().subject()`); with no authentication every caller is anonymous.

## 4. Smaller known limits
- Agent state (logs, referrals, plans, pre-authorizations) is in memory only; a restart of one app loses its part. Persist it if the demo is to survive restarts.
- Ducks are matched by owner last name plus duck name. Use a shared owner key (for example the owner's e-mail, which the clinic does not store) when that gets confusing.
- "Complete next session" may complete a session before its start time (demo shortcut).
- Tool answers that can take minutes (planning) use a status tool plus a callback. MCP Tasks would fit better, Quarkus MCP 2.0.x does not support them (planned for 2.1.0, unverified).
- No tests for the agents beyond the mock run done once in the chat workspace. Add unit tests for the hard limits (`PreauthAgent.parse`, the clamps in `TherapyAgent`, `ReferralAgent.parse`).
- Policies are a first proposal. Adjust the numbers in the three `policies/*.md` files and the matching classes (`PreauthPolicy`, `ClinicPolicy`, `TherapyAgent` constants) together.
