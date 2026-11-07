---
description: Build and test one service, then run architect + security-reviewer + code-reviewer on its diff and summarize findings
argument-hint: <service-name>
---

Verify the service `$ARGUMENTS`.

1. **Build and test**: run `./mvnw -pl services/$ARGUMENTS -am verify`. If it fails, stop and report the failing tests or errors with the relevant output. Do not run the reviewers on a red build.
2. **Collect the diff**: `git diff --stat main...HEAD -- services/$ARGUMENTS shared docs` and `git diff --name-only main...HEAD -- services/$ARGUMENTS shared docs`. Also note uncommitted changes from `git status --short`.
3. **Review in parallel**: launch these subagents in a single message, each given the service name, the changed-file list, and the relevant docs (docs/ParkSmart-Services-and-Entities.md section, docs/openapi/$ARGUMENTS.yaml):
   - `architect` — boundaries, ownership checks, saga/idempotency, OpenAPI vs implementation
   - `security-reviewer` — authn/authz, validation, secrets, PII, uploads, injection
   - `code-reviewer` — correctness, transactions, N+1, indexes, error handling
4. **Check hard rules yourself** (quick greps): no imports of other services' packages; no `ddl-auto` other than `validate`; no hardcoded hosts/passwords in `src/main/resources`; every `@PostMapping|@PutMapping|@PatchMapping|@DeleteMapping` controller has a matching 403 test; commits on this branch have no attribution trailers (`git log main..HEAD --format=%B | grep -iE "co-authored-by|generated with"` must be empty).
5. **Summarize** in this shape:
   - Build: PASS/FAIL (tests run / failed)
   - Blockers (must fix before merge) — merged and de-duplicated across reviewers, with file:line
   - Should-fix
   - Notes
   - Verdict: `READY TO MERGE` or `NOT READY`

Do not fix anything in this command; only report. Ask me before applying fixes.
