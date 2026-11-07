---
description: Run the definition-of-done checklist for a phase, update docs/PROGRESS.md, and propose the commit list
argument-hint: <phase-number>
---

Close out phase $ARGUMENTS.

1. Read docs/PROGRESS.md and find phase $ARGUMENTS: its goal, the services or artifacts it covers, and any open items.
2. Run the definition of done from CLAUDE.md for every service or artifact in the phase:
   - `./mvnw -pl services/<name> -am verify` is green (or `./mvnw verify` for cross-cutting phases)
   - Flyway migrations apply on a clean Postgres (covered by Testcontainers tests; name the test)
   - Hard rules satisfied (use `/verify-service <name>` results if already run this session; otherwise run it)
   - OpenAPI in docs/openapi matches the implementation
   - architect and security-reviewer report no blockers
   - README section and docs/PROGRESS.md updated
   - Commits clean: `git log main..HEAD --format=%B | grep -iE "co-authored-by|generated with"` is empty
   Mark each item PASS / FAIL / N/A with evidence (command + result).
3. Update docs/PROGRESS.md: tick the phase checklist, set "Current status", append decisions to the decisions log (dated 'YYYY-MM-DD'), move resolved open questions out, add new ones.
4. Propose the commit list for anything still uncommitted: small, focused, Conventional Commits (`feat:`, `fix:`, `chore:`, `test:`, `docs:`, `refactor:`), no attribution trailers. Show the list and wait for my OK before committing. Never push.
5. Finish with: what was built, what was deliberately skipped, risks, and the exact commands to run and demo it locally.
