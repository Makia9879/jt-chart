# ADR-0001: Crosshair time sync across price and JT panes

## Status

Accepted.

## Context

The Android chart renders the price K-line pane and JT Regime pane as two separate Lightweight Charts instances. Their visible logical ranges are already synchronized, but crosshair movement was owned by the price pane only. A touch inside the lower JT pane could therefore show a different active moment, or no matching moment in the price pane.

The user-facing requirement is: the upper K-line area and lower JT area must use the same touched time, and the vertical time line must visually continue through both areas.

## Decision

Keep one active crosshair time and derive both panes from that `time`.

- When the price pane crosshair moves, set the JT pane crosshair with the oscillator value for the same `time`.
- When the JT pane crosshair moves, set the price pane crosshair with the candle close for the same `time`.
- If the touched time is missing from the paired pane, clear the paired crosshair instead of guessing a nearby bar.
- Keep the visible logical range synchronization between panes so the same `time` resolves to the same x-coordinate in both charts.
- Redraw the price overlay when either pane changes the viewport or interaction state.

## Consequences

The time line remains anchored to an exact candle/JT sample rather than an interpolated pixel. Any future pane added to the chart should subscribe to the same active-time model instead of keeping an independent crosshair.
