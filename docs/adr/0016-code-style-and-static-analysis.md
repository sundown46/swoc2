# 0016 - Code style and static analysis

Date: 2026-10-02
Status: Accepted

## Context

CLAUDE.md requires "lint, format and typecheck configs" as part of the P0 repo skeleton (item
1), for both the Java backend and the TypeScript frontend, so that style and basic correctness
checks are enforced from the first commit rather than retrofitted once a style has already
drifted across the codebase.

## Decision

- **Backend:** `spotless-maven-plugin`, configured with `palantir-java-format`, bound to the
  `verify` phase (`spotless:check`). `./backend/mvnw verify` fails on unformatted code;
  `./backend/mvnw spotless:apply` fixes it. No separate Checkstyle/PMD ruleset for now - the
  compiler plus Spotless formatting is the P0 baseline; add static-analysis rules when a real
  need shows up rather than preemptively.
- **Frontend:** ESLint 10 flat config (`eslint.config.js`, the only config format ESLint 10
  supports) with `typescript-eslint`'s type-checked rule sets
  (`strictTypeChecked`/`stylisticTypeChecked`) applied to `**/src/**/*.{ts,tsx}`, plus
  `eslint-plugin-react-hooks` and `eslint-plugin-react-refresh` for `apps/web`. Prettier handles
  formatting; `eslint-config-prettier` disables any ESLint rule that would conflict with it.
  `tsconfig.base.json` sets `strict: true` workspace-wide (CLAUDE.md: "no `any`").

## Consequences

- `palantir-java-format` had to be pinned to 2.100.0, not the first version tried (2.67.0),
  because 2.67.0 fails on JDK 25 with a `NoSuchMethodError` against an internal `javac` API
  (`Log$DeferredDiagnosticHandler.getDiagnostics()`) - confirmed by actually running the build,
  not assumed. See ADR 0001 "Validation".
- Type-checked ESLint rules only apply to `**/src/**/*.{ts,tsx}`, not to root-level config files
  (`eslint.config.js`, `vite.config.ts`) - those get plain `js.configs.recommended` plus Node
  globals instead. Applying type-aware linting to the lint config's own file produced rule
  violations that had nothing to do with actual code quality (e.g. flagging
  `typescript-eslint`'s own now-deprecated `tseslint.config()` helper); scoping avoids that
  noise without weakening checks on application code.
- Every new frontend package/app must declare its own lint-relevant devDependencies (ESLint,
  TypeScript) even though the root already has them, because pnpm's strict workspace linking
  does not hoist them automatically for scripts run from inside that package.
