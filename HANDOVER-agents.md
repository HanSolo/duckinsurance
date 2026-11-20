# Handover: agent-to-agent demo (status 2026-11-21)

Read README-agents.md first (design, tools, run). This file says what was verified and what was not.

## Done
- Duck clinic: `ReferralAgent`, referral store, REST `/referrals` and `/agent/log`, MCP tools `get_referral_document` and `report_referral_status`, policy file, MCP client vendored in `third-party/`, pom, Dockerfile, compose, `llm.env.example`, `beans.xml` now `bean-discovery-mode="all"`.
- Therapy clinic: `TherapyAgent`, Ollama client, minimal MCP client, plan store, MCP tools `propose_treatment_plan`, `get_plan_status`, `complete_appointment`, REST `/api/agent/*`, `ClinicService.complete` and `isFree`, policy file, compose, Dockerfile.
- DuckInsurance: `PreauthAgent`, `PreauthStore`, `PartnerTools`, role AGENT (`AgentAccess`), `AgentResource`, page `app/agents.xhtml`, DEMO.md section 10, compose settings.

## Verified here
- [Certain] The therapy clinic's agent code compiles against stubs of the framework annotations and the real records of the project, and ran end to end against mock servers: model answer with a think block, `NEEDS_INFO` then document then `PARTIAL`, booking only the approved sessions, billing after each session, both result formats (structured content and JSON text).
- [Certain] The new Java files of the two Payara projects have no syntax errors (javac parse phase). Types were not checked.

## Multi round-trip (added after the first delivery)
- Insurer `request_preauthorization` asks for `referralDocument` with `input_required`; clinic `get_referral_document` asks "confirm the release?". The therapy agent's `McpCaller` answers both with an input handler. State is signed with `McpRequestStates` as in `DuckTools.confirm` and the clinic's `book_visit`.
- [Certain] The client side was tested against a mock that follows the wire format of the vendored client (capability `elicitation.form` declared, `inputResponses` and `requestState` repeated, nested question).
- [Guessing] **The text field of the server library.** `mcp/DocumentForm` calls `Elicitation.form(msg).string(KEY, p -> p.title(..).required())` and `answer.string(KEY)`. Only `.bool(..)` and `answer.bool(..)` appear in the existing code. If the library names them differently (`text`, `getString`, ...), the build fails in this one file; fix those two lines.

## Not verified (no Maven Central, no Ollama, no containers in the chat workspace)
- [Certain] None of the three projects was built with Maven, none was started.
- [Likely] Needs a look when you first build: the Jakarta Agentic AI M1 API in the DuckClinic (same imports as `ClaimFraudAgent`), `@Resource ManagedExecutorService` in `ReferralService`, `int` and `double` tool parameters in `PartnerTools` (the library's other tools use `long`, `double`, `boolean`), `Event.fire` returning only after the agent workflow finished and rethrowing its exception (the insurer's `AgentCheck` assumes the same).
- [Guessing] Ollama's native `/api/chat` with `"think": false` and `"format": "json"` for the model `qwen3.8:27b`. If it rejects `think`, remove that line in `Llm`.
- [Guessing] The Quarkus MCP extension serializes the records returned by the new tools as JSON text; `McpCaller` and `TherapyClient` accept both text and structured content.
- [Guessing] First answers from a 27b model take a while. The insurer's client timeout for pre-authorization is 240 s, the clinic's call to the therapy clinic returns at once.
- The therapy clinic's earlier unverified items (Quarkus version pins, `ClinicTest`, the web UI) are still unverified.

## Open
- Policies are a first proposal (README-agents.md table, the three `*-agent-policy.md` files). Adjust the numbers in the policy file and in the matching class together.
- Not done: Don Quixote storyline, a cancel operation, persistence of the agent state, authentication between the applications.
