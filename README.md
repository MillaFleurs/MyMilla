# Milla Brain (Clojure + Ollama)

Clojure-based wrapper around a local Ollama instance with a tiny memory DSL (`fact`, `desire`, `opinion`, `backlog`) and chat logging to SQLite.

## Prerequisites
- Java (OpenJDK) and Clojure CLI (`clj`)
- SQLite available on the system
- Ollama running locally (`http://localhost:11434`) with a model matching your config

## Setup
1. Copy or edit `config/milla.yaml` (or `milla-config.yaml` / `milla.yaml`), or point `MILLA_CONFIG` to a custom path. Environment overrides: `MILLA_DB_PATH`, `MILLA_NODE_ID`, `OLLAMA_URL`, `OLLAMA_MODEL`, `MILLA_DEFAULT_SESSION`.
2. Initialize the DB (creates `milla_memory.db` and tables):
   - `bin/milla-init-db`
   - or `clj -M -e "(require 'milla.core) (milla.core/init!)"`

## Usage
- Start the background server (recommended for fast, steady use): `bin/milla-serve` (or `bin/milla-restart-server` after pulling changes). Heartbeat/pid lives at `milla.pid` by default.
- CLI ask (forwards to server if running): `bin/milla llama3.2 "Hello, Milla"` (model optional; defaults to config).
- REPL: `clj -M:repl`, then `(require 'milla.core)` and call `(milla.core/fact "User lives in Bend")`, `(milla.core/ask! {:prompt "What's up?"})`, etc.
- First-time setup helper: `bin/milla-setup` (prompts for location, facts, desires, opinions, backlog and stores them).
- Chat/system prompt is built from stored facts/desires/opinions/backlog for context.
- All scripts support `-h/--help` for usage info.

## Configuration
Default search order: `MILLA_CONFIG` env var → `config/milla.yaml` → `milla-config.yaml` → `milla.yaml`. See `config/milla.yaml` for the sample layout.
- Daemon workflow: start `bin/milla-serve` (or `bin/milla-restart-server` to restart in background). `bin/milla` will auto-forward to the daemon if a heartbeat exists; otherwise it starts the daemon and forwards. Health check: `bin/milla-health`. Migrate schema: `bin/milla-migrate` (runs `init!`). First-run helper: `bin/milla-setup`.
- Stop daemon: `bin/milla-stop` (kills running server and removes pid).

## Database
- Schema snapshot: `doc/schema.sql` (mirrors `milla.core/init!`)
- Live DB file: `milla_memory.db` (created on first run); inspect with `sqlite3 milla_memory.db`.
- Functional spec reference: `doc/milla-spec.pdf` (and `.tex` source alongside).
- Reset to a clean, empty DB: `bin/milla-reset-db` (uses the configured DB path and recreates schema).
- Logging: configure in `:log` (e.g. `level: info`, `file: milla.log`). INFO/WARN go to the log file; ERROR goes to log + STDERR. Optional `:log {:request_bodies? false}` to disable logging payloads; log file is capped at 5MB (truncated when exceeded).
- Ollama retries: configure via env `OLLAMA_RETRIES` and `OLLAMA_RETRY_SLEEP_MS` if your model takes longer to load.
- Ollama keep-alive: set `:ollama {:keep_alive "10m"}` (default) or override with `OLLAMA_KEEP_ALIVE`. Requests include keep-alive and respect prompt context via `:prompt {:max_tokens 2000}` or env `MAX_PROMPT_TOKENS`.
- Thermal throttling: optional; set `:thermal {:enabled true :max_c 85 :cooldown_ms 120000 :sensor_path "/sys/class/thermal/thermal_zone0/temp"}` to pause before requests when the sensor reports temps above the threshold.
- Server: `:server {:host "127.0.0.1" :port 17863 :pid_file "milla.pid" :heartbeat_ms 5000}`; server refuses to start if a fresh heartbeat exists. Use `bin/milla-restart-server` to stop/restart cleanly (removes stale pid).
- Node location: set `:node {:location "your-place"}` or env `MILLA_NODE_LOCATION`; included in the system prompt.
- Merging brains: `bin/milla-merge /path/to/output.db /path/to/db1 /path/to/db2` unions statements/chat/summaries, dedupes, and reruns summarization.
- Merge summary: bin/milla-merge also writes a global bullet summary of all chat into global_summary.
- Daemon/server mode: start `bin/milla-serve` to run a long-lived HTTP server (default port 17863). `bin/milla` will forward prompts to the running server if a heartbeat PID file exists; otherwise it starts the server and forwards. Server heartbeat is written to `milla.pid` (configurable via `:server`).

### Self-edit (guarded, disabled by default)
- Enable via config (opt-in): `:self_edit {:enabled true :intent "rag-tools" :allow_paths ["src/milla/rag" "doc"] :require_user_confirm true}`. Default intent is `rag-tools` to constrain edits to RAG tooling/customization.
- Endpoints (server): `/code/list` (list allowed files), `/code/read` (POST JSON `{"path":"src/milla/rag/...clj"}`), `/code/patch` (POST JSON `{"path":"...","old":"text","new":"text","intent":"rag-tools","confirmed?":true}`) performs a single replace with a timestamped backup. Returns 403 when disabled; 400 if intent/confirmation is missing or disallowed.
- Designed for human-in-the-loop customization; no auto-exec. Backups are written alongside the edited file before any change.

### Conversation history + summarization
- History window is configurable: `:chat {:history_limit N}` (default 50).
- Summarization is rolling: older messages (beyond the last 5 recent turns) are summarized into a single rolling summary per session. The last 5 messages are kept verbatim; summaries are included in the system prompt alongside facts/desires/opinions/backlog.
- Token-aware trimming: the recent set is trimmed from the oldest to stay within `prompt.max_tokens` (`num_ctx`) before sending to Ollama.
- RAG: optional embeddings-backed retrieval using Ollama embedding model; if enabled, relevant facts/summaries are pulled from SQLite embeddings and added to the prompt.
Notes: scripts default to `~/milla/milla-config.yaml` if `MILLA_CONFIG` is not set.

### Custom tools
- User-extensible helpers live in `src/milla/tools.clj`. You may require JVM/Clojure standard libs there and add your own functions (e.g., file utilities, text helpers). Keep them small/pure where possible for safe use from prompts/RAG.

### Web CGI chat (optional)
- Script: `cgi/milla.cgi` (Perl, requires JSON module). Shows a simple chat UI, model dropdown from `ollama list`, and buttons to reset DB/session or download DB dump. Uses `bin/milla`, `bin/milla-reset-db`, and `bin/milla-dump-db` under the repo root.
- Install: copy/symlink `cgi/milla.cgi` into your web server’s CGI path (e.g., `/usr/lib/cgi-bin`), `chmod 755 cgi/milla.cgi`, and ensure the repo `bin/` is executable (755). If the repo isn’t the working dir, set `MILLA_ROOT` or adjust the paths inside the script, or add the repo `bin/` to PATH for the CGI user. Set `MILLA_CONFIG` in the web server env if you use a non-default config.
- Permissions: CGI user must be able to read the repo and write the DB/log path you configured.
- Dependencies: Perl `JSON` module must be installed; CGI user must be able to run `ollama list` and the `bin/` scripts. You may set `MILLA_ROOT` explicitly if the repo lives outside the CGI working directory.

## Sync (kittens)
- `bin/milla-sync-db [pull|push] user@host:/path/to/milla_memory.db` uses `rsync`.
- After `pull`, it runs `milla.sync/merge` to union remote data into the local DB (dedup by content/time/source). You can also call it directly: `clj -M -m milla.sync merge ./milla_memory.remote.db`.

## Tests
- Basic smoke tests live in `test/milla/core_test.clj`. Run with `clj -M:test`.

## License
- GNU Affero General Public License v3.0 (AGPL-3.0). See `LICENSE` for full terms.

## Notes / gaps vs. spec
- Recall/summarization helpers remain to be added; sync merge is a simple union based on content/time/source.

## Backlog / To Do
- Trim runtime dependencies and kernel modules for a minimal Ubuntu/Raspberry Pi deployment.
- Add CI/static checks for self-edit endpoints and patch safety.
- Harden CGI deployment (optional auth/IP whitelist; rate limiting).
- Improve prompt/RAG tests with golden cases and token-budget coverage.
- Add integrations with standard calendars (e.g., Google Calendar) for scheduling/backlog items.
