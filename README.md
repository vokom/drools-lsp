> [!IMPORTANT]
> This project has moved to the Apache KIE project and is now maintained at:
> https://github.com/apache/incubator-kie-tools/
>
> This repository is archived and no longer maintained.

Overview
===
This is an experimental implementation of [LSP](https://microsoft.github.io/language-server-protocol/) based project intended to provide support for a [DRL](https://docs.jboss.org/drools/release/latest/drools-docs/html_single/#drl-rules-con_drl-rules) text editor.

It is composed of 2 parts: the **server** containing the actual LSP implementation and the **client** which is a VSCode extension consuming the services provided by the server.

The server is a Java 17/Maven project. Run `mvn clean install` at the repository root to build it and copy the assembled server JAR into `client/lib/` for the VS Code extension.

Server Architecture
===

The server uses the published `org.drools:drools-drl-parser:10.2.0` and ANTLR 4.13.2, with five supporting modules:

1. **drools-parser-compat** - adapts validated Java lambda function bodies for the published parser, preserving source tokens and formatter safety checks.
2. **drools-completion** - completion, hover, navigation, Java source indexing, workspace grouping and syntax/lint diagnostics; completion uses the C3 engine.
3. **drools-formatter** - DRL formatting, configuration and safety checks, also available as a standalone CLI.
4. **drools-lsp-engine-bridge** - a small bridge loaded against the workspace's own Drools engine for compile diagnostics.
5. **drools-lsp-server** - LSP transport, document and workspace services, formatting and compile-diagnostic lifecycle management.

See [Server configuration and protocols](docs/SERVER.md) for settings, grouping, CLI usage, engine isolation and limitations of the unchanged VS Code client. [Migration notes](docs/SERVER_MIGRATION.md) describe the pinned source and standalone adaptations.

[VSCode](https://code.visualstudio.com/) Usage
===

1. Install the [DRL Editor](https://marketplace.visualstudio.com/items?itemName=drl-editor.vscode-extension-drl-editor) extension from the VS Code Marketplace (search "DRL Editor" in Extensions, or `Ctrl+Shift+X`)
2. Open a `.drl` file and start editing

Alternatively, download a `.vsix` file from [GitHub Releases](https://github.com/kiegroup/drools-lsp/releases) and install manually via `Extensions` → `...` → `Install from VSIX...`.

### Developer notes
See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions and development setup.

[Neovim](https://neovim.io/) Usage
===
Neovim connects to the drools-lsp-server directly, bypassing the VS Code client extension. Not all features have been confirmed in Neovim — if you find missing functionality, please report it via [GitHub Issues](https://github.com/kiegroup/drools-lsp/issues), ideally with a pull request.

Neovim has [built-in](https://neovim.io/doc/user/lsp.html) LSP support, however client configuration is a manual process. It can be made a lot easier, though, if you leverage some of the many plugins available to do the hard parts for you, as you will see in the example below. _Please note:_
- Neovim will connect to the drools-lsp-server directly, bypassing the VSCode client extension.
- You are required to have a java runtime environment installed on your system, either with the `JAVA_HOME` environment variable set, or with the `java` command locatable in your `PATH`.
- The example below only shows the relevant portions of one's nvim `init.lua` file, assuming user familiarity with Neovim configuration (including working with plugins and [keymappings](https://github.com/neovim/nvim-lspconfig#Suggested-configuration), among other things).

**Example Configuration**
1. Use [packer.nvim](https://github.com/wbthomason/packer.nvim) (plugin installer) to install [nvim-lspconfig](http://github.com/neovim/nvim-lspconfig) (standard Neovim LSP configurations), [mason.nvim](https://github.com/williamboman/mason.nvim) (an _excellent_ LSP/DAP/Linter/Formatter package manager), and [mason-lspconfig.nvim](https://github.com/williamboman/mason-lspconfig.nvim) (the `mason.nvim` to `nvim-lspconfig` bridge):
```lua
  use 'wbthomason/packer.nvim'
  use {
    'neovim/nvim-lspconfig',
    requires = {
      'williamboman/mason.nvim',
      'williamboman/mason-lspconfig.nvim',
    },
  }
```
2. Get `mason` up-and-running:
```lua
require('mason').setup {}
require('mason-lspconfig').setup {}
```
3. Initialize the `drools-lsp` server (configuration options [here](https://github.com/neovim/nvim-lspconfig/blob/master/doc/server_configurations.md#drools_lsp)):
```lua
require('lspconfig').drools_lsp.setup {
  -- configuration options can be put here
  -- when using mason, nothing is required!
}
```
4. Add automatic filetype detection of DRL files (necessary to trigger the language server startup):
```lua
vim.cmd[[ autocmd BufRead,BufNewFile *.drl set filetype=drools ]]
```
5. Startup `nvim` anew, have `packer` install the plugins, have `mason` [install the language server](https://github.com/williamboman/mason.nvim/blob/main/PACKAGES.md#drools-lsp), and start editing DRL!
```vim
:PackerSync
:MasonInstall drools-lsp
:edit your.drl
```
