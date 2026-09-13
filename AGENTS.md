HI cluade

You are building the best DBMS system in Java. Make no mistakes.

## Java documentation

Before creating or modifying Java code, read and follow
`.agents/skills/java-docs/SKILL.md`. Treat Javadoc as part of the implementation,
not as a separate follow-up task.

- Read the relevant files under `docs/`, the implementation, and its tests before
  documenting behavior. If they disagree, verify the current contract and report
  the inconsistency instead of guessing.
- Add or update Javadocs whenever a Java change affects an API, behavior,
  invariant, exception, data format, or other documented contract.
- Document every public and protected type, constructor, method, and field.
  Also document package-private and private members when their purpose or
  behavior is not self-explanatory.
- Start each Javadoc with a concise summary sentence ending in a period. Use
  `@param`, `@return`, and `@throws` wherever applicable, following the skill's
  formatting rules. Use `{@code}` for code and `{@link}` or `@see` for useful
  references.
- Use `{@inheritDoc}` for overrides whose behavior has not materially changed.
  Document the difference explicitly when an override changes the inherited
  contract.
- Use `@since` for the release that first introduced the API and `@version` for
  the API's current release. Determine both from repository history, tags, and
  current design documents; do not infer them from a single potentially stale
  source. Use `@author` and `@deprecated` as directed by the skill.
- Keep comments precise and testable. Do not promise stronger atomicity,
  validation, compatibility, or error handling than the implementation provides.

Before completing a Java change, generate private-inclusive Javadocs with
`-Xdoclint:all`, run `mvn -B verify`, and inspect the final diff to confirm that
the documentation still matches the code and relevant design documents.

## AI contribution attribution

When creating a commit that contains material contributions from one or more AI
tools, add an attribution trailer for every AI that contributed. Apply this rule
regardless of the AI provider, product, or model.

- Prefer `Co-authored-by: NAME <EMAIL>` using the AI provider's documented
  canonical identity and an email address linked to that identity's GitHub
  account.
- Never invent, assume, or reuse an unrelated email address. If no verified
  GitHub-linked identity is available, add `AI-Assisted-by: PROVIDER/TOOL/MODEL`
  instead and state that GitHub contributor linking cannot be guaranteed.
- Add attribution only when the AI materially created or modified content in
  the commit. Do not attribute read-only advice, searches, or unrelated AI use.
- Preserve the human author and any truthful existing attribution trailers.
  Add one trailer for each materially contributing AI and avoid duplicates.
- Before pushing, inspect every commit created during the task and verify that
  its required attribution trailers are present. Do not rewrite existing
  history solely to add AI attribution unless the user explicitly requests it.
