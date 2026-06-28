---
version: alpha
name: Guacamaya-mobile-design-system
description: >
  Design system for the Guacamaya emergency-mesh mobile app — a connectionless SOS network for
  disaster scenarios (earthquakes, Venezuela), Android-first, built for low-end phones used under
  stress. Anchored on near-pure black canvas with electric yellow as the brand voltage (inherited
  from the ClickHouse design language: yellow + black, dark-only, confident sans, hierarchy by size
  and weight). Underneath the yellow+black brand sits a strict EMERGENCY-SEMANTIC color layer where
  red=critical SOS, amber=unconfirmed/warning, green=safe, blue=official/verified — every semantic
  also carries an icon and text, never color alone. Tokens are platform-agnostic and map 1:1 to both
  React Native (the build surface) and Jetpack Compose (the native port target).

# ── Brand voltage (inherited from ClickHouse, unchanged) ──────────────────────
colors:
  # Brand / primary action
  primary: "#faff69"            # electric yellow — brand voltage, primary CTAs, active radio state
  primary-active: "#e6eb52"     # pressed / darker variant
  primary-disabled: "#3a3a1f"   # desaturated dark-yellow on dark canvas
  on-primary: "#0a0a0a"         # black text/icon on yellow

  # Text on dark
  ink: "#ffffff"                # headlines, primary text, critical readouts
  body: "#cccccc"               # default running text
  body-strong: "#e6e6e6"        # emphasized paragraphs
  muted: "#888888"              # captions, metadata, timestamps
  muted-soft: "#5a5a5a"         # tertiary / fine print / disabled text

  # Surfaces (near-black, barely-separated panels — engineering-grade dim, no shadows)
  canvas: "#0a0a0a"             # default screen floor
  surface-soft: "#121212"       # section tints, list zebra, bottom-nav bar
  surface-card: "#1a1a1a"       # cards, rows, inputs, sheets
  surface-elevated: "#242424"   # nested cards, pressed rows, menus
  hairline: "#2a2a2a"           # 1px borders / dividers
  hairline-strong: "#3a3a3a"    # input underlines, emphasis dividers

  # ── EMERGENCY-SEMANTIC layer (the safety contract — NOT brand decoration) ──
  danger: "#ff453a"             # CRITICAL SOS / distress / destructive. Reserved for life-safety.
  danger-soft: "#3a1512"        # danger-tinted surface (critical card fill on dark)
  warning: "#ff9f0a"            # UNCONFIRMED community report / caution. Distinct from brand yellow.
  warning-soft: "#3a2710"       # warning-tinted surface
  success: "#30d158"            # SAFE / "estoy bien" / delivered / relayed
  success-soft: "#0e2e18"       # success-tinted surface
  info: "#0a84ff"               # OFFICIAL / verified / informational
  info-soft: "#0a1f3a"          # info-tinted surface
  on-semantic: "#ffffff"        # text/icon on any saturated semantic fill

  # ── SOS-type accents (8 types; color is SECONDARY to the icon — see Crisis UX) ──
  sos-medical: "#2dd4bf"        # 0 médica   (teal + cross glyph)
  sos-distress: "#ff453a"       # 1 auxilio  (red + SOS glyph) — shares danger hue intentionally
  sos-food: "#ff9f0a"           # 2 comida   (amber + utensils glyph)
  sos-water: "#0a84ff"          # 3 agua     (blue + droplet glyph)
  sos-shelter: "#bf5af2"        # 4 refugio  (purple + home glyph)
  sos-fire: "#ff6b00"           # 5 fuego    (orange + flame glyph)
  sos-violence: "#ff2d92"       # 6 violencia(magenta + shield-alert glyph)
  sos-other: "#8e8e93"          # 7 otro     (gray + dots glyph)

# ── Type scale (mobile-tuned; ClickHouse's huge marketing display sizes are NOT used) ─────────────
typography:
  display-lg:        { fontFamily: "Inter", fontSize: 34px, fontWeight: 700, lineHeight: 1.1,  letterSpacing: -1px }     # rare hero number / big SOS count
  display-md:        { fontFamily: "Inter", fontSize: 28px, fontWeight: 700, lineHeight: 1.15, letterSpacing: -0.8px }   # screen titles, empty-state heads
  display-sm:        { fontFamily: "Inter", fontSize: 24px, fontWeight: 700, lineHeight: 1.2,  letterSpacing: -0.5px }   # section heads, sheet titles
  title-lg:          { fontFamily: "Inter", fontSize: 20px, fontWeight: 700, lineHeight: 1.3,  letterSpacing: -0.3px }   # top-app-bar title, channel name
  title-md:          { fontFamily: "Inter", fontSize: 17px, fontWeight: 600, lineHeight: 1.35, letterSpacing: 0 }        # card titles, list primary
  title-sm:          { fontFamily: "Inter", fontSize: 15px, fontWeight: 600, lineHeight: 1.4,  letterSpacing: 0 }        # small labels, row titles
  stat-display:      { fontFamily: "Inter", fontSize: 40px, fontWeight: 700, lineHeight: 1.0,  letterSpacing: -1px }     # counts (saltos, nodos) — ALWAYS yellow or semantic
  body-lg:           { fontFamily: "Inter", fontSize: 16px, fontWeight: 400, lineHeight: 1.5,  letterSpacing: 0 }        # report body, comfortable reading
  body-md:           { fontFamily: "Inter", fontSize: 15px, fontWeight: 400, lineHeight: 1.5,  letterSpacing: 0 }        # default running text
  body-sm:           { fontFamily: "Inter", fontSize: 13px, fontWeight: 400, lineHeight: 1.45, letterSpacing: 0 }        # secondary text
  caption:           { fontFamily: "Inter", fontSize: 12px, fontWeight: 500, lineHeight: 1.4,  letterSpacing: 0 }        # timestamps, metadata
  overline:          { fontFamily: "Inter", fontSize: 11px, fontWeight: 700, lineHeight: 1.4,  letterSpacing: 1.2px }    # SECTION LABELS / badges (uppercase)
  button:            { fontFamily: "Inter", fontSize: 15px, fontWeight: 600, lineHeight: 1.0,  letterSpacing: 0 }        # button labels
  button-sos:        { fontFamily: "Inter", fontSize: 20px, fontWeight: 700, lineHeight: 1.0,  letterSpacing: 0.5px }    # the critical SOS action label
  nav-tab:           { fontFamily: "Inter", fontSize: 11px, fontWeight: 600, lineHeight: 1.2,  letterSpacing: 0 }        # bottom-nav labels
  mono:              { fontFamily: "JetBrains Mono", fontSize: 13px, fontWeight: 400, lineHeight: 1.45, letterSpacing: 0 } # node_id, coords, frame counts, technical readouts

rounded:
  xs: 4px
  sm: 8px
  md: 12px        # buttons, inputs, chips
  lg: 16px        # cards, sheets
  xl: 24px        # large surfaces, SOS button
  pill: 9999px    # badges, toggles, SOS-type chips
  full: 9999px    # avatars, circular icon buttons, map pins

spacing:
  xxs: 4px
  xs: 8px
  sm: 12px
  md: 16px        # default screen gutter
  lg: 20px
  xl: 24px
  xxl: 32px
  section: 40px   # between major mobile blocks (mobile rhythm, NOT ClickHouse's 96px)

components:
  # — Actions —
  button-sos-critical:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.on-semantic}"
    typography: "{typography.button-sos}"
    rounded: "{rounded.xl}"
    minHeight: 72px
    padding: 20px 24px
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button}"
    rounded: "{rounded.md}"
    minHeight: 48px
    padding: 14px 20px
  button-primary-active:
    backgroundColor: "{colors.primary-active}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.md}"
  button-primary-disabled:
    backgroundColor: "{colors.primary-disabled}"
    textColor: "{colors.muted}"
    rounded: "{rounded.md}"
  button-secondary:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.ink}"
    typography: "{typography.button}"
    rounded: "{rounded.md}"
    minHeight: 48px
    padding: 14px 20px
    border: "1px {colors.hairline}"
  button-text-link:
    backgroundColor: transparent
    textColor: "{colors.primary}"
    typography: "{typography.button}"
  button-icon:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.ink}"
    rounded: "{rounded.full}"
    size: 44px
  # — Radio / mesh status —
  toggle-radio:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.body}"
    trackOn: "{colors.primary}"
    trackOff: "{colors.hairline-strong}"
    rounded: "{rounded.pill}"
    minHeight: 56px
    padding: 12px 16px
  status-banner:
    backgroundColor: "{colors.surface-soft}"
    textColor: "{colors.body}"
    typography: "{typography.title-sm}"
    rounded: "{rounded.md}"
    padding: 12px 16px
    border: "1px {colors.hairline}"
  battery-indicator:
    backgroundColor: transparent
    typography: "{typography.caption}"
    # fill color steps by bucket: 0 danger · 1 warning · 2 primary · 3 success
  # — Navigation / chrome —
  top-app-bar:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.title-lg}"
    height: 56px
  bottom-nav:
    backgroundColor: "{colors.surface-soft}"
    textColorInactive: "{colors.muted}"
    textColorActive: "{colors.primary}"
    typography: "{typography.nav-tab}"
    height: 64px
    border-top: "1px {colors.hairline}"
  fab-sos:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.on-semantic}"
    rounded: "{rounded.full}"
    size: 64px
  # — Content surfaces —
  channel-card:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.ink}"
    typography: "{typography.title-md}"
    rounded: "{rounded.lg}"
    padding: 16px
    border: "1px {colors.hairline}"
  record-card:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.body}"
    typography: "{typography.body-md}"
    rounded: "{rounded.lg}"
    padding: 16px
    border: "1px {colors.hairline}"
  record-card-critical:
    backgroundColor: "{colors.danger-soft}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: 16px
    border: "1px {colors.danger}"
  identity-card:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.body}"
    typography: "{typography.mono}"
    rounded: "{rounded.lg}"
    padding: 16px
  # — Trust & semantics —
  trust-badge-verified:
    backgroundColor: "{colors.info-soft}"
    textColor: "{colors.info}"
    typography: "{typography.overline}"
    rounded: "{rounded.pill}"
    padding: 4px 10px
    # icon: shield-check · label: "OFICIAL"
  trust-badge-unconfirmed:
    backgroundColor: "{colors.warning-soft}"
    textColor: "{colors.warning}"
    typography: "{typography.overline}"
    rounded: "{rounded.pill}"
    padding: 4px 10px
    # icon: alert-triangle · label: "NO CONFIRMADO"
  badge-critical:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.on-semantic}"
    typography: "{typography.overline}"
    rounded: "{rounded.pill}"
    padding: 4px 10px
    # icon: alert-octagon · label: "CRÍTICO"
  sos-type-chip:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.ink}"
    typography: "{typography.title-sm}"
    rounded: "{rounded.pill}"
    minHeight: 44px
    padding: 8px 14px
    # leading icon tinted by the matching {colors.sos-*}
  sos-type-chip-selected:
    backgroundColor: "{colors.surface-elevated}"
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
    border: "2px {colors.primary}"
  badge-pill:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.body}"
    typography: "{typography.caption}"
    rounded: "{rounded.pill}"
    padding: 4px 10px
  badge-yellow:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.overline}"
    rounded: "{rounded.pill}"
    padding: 4px 10px
  # — Inputs —
  text-input:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.ink}"
    placeholderColor: "{colors.muted}"
    typography: "{typography.body-lg}"
    rounded: "{rounded.md}"
    minHeight: 48px
    padding: 12px 14px
    border: "1px {colors.hairline-strong}"
  text-input-focused:
    border: "2px {colors.primary}"
  # — Map —
  map-pin:
    rounded: "{rounded.full}"
    size: 36px
    # fill by {colors.sos-*}; white glyph; 2px {colors.canvas} ring for separation
  map-pin-critical:
    rounded: "{rounded.full}"
    size: 40px
    border: "2px {colors.danger}"
  map-cluster:
    backgroundColor: "{colors.surface-elevated}"
    textColor: "{colors.ink}"
    typography: "{typography.title-sm}"
    rounded: "{rounded.full}"
  # — Feedback —
  snackbar:
    backgroundColor: "{colors.surface-elevated}"
    textColor: "{colors.ink}"
    typography: "{typography.body-md}"
    rounded: "{rounded.md}"
    padding: 12px 16px
  empty-state:
    backgroundColor: transparent
    textColor: "{colors.muted}"
    typography: "{typography.body-md}"
---

## Overview

Guacamaya's mobile surface inherits the **ClickHouse brand voltage verbatim**: a **near-pure black
canvas** (`{colors.canvas}` — #0a0a0a) with **electric yellow** (`{colors.primary}` — #faff69) as the
singular brand color, white Inter typography at confident weights, hierarchy built on size and weight
rather than family contrast, and surface cards that are *barely* lighter than canvas (depth from subtle
contrast, never drop shadows). It is **dark-only**.

But Guacamaya is not a marketing site — it is a **life-safety tool** used during disasters on **low-end
Android phones**, possibly outdoors in daylight, possibly one-handed, possibly by a frightened person on
a cracked screen. That reframes the system in three ways the ClickHouse doc never had to consider:

1. **Yellow is the brand and the primary action — but it is never danger.** Beneath the yellow+black
   brand sits a strict **emergency-semantic layer**: `{colors.danger}` red = critical SOS, `{colors.warning}`
   amber = unconfirmed community report, `{colors.success}` green = safe / "estoy bien", `{colors.info}`
   blue = official / verified. These four colors carry *meaning*, not style, and are spent sparingly.
2. **Color is never the only signal.** Every semantic state and every SOS type pairs its color with an
   **icon and a text label**. A report is "✅ OFICIAL" or "⚠️ NO CONFIRMADO" in words, not just hue —
   this defends against colorblindness, glare, and cheap panels with poor color rendering.
3. **Touch targets are generous and forgiving.** The critical SOS action is a ≥72dp block; standard
   buttons ≥48dp; nothing interactive below 44dp. Calm precision, but bigger.

The product's job is to move three things with zero ambiguity under stress: **what is happening**
(channels: `alertas`, `refugios`, `ayuda-medica`, `estoy-bien`, `solicito-ayuda`), **can I trust it**
(the verified/unconfirmed badge), and **how do I call for help** (the SOS action with its 8 types). The
design system exists to make those three unmistakable.

**Key characteristics:**
- Near-pure black canvas with white Inter type. No light mode.
- Electric yellow (`{colors.primary}`) for brand, primary CTAs, and the *active radio* state (broadcasting).
- A four-color emergency-semantic layer (`danger / warning / success / info`) used scarcely and always with icon + text.
- Surface cards (`{colors.surface-card}` — #1a1a1a) barely lighter than canvas; depth by contrast, **no shadows** (also a battery/GPU win on low-end devices).
- Inter 700 for titles, 600 for labels/buttons, 400 for body. JetBrains Mono only for technical readouts (`node_id`, coordinates, frame/hop counts).
- Border radius is mobile-generous: `{rounded.md}` (12px) buttons/chips, `{rounded.lg}` (16px) cards, `{rounded.xl}` (24px) the SOS block, `{rounded.pill}` badges & toggles.
- Mobile section rhythm `{spacing.section}` (40px), screen gutter `{spacing.md}` (16px).
- **Spanish is the UI language** (channel names, labels, badges); code identifiers stay English.

## Colors

### Brand & Action (inherited, unchanged)
- **Primary (Electric Yellow)** (`{colors.primary}` — #faff69): brand voltage. Primary CTAs, the
  **broadcasting-active** indicator, focus rings, selected states, stat numbers. The yellow is the brand.
- **Primary Active** (`{colors.primary-active}` — #e6eb52): pressed.
- **Primary Disabled** (`{colors.primary-disabled}` — #3a3a1f): desaturated dark-yellow on canvas.
- **On Primary** (`{colors.on-primary}` — #0a0a0a): black text/icon on yellow.

### Surface (inherited)
- **Canvas** (`{colors.canvas}` — #0a0a0a): screen floor.
- **Surface Soft** (`{colors.surface-soft}` — #121212): bottom-nav, section tints, list zebra.
- **Surface Card** (`{colors.surface-card}` — #1a1a1a): cards, rows, inputs, sheets.
- **Surface Elevated** (`{colors.surface-elevated}` — #242424): nested cards, pressed rows, menus, snackbars.
- **Hairline / Hairline Strong** (`{colors.hairline}` #2a2a2a / `{colors.hairline-strong}` #3a3a3a): borders, input underlines.

### Text (inherited)
- **Ink** (`{colors.ink}` — #ffffff) → headlines, primary text. **Body** (#cccccc) → running text.
  **Body Strong** (#e6e6e6) → emphasis. **Muted** (#888888) → metadata/timestamps. **Muted Soft**
  (#5a5a5a) → disabled/fine print.

### Emergency-Semantic layer (the safety contract)
This is the part the brand inherits but the *mission* defines. Four colors, each meaning one thing,
each always accompanied by an icon and a word. **Never** use these for decoration.

| Token | Hex | Means | Where it appears |
|---|---|---|---|
| `{colors.danger}` | #ff453a | **Critical / SOS / destructive** | SOS button, `badge-critical`, critical record outline, map-pin-critical, distress pins |
| `{colors.warning}` | #ff9f0a | **Unconfirmed / caution** | `trust-badge-unconfirmed`, community reports, low-confidence states |
| `{colors.success}` | #30d158 | **Safe / delivered / relayed** | "estoy bien" channel, "frame relayed" confirmation, healthy battery |
| `{colors.info}` | #0a84ff | **Official / verified / info** | `trust-badge-verified` ("OFICIAL"), backend-signed records, info notes |

Each has a `-soft` companion (e.g. `{colors.danger-soft}`) for **tinted surfaces** — a critical record
card fills `danger-soft` with a `danger` 1px border, never a full saturated fill (saturated red as a
large fill is fatiguing and harms the readability of the text on top of it).

> **Why amber for "unconfirmed" instead of the brand yellow?** Brand yellow (`#faff69`, an electric
> chartreuse) and `{colors.warning}` (`#ff9f0a`, an amber-orange) are deliberately different hues so the
> *brand action* color and the *caution* color never blur. Don't substitute one for the other.

### SOS-type accents (color is secondary to the icon)
The 22-byte payload's `sosType` field (0–7) maps to a color **and** a glyph **and** a Spanish label.
The icon is the primary identifier; color is reinforcement (so two reddish types — distress and fire —
are still told apart by flame vs. SOS glyphs).

| `sosType` | Token | Hex | Glyph | Label (ES) |
|---|---|---|---|---|
| 0 medical | `{colors.sos-medical}` | #2dd4bf | cross | Médica |
| 1 distress | `{colors.sos-distress}` | #ff453a | SOS | Auxilio |
| 2 food | `{colors.sos-food}` | #ff9f0a | utensils | Comida |
| 3 water | `{colors.sos-water}` | #0a84ff | droplet | Agua |
| 4 shelter | `{colors.sos-shelter}` | #bf5af2 | home | Refugio |
| 5 fire | `{colors.sos-fire}` | #ff6b00 | flame | Fuego |
| 6 violence | `{colors.sos-violence}` | #ff2d92 | shield-alert | Violencia |
| 7 other | `{colors.sos-other}` | #8e8e93 | dots | Otro |

## Typography

### Font Family
**Inter** for everything except technical readouts; **JetBrains Mono** for `node_id`, coordinates, hop
counts, and frame stats. Fallback stack: `Inter → Roboto → system-ui, sans-serif`. On low-end devices
where bundling Inter inflates the APK, **Roboto** (the Android system face) is an acceptable substitute —
it shares Inter's geometric-humanist character closely enough to preserve the voice.

### Hierarchy

| Token | Size | Weight | Use |
|---|---|---|---|
| `{typography.display-lg}` | 34 | 700 | Rare hero number, big SOS count |
| `{typography.display-md}` | 28 | 700 | Screen titles, empty-state heads |
| `{typography.display-sm}` | 24 | 700 | Section heads, bottom-sheet titles |
| `{typography.title-lg}` | 20 | 700 | App-bar title, channel name |
| `{typography.title-md}` | 17 | 600 | Card titles, list primary line |
| `{typography.title-sm}` | 15 | 600 | Row titles, small labels |
| `{typography.stat-display}` | 40 | 700 | Counts (saltos, nodos en alcance) — yellow or semantic |
| `{typography.body-lg}` | 16 | 400 | Report body, comfortable reading |
| `{typography.body-md}` | 15 | 400 | Default running text |
| `{typography.body-sm}` | 13 | 400 | Secondary text |
| `{typography.caption}` | 12 | 500 | Timestamps, metadata |
| `{typography.overline}` | 11 | 700 | UPPERCASE section labels & badges |
| `{typography.button}` | 15 | 600 | Button labels |
| `{typography.button-sos}` | 20 | 700 | The critical SOS action label |
| `{typography.nav-tab}` | 11 | 600 | Bottom-nav labels |
| `{typography.mono}` | 13 | 400 | `node_id`, coords, frame/hop counts |

### Principles
Title weights stay 700; labels/buttons 600; body 400. Negative letter-spacing on display/title sizes
(−0.3 to −1px) keeps Inter from reading too wide — same engineered tightness as ClickHouse, scaled down
for the phone. **All text sizes are declared in `sp`** (see Platform Mapping) so they respect the user's
system font-scale — a non-negotiable accessibility requirement for an emergency app.

## Layout

### Spacing System
- **Base unit:** 4px. Tokens: `{spacing.xxs}` 4 · `{spacing.xs}` 8 · `{spacing.sm}` 12 · `{spacing.md}` 16
  · `{spacing.lg}` 20 · `{spacing.xl}` 24 · `{spacing.xxl}` 32 · `{spacing.section}` 40.
- **Screen gutter:** `{spacing.md}` (16px) left/right on all screens.
- **Card padding:** `{spacing.md}` (16px). **Block rhythm:** `{spacing.section}` (40px) between major blocks.
- **List row vertical padding:** `{spacing.sm}`–`{spacing.md}` (12–16px), targets ≥48dp tall.

### Grid & Container
- **Single-column, full-bleed-minus-gutter.** No multi-column desktop grids — this is a phone.
- **Max content width:** none on phone; on large phones / small tablets cap readable text blocks at ~520dp centered.
- **SOS-type selector:** a 2-column grid of `sos-type-chip` (or a horizontal scroll on the smallest devices).
- **Safe areas:** respect status bar, nav bar / gesture inset, and the foreground-service notification.

### Whitespace Philosophy
Denser than a consumer app, calmer than a dashboard. Enough breathing room to parse under stress, tight
enough that the most important action (SOS) and the most important fact (verified?) are always reachable
without scrolling on a small phone.

## Elevation & Depth

| Level | Treatment | Use |
|---|---|---|
| Flat | No border, no shadow | Canvas, app bar, body sections |
| Hairline | 1px `{colors.hairline}` | Cards, rows, inputs, bottom-nav top edge |
| Surface card | `{colors.surface-card}` fill | Channel/record cards, sheets, inputs |
| Elevated | `{colors.surface-elevated}` fill | Pressed rows, menus, snackbars, map clusters |
| Semantic tint | `*-soft` fill + 1px semantic border | Critical / warning / verified emphasis cards |

**No drop shadows** — depth is contrast between canvas and surface tones. This is both the inherited
ClickHouse look *and* a deliberate low-end-device choice (shadows and blurs cost GPU/battery). The one
exception: the **`fab-sos`** and **`button-sos-critical`** may carry a subtle solid-color glow/scrim
only to guarantee they separate from any map or photo behind them.

## Shapes

| Token | Value | Use |
|---|---|---|
| `{rounded.xs}` | 4px | Inline accents |
| `{rounded.sm}` | 8px | Small controls |
| `{rounded.md}` | 12px | Buttons, inputs, chips |
| `{rounded.lg}` | 16px | Cards, sheets |
| `{rounded.xl}` | 24px | SOS button, large surfaces |
| `{rounded.pill}` | 9999px | Badges, toggles, SOS-type chips |
| `{rounded.full}` | 9999px / 50% | Avatars, icon buttons, map pins, FAB |

## Components

### Actions

**`button-sos-critical`** — THE emergency action. Background `{colors.danger}`, white label
`{typography.button-sos}` (Inter 20/700), rounded `{rounded.xl}` (24px), **minHeight 72dp**, full-width.
Unmistakable, hard to miss, hard to misfire (pair with a short hold-to-confirm or a confirm sheet — see
Crisis UX). This is the single most important component in the app.

**`button-primary`** — yellow brand CTA. Background `{colors.primary}`, black label, `{rounded.md}`,
minHeight 48dp. Used for "Transmitir", "Publicar reporte", "Subir al servidor" (data-mule upload).

**`button-secondary`** — `{colors.surface-card}` fill, 1px `{colors.hairline}`, white label. Secondary
actions ("Cancelar", "Ver en mapa").

**`button-text-link`** — yellow inline text action, no fill.

**`button-icon`** — 44dp circular icon button on `{colors.surface-card}`.

**`fab-sos`** — 64dp circular `{colors.danger}` FAB, persistent shortcut to the SOS flow from the
Channels and Map screens. White SOS glyph.

### Radio / Mesh status

**`toggle-radio`** — the Broadcast / Observe switches (the app's two core radio toggles, owned by
`SosForegroundService`). A row with label + description + a switch; track turns `{colors.primary}` when
ON, `{colors.hairline-strong}` when OFF. minHeight 56dp. **Broadcasting ON = yellow** is the system's
clearest "you are live on the mesh" signal.

**`status-banner`** — a compact strip communicating mesh/connectivity state in words + icon:
"Transmitiendo" (yellow dot), "Escuchando" (body dot), "Sin internet — modo malla" (muted), "Subiendo
reportes…" (info). `{colors.surface-soft}` fill, 1px hairline.

**`battery-indicator`** — reflects the payload's `batteryBucket` (0–3). Four steps: 0 `{colors.danger}`,
1 `{colors.warning}`, 2 `{colors.primary}`, 3 `{colors.success}`. Always paired with a numeric/% or bar,
never color alone.

### Navigation & chrome

**`top-app-bar`** — 56dp, `{colors.canvas}`, title in `{typography.title-lg}`, optional leading/trailing
icon buttons. Flat (no shadow).

**`bottom-nav`** — 64dp, `{colors.surface-soft}`, 1px `{colors.hairline}` top edge. Tabs: **Estado ·
Canales · Mapa** (plus the `fab-sos` for the SOS action). Active tab label + icon turn `{colors.primary}`;
inactive `{colors.muted}`. Labels in `{typography.nav-tab}`.

### Content surfaces

**`channel-card`** — a channel in the list (e.g. `refugios`, `solicito-ayuda`). `{colors.surface-card}`,
`{rounded.lg}`, 16px padding. Shows channel name (`{typography.title-md}`), last-update time, unread/new
count badge, and — for official channels — a small `trust-badge-verified`.

**`record-card`** — one report inside a channel. Carries: the message body, a `sos-type-chip` if it's an
SOS, a **`trust-badge`** (verified or unconfirmed), author (`device-…` in mono or "Oficial"), timestamp,
and hop/relay metadata. `{colors.surface-card}`, `{rounded.lg}`.

**`record-card-critical`** — a record flagged `critical` (payload flags bit1). Fills `{colors.danger-soft}`
with a 1px `{colors.danger}` border and a leading `badge-critical`. Used sparingly for life-threatening
reports.

**`identity-card`** — shows the device's own pseudonymous identity: short `node_id` and pubkey fingerprint
in `{typography.mono}`. Reinforces "this is your durable, key-bound identity on the mesh."

### Trust & semantics

**`trust-badge-verified`** — `{colors.info-soft}` fill, `{colors.info}` text + shield-check icon, pill,
label **"OFICIAL"**. Means backend-signed (`verified:true`). This badge is a primary defense against
disinformation during a disaster — it must be unambiguous.

**`trust-badge-unconfirmed`** — `{colors.warning-soft}` fill, `{colors.warning}` text + alert-triangle
icon, pill, label **"NO CONFIRMADO"**. The default for all community reports (`verified:false`), including
everything ingested via the data-mule path. Never style a community report to look official.

**`badge-critical`** — saturated `{colors.danger}` pill, white text + alert-octagon icon, label **"CRÍTICO"**.

**`sos-type-chip`** / **`sos-type-chip-selected`** — the 8 SOS types as pills with a tinted leading glyph
(see SOS-type table). Selection is a 2px `{colors.primary}` ring on `{colors.surface-elevated}` — yellow,
not the type's own color, marks "chosen", so selection state never collides with type color. minHeight 44dp.

**`badge-pill`** (neutral metadata) and **`badge-yellow`** (brand emphasis, e.g. "NUEVO") round out the
badge family.

### Inputs

**`text-input`** — `{colors.surface-card}`, white text, `{colors.muted}` placeholder, 1px
`{colors.hairline-strong}`, `{rounded.md}`, minHeight 48dp. Used in the **create-report** flow.
**`text-input-focused`** thickens the border to 2px `{colors.primary}`.

### Map (offline OSMDroid)

**`map-pin`** — 36dp circular pin filled by the matching `{colors.sos-*}`, white glyph, 2px `{colors.canvas}`
ring so it separates from the map tiles. **`map-pin-critical`** is 40dp with a `{colors.danger}` ring/pulse.
**`map-cluster`** — `{colors.surface-elevated}` circle with a count in `{typography.title-sm}`. Only
**verified** frames produce pins (mirrors the mesh rule that only verified frames persist).

### Feedback

**`snackbar`** — `{colors.surface-elevated}`, `{rounded.md}`, transient confirmations: "Reporte
transmitido", "Frame retransmitido (salto 2)", "3 reportes subidos al servidor".

**`empty-state`** — muted centered icon + line for empty channels / no pins yet ("Aún no hay reportes en
este canal").

## Do's and Don'ts

### Do
- Anchor every screen on `{colors.canvas}` with white Inter type. Keep the yellow+black brand voltage.
- Reserve `{colors.primary}` (yellow) for **brand, primary actions, and the broadcasting-active state**.
- Reserve the four semantic colors for **meaning**, spend them sparingly, and **always pair color with an icon + a word**.
- Make the SOS action a ≥72dp `{colors.danger}` block; keep all touch targets ≥44dp (≥48dp for primary actions).
- Mark every record's trust state with `trust-badge-verified` / `trust-badge-unconfirmed`, in words.
- Declare text in `sp` and sizes in `dp`; honor system font-scale and respect-reduced-motion.
- Use `{typography.mono}` for technical truth (`node_id`, coords, hop counts) so it reads as data, not prose.
- Keep depth as contrast between surface tones. No shadows (look + battery).

### Don't
- Don't use `{colors.primary}` (yellow) to mean danger, and don't use `{colors.warning}` (amber) as a brand color — they are different hues for different jobs.
- Don't style a community / data-mule report to look official. `verified:false` always reads "NO CONFIRMADO".
- Don't rely on color alone for any semantic or SOS type — glare, cheap panels, and colorblindness will defeat it.
- Don't introduce a third brand color or drop shadows. Don't bold titles past 700 — hierarchy is size, then weight, then (sparingly) color.
- Don't shrink any interactive element below 44dp or bury the SOS action behind a scroll.
- Don't repeat the same surface tone in two stacked containers — alternate canvas → card → elevated.
- Don't add hover styling (this is touch); define **Default** and **Pressed/Active** states only.

## Responsive Behavior (mobile device classes)

This is a phone-first app; "responsive" means **device classes and orientation**, not desktop breakpoints.

| Class | Width (dp) | Key changes |
|---|---|---|
| Compact (low-end target) | ≤ 360 | Tightest gutters (12px), SOS-type selector may horizontal-scroll, single-column everything, display sizes hold (legibility > density) |
| Standard | 360–420 | Default layout, 2-col SOS-type grid, 16px gutter |
| Large phone / small tablet | 420–600 | Cap text blocks ~520dp centered, more breathing room, 2-col channel grid optional |
| Landscape | — | Bottom-nav may move to a side rail; SOS action stays persistent and full-height-reachable |

### Touch Targets
- `button-sos-critical` ≥ **72dp** tall; `fab-sos` 64dp.
- `button-primary` / `button-secondary` ≥ **48dp**.
- `button-icon`, `sos-type-chip` ≥ **44dp** (Android guidance 48dp where space allows).
- `text-input`, list rows ≥ **48dp** tall.

### Collapsing / scaling strategy
- Font-scale (system accessibility) can grow text ~130%+ — layouts must reflow, never clip. Test at max scale.
- Map retains tile size; pins keep a fixed dp size and never scale below 36dp.
- Long Spanish labels wrap to two lines rather than truncating critical words ("NO CONFIRMADO").

## Platform Mapping — React Native ↔ Jetpack Compose

The build flow is **author in React Native, port to native Kotlin/Compose**. These tokens are
platform-agnostic and map 1:1. Author the RN layer so the Compose port is mechanical.

### Unit equivalence
- RN's unitless numbers are **density-independent pixels** = Android **dp**. So `spacing.md = 16` in RN
  StyleSheet ⇄ `16.dp` in Compose — **same number**.
- **Text must use `sp`** (scales with the user's font setting). RN: set `allowFontScaling` true (default)
  and size in the unitless value, which RN treats as sp-like for fonts. Compose: `fontSize = 15.sp`.
  Never hard-pin text to dp.

### Token → platform

| Token group | React Native | Jetpack Compose |
|---|---|---|
| Colors | `const colors = { primary: '#faff69', ... }` (a theme object, or NativeWind `theme.extend.colors`) | A custom `data class GuacamayaColors(...)` exposed via `CompositionLocal`; seed a Material3 `darkColorScheme(...)` and extend it with the semantic + SOS colors Material lacks |
| Typography | `const type = { titleLg: { fontFamily:'Inter', fontSize:20, fontWeight:'700', letterSpacing:-0.3 } }` | `Typography(titleLarge = TextStyle(fontFamily = Inter, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp))` plus custom styles (`buttonSos`, `mono`, `overline`) the Material set doesn't cover |
| Spacing | `const spacing = { md: 16, ... }` | `object Spacing { val md = 16.dp; ... }` |
| Rounded | `borderRadius: rounded.lg` (16) | `RoundedCornerShape(rounded.lg.dp)` / a `Shapes(...)` set |
| Elevation | flat fills + `borderWidth`/`borderColor`; no `elevation`/`shadow*` | flat `Surface(tonalElevation = 0.dp, shadowElevation = 0.dp)`; depth via `color` only |
| Component | RN function component (e.g. `<TrustBadge variant="verified" />`) | `@Composable fun TrustBadge(variant: TrustVariant)` |

### Suggested component name parity (port becomes 1:1)

| RN component | Compose composable |
|---|---|
| `<SosButton />` | `SosButton()` |
| `<PrimaryButton />` | `PrimaryButton()` |
| `<RadioToggle kind="broadcast" />` | `RadioToggle(kind = RadioKind.Broadcast)` |
| `<TrustBadge variant />` | `TrustBadge(variant)` |
| `<SosTypeChip type selected />` | `SosTypeChip(type, selected)` |
| `<ChannelCard />` / `<RecordCard />` | `ChannelCard()` / `RecordCard()` |
| `<StatusBanner state />` | `StatusBanner(state)` |
| `<MapPin type critical />` | `MapPin(type, critical)` |

### Porting notes
- Keep tokens in **one source-of-truth file per platform** (`theme.ts` ⇄ `Theme.kt`) generated from the
  same value table — when a token changes, change it in both. Consider a tiny codegen from this doc's
  frontmatter to avoid drift.
- RN map (`react-native-maps` / MapLibre) ⇄ the native **OSMDroid** offline map already in the Kotlin app
  (`ui/MapViewModel`). Pin styling (`map-pin`) is the contract both must satisfy.
- The two **radio toggles** drive `SosForegroundService` intents (`ACTION_START/STOP/OBSERVE_ON/OBSERVE_OFF`).
  In RN they cross a native-module bridge; in Compose they call the service directly. The *visual* contract
  (yellow = broadcasting) is identical.
- The **create-report** flow produces a 22-byte signed payload (`proto/Payload`); the UI only collects
  `sosType`, `critical`, message, and reads GPS. Keep the form dumb — crypto/codec live in native.

## Crisis UX & Accessibility (non-negotiable for this product)

This app is used when things are going wrong. The design must hold up under stress, glare, low literacy
of the situation, damaged hardware, and dying batteries.

- **Contrast:** body text meets WCAG **AA** (≥4.5:1) on canvas; critical text and the SOS action target
  **AAA** (≥7:1). White-on-#0a0a0a and the semantic colors on canvas all clear AA; verify any `*-soft`
  surface keeps its text ≥4.5:1.
- **Never color-only:** every semantic state and SOS type = **color + icon + Spanish word**. A grayscale
  screenshot of any screen must still be fully understandable.
- **Forgiving, large targets:** SOS ≥72dp; primary ≥48dp; nothing tappable <44dp. Generous hit-slop.
- **Misfire protection on SOS:** the critical action confirms intent (hold-to-send or a confirm sheet
  showing type + location) — but confirmation must be **one extra deliberate step, not a maze**.
- **Font scaling:** all text in `sp`; layouts reflow to ~130%+ scale without clipping. Test at max scale.
- **Reduced motion / battery:** honor system reduce-motion; avoid continuous animation. No shadows/blur.
  Dark canvas is itself a battery choice on OLED.
- **Offline-first honesty:** the UI states clearly when there's no internet ("modo malla"), when data is
  unconfirmed, and when a report is queued vs. transmitted vs. relayed vs. uploaded. Never imply delivery
  that didn't happen.
- **Low-end performance:** flat fills, no shadow layers, modest list sizes (the mesh prunes to 500 rows —
  the UI should virtualize and not try to render more).
- **One-handed reach:** keep the SOS action and the trust badge reachable in the thumb zone; primary
  actions near the bottom.
- **Language:** UI copy is **Spanish**, plain and short. Avoid jargon; "NO CONFIRMADO" beats "unsigned".

## Iteration Guide

1. Focus on ONE component at a time; reference its YAML key (`{components.trust-badge-verified}`).
2. Variants live as separate entries (`-active`, `-selected`, `-critical`, `-focused`).
3. Use `{token.refs}` everywhere — never inline hex in components.
4. No hover. Define **Default** and **Pressed/Active** only (touch surface).
5. Titles stay Inter 700 with negative letter-spacing; body stays Inter 400.
6. The yellow+black pairing is the brand contract; the four semantic colors are the safety contract.
   Don't blur the two — yellow is action, red is danger.
7. When emphasizing: bigger Inter 700 first, then a semantic color (with icon + word), then never a new hue.
8. Every change must survive the three field tests: **grayscale** (color-blind safe?), **max font-scale**
   (still fits?), **arm's length in daylight** (still readable?).

## Known Gaps / Open Questions

- **Icon set: Material Symbols (preferred), pending cross-platform confirmation.** Use **Material
  Symbols** for the SOS-type glyphs and semantic icons **if it stays compatible with the RN ↔ Kotlin
  flow** — i.e. the *same* named glyph must render identically in both React Native and Jetpack Compose.
  - **Compose:** native via `androidx.compose.material:material-icons-extended` (or the Material Symbols
    variable font).
  - **React Native:** via `react-native-vector-icons` (MaterialIcons) or the Material Symbols font loaded
    directly.
  - **Parity caveat:** the newer *Material Symbols* variable font (3 axes: weight/fill/grade) and the
    older static *Material Icons* set are not identical. If the variable font can't be matched 1:1 across
    both platforms, fall back to the **static Material Icons** shared subset to guarantee parity. Pin the
    exact glyph name per SOS type + semantic state before building components.
- **SOS confirm interaction** (hold-to-send vs. confirm sheet) is described but not finalized — decide and
  document as a flow.
- **Exact AA/AAA contrast audit** of every `*-soft` surface + text pair is pending (values chosen to pass,
  not yet measured).
- **Inter vs. Roboto** final call for the low-end APK-size trade-off is left to the build team.
- **Token codegen** from this frontmatter to `theme.ts` / `Theme.kt` is recommended but not built.
- **Marketing/landing surface** is explicitly out of scope (this doc is the app). If one is needed later,
  it can reuse the brand layer with ClickHouse's marketing components.
- This system inherits ClickHouse's color *atmosphere*; the **emergency-semantic and SOS-type hexes are
  Guacamaya-original** and may be tuned once tested on real low-end panels in daylight.
