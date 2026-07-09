# Agent latency improvements — design doc

**Status: proposed, not implemented.** This document specifies two independent
changes to cut agent-perceived latency in the day-to-day `scripts/*.sh` ops.
It's written to be implementable directly from this doc — exact functions,
exact call sites, exact new files, a safety proof, and a test-impact analysis
against the existing suite. No source in `scripts/` has been changed yet.

Origin: observed while using the skill against a large app (dash8) where
individual `eval-cljs.sh` / `dispatch.sh` calls felt slow, and where an agent
polls `console-tail.sh` / `trace-recent.sh` repeatedly over a long debugging
session.

---

## 0. How the transport actually works (context for both edits)

Every `scripts/*.sh` is a thin wrapper (`exec bb ops.clj <subcommand> "$@"`).
`ops.clj` opens a raw TCP socket to shadow-cljs's nREPL port and speaks
bencode directly (no classpath deps — this is why `bb` boots in ~40ms;
measured locally, not the bottleneck). The real work happens via:

```clojure
(shadow.cljs.devtools.api/cljs-eval build-id form-str {})
```

sent as the `code` of an nREPL `eval` op. shadow-cljs relays that form over
its **own** websocket to the connected browser tab, the tab evaluates it, and
the result relays back through the same nREPL response. That JVM↔browser hop
— not `bb` startup, not the TCP socket — is where per-call latency actually
lives, and it's paid once per `cljs-eval` call.

Both edits below are about **reducing the number of `cljs-eval` round trips
per unit of useful work**, not about the transport itself.

---

## 1. Edit 1 — stop paying for a sentinel check on every single call

### 1.1 The problem

Every op that touches the browser calls `ensure-injected!` first:

```clojure
;; scripts/ops.clj — eval-op, current code
(defn- eval-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-form :hint "usage: eval '<form>' [--build :app]"))
  (let [form        (first args)
        build-id    (build-id-from-args (rest args))
        reinjected? (ensure-injected! build-id)]   ;; <-- always runs first
    (try
      (let [{:keys [value] :rfp.wire/keys [cursor elisions value-fits?]} (cljs-eval-wire build-id form)]
        (emit (cond-> {:ok? true :value value} ... reinjected? (assoc :reinjected? true))))
      (catch Exception e ...))))
```

`ensure-injected!` (ops.clj, around line 890) does:

```clojure
(defn- ensure-injected!
  ([build-id] (ensure-injected! build-id {:capture? true}))
  ([build-id {:keys [capture?] :or {capture? true}}]
   (if (runtime-already-injected? build-id)
     false
     (let [results (chunked-inject! build-id (runtime-cljs-paths))] ...))))
```

`runtime-already-injected?` is itself a **full `cljs-eval` round trip** — it
evaluates `(re-frame-pair.runtime/session-id)` in the browser and checks the
result is a non-empty string:

```clojure
(defn- runtime-already-injected? [build-id]
  (try
    (let [resp   (cljs-eval nil build-id (runtime-symbol 'session-id) {:wrap? false})
          parsed (parse-cljs-eval-response resp)]
      (and (string? parsed) (seq parsed)))
    (catch Exception _ false)))
```

So **every single `eval-op` / `dispatch-op` / `trace-recent-op` /
`console-tail-op` / `handler-source-op` / `app-summary-op` call pays two full
browser round trips**: one to ask "is the runtime there?", one to do the
actual work — even though re-injection is only ever needed once per hard page
refresh (SKILL.md / ops.clj comments call this out explicitly: "Spec §3.4 —
the sentinel is why re-injection only fires on full page refresh, not every
connect"). In the steady state (which is nearly 100% of calls in a normal
session), the second round trip is pure waste.

`watch-op` and `tail-build-op` do **not** have this problem — they already
hold one persistent nREPL connection open (`with-current-nrepl-connection`)
for the lifetime of one invocation and pay the `ensure-injected!` cost exactly
once, amortized over many polls. This edit brings the other six ops in line
with that same "pay once, not per call" principle — but since they're
single-shot (no internal poll loop to amortize over), the fix has to be
"don't pay unless you need to," not "reuse a connection."

### 1.2 The fix — try first, fix on failure, don't re-probe what `ensure-injected!` already checks

Add one new private function to `scripts/ops.clj`, placed directly after
`ensure-injected!` (so it can call it):

```clojure
(defn- with-optimistic-inject
  "Call `(f)` — a thunk that performs a cljs-eval-shaped browser call —
   optimistically: assume the runtime is already injected (true for the
   overwhelming majority of calls, since injection only needs to happen
   once per browser page load) and just invoke `f` once. Returns
   `[result reinjected?]` on success — 1 round trip in the common case,
   down from the current 2 (sentinel probe + real call).

   If `(f)` throws an ex-info:

     - `:reason :browser-runtime-not-attached` (no browser tab connected
       at all — shadow-cljs's \"No available JS runtime.\") is not
       fixable by injecting anything. Rethrow immediately; don't waste a
       round trip probing a browser that isn't there.

     - Any other failure MIGHT mean the runtime ns isn't loaded in this
       browser (most commonly: the operator hard-refreshed the page, or
       this is the very first call of the session and nothing has
       injected yet). Delegate the disambiguation to the EXISTING
       `ensure-injected!` — it already starts with the cheap sentinel
       probe (`runtime-already-injected?`) and only pays for a full
       chunked re-inject when the sentinel is truly missing. Do NOT
       call `runtime-already-injected?` again here first — that would
       just re-do the probe `ensure-injected!` is about to do itself.

       - `ensure-injected!` returns `false` (sentinel WAS present, no
         reship happened) → the original failure was a genuine eval
         error, unrelated to injection. Rethrow the ORIGINAL exception
         (`e`) unchanged — same error the caller would see today.

       - `ensure-injected!` returns `true` (a reship just happened) →
         retry `(f)` once. If THAT retry also throws (a different,
         genuine error this time), tag its ex-data with
         `:reinjected? true` before rethrowing, so callers that read
         `(:reinjected? (ex-data e))` in their catch block don't lose
         the diagnostic flag just because the failure happened after a
         reinject.

   Retry safety (why retrying `f` can't double-fire a dispatch): a call
   that fails because the runtime ns isn't loaded fails at CLJS
   *analysis* time inside shadow's REPL compiler — the form never
   becomes JS shipped to the browser, so nothing the form would have
   done (dispatch, mutation, whatever) executes even once. The failure
   happens before shadow ships anything. Retrying the exact same form
   after injecting is therefore side-effect-free to retry, including
   for `dispatch-op`.

   `ensure-injected!`'s own failure path (`die` on a compile/transport
   error in the chunked inject) is unchanged by this function — it
   already calls `(emit ...)` + `(System/exit 1)` today, called
   unconditionally on every op before this change. Moving that call
   behind `with-optimistic-inject`'s failure branch doesn't add or
   remove that behavior, just changes when it's reached."
  [build-id f]
  (try
    [(f) false]
    (catch clojure.lang.ExceptionInfo e
      (if (= :browser-runtime-not-attached (:reason (ex-data e)))
        (throw e)
        (let [reinjected? (ensure-injected! build-id)]
          (if reinjected?
            (try
              [(f) true]
              (catch clojure.lang.ExceptionInfo e2
                (throw (ex-info (.getMessage e2)
                                 (assoc (ex-data e2) :reinjected? true)))))
            (throw e)))))))
```

Cost accounting per call, before vs. after:

| Case | Today | After | Notes |
|---|---|---|---|
| Runtime already injected, call succeeds (the overwhelming majority) | 2 round trips | **1** | The actual win |
| Runtime already injected, call is a genuine user error | 2 round trips | 2 | `f` fails (1) + `ensure-injected!`'s internal sentinel probe confirms "still there" (1) → rethrow original. No regression. |
| Runtime missing (post page-refresh, or very first call) | sentinel(1) + chunked-inject(N) + health(1) + real call(1) | `f` fails(1) + sentinel-inside-`ensure-injected!`(1) + chunked-inject(N) + health(1) + retry(1) | +1 round trip in this rare path only — acceptable, this path is uncommon |
| Browser tab not connected at all | sentinel(1, also fails) + real call(1, fails) → confusing error | `f`(1, fails) → rethrow immediately | Actually *better*: today wastes a sentinel round trip finding out the same thing twice |

### 1.3 Exact call-site changes

#### `eval-op` (fully worked)

```clojure
;; before
(defn- eval-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-form :hint "usage: eval '<form>' [--build :app]"))
  (let [form        (first args)
        build-id    (build-id-from-args (rest args))
        reinjected? (ensure-injected! build-id)]
    (try
      (let [{:keys [value] :rfp.wire/keys [cursor elisions value-fits?]} (cljs-eval-wire build-id form)]
        (emit (cond-> {:ok? true :value value}
                cursor          (assoc :rfp.wire/cursor cursor)
                (seq elisions)  (assoc :rfp.wire/elisions elisions)
                (some? value-fits?) (assoc :rfp.wire/value-fits? value-fits?)
                reinjected?     (assoc :reinjected? true))))
      (catch Exception e
        (emit (cond-> {:ok? false
                       :reason (or (:reason (ex-data e)) :eval-error)
                       :message (.getMessage e)
                       :data (dissoc (ex-data e) :reason)}
                reinjected? (assoc :reinjected? true)))))))

;; after
(defn- eval-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-form :hint "usage: eval '<form>' [--build :app]"))
  (let [form     (first args)
        build-id (build-id-from-args (rest args))]
    (try
      (let [[{:keys [value] :rfp.wire/keys [cursor elisions value-fits?]} reinjected?]
            (with-optimistic-inject build-id #(cljs-eval-wire build-id form))]
        (emit (cond-> {:ok? true :value value}
                cursor          (assoc :rfp.wire/cursor cursor)
                (seq elisions)  (assoc :rfp.wire/elisions elisions)
                (some? value-fits?) (assoc :rfp.wire/value-fits? value-fits?)
                reinjected?     (assoc :reinjected? true))))
      (catch Exception e
        (emit (cond-> {:ok? false
                       :reason (or (:reason (ex-data e)) :eval-error)
                       :message (.getMessage e)
                       :data (dissoc (ex-data e) :reason :reinjected?)}
                (:reinjected? (ex-data e)) (assoc :reinjected? true)))))))
```

Note the catch block now reads `:reinjected?` from `(ex-data e)` instead of a
lexically-bound var — that lexical var no longer exists in scope in the catch
block once the `ensure-injected!` call moves inside `with-optimistic-inject`.
This is the one subtle detail every other call site below needs too.

#### `dispatch-op` (fully worked — has 3 branches, needs care)

```clojure
;; before (relevant excerpt — top of the function)
(defn- dispatch-op [args]
  (ensure-port!)
  (let [[event-str rest-args] (partition-dispatch-args args)]
    (when-not event-str
      (die :missing-event :hint "usage: dispatch '[:ev/id args...]' [--sync] [--trace] [--build=<id>]"))
    (let [build-id    (build-id-from-args rest-args)
          sync?       (has-flag? rest-args "--sync")
          trace?      (has-flag? rest-args "--trace")
          stub-fx-ids (parse-stub-fx-ids rest-args)
          reinjected? (ensure-injected! build-id)
          tag-reinj   (fn [m] (cond-> m reinjected? (assoc :reinjected? true)))]
      (try
        (cond
          trace? ...           ;; uses tag-reinj
          sync?  (emit (tag-reinj (merge {:mode :sync} (cljs-eval-value build-id (dispatch-form :sync event-str stub-fx-ids)))))
          :else  (emit (tag-reinj (merge {:mode :queued} (cljs-eval-value build-id (dispatch-form :queued event-str stub-fx-ids))))))
        (catch Exception e
          (emit (tag-reinj {:ok? false :reason :dispatch-failed :message (.getMessage e) :ex-data (ex-data e)})))))))

;; after
(defn- dispatch-op [args]
  (ensure-port!)
  (let [[event-str rest-args] (partition-dispatch-args args)]
    (when-not event-str
      (die :missing-event :hint "usage: dispatch '[:ev/id args...]' [--sync] [--trace] [--build=<id>]"))
    (let [build-id    (build-id-from-args rest-args)
          sync?       (has-flag? rest-args "--sync")
          trace?      (has-flag? rest-args "--trace")
          stub-fx-ids (parse-stub-fx-ids rest-args)]
      (try
        (cond
          trace?
          (let [opts-form         (if (seq stub-fx-ids) (pr-str {:stub-fx-ids stub-fx-ids}) "{}")
                [start reinjected?] (with-optimistic-inject build-id
                                      #(cljs-eval-value build-id
                                         (runtime-form 'dispatch-and-settle! event-str opts-form)))
                tag-reinj         (fn [m] (cond-> m reinjected? (assoc :reinjected? true)))]
            (cond
              (and (map? start) (:ok? start) (:handle start))
              (let [resolved (await-settle-loop build-id (:handle start))]
                (emit (tag-reinj (merge {:mode :trace} (dissoc start :handle :pending?) resolved))))

              (and (map? start) (= :dispatch-and-settle-unavailable (:reason start)))
              (let [sync-result (cljs-eval-value build-id (dispatch-form :trace-legacy event-str stub-fx-ids))]
                (if (and (map? sync-result) (:ok? sync-result))
                  (do (Thread/sleep legacy-trace-collect-wait-ms)
                      (let [collected (cljs-eval-value build-id
                                        (runtime-form 'collect-after-dispatch (pr-str (:dispatch-id sync-result))))]
                        (emit (tag-reinj (merge {:mode :trace :legacy? true} sync-result collected)))))
                  (emit (tag-reinj (merge {:mode :trace :epoch nil :legacy? true} sync-result)))))

              :else
              (emit (tag-reinj (merge {:mode :trace :epoch nil} start)))))

          sync?
          (let [[result reinjected?] (with-optimistic-inject build-id
                                        #(cljs-eval-value build-id (dispatch-form :sync event-str stub-fx-ids)))]
            (emit (cond-> (merge {:mode :sync} result) reinjected? (assoc :reinjected? true))))

          :else
          (let [[result reinjected?] (with-optimistic-inject build-id
                                        #(cljs-eval-value build-id (dispatch-form :queued event-str stub-fx-ids)))]
            (emit (cond-> (merge {:mode :queued} result) reinjected? (assoc :reinjected? true)))))
        (catch Exception e
          (emit (cond-> {:ok? false :reason :dispatch-failed :message (.getMessage e) :ex-data (dissoc (ex-data e) :reinjected?)}
                  (:reinjected? (ex-data e)) (assoc :reinjected? true))))))))
```

Key point: only the **first** eval of whichever branch executes needs
`with-optimistic-inject` — everything downstream in that branch (the
`await-settle-loop` poll, the legacy `collect-after-dispatch` follow-up call)
is guaranteed to run against an already-confirmed-present runtime, so it uses
`cljs-eval-value` directly, unchanged.

#### The remaining four ops — mechanical template

`trace-recent-op`, `handler-source-op`, `app-summary-op`, `console-tail-op`
all share one shape: `ensure-injected!` up front, one `cljs-eval-value` call,
a `cond->`-tagged catch block with no extra `:data`/`:ex-data` merge. Apply
this transform to each:

```clojure
;; before (shape shared by all four; console-tail-op shown as the example)
(defn- console-tail-op [args]
  (ensure-port!)
  (let [since-id    (flag-value args "--since-id" "0")
        who         (flag-value args "--who" nil)
        build-id    (build-id-from-args args)
        reinjected? (ensure-injected! build-id)
        who-kw      (when who (str ":" (if (str/starts-with? who ":") (subs who 1) who)))
        form        (if who-kw (runtime-form 'console-tail-since since-id who-kw)
                                (runtime-form 'console-tail-since since-id))]
    (try
      (let [result (cljs-eval-value build-id form)]
        (emit (cond-> result reinjected? (assoc :reinjected? true))))
      (catch Exception e
        (emit (cond-> {:ok? false :reason :console-tail-failed :message (.getMessage e)}
                reinjected? (assoc :reinjected? true)))))))

;; after
(defn- console-tail-op [args]
  (ensure-port!)
  (let [since-id (flag-value args "--since-id" "0")
        who      (flag-value args "--who" nil)
        build-id (build-id-from-args args)
        who-kw   (when who (str ":" (if (str/starts-with? who ":") (subs who 1) who)))
        form     (if who-kw (runtime-form 'console-tail-since since-id who-kw)
                             (runtime-form 'console-tail-since since-id))]
    (try
      (let [[result reinjected?] (with-optimistic-inject build-id #(cljs-eval-value build-id form))]
        (emit (cond-> result reinjected? (assoc :reinjected? true))))
      (catch Exception e
        (emit (cond-> {:ok? false :reason :console-tail-failed :message (.getMessage e)}
                (:reinjected? (ex-data e)) (assoc :reinjected? true)))))))
```

Apply the identical transform to:
- `trace-recent-op` — `:reason :trace-failed`, form is `(runtime-form 'epochs-in-last-ms (str ms))`.
- `handler-source-op` — `:reason :handler-source-failed`, form is `(runtime-form 'handler-source (kw kind) (kw id))`.
- `app-summary-op` — `:reason :app-summary-failed`, form is `(runtime-form 'app-summary)`.

### 1.4 Explicitly out of scope: `watch-op` and `tail-build-op`

Both already call `ensure-injected!` exactly once per invocation and reuse a
single `with-current-nrepl-connection` for every poll inside that
invocation — the "pay once, amortize" property this edit is trying to add to
the other six ops is already present here, just implemented differently (a
long-lived connection instead of a lazy optimistic retry). Leave them
unchanged.

Reason to be careful, not just "it'd also work fine": both have existing
tests that pin an exact connection-reuse invariant —
`watch-op-reuses-single-nrepl-connection` and
`tail-build-op-reuses-single-nrepl-connection` (`tests/ops_smoke.bb`) — by
redefining `ops/with-current-nrepl-connection` and asserting
`(is (= 1 @opened))`. `with-optimistic-inject` as designed above calls the
*connectionless* `ensure-injected!` (which internally uses the raw
`cljs-eval`/`with-nrepl-connection` path, not `with-current-nrepl-connection`)
for its disambiguation step. Wiring it into `watch-op`/`tail-build-op` as-is
wouldn't break correctness, but it would open an extra ad hoc connection
outside the one the tests are specifically asserting is the *only* one opened
— a false test failure on a currently-correct implementation detail. Not
worth the churn for a one-time-per-invocation cost that's already amortized
over many polls. If this is revisited later, the connection-aware version
would need `with-optimistic-inject` to accept an optional `conn` and thread
it through to the retry call — sketch only, not designed here.

### 1.5 Test impact

Ran a scan of `tests/ops_smoke.bb` (1753 lines, 57 deftests) for every
`ops/ensure-injected!` reference to check what breaks.

**Unaffected — test `ensure-injected!` directly, not its call sites (11
deftests around lines 948–1017 and 1584–1620):**
`ensure-injected-installs-captures-after-reship`,
`ensure-injected-skips-health-on-fast-path`,
`ensure-injected-skips-health-when-chunk-fails`,
`ensure-injected-dies-when-sentinel-missing-post-inject`,
`ensure-injected-succeeds-when-sentinel-present-post-inject`. This edit does
not touch `ensure-injected!`'s own implementation — only when/whether call
sites invoke it. All pass unchanged.

**Unaffected — call-site tests that stub `ops/ensure-injected!` to
`(fn [_] false)` while testing `dispatch-op`'s branch selection (≈12
deftests, lines 605–905):** `dispatch-trace-legacy-fallback-routes-stubs-through-with-stubs`,
`dispatch-trace-legacy-fallback-no-stubs-still-uses-tagged-dispatch-sync`,
`dispatch-trace-legacy-fallback-rf-ge8-also-unavailable-surfaces-error`,
`dispatch-sync-routes-stubs-through-dispatch-sync-with-stubs`,
`dispatch-sync-no-stubs-uses-tagged-dispatch-sync`,
`dispatch-queued-stubs-and-no-stubs`. None of these assert a call count on
`ensure-injected!` or assert `:reinjected?` in the emitted map — they assert
`:mode`, `:stubbed-fx-ids`, `:legacy?`, and which runtime fn name appears in
the captured eval forms. Since `with-optimistic-inject`'s fast path (the
stubbed `cljs-eval-value` in these tests always returns `{:ok? true ...}`,
never throws) never calls `ensure-injected!` at all, these tests keep passing
regardless of what `ensure-injected!` is stubbed to return — it's just never
invoked. Verify this assumption holds when implementing (run
`npm run test:ops` before and after) rather than taking it on faith.

**Unaffected:** `watch-op-reuses-single-nrepl-connection`,
`tail-build-op-reuses-single-nrepl-connection`,
`await-settle-loop-reuses-single-nrepl-connection`,
`await-settle-loop-times-out-while-pending` — these test functions this edit
doesn't touch (§1.4).

**New tests to add** (mirror the existing `with-redefs` style in
`tests/ops_smoke.bb`):

1. `with-optimistic-inject-skips-ensure-injected-on-success` — stub `f` to
   return a value with no throw; assert `ensure-injected!` is never called
   (redef it to throw if invoked) and the returned tuple is `[value false]`.
2. `with-optimistic-inject-rethrows-browser-not-attached-without-probing` —
   stub `f` to throw `(ex-info "x" {:reason :browser-runtime-not-attached})`;
   assert it propagates unchanged and `ensure-injected!` is never called.
3. `with-optimistic-inject-rethrows-genuine-error-when-sentinel-present` —
   stub `f` to always throw a generic ex-info; stub `ensure-injected!` to
   return `false`; assert the ORIGINAL exception (same message/ex-data)
   propagates, and `f` was called exactly once (no blind retry).
4. `with-optimistic-inject-retries-once-after-reinject` — stub `f` with an
   atom-backed call counter: first call throws, second call succeeds; stub
   `ensure-injected!` to return `true`; assert result is `[value true]` and
   `f` was called exactly twice.
5. `with-optimistic-inject-tags-reinjected-on-second-failure` — stub `f` to
   always throw; stub `ensure-injected!` to return `true`; assert the
   rethrown exception's `ex-data` contains `:reinjected? true`.
6. For each of the 6 rewritten ops, one smoke test confirming the happy path
   makes exactly one `cljs-eval`-family call (add a counter atom in the
   stubbed `cljs-eval-value`/`cljs-eval-wire`, assert `= 1 @calls`) —
   this is the actual regression guard for the perf win itself, since
   nothing today asserts a call *count* on these six ops.

### 1.6 Verifying the failure-shape assumption before hardening on it

`with-optimistic-inject`'s correctness does **not** depend on knowing the
exact wire shape of a "runtime not loaded" failure — it delegates that
entirely to the existing, already-tested `ensure-injected!`/
`runtime-already-injected?` pair rather than pattern-matching an error
string. That's deliberate: it's robust to shadow-cljs version differences in
exactly how a missing-ns reference gets reported.

For extra confidence, or if a future optimization wants to skip even the
`ensure-injected!` sentinel-probe round trip in the common "never injected
this process" sub-case by matching the error text directly, here's the
concrete evidence already in the codebase and how to extend it:

- `tests/ops_smoke.bb` (`parse-surfaces-buried-undeclared-var-as-cljs-eval-error`,
  around line 1482) proves that when shadow-cljs's compiler produces an
  `:undeclared-var` warning (referencing a symbol whose namespace was never
  loaded), it shows up as a **buried warning in `:err` with empty
  `:results`**, and `parse-cljs-eval-response` already classifies this as
  `:reason :cljs-eval-error` with `:err` containing the literal string
  `":undeclared-var"`. This is the "never injected in this shadow-cljs
  process at all" case (case (a)).
- What is **not** yet empirically confirmed: the wire shape for the
  "injected once already, but the browser page was hard-refreshed since"
  case (case (b) — the actually operationally common one, since
  `discover-app.sh` at session start means case (a) rarely occurs in
  practice). Once the page refreshes, shadow's JVM-side analyzer still
  remembers the `re-frame-pair.runtime` ns exists (from the earlier inject in
  this same shadow-cljs process), so the form now *compiles* — but the
  actual JS var doesn't exist in the fresh page, so evaluating it in the
  browser is a runtime failure, not a compile-time one. This could surface
  as `:ex` set, or as an error string inside `:results`, or another shape
  entirely — genuinely unconfirmed without a live test.

To confirm case (b) empirically: this machine has `~/day8/re-frame` and
`~/day8/re-com` cloned as `tests/fixture`'s `:local/root` sibling deps, but
**not** `~/day8/re-frame-10x` or `~/day8/re-frame-debux` (also required by
`tests/fixture/deps.edn`) — clone those two first. Then:

```bash
cd tests/fixture
npm install
npx shadow-cljs watch app   # nREPL :8777, http :8280
```

Open `http://localhost:8280` in a browser, then from another shell:

```bash
cd tests/fixture
../../scripts/discover-app.sh                      # injects; confirm :ok? true
../../scripts/eval-cljs.sh '(re-frame-pair.runtime/session-id)'   # should succeed
```

Hard-refresh the browser tab (Ctrl-Shift-R). Then, **without** calling
`discover-app.sh` again, eval a runtime form directly bypassing
`ensure-injected!` to see the raw failure — easiest via a one-off bb snippet
loaded as a library (same pattern `tests/ops_smoke.bb` uses):

```bash
OPS_NO_AUTO_RUN=1 bb -e '
(load-file "scripts/ops.clj")
(in-ns (quote ops))
(println (try (cljs-eval-wire :app "(re-frame-pair.runtime/session-id)" {:wrap? false})
              (catch Exception e {:message (.getMessage e) :ex-data (ex-data e)})))'
```

Whatever this prints is the case-(b) failure shape. This doesn't change
`with-optimistic-inject`'s design (it's already correct either way), but it's
worth capturing as a code comment or a new `parse-cljs-eval-response` test
fixture once known, and it's the natural first step of implementing this
edit.

### 1.7 Expected impact

`bb` startup measured at ~40ms locally (not the bottleneck). The bottleneck
is the JVM↔browser `cljs-eval` hop, whose absolute latency varies with
browser responsiveness/render load and wasn't benchmarked live against a
running app as part of writing this doc (dash8's shadow-cljs wasn't running
in this session, and this repo's own fixture is missing two sibling deps —
see §1.6). Whatever that per-hop cost is, this edit removes one full hop from
each of `eval-cljs.sh`, `dispatch.sh`, `trace-recent.sh`, `console-tail.sh`,
`handler-source.sh`, `app-summary.sh` in the common case — roughly halving
the wall-clock cost of each of those calls. This compounds because an agent
session typically calls these dozens of times.

---

## 2. Edit 2 — a file-backed activity daemon for high-frequency reads

### 2.1 Why not the streaming design already sketched in the repo

`docs/initial-spec.md` §4.4 and `STATUS.md`'s "Deferred (v0.2+)" list both
already flag this exact problem — "pull-mode... Streaming reduces
round-trips for long watches" — and `docs/initial-spec.md` sketches a
push-mode design using `js/setInterval` inside a long-lived `cljs-eval` that
never returns, relying on nREPL's `:out` channel to forward `prn`'d epochs
back while the eval is still open. That sketch is explicitly marked
"unproven" — the author's own comment in the sketch: `; does this actually
reach nREPL :out? — to be proven`. The core issue: `:out` is only bound
during an *active* eval; ClojureScript has no blocking primitive to park an
eval open while async ticks happen, so it's unclear shadow's nREPL would ever
forward `:out` from a still-open eval whose actual work happens on a
`setInterval` tick after the eval call itself has technically "returned" to
the JS event loop.

This edit sidesteps that unproven mechanism entirely rather than trying to
prove it. It doesn't change the transport at all — it reuses the **already
proven** pull-mode primitive (`epochs-since`, `console-tail-since`, the exact
pattern `watch-op`'s `fetch-form` already uses), just runs it as a
long-lived **background process** instead of inside one ephemeral CLI
invocation, and writes results to a local file instead of printing them to
that invocation's stdout. Reads then become plain file reads — zero browser
round trips, independent of the daemon's poll cadence.

### 2.2 What already exists that this reuses, unchanged

No changes to `scripts/re_frame_pair/runtime/*.cljs` are needed. The three
runtime functions this design polls already exist with exactly the shapes
below (verified by reading the source, not guessed):

```clojure
;; scripts/re_frame_pair/runtime/epochs.cljs
(defn latest-epoch-id [] ...)          ;; => int or nil
(defn epochs-since [id] ...)           ;; => {:epochs [...] :id-aged-out? bool}

;; scripts/re_frame_pair/runtime/console.cljs
(defn console-tail-since
  ([since-id] ...)
  ([since-id who] ...))                ;; => {:ok? true :entries [...] :next-id int :max-size int}
```

`watch-op` already builds a combined one-round-trip poll form
(`fetch-form` closure inside `watch-op`, ops.clj ~line 1539) that fetches
`epochs-since` + `latest-epoch-id` together. The daemon's poll form (below)
extends that same pattern to also pull `console-tail-since` in the same
round trip.

### 2.3 New subcommand: `daemon`

Add to the dispatcher in `-main`:

```clojure
"daemon" (daemon-op (rest args))
```

and add `"daemon"` to the `:valid` set in the `unknown-subcommand` die call.

`daemon-op` dispatches on its first positional arg (`start`, and the shell
wrapper handles `stop`/`status` without ever calling into bb for those — see
§2.5, no JVM boot needed to check a PID file):

```clojure
(def ^:private default-daemon-poll-ms 250)
(def ^:private default-daemon-hard-ms (* 4 60 60 1000)) ;; 4h — "never silently runs forever," same invariant as watch-op's hard-cap

(defn- daemon-out-dir [args]
  (io/file (flag-value args "--out-dir" ".re-frame-pair")))

(defn- daemon-poll-form
  "One combined eval per tick: epochs since `last-epoch-id` + console
   entries since `last-console-id`, in a single round trip — same
   shape as watch-op's `fetch-form`, extended with the console half."
  [last-epoch-id last-console-id]
  (format
   "(let [e (re-frame-pair.runtime/epochs-since %s)
          c (re-frame-pair.runtime/console-tail-since %s)]
      {:epochs           (:epochs e)
       :id-aged-out?     (:id-aged-out? e)
       :head-epoch-id    (re-frame-pair.runtime/latest-epoch-id)
       :console          (:entries c)
       :next-console-id  (:next-id c)})"
   (pr-str last-epoch-id) (pr-str last-console-id)))

(defn- append-edn-lines!
  "Append each item in `items` as one pr-str'd EDN line to `file`,
   creating parent dirs on first use. No-op on an empty coll — avoids
   an open+flush+close on every quiet poll tick."
  [file items]
  (when (seq items)
    (.mkdirs (.getParentFile file))
    (with-open [w (io/writer file :append true)]
      (doseq [item items]
        (.write w (pr-str item))
        (.write w "\n")))))

(defn- daemon-start [args]
  (ensure-port!)
  (let [build-id     (build-id-from-args args)
        out-dir      (daemon-out-dir args)
        poll-ms      (Long/parseLong (flag-value args "--poll-ms" (str default-daemon-poll-ms)))
        hard-ms      (Long/parseLong (flag-value args "--hard-ms" (str default-daemon-hard-ms)))
        epochs-file  (io/file out-dir "epochs.edn")
        console-file (io/file out-dir "console.edn")
        log-file     (io/file out-dir "daemon.log")]
    (ensure-injected! build-id) ;; one-time cost for a long-lived process — no need for with-optimistic-inject here
    (with-current-nrepl-connection
      (fn [conn]
        (let [start        (System/currentTimeMillis)
              last-epoch   (atom (cljs-eval-value conn build-id (runtime-form 'latest-epoch-id)))
              last-console (atom 0)
              aged-warned? (atom false)]
          (append-edn-lines! log-file [{:event :daemon-started :t start :build-id build-id}])
          (loop []
            (Thread/sleep poll-ms)
            (let [result (try (cljs-eval-value conn build-id (daemon-poll-form @last-epoch @last-console))
                              (catch Exception e {:poll-error (.getMessage e)}))]
              (when-let [err (:poll-error result)]
                (append-edn-lines! log-file [{:event :poll-error :t (System/currentTimeMillis) :message err}]))
              (when-not (:poll-error result)
                (append-edn-lines! epochs-file (:epochs result))
                (append-edn-lines! console-file (:console result))
                (when (:head-epoch-id result) (reset! last-epoch (:head-epoch-id result)))
                (when (:next-console-id result) (reset! last-console (:next-console-id result)))
                (when (and (:id-aged-out? result) (not @aged-warned?))
                  (reset! aged-warned? true)
                  (append-edn-lines! log-file [{:event :id-aged-out :t (System/currentTimeMillis)
                                                 :note "epoch id fell off 10x's ring buffer between polls — some epochs may be missing from epochs.edn"}])))
              (if (>= (- (System/currentTimeMillis) start) hard-ms)
                (append-edn-lines! log-file [{:event :daemon-stopped :reason :hard-cap :t (System/currentTimeMillis)}])
                (recur)))))))))

(defn- daemon-op [args]
  (case (first args)
    "start" (daemon-start (rest args))
    (die :unknown-daemon-action :arg (first args) :valid #{"start"})))
```

Notes on this sketch:

- `append-edn-lines!` opens/closes the file each tick. That's a local
  filesystem op (microseconds), nothing like the browser round trip — do
  **not** try to hold file writers open across the daemon's lifetime purely
  for micro-efficiency; per-tick open/close is simpler, survives the process
  being killed mid-write without a corrupt/partial file from a buffered
  writer, and is what's specified here. Only writes when there's new content
  (`when (seq items)` inside `append-edn-lines!`), so quiet ticks cost zero
  disk I/O.
- Files are **EDN**, one map per line (not JSON) — consistent with "all ops
  return edn on stdout" elsewhere in this tool, and avoids adding a JSON
  round-trip dependency for a feature that doesn't need one (Cheshire is
  already a dep for the unrelated version-check feature, but there's no
  reason to reach for it here).
- The daemon does not itself daemonize (fork/detach) — that's the shell
  wrapper's job (`nohup ... & disown`), matching how every other op here is
  a foreground bb process. `daemon-start` just runs an infinite loop in the
  current process until `hard-ms` elapses or the process receives a signal
  (SIGTERM from `daemon.sh stop`, see below).

### 2.4 Combined-poll design note: what this does *not* replace

`watch-epochs.sh`'s rich predicate engine (`--event-id-prefix`, `--effects`,
`--timing-ms`, `--touches-path`, `--sub-ran`, `--render`,
`epoch-matches?`) is **not** reproduced here. The daemon captures everything,
unfiltered, into the file — filtering happens client-side against the file
(`grep`, or a small `jq`-style pass over EDN lines) for simple substring
matches, but the daemon is not a replacement for `watch-epochs.sh` when a
complex predicate query is actually needed. Document this trade-off plainly
wherever this feature is described in `SKILL.md` — don't oversell the daemon
as a superset of `watch-epochs.sh`.

### 2.5 New shell wrappers

`scripts/daemon.sh` — a single script with a subcommand argument, following
the existing `scripts/*.sh` header/help conventions:

```bash
#!/usr/bin/env bash
# daemon.sh — background poller that continuously appends new epochs and
# console entries to local files, so repeated reads (console-tail-ish /
# trace-recent-ish queries during a long debugging session) don't each pay
# a browser round trip. Read .re-frame-pair/epochs.edn / console.edn
# directly once the daemon is running.
#
# Usage:
#   scripts/daemon.sh start [--build=:app] [--poll-ms 250] [--out-dir .re-frame-pair]
#   scripts/daemon.sh stop  [--out-dir .re-frame-pair]
#   scripts/daemon.sh status [--out-dir .re-frame-pair]
set -euo pipefail
case "${1:-}" in --help|-h) sed -n '2,/^$/p' "$0" | sed 's/^# \?//'; exit 0 ;; esac
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v bb >/dev/null 2>&1 || {
  echo '{:ok? false :reason :babashka-missing :hint "Install babashka: https://babashka.org"}' >&2
  exit 1
}

action="${1:-}"; shift || true

out_dir=".re-frame-pair"
args=("$@")
for ((i=0; i<${#args[@]}; i++)); do
  if [[ "${args[$i]}" == --out-dir=* ]]; then out_dir="${args[$i]#--out-dir=}"; fi
done
pid_file="$out_dir/daemon.pid"

case "$action" in
  start)
    if [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
      echo "{:ok? false :reason :already-running :pid $(cat "$pid_file")}"
      exit 1
    fi
    mkdir -p "$out_dir"
    nohup bb "$HERE/ops.clj" daemon start "$@" > "$out_dir/daemon-stdout.log" 2>&1 &
    disown
    echo $! > "$pid_file"
    echo "{:ok? true :pid $! :out-dir \"$out_dir\" :epochs-file \"$out_dir/epochs.edn\" :console-file \"$out_dir/console.edn\"}"
    ;;
  stop)
    if [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
      kill "$(cat "$pid_file")"
      rm -f "$pid_file"
      echo '{:ok? true :stopped? true}'
    else
      echo '{:ok? false :reason :not-running}'
      exit 1
    fi
    ;;
  status)
    if [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
      echo "{:ok? true :running? true :pid $(cat "$pid_file")}"
    else
      echo '{:ok? true :running? false}'
    fi
    ;;
  *)
    echo '{:ok? false :reason :unknown-action :valid #{"start" "stop" "status"}}' >&2
    exit 1
    ;;
esac
```

`stop`/`status` are pure bash (PID-file + `kill -0`) — no `bb`/JVM boot
needed for either, which matters since these are meant to be near-instant
housekeeping calls.

### 2.6 Lifecycle, safety, and trade-offs to document

- **Double-start guard.** `daemon.sh start` refuses to spawn a second daemon
  over a live PID file (checked via `kill -0`) — two daemons writing to the
  same files would interleave/duplicate entries.
- **Hard cap.** `default-daemon-hard-ms` (4h) mirrors the "never silently
  runs forever" invariant `watch-op` already has (`default-watch-hard-cap-ms`,
  300000ms) — sized much larger here since a daemon is meant to outlive many
  individual CLI calls across a whole session, but still bounded so an
  abandoned daemon from a stale session doesn't run indefinitely.
- **Ring-buffer aging is still possible.** If `poll-ms` is slow relative to
  app activity, the browser-side console/epoch ring buffers (500 entries for
  console, 10x's own cap for epochs) can age out data between polls. The
  daemon surfaces this the same way `watch-op` already does for epochs
  (`:id-aged-out?` → a log line), but there's no equivalent aged-out signal
  from `console-tail-since` today — check whether one should be added to
  `console.cljs` as a small follow-up, or document the gap.
- **Unbounded file growth.** `epochs.edn`/`console.edn` grow for as long as
  the daemon runs; no rotation/truncation in this design. Fine for a single
  dev session; call this out as a known limitation rather than solving it
  here (rotation is a stretch goal, not required for v1).
- **`.re-frame-pair/` needs gitignoring in the *host* app**, not this repo —
  it's created in whatever directory the agent runs `scripts/*.sh` from
  (typically the consuming app's `client/` dir), not inside
  `re-frame-pair` itself. Document this in whatever `SKILL.md` section ends
  up describing the daemon, and consider having `daemon.sh start` print a
  one-time hint if `.re-frame-pair/` isn't already gitignored in the current
  repo.

### 2.7 Agent-facing usage recipe (for a later `SKILL.md` update — not part of this doc's diff)

At session start, after `discover-app.sh`: if the session is expected to
involve repeated polling (an extended debugging/watch session), run
`scripts/daemon.sh start` once. From then on, read `.re-frame-pair/console.edn`
/ `.re-frame-pair/epochs.edn` directly (tail, grep, or the `Read` tool) instead
of re-invoking `console-tail.sh`/`trace-recent.sh` — zero browser round trips
per read. Call `scripts/daemon.sh stop` when done. `console-tail.sh` /
`trace-recent.sh` remain the correct choice for one-off queries when the
daemon isn't running, and `watch-epochs.sh` remains the correct choice for
predicate-filtered live watching (§2.4).

### 2.8 Test impact

Purely additive — new subcommand, new files, nothing existing changes. No
existing `tests/ops_smoke.bb` deftest touches `daemon-op`/`daemon-start`, so
none can regress.

New tests to add, following the existing style (e.g.
`watch-op-reuses-single-nrepl-connection`'s `with-redefs` pattern, and using
a real temp dir — `(io/file (System/getProperty "java.io.tmpdir") (str "rfp-test-" (random-uuid)))`
or equivalent, cleaned up in a `finally`):

1. `daemon-poll-form-fetches-epochs-and-console-in-one-call` — stub
   `cljs-eval-value` to assert the poll form is a single string containing
   both `epochs-since` and `console-tail-since`.
2. `daemon-appends-new-epochs-and-console-entries-to-files` — redef
   `with-current-nrepl-connection`/`cljs-eval-value` to return two
   canned poll results across two ticks (use `hard-ms 0`-ish tiny loop or a
   poll-count-based stub that throws/exits after N ticks to terminate the
   `loop`/`recur` — mirror how `tail-build-op`'s tests bound their poll loops
   via a finite canned `values` atom), then assert `epochs.edn`/`console.edn`
   contain the expected EDN lines.
3. `daemon-append-edn-lines-is-a-noop-on-empty-coll` — assert no file is
   created/touched when `items` is empty.
4. `daemon-op-rejects-unknown-action` — assert `"bogus"` dies with
   `:unknown-daemon-action`.
5. A bats/bash-level test (or manual smoke step, since `tests/shim/` isn't
   built yet per `docs/TESTING.md` §2) for `daemon.sh start` / `status` /
   `stop`'s PID-file handling — this is bash logic `ops_smoke.bb` can't
   exercise.

---

## 3. Explicitly out of scope for both edits

- Changing `watch-op`'s or `tail-build-op`'s internals (§1.4).
- Reproducing `watch-epochs.sh`'s predicate engine for file-tailed reads
  (§2.4).
- File rotation/truncation for the daemon's output files (§2.6).
- Solving the streaming-via-`:out` "unproven" question from
  `docs/initial-spec.md` §4.4 — this doc proposes a different mechanism
  entirely rather than resolving that one. If someone later proves the
  `:out`-forwarding mechanism actually works, it could subsume Edit 2, but
  that's a separate, harder problem.
- Adding an aged-out signal to `console-tail-since` (flagged as a possible
  follow-up in §2.6, not designed here).

---

## 4. Implementation checklist

1. Clone `re-frame-10x` and `re-frame-debux` as siblings of this repo (only
   `re-frame` and `re-com` are present on this machine today) if doing the
   optional live verification in §1.6.
2. (Optional but recommended) Run the §1.6 recipe to confirm the case-(b)
   failure shape; note the finding as a code comment near
   `with-optimistic-inject` regardless of outcome, since the design doesn't
   depend on it but future readers will wonder.
3. Implement `with-optimistic-inject` in `scripts/ops.clj` (§1.2).
4. Rewrite `eval-op`, `dispatch-op`, `trace-recent-op`, `console-tail-op`,
   `handler-source-op`, `app-summary-op` per §1.3. Leave `watch-op` /
   `tail-build-op` untouched (§1.4).
5. `npm run test:ops` — confirm the existing 57 deftests still pass with
   zero changes, per the analysis in §1.5.
6. Add the 6 new `with-optimistic-inject` unit tests + the 6 per-op
   call-count smoke tests from §1.5.
7. Implement `daemon-op` / `daemon-start` / `append-edn-lines!` /
   `daemon-poll-form` in `scripts/ops.clj`, wire `"daemon"` into `-main`
   and the `unknown-subcommand` `:valid` set (§2.3).
8. Add `scripts/daemon.sh` (§2.5); `chmod +x`.
9. Add the daemon tests from §2.8.
10. `npm test` (full suite: runtime CLJS tests + `ops_smoke.bb` +
    `skill_recipe_smoke.bb`) — should be unaffected by either edit but
    confirm.
11. Manually verify against `tests/fixture/` (once its sibling deps are
    available) or against a real host app: `daemon.sh start`, dispatch a
    few events / cause some console output, confirm
    `.re-frame-pair/epochs.edn` and `console.edn` fill in, `daemon.sh
    status`, `daemon.sh stop`, confirm the process actually exits
    (`ps`/`kill -0`).
12. Update `SKILL.md` / `docs/skill/operations.md` with the daemon recipe
    from §2.7 (separate concern from the `ops.clj` change — a docs-only
    follow-up once the mechanism is proven live).
13. Update `CHANGELOG.md`'s `[Unreleased]` section once this ships (skipped
    in this doc's own diff, per the changelog's own scope note: "Internal
    refactors... are kept out unless they change observable behaviour" —
    the doc itself doesn't ship behavior, the implementation PR should carry
    the changelog entry).

---

## 5. Open questions

1. What exactly does a case-(b) (post-refresh) `cljs-eval` failure look like
   on the wire? Unconfirmed — see §1.6. Doesn't block implementation since
   `with-optimistic-inject` doesn't depend on knowing this, but worth
   nailing down for a good code comment and possibly a faster fast-path
   later.
2. Should `console-tail-since` gain an `:id-aged-out?`-equivalent signal so
   the daemon (and `console-tail.sh` itself) can detect lost console entries
   the same way epoch tracking already can? Small, separate change to
   `console.cljs` if wanted.
3. Is a 4-hour daemon hard-cap the right default? Picked to comfortably
   outlast a normal dev session without being "forever" — no strong evidence
   either way, easy to tune via `--hard-ms`.
4. Should `daemon.sh start` auto-run `discover-app.sh`-equivalent logic if
   the port isn't found, or just fail with the existing `:nrepl-port-not-found`
   shape like every other op? Leaning toward the latter (consistency) —
   not settled here.
