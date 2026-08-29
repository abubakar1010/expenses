# UI/UX Design Guide
**Product:** Personal Finance Manager (Android)
**Version:** 1.0 — MVP
**Design direction:** *DayBook*

---

## 1. The design brief, stated plainly

Before any colour or font decision, the design has to know what it is for.

**One user.** Their own device. No onboarding funnel, no marketing surface, no social layer.

**Used in bursts, standing up.** The dominant interaction is logging a purchase seconds after making it — at a shop counter, in a rickshaw, walking. Often one-handed, often outdoors in bright daylight.

**On a cheap LCD screen.** Low maximum brightness, poor contrast ratio, no OLED black. Sunlight legibility is a functional requirement here, not a refinement.

**The screen's single job:** answer *"can I spend this today?"* Everything else on the dashboard is supporting evidence.

**Income is lumpy.** Salary is steady; farming and property arrive in bursts. The interface must not imply a smooth monthly rhythm that this user's life does not have.

Every decision below traces back to one of those five facts. Where a decision is merely aesthetic preference, I say so.

---

## 2. Direction: *DayBook*

The obvious move for a finance app is the current fintech default: a dark surface, a mint or electric-green accent, a large gradient balance card, rounded pill buttons, a donut chart. It is the house style of roughly every banking app shipped since 2019. It is also wrong for this product — it is designed to make a *balance* feel impressive, while this app exists to make a *limit* feel real.

The direction instead comes from the object this app replaces: the **khata**, the ruled ledger book kept by every shopkeeper and household in Bangladesh — the *daybook* of English bookkeeping, which is where the product takes its name.

Three things carry over, and only three — the point is to borrow the logic, not to draw a fake paper texture:

**Blue-black ink records; red ink demands attention.** In a real ledger these are two physical pens, and the distinction is universally understood. It maps exactly onto the budget states this app needs, and it means red is scarce — it appears only when something is genuinely wrong, so it retains its force.

**The ruled line is the grid.** Ledger rules are not decoration; they are what makes a column of figures readable. In this app the horizontal rule is the primary structural device, replacing the card-with-shadow that Material reaches for by default. Fewer elevated surfaces also means less overdraw, which the rendering budget appreciates.

**Figures are the content.** In a ledger the numbers are the point and the labels are annotation. This inverts the usual app hierarchy where the label is set in comfortable body text and the number is squeezed in beside it.

**What I explicitly did not take:** paper texture, drop shadows imitating a book, handwriting fonts, sepia tones, page-curl animations. Skeuomorphism would cost real rendering time to communicate something a colour and a rule already say.

---

## 3. Colour

### 3.1 Light theme — the default

Light is the default, and this is a functional choice rather than a stylistic one. The target device has an LCD panel, so a dark theme saves no battery — that only holds on OLED — and dark themes are markedly harder to read in direct sunlight, which is a common usage condition here. Dark theme ships as a genuine option, not as the primary experience.

| Token | Hex | Role |
|---|---|---|
| `paper` | `#F6F5F1` | App background. Warm off-white, not pure white — reduces glare at high brightness |
| `card` | `#FFFFFF` | Raised surfaces, sheets, input fields |
| `rule` | `#E4E1D8` | Hairlines, dividers, the ledger rule |
| `ink` | `#141C28` | Primary text and figures |
| `ink-soft` | `#5D6675` | Labels, secondary text, dates |
| `indigo` | `#25407A` | Primary action, selection, the "record" ink |
| `vermilion` | `#B23A22` | Over budget, destructive, the "correction" ink |
| `moss` | `#3F6B4A` | Income, positive net, healthy budget |
| `amber` | `#8A5D10` | Approaching limit (≥80%) |

Every one of these clears 4.5:1 against its intended background. `ink` on `paper` measures far beyond that, which is deliberate — high contrast is what makes the app readable outdoors on a dim panel.

**On avoiding the two obvious palettes.** The warm-cream-plus-terracotta combination has become a visual tell; it is a default rather than a choice. So the paper here is cooler and lighter than that cream, and the warm accent is a true red pen rather than clay. The other default — near-black with one acid accent — is ruled out by the sunlight requirement.

### 3.2 Dark theme

Not an inversion. Pure black backgrounds with pure white text cause halation on cheap panels, so both ends pull inward.

| Token | Hex |
|---|---|
| `paper` | `#12151A` |
| `card` | `#1A1E25` |
| `rule` | `#2A2F38` |
| `ink` | `#E8E6E0` |
| `ink-soft` | `#98A0AC` |
| `indigo` | `#8FAEE8` |
| `vermilion` | `#E88A72` |
| `moss` | `#7FB98E` |
| `amber` | `#D9AE5A` |

Accents lighten and desaturate, because a colour tuned for contrast against white is illegible against near-black.

### 3.3 Colour is never the only signal

NFR-USE-05 requires this, and the app's most important state — over budget — sits on exactly the red/green axis that deuteranopia collapses. Every budget state therefore carries three signals:

| State | Colour | Fill | Text |
|---|---|---|---|
| Under | `moss` | Solid, partial width | `৳2,400 left` |
| Near (≥80%) | `amber` | Solid + hatched cap | `৳900 left` |
| Over (≥100%) | `vermilion` | Solid + full-width rule above | `৳600 over` |
| Unbudgeted | `ink-soft` | Outline only | `No limit set` |

Remove all colour and the row still reads correctly. That is the test.

### 3.4 Dynamic colour: declined

Material You's dynamic colour, which derives a palette from the user's wallpaper, is off. Two reasons: it requires API 31+ while the floor is API 26, so it would ship an inconsistent experience across the target device range; and it would hand the semantic budget colours to an algorithm, which is unacceptable when red carries a specific meaning. Wallpaper-derived accents are a personalisation feature for apps whose colours are decorative.

---

## 4. Typography

### 4.1 The font budget problem, turned into the solution

The APK ceiling is 6 MB. A full weight of a bundled typeface costs 150–400 KB, and Bengali coverage costs considerably more. Bundling a display family is not affordable.

But numbers are this app's hero content, and they need tabular figures — without them, digits shift width as values change and a scrolling column of amounts visibly jitters.

The resolution: **bundle exactly one typeface, subsetted to the twelve glyphs the app sets large.**

```
Subset:  0 1 2 3 4 5 6 7 8 9  ৳  ,  .  −  +
Face:    IBM Plex Mono, Medium (SIL Open Font License)
Cost:    ~12–18 KB after subsetting and WOFF2/OTF compression
```

Everything else uses the system faces already on the device: **Roboto** for Latin UI text, **Noto Sans Bengali** for Bangla, both present on every Android 8+ device at zero cost.

This is the type strategy in one sentence: spend the entire font budget on the twelve characters that matter, and take the system default for the rest. Plex Mono is chosen over Roboto Mono because its digits have more character — flat-sided, slightly condensed, with a machine-ledger quality that suits a record of transactions rather than a dashboard.

### 4.2 Scale

Android `sp` units, so the scale respects the system font-size setting. Layouts are verified at 0.85× and 1.3× per NFR-COMP-04.

| Role | Size / Line | Face | Weight | Tracking |
|---|---|---|---|---|
| Hero figure (safe-to-spend) | 44 / 48 | Plex Mono | 500 | −0.02em |
| Section figure (totals) | 28 / 32 | Plex Mono | 500 | −0.01em |
| Row figure (amounts) | 17 / 24 | Plex Mono | 500 | 0 |
| Screen title | 22 / 28 | Roboto | 600 | 0 |
| Section header | 13 / 16 | Roboto | 600 | +0.08em, uppercase |
| Body / row label | 15 / 22 | Roboto | 400 | 0 |
| Caption, date, meta | 12 / 16 | Roboto | 400 | +0.01em |

Only two sans weights ship — 400 and 600. A third adds no clarity and costs a variant.

Section headers are the one place with uppercase and open tracking: they behave like the printed column headings of a ledger page, which is a real borrowing rather than a stylistic tic.

### 4.3 Rules for setting money

- **Always tabular figures.** Non-negotiable in any list.
- **Always the ৳ symbol**, at 0.7em and `ink-soft`, so the glyph does not compete with the digits.
- **Group thousands per locale.** Note that South Asian grouping conventions differ from Western ones for large values — follow the device locale rather than hard-coding a pattern.
- **Hide the decimals** when the value is a whole taka, which is nearly always. `৳1,250` not `৳1,250.00`. Show paisa only when non-zero.
- **Negative amounts** use a true minus (−), never a hyphen, and take `vermilion`.
- **Never abbreviate to `1.2k`.** In a ledger, precision is the product.

### 4.4 Bengali and numeral form

The app must render Bangla category names correctly — `আমার বাড়ি ভাড়া` should look right, not fall back to boxes. Noto Sans Bengali handles this, but Bengali has taller ascenders and the *matra* (the connecting headline), so **Bengali text needs roughly 1.15× the line height of Latin at the same size.** Set line heights generously rather than tightly.

Numerals get a setting: Latin (`1250`) by default, Bengali (`১২৫০`) optional. Latin is the default because Bangladeshi financial documents, price tags, and mobile-money interfaces overwhelmingly use Latin digits — but the option matters, and the bundled Plex subset covers only Latin digits, so Bengali numerals fall back to the system face. That is an acceptable, deliberate degradation.

---

## 5. Layout

### 5.1 Spatial system

4 dp base unit; 8 dp is the working rhythm.

| Token | Value | Use |
|---|---|---|
| `space-1` | 4 dp | Between a label and its figure |
| `space-2` | 8 dp | Within a component |
| `space-3` | 16 dp | Screen gutters, between rows |
| `space-4` | 24 dp | Between sections |
| `space-5` | 32 dp | Above the hero figure |

Screen gutter is 16 dp. On a 320 dp phone that leaves 288 dp of content, which is the width every layout is designed against — not 360 or 411.

### 5.2 The thumb zone

NFR-USE-06 requires one-handed operation. On a 5-inch phone held in one hand, the comfortable arc covers roughly the bottom 55% of the screen and the near side.

```
┌─────────────────────┐
│   READ ONLY         │  Titles, hero figure, totals.
│   (hard to reach)   │  Nothing tappable above ~40%.
│                     │
├─────────────────────┤
│   MIXED             │  Scrollable list. Reachable
│                     │  by scrolling content up.
├─────────────────────┤
│   ACT               │  Nav bar, FAB, sheet buttons,
│   (thumb arc)       │  keypad. Every primary action.
└─────────────────────┘
```

Consequence: the confirm button in the Quick Add sheet sits at the bottom, full width. Cancel is a swipe-down on the sheet, not a button competing for the same space.

### 5.3 The ledger row

The core repeated component across four screens. It uses the rule as its structure, with no card, no shadow, no elevation.

```
 ┌ 16dp gutter
 │
 │  Grocery                              ৳ 5,600
 │  ····································        ← leader rule, `rule` colour
 │  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░  80%   ৳1,400 left
 │
 ├─────────────────────────────────────────────  ← hairline, `rule`
 │
 │  Transport                            ৳ 2,100
```

The leader dots connecting label to figure are the direct borrowing from a printed ledger — they carry the eye across the gap, which is exactly the job they do on a paper page. They also cost one `drawLine` call with a dash effect.

Row height is 72 dp with a budget bar, 56 dp without. Both clear the 48 dp touch minimum.

### 5.4 Screen anatomy — Dashboard

```
┌───────────────────────────────────────┐
│  ‹  August 2026  ›              ⚙     │  Period switcher. Tap month = picker.
├───────────────────────────────────────┤
│                                       │
│   SAFE TO SPEND TODAY                 │  13sp, tracked, ink-soft
│                                       │
│   ৳ 1,240                             │  44sp Plex Mono — the hero
│                                       │
│   ▏▎▍▂▃▁▅▂▃▇▂▁▃▂▎▏· · · · · · · · ·   │  ← THE MONTH RIBBON
│   1        today↑              31     │
├───────────────────────────────────────┤
│  Earned ৳48,000   Spent ৳31,600       │  Net line. moss / ink.
│  Net +৳16,400 · saving 34%            │
├───────────────────────────────────────┤
│  NEEDS ATTENTION                      │
│  Grocery          104%   ৳280 over ▲  │  vermilion, only when real
├───────────────────────────────────────┤
│  VARIABLE EXPENSES        ৳12,400/18k │
│  Grocery ·············· 104%  ৳7,280  │
│  Transport ············  61%  ৳2,100  │
│  Dining out ···········  22%    ৳880  │
│                                       │
│  FIXED EXPENSES           ৳18,000/18k │
│  House rent ··········· 100% ৳15,000  │
│  ...                                  │
├───────────────────────────────────────┤
│   ▣        ▤      ⊕     ▥       ▦     │  Dashboard Ledger [Add] Income Budget
└───────────────────────────────────────┘
```

Three notes on this arrangement:

**The hero is a decision, not a balance.** Most finance apps put total balance or total spent at the top. Neither answers a question the user has at a shop counter. Safe-to-spend does.

**"Needs attention" only appears when something needs attention.** An empty state here would train the user to ignore the region. Sections that have nothing to say are absent, not empty.

**Fixed expenses sit below variable ones**, despite being larger, because rent is not a decision. Ordering by actionability rather than by amount is the whole point of separating the categories in the first place.

**The mix says what its percentages are of, but only when that is not obvious.** A nature whose net is at or below zero for the period is dropped from "Where it goes" rather than drawn at 0% — FR-EXP-06 makes a negative expense a refund, and a slice cannot have negative width. The remaining shares then total 100% of what is *displayed*, which is the honest reading of a mix, and not of the figure printed above them: ৳5,000 of variable spending beside a ৳1,000 refund on unpredictable shows one slice at 100% over a total of ৳4,000.

The rule is a caption beneath the slices naming the gap, and **only when the gap is non-zero**. Not a permanent subtitle: a line that is always there is a line nobody reads on the day it matters, which is the same argument "needs attention" is built on two paragraphs above. Not a recalculated total either — the hero figure is the period's net and changing it to match the slices would be answering a presentation problem by making a number wrong.

`SpendMix.excludedFrom` is what a screen asks. `06-implementation-log.md` §22.10 recorded this as an open framing question before it was one.

### 5.5 Signature element: the month ribbon

This is the one element the app should be remembered by.

A strip of 28–31 vertical bars, one per day of the period. Bar height encodes that day's total spend against the month's busiest day. Days before today are solid `indigo`; today is marked with a `vermilion` hairline; remaining days are faint dots on the baseline.

It occupies about 32 dp of height and communicates four things simultaneously: your spending rhythm, whether this week is heavier than last, where you are in the month, and how much runway is left. A donut chart of category shares — the default choice — communicates one thing, and one the user already knows.

It is also almost free to draw: thirty-one `drawRect` calls on a Compose `Canvas`, no library, no layout pass, no allocation in the draw scope. The design and the performance budget agree, which is usually the sign of a correct decision.

### 5.6 Screen anatomy — Quick Add

The most-used screen in the app. Target: three taps, under five seconds.

```
┌───────────────────────────────────────┐
│               ────                    │  drag handle
│                                       │
│   ৳ 250                               │  40sp, cursor live on open
│   ───────────────────────────         │
│                                       │
│   Grocery   Transport   Rickshaw      │  ← last-used chips, one tap
│   Dining    Recharge    More…         │
│                                       │
│   Today ·  Cash ·  Add note           │  inline, tappable, pre-filled
│                                       │
│   ┌─────────────────────────────────┐ │
│   │            Save                 │ │  full width, thumb zone
│   └─────────────────────────────────┘ │
│   ┌───┬───┬───┬───┐                   │
│   │ 1 │ 2 │ 3 │ ⌫ │                   │  custom keypad, always up
│   ├───┼───┼───┼───┤                   │
│   │ 4 │ 5 │ 6 │   │                   │
│   ...                                 │
└───────────────────────────────────────┘
```

Four decisions that create the speed:

**A custom numeric keypad, not the system IME.** The system keyboard costs an inflation and an animation on open, and on low-end devices that is a visible delay of 150–300 ms at exactly the wrong moment. A `Canvas`-light custom pad is instant, always present, and can be laid out entirely in the thumb arc.

**Recent categories as chips, not a dropdown.** Spending is habitual — a handful of categories cover most days. Six chips derived from the `app_meta` last-used data turn category selection from *tap, scroll, find, tap* into one tap. "More…" opens the full picker for the rest.

**Date, method, and note are pre-filled inline text**, not fields. They read as a sentence — *Today · Cash · Add note* — and each is tappable to change. A form with five labelled inputs would be correct and would also be slow.

**Save is a real button in the thumb zone**, not a checkmark in the top app bar. The top-right checkmark is the Material convention and it is unreachable one-handed on the most frequent action in the app.

### 5.7 Screen anatomy — Income

The screen that must respect lumpy income.

```
┌───────────────────────────────────────┐
│  ‹  2026  ›                    Month ▾│  ← defaults to YEAR, not month
├───────────────────────────────────────┤
│   EARNED THIS YEAR                    │
│   ৳ 5,84,000                          │
│                                       │
│   ▁▁█▁▁▁▁▇▁▁█▁                        │  12-month bars — the lumpiness
│   J F M A M J J A S O N D             │  is the information
├───────────────────────────────────────┤
│   Salary        ৳3,60,000    62%  ●   │  ● = stable
│   Real estate   ৳1,44,000    25%  ○   │  ○ = variable
│   Farming        ৳80,000     14%  ○   │
├───────────────────────────────────────┤
│   Stable income covers 71% of your    │  ← the insight that matters
│   spending this year.                 │
└───────────────────────────────────────┘
```

The income screen **defaults to a yearly view** while every other screen defaults to monthly. This is the single most important UX accommodation for this user's situation: a farming month showing ৳0 is alarming and meaningless in isolation. The year is the honest unit for this income; the month is the honest unit for spending. The app should not pretend otherwise for the sake of consistency.

Sources are marked stable or variable with a filled or hollow dot — a shape difference, not a colour difference, so it survives both greyscale and colourblindness.

### 5.8 Screen anatomy — Backup

The screen whose job is to be believed.

```
┌───────────────────────────────────────┐
│   Backup                              │
│                                       │
│   Last backup 22 Aug 2026, 9:14 pm    │  ← section figure, not a caption.
│   4,182 records · daybook-backup-…      │    "Never backed up" reads the same
├───────────────────────────────────────┤    size, in ink-soft
│   WHERE BACKUPS GO                    │
│   Backups are written when you open   │  ← both sentences before the
│   DayBook, not while it is closed.      │    control, not after it
│   They go in a folder you own, so     │
│   they stay when DayBook is             │
│   uninstalled. To survive a lost      │
│   phone, send a copy somewhere else.  │
│                                       │
│   Documents/DayBook                     │
│   Change folder                       │
├───────────────────────────────────────┤
│   HOW OFTEN                           │
│   (Only when I tap)(Every day)(Every  │  ← chips, same as the theme row
│   week)                               │
├───────────────────────────────────────┤
│   PROTECT WITH A PASSPHRASE           │
│   Off                                 │
│   Off by default. If you forget it,   │  ← said here and again in the
│   the backup cannot be opened — not   │    sheet that sets it
│   by you, and not by us.              │
└───────────────────────────────────────┘
```

**The two sentences under the folder header are the screen.** Everything else is
controls; those are the only part that decides whether the feature is honest.
They state what survives an uninstall, what does not, and when backups actually
happen — because §12 rules out a background service, so a bare "Every day" chip
would promise something the app cannot deliver. A user who believes they are
covered and is not would be worse off than one with no backup at all, and this
is the one screen where that mistake is unrecoverable.

The passphrase warning appears twice, which nothing else in the app does. It is
on the row, and again in the sheet that sets one, because the moment it can
still be acted on is the moment before a passphrase is chosen. §9 forbids
apologising; it does not forbid saying a hard thing once more where it counts.

**"Last backup" is a section figure, not a caption.** It is the number the user
came to check, and it is set at the same weight as the money on every other
screen for the same reason: it is the fact, and the controls under it are the
action.

---

## 6. Components

| Component | Specification |
|---|---|
| **Ledger row** | 56 dp plain / 72 dp with bar; hairline separator; leader dots; label left, figure right |
| **Budget bar** | 6 dp tall, 3 dp radius, full-width track in `rule`, fill in state colour, hatched cap at ≥80% |
| **Month ribbon** | 32 dp tall; bar per day; `indigo` past, `vermilion` today marker, dotted future |
| **Chip** | 32 dp, 16 dp radius, `card` background, 1 dp `rule` border; selected = `indigo` fill, `card` text |
| **FAB** | 56 dp, centre-docked in nav bar, `indigo`, plus glyph. Only FAB in the app |
| **Bottom nav** | 4 items + centre FAB slot; icon 24 dp + 11sp label; active = `indigo` + filled icon |
| **Bottom sheet** | 16 dp top radius, `card`, drag handle, primary button full-width at base |
| **Snackbar** | `ink` surface, `paper` text, 5 s, Undo in `moss`. Every delete gets one |
| **Empty state** | One line stating the situation, one button starting the fix. No illustration — illustrations cost APK size and say nothing |
| **Input field** | Underline only, not a filled box. `rule` at rest, `indigo` focused, `vermilion` on error with the message below |

**Corner radius:** 8 dp on sheets and dialogs, 4 dp on inputs and chips, 3 dp on bars, 16 dp only on the FAB and selected chips. Deliberately tighter than the Material default — a ledger is made of rectangles, and heavy rounding reads as playful in a context where precision is the message.

**Elevation:** used in exactly three places — the bottom sheet, the FAB, and the nav bar. Everywhere else, hierarchy comes from the rule and from type weight. Every shadow is an overdraw cost paid on every frame.

---

## 7. Motion

Material 3 Expressive, which shipped with Android 16, moves to spring-based physical motion with visible overshoot and bounce. Adopt the component and accessibility standards; **decline the expressive motion scheme.**

The reason is measurable rather than aesthetic. Spring animations run until they settle, producing a variable-length tail of frames; on a Cortex-A53 with Compose recomposition already on the critical path, that tail is where dropped frames appear. A fixed 180 ms tween has a known cost. This is a case where the platform's current direction is optimised for flagship hardware, and the brief here is explicit that the app must be smooth on the opposite end of the market.

| Motion | Duration | Curve |
|---|---|---|
| Bottom sheet in / out | 200 / 160 ms | Standard decelerate / accelerate |
| Budget bar fill change | 180 ms | `FastOutSlowIn` |
| Chip selection | 80 ms | Linear |
| Screen transition | 150 ms | Fade through — no slide, no shared element |
| Snackbar | 150 ms | Slide up |

**What is never animated:** list item appearance, number counting up, the month ribbon on load, screen entry content, or anything decorative. A number that counts up from zero to ৳1,240 delays the answer to the user's question by 400 ms in order to look impressive. That is the wrong trade for this product.

`Settings.Global.ANIMATOR_DURATION_SCALE` is honoured. At zero, all animation is skipped rather than shortened.

---

## 8. Interaction principles

**One tap to add, from anywhere.** The FAB is present on all four primary screens, in the same position.

**Nothing waits on the database.** The dashboard renders its structure immediately and fills figures in as flows emit, per the startup design. A skeleton for 80 ms is better than a spinner for 300 ms, and far better than a blank screen.

**Every destructive action is undoable for 5 seconds.** No confirmation dialogs for deletes — a dialog interrupts before the fact and is dismissed reflexively; a snackbar corrects after it and costs nothing when the action was intended. The exception is "delete all data," which requires typed confirmation, because there is no undo for it.

**The app never nags.** Budget warnings appear on the dashboard when the user looks. No push notifications in v1 — there is no background service, and an app that scolds you about spending gets uninstalled.

**Defaults do the work.** Today's date, last-used payment method, most-used categories, current period. The user should be able to log a typical expense without changing a single default.

---

## 9. Writing

The interface's vocabulary is part of the design. Rules:

- **Sentence case everywhere** except the tracked section headers.
- **Name things by what the user controls.** "Money coming in," not "income transactions." "Spending limit," not "budget allocation."
- **A control says what happens.** "Save expense" → the snackbar says "Expense saved." The verb does not change between the button and the result.
- **Errors state the problem and the fix**, without apology. Not "Oops! Something went wrong." Instead: "Amount can't be zero. Enter how much you spent."
- **Empty states are invitations.** Not "No transactions found." Instead: "Nothing logged today. Tap + to add your first expense."
- **Never congratulate.** No "Great job staying under budget!" This is a ledger, not a coach, and the user did not ask for encouragement from software.

Sample strings:

| Situation | Copy |
|---|---|
| Over budget | `৳280 over in Grocery` |
| Approaching | `৳900 left in Grocery — 6 days to go` |
| No budget set | `No limit set. Set one` |
| Zero income month | `Nothing recorded in August. Your year is at ৳5,84,000` |
| Import success | `Restored 4,182 expenses and 96 income entries` |
| Import failure | `That file is from a newer version of the app. Update, then import again` |

The zero-income line is worth noting: it refuses to render an empty month as a failure, and immediately reframes to the unit that is meaningful for this user.

---

## 10. Accessibility

Requirements, not aspirations. Each is testable.

| Requirement | Standard | Verification |
|---|---|---|
| Text contrast | ≥ 4.5:1 body, ≥ 3:1 large | Every token pair checked; Accessibility Scanner clean |
| Touch targets | ≥ 48 × 48 dp | Includes chips, nav items, period arrows |
| State without colour | Always | Screenshot in greyscale; states still distinguishable |
| Font scale | 0.85× – 1.3× | No clipping, no overlap, at 320 dp width |
| TalkBack | Full traversal | Money reads as "one thousand two hundred fifty taka," not "৳1250" |
| Content descriptions | All icon-only controls | FAB reads "Add expense" |
| Reduced motion | Honoured | Animator scale 0 → no animation |
| Focus order | Logical | Hero → alerts → categories → nav |

The TalkBack money point deserves emphasis: a raw currency string read character-by-character is unusable, and it is the single most common accessibility failure in finance apps. Every amount needs an explicit `contentDescription` with the spoken form.

---

## 11. Reference material

Grouped by what each is actually good for, since the difficulty is knowing which to consult when.

### Platform standards — treat as binding

| Reference | Use it for |
|---|---|
| `m3.material.io` | Component anatomy, states, spacing specs. The definitive source for what a bottom sheet or nav bar should measure |
| `developer.android.com/design` | Android-specific layout, navigation patterns, large-screen and accessibility guidance |
| `developer.android.com/develop/ui/compose/designsystems/material3` | Implementing the above in Compose; current M3 Expressive coverage |
| `developer.android.com/guide/topics/ui/accessibility` | TalkBack, content descriptions, touch targets |
| `developer.android.com/develop/ui/compose/performance` | Recomposition, stability, deferred reads — directly relevant to the frame budget |
| `w3.org/WAI/WCAG22/quickref` | Contrast and non-colour-signalling criteria |

Note that as of I/O 2026 Google has declared Android UI development Compose-first, with the older View-based Material library in maintenance. When following any Material guidance, check that the Compose implementation covers it — Expressive coverage has landed unevenly across platforms.

### Craft — read once, apply repeatedly

| Reference | Why |
|---|---|
| *Refactoring UI* — Wathan & Schoger | The most directly useful book for a developer designing alone. Hierarchy, spacing, colour without a colour theory background |
| `lawsofux.com` | Fitts, Hick, Miller, Jakob — one page each. The thumb-zone and chip-selection decisions above are Fitts and Hick applied |
| `nngroup.com` | Nielsen Norman's research articles, particularly on forms, mobile input, and error messaging |
| *The Elements of Typographic Style* — Bringhurst | If the type scale ever needs defending or extending |
| `practicaltypography.com` | Butterick. Free online. Especially the sections on tabular figures and setting numbers |

### Tools

| Tool | Use |
|---|---|
| `webaim.org/resources/contrastchecker` | Verify every token pair before committing |
| `material-foundation.github.io/material-theme-builder` | Generate the full M3 token set from the seed colours above |
| `fonts.google.com/specimen/IBM+Plex+Mono` | The money face; check the licence terms |
| `fonts.google.com/noto/specimen/Noto+Sans+Bengali` | Reference for Bengali metrics; the system provides it at runtime |
| `pyftsubset` (fonttools) | Subset Plex Mono to the twelve glyphs. This is the step that makes the font budget work |
| Android Studio Layout Inspector | Overdraw and recomposition counts |
| Accessibility Scanner (Play Store) | Automated pass for targets and contrast |

### Apps worth studying

Not to imitate, but because each solved one problem well:

- **Monefy** — the fastest expense entry on Android; study the entry flow, ignore everything else
- **Money Manager (Realbyte)** — dense information without feeling cramped, and a well-handled category picker
- **Actual Budget** — the clearest treatment of envelope budgeting and of "what is safe to spend"
- **YNAB** — the reference for the philosophy of assigning every unit of income a job, which is the model underlying the fixed/variable/unpredictable split

### Deliberately not references

Dribbble, Behance, and Pinterest finance-app concepts. They optimise for a static image at 2× on a desktop screen — gradients, glassmorphism, generous whitespace, six-figure balances. None of that survives contact with a 720p LCD, real data at 40 characters of category name, or a 6 MB APK budget. Following them is the most reliable way to produce something that looks excellent in a screenshot and is unusable at a shop counter.

---

## 12. Summary of consequential decisions

| Decision | Alternative rejected | Why |
|---|---|---|
| Light theme default | Dark default | LCD target — no battery gain, worse in sunlight |
| Ledger rules over cards | Material card stack | Less overdraw; matches the object being replaced |
| Safe-to-spend as hero | Total balance / total spent | It's the only figure that answers a question the user actually has |
| Month ribbon | Category donut chart | Encodes four facts instead of one; ~31 draw calls |
| One subsetted numeral font | Full display family, or system-only | 15 KB buys tabular figures exactly where they matter |
| Custom numeric keypad | System IME | Removes 150–300 ms from the most frequent action |
| Fixed tweens, 80–200 ms | M3 Expressive springs | Variable settle tail drops frames on Cortex-A53 |
| Dynamic colour off | Material You theming | API 31+ only; would hand semantic red to an algorithm |
| Income defaults to year | Month, for consistency | A ৳0 farming month is meaningless in isolation |
| Undo snackbars | Confirmation dialogs | Corrects after the fact instead of interrupting before it |
| No notifications | Budget alerts / reminders | No background service, and nagging gets apps uninstalled |
| Backup says when it runs | A bare "Every day" | There is no background service, so the honest sentence is "when you open DayBook". A feature nobody can trust is worse than one nobody has |
