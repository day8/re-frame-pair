# Post-Mortems

Use this when the user is stuck in a broken state, cannot remember the exact reproduction path, or asks how the app got into its current state.

The value is that the user does not need to describe every click and state transition. The epoch trail records what actually happened.

## Procedure

1. Ask for the symptom in observable terms: "the save button is grey", "the dashboard is empty", "the total is wrong".
2. Resolve any UI reference to source with `dom/source-at` when possible.
3. Identify the app-db key or subscription that governs the symptom. If unclear, find the recent render for the component and walk its subscription inputs.
4. Search recent epochs for the transition that introduced the current bad value:
   ```bash
   scripts/eval-cljs.sh '(re-frame-pair.runtime/find-where
                           (fn [e]
                             (= :expired
                                (get-in (:only-after (:app-db/diff e))
                                        [:auth-state]))))'
   ```
5. Report the culprit epoch: event vector, app-db diff, effects fired, parent/child dispatch ids, renders, and source call sites.
6. If the culprit event is a child dispatch, follow `:parent-dispatch-id` upstream with `trace/epoch`.
7. If the state drifted over many events, use `find-all-where` and narrate the few most relevant transitions.
8. Propose a fix only after the causal path is clear.

## Reporting Shape

Keep the report concise:

- "The bad value first appeared in epoch X."
- "The event was `[...]`, dispatched from file:line."
- "It changed `[:path]` from A to B."
- "It fired these effects."
- "That invalidated these subscriptions and rendered these components."
- "The root cause appears to be..."

## Retention Caveat

The epoch buffer is a bounded ring. If the relevant event aged out, say so explicitly:

> I can see the last N events, but the transition happened before that. Reproduce from a known state and I can watch the epoch trail.

Do not pretend the trail proves absence when retention may be the limit.
