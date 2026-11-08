# Standalone server migration

## Source and scope

The server snapshot comes from `packages/drools-lsp` in the local
`incubator-kie-tools` checkout at
`f3fe2616b7681438c5d2288ee8eaa4c5dcb29b05`, compared with its initial import
`858dd7c041`. All four source modules' Java code, tests, resources and service
registrations are included. The source checkout was left unchanged.

This imports completion and hover improvements, Java source and static-member
indexing, same-package resolution, workspace grouping, deprecated-syntax
diagnostics, formatting, compile diagnostics and the engine bridge. The VS Code
extension source, manifest, test fixture and lockfile are unchanged. No source
client features, monorepo JavaScript packaging or release changes are imported.
The repository's migration notice is unchanged.

## Standalone adaptations

| Area | Adaptation |
| --- | --- |
| Maven parent | Keep `org.drools`, `1.0.1-SNAPSHOT` and Java 17; replace inherited monorepo properties with explicit dependency management and plugin versions |
| Dependencies | Published Drools parser/engine test dependencies at 10.2.0, ANTLR 4.13.2, C3 1.1, LSP4J 0.19.0, Gson 2.10.1, JUnit 5.9.1 and AssertJ 3.23.1 |
| Parser | Remove the old bundled grammar/generated-parser module; depend on the published parser with the source's `drools-core` and `drools-io` exclusions |
| Compatibility | Add `drools-parser-compat`, shared by completion and formatting, for validated Java lambda function bodies |
| Engine bridge | Keep provided `kie-api`, runtime bridge dependency from the server, and test-only compiler/MVEL dependencies |
| Packaging | Keep `org.drools.lsp.server.Main`, the existing server JAR filename and install copy to `client/lib/`; add the formatter CLI assembly |
| Attribution | Preserve source Apache headers and its NOTICE; include LICENSE/NOTICE in module JARs and at assembly roots, aggregate dependency metadata in `META-INF/DEPENDENCY-LICENSES`, and merge service descriptors |

The shared `assembly/uber-jar.xml` retains the `jar-with-dependencies` classifier.
It extends Maven's standard descriptor to preserve attribution and service
providers when unpacking dependencies. No workspace engine implementation is
bundled in either assembled JAR.

## Published parser compatibility

The source monorepo selects the custom `999-20260908-local` Drools build. With
published 10.2.0, its formatter regression
`aFunctionBodyUsingALambdaFormatsNormally` fails. The imported Java lambda rule
resolves its expression body through the older DRL expression grammar, which
rejects valid Java function bodies. It can also cause an earlier annotated
rule's annotation chunk to absorb the following function.

`DrlParserFactory` uses the published parser unchanged. For a function body
containing an arrow, it first validates the DRL signature and the entire body
with the published Java parser. Only a valid Java body becomes an opaque block
for DRL lookahead. Its original default-channel tokens, text, offsets and body
terminals remain available. Functions already use verbatim body emission in the
source formatter. Invalid bodies follow the original parser/recovery path, so
malformed input and structural-refusal tests retain their behavior.

This is an editor compatibility adapter, not a replacement engine grammar.
Validated lambda bodies retain terminals rather than a structured expression
tree. Bodies using syntax rejected by both published parsers remain subject to
normal diagnostics and formatter refusal. Workspace compilation continues to
use the workspace's own engine without this adapter.

Regressions cover nested and block lambdas, typed parameters, annotated rules
before a function, declarations after it, supplementary Unicode before the
function, original token channels and text, late error-listener attachment,
malformed lambdas, completion after a function, formatting idempotence and
content/comment preservation. The packaged parser classes are byte-for-byte
identical to the published artifact; no generated parser classes are copied or
shadowed locally.

## Source comparison audit

Of the 148 files in the four pinned source modules, 137 are byte-identical.
There are no missing source files. The 11 adapted files are:

- The four module POMs, for standalone dependencies and packaging.
- `DRLCompletionHelper`, `DRLDiagnosticHelper`, `DRLParsers`, `DRLFormatter` and
  `Emitter`, to construct parsers through `DrlParserFactory`.
- `DRLCompletionHelperTest` and `FormatterMissingLhsGuardTest`, with additional
  compatibility regressions. All imported tests remain present.

Local additions outside that snapshot are the compatibility module, root build
adaptations, the shared assembly descriptor, NOTICE and server documentation.
The bundled `drools-parser` sources and tests are removed; the imported
`DRLParserSmokeTest` and compatibility/feature suites test the published parser.

## Verification

Verification used Corretto 17.0.9:

- `mvn -B -ntp clean install`: 827 tests, no failures, errors or skips. This
  includes 464 completion/indexing/grouping/lint tests, 208 formatter tests,
  144 server tests, 8 bridge tests and 3 compatibility tests.
- Compile tests cover engine presence/absence, isolated class loading, timeouts,
  stale results, engine replacement, regrouping and queued builds.
- Packaged stdio LSP smoke checks cover initialize/shutdown, document formatting
  with a lambda function, file-group protocol round trips and diagnostics with
  no engine. A separate Maven workspace using Drools 10.2.0 confirms real
  compile errors are returned through the packaged bridge.
- Formatter CLI smoke checks cover `--help`, stdin formatting, idempotent
  `--check` and malformed-input refusal on Java 17.
- Assembly checks confirm one published parser, one ANTLR 4 runtime at 4.13.2,
  unchanged published parser class bytes, no duplicate class entries, no bundled
  compiler/core/MVEL implementation, the bridge, resolver service descriptor,
  attribution files and an identical copy in `client/lib/`. The parser's legacy
  ANTLR 3 runtime is a distinct dependency retained for its legacy APIs.
- `npm run pretest`: unchanged fixture compilation, TypeScript compilation and
  lint pass. The existing extension suite passes both tests in VS Code 1.145.2
  against the migrated server on Java 17. The locked `@vscode/test-electron`
  launcher expects a macOS executable named `Electron`, while this downloaded
  build uses `Code`; the tests ran through its `runTests` API with
  `vscodeExecutablePath` set to that executable. No client files were changed.

The commands download dependencies and VS Code where needed. They do not
publish artifacts, create a release, commit or push changes.
