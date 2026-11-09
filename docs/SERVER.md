# Server configuration and protocols

Build from the repository root with Java 17 and `mvn clean install`. Maven
coordinates remain `org.drools:*:1.0.1-SNAPSHOT`. Installation copies
`drools-lsp-server/target/drools-lsp-server-jar-with-dependencies.jar` into
`client/lib/`. Start the server over stdio with:

```bash
java -jar client/lib/drools-lsp-server-jar-with-dependencies.jar
```

The entry point is `org.drools.lsp.server.Main`; the TCP development launcher is
`org.drools.lsp.server.DroolsLspTCPLauncher`.

## Configuration

The initialization options accept these objects:

```json
{
  "formatter": {
    "tabSize": 2,
    "insertSpaces": true,
    "lineLength": 110,
    "lineEndings": "preserve",
    "parenPadding": true,
    "bindingColonSpace": false,
    "normalizeTerminators": true,
    "alignDeclarations": true,
    "headerMetadata": "indented"
  },
  "compile": { "onSave": true, "timeoutSeconds": 120 },
  "grouping": {
    "kbases": [{ "name": "orders", "packages": ["com.example.orders.*"] }]
  },
  "workspaceFiles": ["file:///workspace/src/main/resources/orders.drl"]
}
```

Omit `workspaceFiles` to let the server discover workspace files. An empty list
is an explicit empty workspace inventory. Grouping paths use the workspace root
for settings and the containing configuration directory for disk configuration.

Clients advertising `workspace.configuration` are queried for
`drools.lsp.formatter` and `drools.lsp.compile` after initialization and on
configuration changes. The server supports dynamic configuration registration.
Push settings under `drools.lsp` are also accepted. Grouping updates use the custom
notification described below. Unknown formatter keys and invalid values fall
back to defaults. `lineEndings` accepts `preserve`, `lf`, `crlf`;
`headerMetadata` accepts `indented`, `flush`, `inline`. The compile timeout is at
least 10 seconds and defaults to 120 seconds. Compile on save defaults to true.

Standard `textDocument/formatting` requests use the configured style with the
request's `tabSize` and `insertSpaces` values. Formatting returns no edits if
parsing, structural, content or comment checks refuse the document. Skip markers
`@formatter:off` and `@formatter:on` preserve enclosed statements.

JVM properties available before `-jar` include:

| Property | Purpose |
| --- | --- |
| `drools.lsp.logLevel` | Java logging level, default `INFO` |
| `drools.lsp.maven.pomPath` | A POM or platform-path-separated POM list for classpath resolution |
| `drools.lsp.java.sourcePaths` | Additional Java source roots, separated by `;` |
| `drools.lsp.java.packageFilters` | Java source package prefixes, separated by `;` |
| `drools.lsp.lint.deprecated` | Severity of deprecated syntax findings, default `warning` |
| `drools.lsp.lint.missingEnd`, `missingSeparator`, `missingSemicolon`, `unbalancedParens`, `unknownTypes` | Existing lint severity controls |
| `drools.lsp.lint.mvelPropertyAccess` | Property access style hint, default `off` |
| `drools.lsp.lint.unusedBindings` | Severity of "binding is never used" findings, default `hint`; tagged `Unnecessary` so clients grey them out. `off` disables |
| `drools.lsp.lint.typeMismatch` | Severity of a field compared with a literal of another kind (a number field against a string, and so on), default `warning`. Drools coerces these at run time, so the engine reports no build error. `off` disables |
| `drools.lsp.inlayHints.enabled` | Binding type hints |

Severity values are `off`, `hint`, `info`, `warning`, `error`. Deprecated syntax
recognition includes half constraints, legacy pattern connectives, misplaced
annotations, `agenda-group`, and project-declared custom operators lacking `##`.

## Workspace grouping and protocol

The built-in resolver reads `drl-lsp-kbases.json`, adopted manifests and
`META-INF/kmodule.xml`. Settings grouping takes precedence over disk grouping;
explicit JSON declarations take precedence over kmodule declarations. Unclaimed
files fall back to same-directory siblings. Package rules and kbase includes
follow the source resolver's semantics.

Example `drl-lsp-kbases.json`:

```json
{
  "kbases": [
    { "name": "shared", "files": ["rules/Types.drl"] },
    { "name": "orders", "packages": ["com.example.orders.*"], "includes": ["shared"] }
  ],
  "sources": [
    {
      "include": "**/*_Rule-Configs.json",
      "pathsRelativeTo": ["**/src/main/resources"],
      "aliases": { "kbases": "rule.config.list", "name": "rule.config.type", "files": "relative.path.list" }
    }
  ]
}
```

Custom methods are on the language-server endpoint. Paths in protocol payloads
are document URI strings.

| Method | Kind | Payload or result |
| --- | --- | --- |
| `drools/fileGroups` | Request | No parameters; returns a map from group name to `{ "files": [uri], "kind": string or null, "declaredIn": uri or null }` |
| `drools/setFileGroup` | Notification | `{ "uri": uri, "group": name }`; blank group clears the pin |
| `drools/reloadFileGroups` | Notification | No parameters; reload disk configuration |
| `drools/setGroupingConfig` | Notification | `{ "config": object or null }`; null clears settings grouping |
| `drools/setWorkspaceFiles` | Notification | `{ "uris": [uri] }`; null withdraws the client inventory |
| `drools/rebuildWorkspace` | Notification | `{ "uri": uri }`; compile the workspace containing that file |
| `drools/fileGroupsChanged` | Server notification | No parameters; clients should re-request groups |

Grouping changes invalidate compile diagnostics and request a diagnostic refresh.
Pins are per document and are cleared when switching workspace roots.

`WorkspaceSiblingResolver` remains a service-provider extension point. Put its
implementation JAR on the server classpath and register the implementation in
`META-INF/services/org.drools.completion.WorkspaceSiblingResolver`. External
providers take precedence over the shipped `ConfiguredGroupingResolver`.
Use `java -cp 'server.jar:resolver.jar' org.drools.lsp.server.Main` (use `;` on
Windows) when adding provider JARs.

## Compile diagnostics

Syntax and lint diagnostics work without a workspace engine. Compile diagnostics
require the workspace classpath to provide `kie-api`, `drools-compiler` and
`drools-mvel`, plus the project's compiled classes and dependencies. Build the
workspace first when prompted. Maven classpath resolution and build output
discovery supply the engine entries.

The workspace engine uses a URL classloader whose parent is the Java platform
loader. A child loader defines the bridge from the server JAR and resolves KIE
against the workspace. The server's published parser and ANTLR runtime cannot
replace the workspace's engine versions. Engine compiler/MVEL dependencies are
test-scoped in the standalone build; `kie-api` is provided in the bridge module.

Save-triggered compilation uses the active file group. Workspace rebuild checks
all DRL files under the workspace root. Results are cached for pull diagnostics
and invalidated by edits, regrouping and classpath changes. Work is serialized,
new saves replace pending work, stale results are discarded, and the source
implementation's cancellation and timeout handling are retained. Engine workers
are daemon threads; interruption on timeout is best effort, so an engine that
ignores interrupts may continue in the background.

## Formatter CLI

```bash
java -jar drools-formatter/target/drools-formatter-jar-with-dependencies.jar --help
java -jar drools-formatter/target/drools-formatter-jar-with-dependencies.jar --check rules.drl
java -jar drools-formatter/target/drools-formatter-jar-with-dependencies.jar --write rules.drl
java -jar drools-formatter/target/drools-formatter-jar-with-dependencies.jar --config style.json --stdin < rules.drl
```

The JSON config has the formatter object's shape shown above. The CLI also
supports directory writes and line ranges; see `--help`. Exit status is 0 for
success, 1 for usage/I/O errors or files needing formatting under `--check`, and
2 for a safety refusal. Refused documents are not overwritten.

## Existing VS Code client

The extension source and configuration contributions are unchanged. Standard LSP
features, including document formatting and diagnostics, are available through
its language client. Existing lint/logging/Maven controls remain wired.

There are no new extension controls for formatter style, compile settings,
deprecated-syntax severity, Java source roots/package filters, grouping or
workspace rebuild. The unchanged client does not enumerate workspace files,
restore group pins, display group choices or send the custom reload/rebuild
notifications. Disk grouping and server defaults still work. Use a client that
sends the initialization/configuration/custom protocol described here for those
controls. New-file and grouping-config changes may require reload or restart
when using the unchanged extension.
