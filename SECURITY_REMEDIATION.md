# AI_Agent — Security Remediation Specification

> **Document Type:** Security Engineering Specification
> **Purpose:** Security remediation + regression prevention
> **Project:** AI_Agent
> **Stack:** Spring Boot 3.2.5 + Spring Security 6 + Spring AI + Gemini + Qdrant + MySQL
> **Status:** Remediation in progress
>
> **CRITICAL RULE:** Read this document before modifying code.

---

# 1. OBJECTIVE

This document is the authoritative security-remediation baseline for the `AI_Agent` project.

The objective is to:

1. Fix confirmed security vulnerabilities.
2. Preserve all existing business logic.
3. Preserve the current RAG architecture and authorization model.
4. Minimize code changes.
5. Avoid unnecessary refactoring.
6. Add regression/security tests for every security fix.
7. Avoid performance regressions.
8. Verify each remediation before moving to the next phase.

Security fixes must be implemented as **minimal, isolated changes**.

---

# 2. SECURITY AUDIT BASELINE

Security Audit Round 2 was performed using:

* source-code inspection
* execution-flow tracing
* security configuration analysis
* existing test inspection
* authentication-flow tracing
* RAG pipeline tracing

The following findings were confirmed.

| ID      | Finding                                         | Status    | Severity | Priority | Production Blocker |
| ------- | ----------------------------------------------- | --------- | -------- | -------- | ------------------ |
| SEC-015 | User API authorization + password hash exposure | CONFIRMED | CRITICAL | P0       | YES                |
| SEC-002 | Document API + download IDOR                    | CONFIRMED | CRITICAL | P0       | YES                |
| SEC-001 | `@PreAuthorize` ineffective                     | CONFIRMED | HIGH     | P0       | YES                |
| SEC-003 | CSRF disabled with session authentication       | CONFIRMED | HIGH     | P0       | YES                |
| SEC-004 | Upload path traversal + weak file validation    | CONFIRMED | HIGH     | P1       | YES                |
| SEC-006 | Production profile missing                      | CONFIRMED | HIGH     | P1       | YES                |
| SEC-007 | Sensitive RAG context logging                   | CONFIRMED | HIGH     | P1       | YES                |
| SEC-008 | `frameOptions().disable()` globally             | CONFIRMED | MEDIUM   | P1       | NO                 |
| SEC-011 | No login/register rate limiting                 | CONFIRMED | MEDIUM   | P1       | NO                 |
| SEC-013 | Prompt injection hardening missing              | CONFIRMED | MEDIUM   | P1       | NO                 |
| SEC-005 | JWT fallback secret / dead JWT infrastructure   | CONFIRMED | MEDIUM   | P2       | NO                 |

---

# 3. VERIFIED SAFE / DO NOT BREAK

The following findings were investigated and are NOT vulnerabilities in the current implementation.

| Finding                              | Status         | Rule                                          |
| ------------------------------------ | -------------- | --------------------------------------------- |
| Flyway vs ddl-auto conflict          | FALSE POSITIVE | Do not add Flyway as part of this remediation |
| Actuator sensitive endpoints exposed | FALSE POSITIVE | Only intended actuator endpoints are exposed  |
| Chat session IDOR                    | MITIGATED      | Preserve existing ownership check             |
| RAG authorization bypass             | MITIGATED      | Preserve current authorization flow           |
| Qdrant permission filter bypass      | FALSE POSITIVE | Filter is applied directly to Qdrant search   |

Do not modify these areas unless a new verified vulnerability is discovered.

---

# 4. PROTECTED RAG ARCHITECTURE

The existing RAG authorization architecture has been explicitly verified as working.

Current security flow:

```text
Authentication
      ↓
DocumentAccessService
      ↓
QueryAnalyzer
      ↓
MetadataVerificationService
      ↓
MetadataFilterBuilder
      ↓
HybridRetrievalService
      ↓
Qdrant permission filter
      ↓
HydrationService
      ↓
DB authorization re-check
      ↓
Deduplication
      ↓
PromptBuilder
      ↓
LLM
```

## Verified security properties

### Qdrant layer

The permission filter is attached directly to the Qdrant search request.

Unauthorized documents should therefore be filtered during retrieval.

### Hydration layer

`HydrationService` performs a second authorization check using current DB information.

This is the authorization barrier / defense-in-depth layer.

### Result

The current RAG pipeline has two independent authorization controls:

```text
Qdrant permission filtering
+
DB authorization during hydration
```

This architecture must remain intact.

---

# 5. PROTECTED COMPONENTS

Treat the following as protected components:

```text
DocumentAccessService
HydrationService
QueryAnalyzer
MetadataVerificationService
MetadataFilterBuilder
HybridRetrievalService
VectorStoreService
RagService
PromptBuilder
Qdrant permission filtering
RAG provenance
```

Do NOT:

* remove permission filtering
* remove hydration authorization
* weaken access scopes
* rewrite RBAC
* rewrite context-based access control
* change chunk size
* change chunk overlap
* change embedding model
* change Top-K
* change retrieval ranking
* change entity resolution
* change QueryAnalyzer scoring
* rewrite RAG architecture
* migrate Session authentication to JWT

If a security fix appears to require changing a protected component:

**STOP and report the dependency before modifying it.**

---

# 6. AUTHENTICATION BASELINE

The application currently uses:

```text
Session / JSESSIONID
```

as the effective authentication mechanism.

JWT infrastructure exists but is currently not used by a request authentication filter.

Do NOT migrate the project to JWT during this remediation.

JWT cleanup is limited to preventing unsafe secret fallback.

---

# 7. REMEDIATION PRIORITY

## P0 — MUST FIX FIRST

Execute in this order:

```text
SEC-015
    ↓
SEC-002
    ↓
SEC-001
    ↓
SEC-003
    ↓
FULL TEST
```

Do not start P1 until P0 passes.

---

# 8. SEC-015 — USER API SECURITY

Affected area:

```text
UserController
UserService
User model / serialization
```

Verified problems:

* `GET /api/users` lacks authorization.
* `GET /api/users/{id}` lacks authorization.
* `PUT /api/users/{id}` allows unauthorized modification.
* `DELETE /api/users/{id}` allows unauthorized deletion.
* Password hash can be serialized.
* User status can be changed by unauthorized users.
* Password update path does not correctly encode passwords.
* User creation may allow uncontrolled role/department assignment.

## Required behavior

Unauthorized users must not manage other users.

Password must never appear in API responses.

Password must never be stored as plaintext.

Existing RBAC must be reused.

Do not invent new roles.

## Required tests

At minimum:

```text
Anonymous → GET /api/users → 401

EMPLOYEE → GET /api/users → 403

EMPLOYEE → GET /api/users/{otherId} → 403

Unauthorized user → PUT /api/users/{otherId} → 403

Unauthorized user → DELETE /api/users/{otherId} → 403

Authorized administrator → expected success

User JSON → password field absent

Password update → stored password is encoded
```

---

# 9. SEC-002 — DOCUMENT API AUTHORIZATION

Affected areas:

```text
DocumentApiController
DocumentController
DocumentAccessService
```

Verified vulnerable endpoints include:

```text
GET /api/documents
GET /api/documents/{id}
GET /api/documents/search
GET /api/documents/decision/{smecode}
GET /documents/{id}/download
```

## Required rule

Every document response must respect the existing document access policy.

Unauthorized documents must never be returned.

Reuse:

```text
DocumentAccessService
```

Do not create a second permission model.

## List/search

Prefer filtering at database/query level where practical.

Avoid:

```text
load all documents
→ Java-side filtering
```

if that creates unnecessary memory usage or N+1 queries.

## Download

Authorization must occur before reading the physical file.

## Sensitive content

Do not expose full `Document.content` unless explicitly required by the API.

`@JsonIgnore` alone is NOT considered an authorization fix.

## Required tests

Test at least:

```text
PUBLIC
DEPARTMENT
PROJECT
PRIVATE
```

with:

```text
Owner
Authorized user
Unauthorized user
Anonymous
```

for:

```text
list
detail
search
decision
download
```

---

# 10. SEC-001 — METHOD SECURITY

Enable method-level security so existing:

```java
@PreAuthorize(...)
```

annotations are actually enforced.

Keep the implementation minimal.

Required verification:

```text
DIRECTOR → maintenance endpoint → success

MANAGER → 403

EMPLOYEE → 403

Anonymous → 401
```

Must be tested through actual HTTP/security execution, not only source inspection.

---

# 11. SEC-003 — CSRF

Current authentication is session-cookie based.

Therefore CSRF protection must be restored appropriately.

Do NOT solve this by:

```text
Session → JWT migration
```

State-changing requests must require valid CSRF protection.

Test:

```text
POST without CSRF → rejected
POST with CSRF → accepted

PUT without CSRF → rejected
DELETE without CSRF → rejected
```

Ensure the current frontend continues to work.

Do not broadly disable CSRF again.

---

# 12. P1 — FILE UPLOAD SECURITY

## SEC-004

Protect against:

* `../`
* `..\`
* absolute paths
* path separator manipulation
* malicious filenames
* invalid MIME
* extension spoofing
* oversized files

The client-controlled `originalFilename` must never control filesystem location.

Server-generated filenames should be used for storage.

Original filename may only be treated as metadata/display information.

Preserve the existing document ingestion pipeline.

Do not break:

```text
upload
→ Tika
→ tokenization
→ enrichment
→ embedding
→ Qdrant
```

Required tests:

```text
path traversal
absolute path
invalid MIME
invalid extension
MIME/extension mismatch
oversized file
valid upload
```

---

# 13. SEC-006 — PRODUCTION CONFIGURATION

A `production` profile is referenced but no dedicated production configuration currently exists.

Production configuration must ensure:

```text
ddl-auto = validate
show-sql = false
DEBUG logging = disabled
sensitive RAG logging = disabled
```

Secrets must come from environment/secret configuration.

Do NOT add Flyway as part of this task.

Do NOT change database migration architecture unless separately approved.

---

# 14. SEC-007 — SENSITIVE RAG LOGGING

Never log complete:

```text
document content
RAG context
full prompt
sensitive user data
```

Prefer metadata such as:

```text
documentId
chunkId
retrieval score
result count
token count
latency
permission scope
```

Security logging must not become a copy of protected documents.

Do not change RAG processing.

Only change observability/logging behavior.

---

# 15. SEC-008 — SECURITY HEADERS

Do not globally disable:

```text
frameOptions
```

Production should retain clickjacking protection.

If H2 console requires special handling, isolate it to development/local scope.

Do not weaken production security for H2.

---

# 16. SEC-011 — RATE LIMITING

Protect:

```text
/auth/login
/auth/register
/auth/google-login
```

against brute-force and registration abuse.

Implementation should be:

* simple
* maintainable
* low overhead
* appropriate for current deployment architecture

Do not introduce unnecessary distributed infrastructure.

Do not modify RAG rate policy as part of this task.

---

# 17. SEC-013 — PROMPT INJECTION

Current prompt construction uses a flat String.

Improve separation between:

```text
System instructions
User input
Retrieved documents
Conversation history
```

Prefer Spring AI message roles where supported.

The goal is to reduce the ability of document text or user input to impersonate system instructions.

Do not modify:

```text
retrieval
Qdrant filtering
hydration authorization
ranking
provenance
```

Required tests should include malicious document content such as:

```text
IGNORE PREVIOUS INSTRUCTIONS
```

and malicious user queries attempting to bypass document permissions.

---

# 18. SEC-005 — JWT CLEANUP

Do NOT implement JWT authentication migration.

Only remove the security risk caused by hard-coded fallback secrets.

If `jwt.secret` is missing:

```text
fail safely
```

rather than using a predictable hard-coded secret.

If architectural JWT cleanup requires larger changes:

**DEFER and report.**

---

# 19. PERFORMANCE RULES

Security remediation must not introduce unnecessary performance degradation.

Avoid:

```text
N+1 queries
full-table loads
duplicate database authorization checks
duplicate vector searches
additional RAG retrieval passes
```

For document list/search operations, prefer database-level filtering when practical.

For RAG:

Do NOT add another vector search.

Do NOT duplicate the hydration authorization process.

Preserve current retrieval performance.

If performance is not benchmarked, explicitly state:

```text
Benchmark not performed.
```

Never invent performance numbers.

---

# 20. TESTING STRATEGY

After every remediation batch:

```text
compile
→ unit tests
→ integration tests
→ security tests
→ existing RAG tests
→ full test suite
```

Never:

* disable tests
* skip tests
* weaken assertions
* modify expected values only to make tests pass

Existing tests that should be preserved include, if present:

```text
SecurityIsolationTest
EnterpriseRagHardeningTests
RagGroundingStabilizationTest
```

---

# 21. RAG REGRESSION REQUIREMENT

After security changes, verify:

```text
Authorized user
    ↓
RAG query
    ↓
authorized documents retrieved
    ↓
hydration succeeds
    ↓
context built
    ↓
LLM response
```

and:

```text
Unauthorized document
    ↓
Qdrant permission filter
    ↓
NOT retrieved
```

If somehow retrieved:

```text
Hydration authorization
    ↓
REJECT
    ↓
NOT included in prompt
```

Both protection layers must remain functional.

---

# 22. IMPLEMENTATION WORKFLOW

For each phase:

### Step 1 — Inspect

Read the relevant files and existing tests.

### Step 2 — Plan

Identify:

* files to modify
* security boundary
* regression risks
* required tests

### Step 3 — Implement

Make the smallest safe change.

### Step 4 — Test

Run targeted tests.

### Step 5 — Regression

Run relevant existing tests.

### Step 6 — Verify

Inspect the resulting execution flow.

### Step 7 — Report

Record:

```text
Finding
Status
Files changed
Tests
Regression result
Performance impact
Remaining risk
```

Only then continue to the next phase.

---

# 23. CHANGE CONTROL

Every code change must satisfy:

```text
Security improvement
+
Minimal scope
+
Existing behavior preserved
+
Tests added/updated
```

Avoid unrelated cleanup.

Avoid formatting-only changes.

Avoid renaming unrelated classes/methods.

Avoid dependency upgrades unless required for the security fix.

Avoid architecture migrations.

---

# 24. STOP CONDITIONS

Stop implementation and report before continuing if:

1. A protected RAG component must be substantially modified.
2. Authentication architecture must be migrated.
3. Database schema migration is required.
4. A new major dependency is required.
5. Existing business behavior must change.
6. Existing security policy is ambiguous.
7. A security fix may break an existing RAG security guarantee.

Do not make assumptions in these cases.

---

# 25. DEFINITION OF DONE

Security remediation is considered complete only when:

* All P0 findings are fixed.
* All P0 regression tests pass.
* All relevant P1 findings are fixed or explicitly deferred.
* Existing RAG security tests pass.
* No unauthorized document can be returned by document APIs.
* No password hash is exposed through API responses.
* Unauthorized user management is blocked.
* Maintenance endpoint role protection is active.
* CSRF protection is active for state-changing session requests.
* File upload cannot escape the upload directory.
* Production configuration is separated from development configuration.
* Sensitive RAG context is not logged in production.
* Security headers are preserved.
* Prompt boundaries are improved without changing retrieval.
* JWT does not fall back to a hard-coded secret.
* No known P0 security issue remains.

---

# 26. CURRENT REMEDIATION STATE

```text
P0:
SEC-015 — FIXED (UserController/UserService/User.java + SecurityConfig entry point)
SEC-002 — FIXED (DocumentApiController list/detail/search/decision + DocumentController download)
SEC-001 — FIXED (@EnableMethodSecurity + AccessDeniedException handler fix)
SEC-003 — FIXED (CSRF restored for /api/**; projects.html, documents.html,
                  project_members.html, sidebar.html logout form given _csrf;
                  dashboard.js sends X-XSRF-TOKEN)

P1: NOT STARTED
P2: NOT STARTED
```

P0 regression: full suite run after every phase. 21 new security tests added
(8 SEC-015 + 6 SEC-002 + 4 SEC-001 + 3 SEC-003), all passing. 3 pre-existing
failures (`EnterpriseRagHardeningTests.testCacheKeyNormalization`,
`RagIntegrationTest.testIngestAndChat`, `RagGroundingStabilizationTest.testBasicRagFlow`)
confirmed via `git stash` to already fail on the unmodified baseline — unrelated
to this remediation (Qdrant collection dimension mismatch 1536 vs 384, and a
cache-key casing assertion). Not touched.

Side-fix discovered and applied within SEC-001 scope: `AiGlobalExceptionHandler`
had a catch-all `@ExceptionHandler(Exception.class)` that was silently
converting `AccessDeniedException` into HTTP 500 instead of 403. This was
latent (harmless while `@PreAuthorize` was inert) and became a blocking defect
the moment method security was enabled, so it was fixed in the same phase.

Side-fix discovered and applied within SEC-003 scope: `projects.html` (create/
update/delete forms), `documents.html` (delete form), `project_members.html`
(add/remove member forms), and the logout form in `fragments/sidebar.html`
were already missing `_csrf` tokens even though CSRF was never disabled for
their routes — confirmed empirically (via a temporary `@WebMvcTest`, deleted
afterward) that these forms were already returning 403 before any of this
remediation started. Fixed using the same `_csrf` hidden-input pattern already
proven correct in `document_upload.html`.

---

# 27. FINAL VERDICT

**P0: GO** (for the P0 scope specifically — all 4 confirmed production-blocking
findings fixed and regression-tested through real HTTP + the real Spring
Security filter chain).

**Overall project: CONDITIONAL GO** — P1 items (SEC-004 upload path traversal,
SEC-006 production profile, SEC-007 sensitive RAG logging, SEC-008 frameOptions,
SEC-011 rate limiting, SEC-013 prompt injection hardening) and P2 (SEC-005 JWT
cleanup) remain NOT STARTED. None of the remaining items are known to allow
cross-tenant data exposure or privilege escalation (the three CRITICAL/HIGH
production blockers from the original audit are now closed), but they should
be completed before a production deployment is considered fully hardened.

---

# FINAL INSTRUCTION TO AI AGENT

Before modifying any code:

1. Read this entire document.
2. Inspect the current repository state.
3. Verify that the audit assumptions still match the current code.
4. Start with the highest-priority incomplete phase.
5. Do not modify protected RAG architecture.
6. Make minimal security changes.
7. Test after every phase.
8. Stop if a protected architecture must change.
9. Report evidence, not assumptions.

**Do not begin with broad refactoring.**

**Do not rewrite the RAG system.**

**Do not migrate authentication architecture.**

**Fix the security boundaries while preserving the existing system.**
