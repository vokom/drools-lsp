# Repository Guidelines

## Context-Efficient Tooling
- Prefix shell commands with `rtk` to compress their output; a hook may already do this, and a prefixed command passes through it unchanged. For an exact, complete list (e.g. diffing failure sets), use `rtk bypass <cmd>`.
- Search with `rg`, scoped by path or file type. Use `ast-grep` (`sg`) for structural searches and rewrites where text matching gives false positives (e.g. `sg -l java -p 'logger.log($$$)'`), and preview rewrites before applying them.
- Use `zg "<query>"` or the `zvec_grep` MCP tools for semantic or cross-file discovery. Refresh with `zg --index`, which reuses the saved model and scan rules.
- `.claudeignore` lists bulky generated paths, and `Read` deny rules in `.claude/settings.json` block them for file tools. Adding a path means updating both. When a task needs one, query it with `jq`, `rg`, or a short script. Both files are git-ignored by the `.*` rule in `.gitignore`.
- Read a file before changing it, and do not re-read it unless it has changed; an edit result is the confirmation. Locate before reading: `rg -n 'class |^\s+(public|private|static).*\(' <file>` (or `rg -n '^#'` for docs), then read only that range. Skip files over 100KB unless the task requires them.
- `DRLLintHelper.java` (1.4k lines), `DroolsLspServer.java`, `DRLFormatter.java`, `DroolsLspDocumentService.java`, `CompileDiagnostics.java` and the `DRL*HelperTest.java` suites are large; never read them whole to find one section.
- Change files with targeted edits. Rewrite a file only when most of it changes.
- Cap long commands with `tail -N`, `head -N`, or `rg -c`. Run tests with `mvn -o -B -q test 2>&1 | tail -40`, and use `git diff --stat`/`git log --oneline` before any full diff. Do not pipe a script that writes files into `head`; the broken pipe can kill it before it writes.
- Reuse results already in the conversation instead of re-running commands or re-reading files.
- Keep updates concise and technical. Do not require Claude-specific middleware such as Headroom or Caveman.
- Do not use IDE or computer-use MCP tools unless the task depends on unsaved editor state, IDE-only diagnostics, or GUI interaction.

## What the System Does
- Language server for Drools DRL (Java 17, Maven) plus a VS Code extension in `client/` that launches it over stdio. Also usable from Neovim and IntelliJ. It provides completion, hover, navigation, references/rename, lint, formatting and compile diagnostics. The README says the project moved to Apache KIE (`incubator-kie-tools`) and this repo is archived; this copy is the standalone port.
- The server uses the published `org.drools:drools-drl-parser` 10.2.0 and ANTLR 4.13.2. Do not bundle or shadow parser classes; the packaged parser must stay identical to the published artifact.
- Compile diagnostics never use the server's own engine. `drools-lsp-engine-bridge` is loaded in a child classloader against the workspace's `kie-api`/`drools-compiler`/`drools-mvel`. Keep engine and MVEL dependencies test-scoped in the server and `kie-api` `provided` in the bridge.
- `docs/SERVER.md` is the source of truth for settings, custom `drools/*` protocol methods, grouping and the formatter CLI exit codes (0 ok, 1 usage/check failure, 2 safety refusal). `docs/SERVER_MIGRATION.md` lists the pinned upstream commit and the 11 files adapted from it.
- Formatter safety: formatting returns no edits when parse, structural, content or comment checks refuse the document. Keep refusals non-destructive.
- `WorkspaceSiblingResolver` is a ServiceLoader extension point (`META-INF/services` in `drools-completion`); external providers take precedence over `ConfiguredGroupingResolver`.
- The `client/` extension source, manifest, fixture and lockfile are intentionally unchanged from the original; new server controls have no extension UI (see `docs/SERVER.md`).

## Project Structure
- `pom.xml` — parent POM (`org.drools:*:1.0.1-SNAPSHOT`, release 17, JUnit 5.9.1, surefire 3.2.5). Modules: parser-compat, lsp-server, formatter, engine-bridge, completion.
- `drools-parser-compat/` — `DrlParserFactory`: wraps the published parser so valid Java lambda function bodies parse as opaque blocks; shared by completion and formatter.
- `drools-completion/` — `org.drools.completion`: `DRL*Helper` classes per feature (completion via C3, hover, definition, references, rename, lint, code lens, inlay hints, type hierarchy), Java source/class indexing (`JavaSourceTypeIndex`, `ClassIndex`), workspace grouping (`DrlFileGrouping`, `KModuleParser`, `ConfiguredGroupingResolver`).
- `drools-formatter/` — `DRLFormatter` and per-section formatters (`LhsFormatter`, `RhsFormatter`, `DeclareFormatter`); `FormatCLI` is the standalone CLI.
- `drools-lsp-engine-bridge/` — `EngineBridge`: compiles DRL with whichever engine its own classloader sees.
- `drools-lsp-server/` — `Main` (stdio), `DroolsLspTCPLauncher` (dev, port 9925), `DroolsLspServer`, `DroolsLspDocumentService`, `DroolsLspWorkspaceService`, `CompileDiagnostics` (serialized, cancellable compile work), `MavenClasspathResolver`, `ProjectEngine`.
- `assembly/uber-jar.xml` — shared `jar-with-dependencies` descriptor; preserves LICENSE/NOTICE and merges service descriptors.
- `client/` — VS Code extension (TypeScript); `client/src/testFixture/` is a small Maven project used by extension tests.
- `.github/workflows/` — `drools-lsp-pr.yml` (CI), `release.yml`, `pre-release.yml`.

## Generated Assets
- `mvn install` copies `drools-lsp-server/target/drools-lsp-server-jar-with-dependencies.jar` into `client/lib/` (git-ignored). The formatter CLI jar is `drools-formatter/target/drools-formatter-jar-with-dependencies.jar`.
- `target/`, `client/out/`, `client/node_modules/`, `.zvec-grep/` are git-ignored build or index output.

## Build, Test, and Development Commands
```bash
mvn clean install            # build all modules, run tests, copy server jar to client/lib/
mvn -o -B -q test            # Java suites only (no install side effects)
java -jar client/lib/drools-lsp-server-jar-with-dependencies.jar   # run server over stdio
cd client && npm ci && npm run pretest && npm test   # extension build, lint, e2e
```
- Baseline: `mvn test` runs 894 tests across all modules, all green, with no network once dependencies are cached; a failure is a real regression. Test output is noisy `java.util.logging` INFO/WARNING lines, which are expected.
- `npm test` downloads and launches VS Code and needs a graphical session (CI uses `xvfb-run` on Linux). Not run during verification of this file.
- CI also runs `mvn -f client/src/testFixture/pom.xml compile`. Verify Java changes on JDK 17.
- Releases are tag-driven (`v1.0.1`); see `CONTRIBUTING.md`. Do not tag, push or publish unless asked.

## Coding Style & Naming Conventions
- Java 17, 4-space indentation, no tabs, `java.util.logging` (`Logger`/`Level`) for logs, Apache license header on most Java files (a few newer ones lack it; add it to new files). No formatter or linter is configured in Maven; match surrounding code.
- Feature logic lives in static-style `DRL*Helper` classes in `drools-completion`; the server module only adapts them to LSP4J types. Put new language logic in `drools-completion`, not in `drools-lsp-server`.
- Lint severities and similar switches are JVM properties named `drools.lsp.*` and documented in `docs/SERVER.md`; update that table when adding one.
- TypeScript in `client/` is checked by `npm run lint` (eslint).
- When a repository's documentation and its existing code disagree, follow the conventions used in the existing code, and in your final reply point out each inconsistency you found between the documentation and the code.
- Do not guess APIs, versions, flags, commit SHAs, or package names. Verify by reading code or docs before asserting.

## Testing Guidelines
- JUnit 5 with AssertJ; tests mirror main packages under `src/test/java`. Shared fixtures for completion are in `drools-completion/src/test/java/org/drools/completion/fixtures/`; formatter sample in `drools-formatter/src/test/resources/`.
- Tests are deterministic and use temp directories and in-process servers; engine tests use Drools 10.2.0 as a test dependency.
- Changing the formatter: cover idempotence plus content and comment preservation. Changing the parser adapter: cover malformed lambdas and the original token channels. Changing lint: add cases to `DRLLintHelperTest`.
- VS Code extension behaviour needs a manual or graphical run; say if you did not run it.

## Commit & Pull Request Guidelines
- History is mixed: short imperative sentences (`Fix modify-block navigation, unit member checks and false rule-unit errors`), `feat:`/`fix:` prefixes on some, `Feat/<topic> (#NN)` squash merges, and Dependabot `build(deps-dev): Bump ...`.
- PRs state the behaviour change, the verification commands with results, and any environment limits (for example extension tests not run without a display).

## Security & Configuration Tips
- Never commit `VSCE_PAT` or other publishing tokens; the Marketplace token lives only as a repository secret used by `release.yml`.
- The server reads no credentials. Maven classpath resolution only reads `pom.xml` files named by `drools.lsp.maven.pomPath` or found in the workspace.
- Keep Apache headers and the root `NOTICE`/`LICENSE` intact; they are bundled into module jars.
