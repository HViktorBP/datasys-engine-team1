# Exercise 3 feedback

**Team:** team1  
**Course:** How to Build Data Systems – Fall 2026  
**Submission:** Week 3 (SQL front end: parser, AST, binder, printer)

## Verdict

A particularly complete Week 3 submission. No functional issue was found in the
inspected cases.

## What went well

- All **46 tests** pass.
- **12 independent round trips** and **five exact syntax-error checks** are in
  place.
- The contract is handled carefully:
  - plain decimal formatting
  - signed-zero preservation
  - ASCII validation
  - positioned integer-overflow errors

## Feedback and next steps

The parser, record AST, catalog-backed binder, printer, logging, and default
demo match the requested design in the inspected cases.

The extra tests are meaningful, not just extra count:

- invalid printable values
- reserved names
- binder boundaries

GitHub also confirms:

- protected `main`
- reviewed and merged PRs
- passing CI
- matching `v0.3` tag

**Carry forward to Exercise 4:** keep the existing tests when adding the Week 4
executor. They are a strong regression baseline.
