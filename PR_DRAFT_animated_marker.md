# Add optional stable `Marker.id` + Android marker rotation (animated markers)

## Problem

Markers are keyed in the Compose tree (and native caches) by `Marker.getId()`, which is
`"marker_${hashCode()}"`. Because `Marker` is a `data class`, its `hashCode()` includes
`coordinates`, so a marker that changes position gets a **new id every move**. The
`key(marker.getId(), …)` block around each marker is therefore disposed and recreated on
every position change — a marker that moves every frame (e.g. a live "driving puck" / vehicle
follower) is torn down and rebuilt continuously, which flickers.

The Android renderer already has a `LaunchedEffect(marker.coordinates)` that repositions
`MarkerState.position` in place, but it never survives because the surrounding `key(...)`
churns.

## Change

**1. Optional stable `Marker.id` (commonMain).** A new `val id: String? = null` on `Marker`.
`getId()` uses it when present, else falls back to the previous `hashCode` id:

```kotlin
public fun Marker.getId(): String = id?.let { "marker_$it" } ?: "marker_${hashCode()}"
```

When a caller sets a stable `id`, a marker that only changes `coordinates` keeps the same
identity, so it is **repositioned in place** (via the existing `LaunchedEffect`) instead of
being disposed/recreated. `id == null` is 100% backward-compatible.

On Android the outer key no longer needs `contentId` (it was redundant with the hashCode id);
instead `contentId` is passed as the `MarkerComposable` snapshot key, so the cached bitmap
**re-renders in place** when the visual content changes (e.g. a puck's dim state) without
changing marker identity.

**2. Android marker `rotation` + `flat` (AndroidMarkerOptions).** Forwarded to the underlying
maps-compose `Marker`/`MarkerComposable`. Combined with a stable `id`, a marker can rotate in
place (a puck turning to its GPS heading) from a single bitmap instead of baking every heading
into a separate cached bitmap. `flat = true` makes rotation relative to north and respects map
tilt.

## Compatibility

- Both additions are optional with defaults that reproduce today's behaviour exactly.
- No public API removed or changed in signature (new fields appended).
- Verified `:kmp-maps:core:compileReleaseKotlinAndroid` on `main`.

## Notes

The iOS/JVM renderers already route through `getId()`, so they inherit the stable identity.
Rotation is wired for Android only in this PR; happy to extend to iOS in a follow-up.
