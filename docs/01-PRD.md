# Product Requirements Document
**Product:** Personal Finance Manager (Android)
**Version:** 1.0 — MVP
**Platform:** Android only
**Date:** August 2026

---

## 1. Problem statement

Personal finance apps fail for one reason: logging a transaction is slow, so the user stops logging after two weeks. Once the data is incomplete, every report becomes untrustworthy and the app is abandoned.

A secondary failure mode applies specifically to users with irregular income — salary plus real estate plus farming. Standard apps assume a fixed monthly salary, so their "monthly average" figures are meaningless when five months earn nothing and the sixth earns a year's worth.

This product solves both: sub-5-second expense entry, and income analytics that separate stable from variable earnings.

## 2. Goals

| Goal | Measure of success |
|---|---|
| Frictionless daily logging | Expense entry completes in ≤ 3 taps, ≤ 5 seconds |
| Runs on cheap hardware | Smooth on 2 GB RAM, Android 8 device |
| Works with no connectivity | 100% of features function in airplane mode |
| Trustworthy numbers | Every reported total reconciles to the transaction ledger |
| Actionable insight | User can answer "can I afford this today?" in one glance |

## 3. Non-goals for v1

Explicitly out of scope. Each is a real feature, deliberately deferred:

- Bank, bKash, or Nagad API integration (not available to individuals)
- SMS-based transaction auto-import (fragile, format-dependent — flagship v2 candidate)
- Receipt photo capture or OCR
- Cloud sync, user accounts, authentication
- Multi-user or family sharing
- Multi-currency
- Investment, asset, or net-worth tracking
- Loan / EMI amortisation
- Debt payoff planning
- iOS, web, or tablet-optimised layouts
- AI assistant or natural-language entry
- Widgets, wear OS, notifications beyond budget alerts

## 4. Target user

Single user, owner of the device. Income from multiple heterogeneous sources, some regular and some seasonal. Comfortable with manual entry if it is fast. Uses a mid-range or budget Android phone. May have limited or intermittent connectivity.

There is one user account and no login. The device lock screen is the security boundary for v1; an optional app-level PIN is P1.

## 5. Core constraints

These are product requirements, not engineering preferences. They constrain the solution space and must be honoured in design reviews.

| Constraint | Target |
|---|---|
| APK size (download) | ≤ 6 MB |
| Installed size | ≤ 20 MB |
| Cold start to interactive | ≤ 800 ms on reference low-end device |
| Warm start | ≤ 250 ms |
| Expense save → UI updated | ≤ 100 ms |
| Dashboard render with 5 years of data | ≤ 300 ms |
| RAM (steady state) | ≤ 80 MB |
| Minimum Android version | 8.0 (API 26) |
| Network permission | Not requested at all in v1 |
| Offline capability | Total — no server exists |

**Reference low-end device for benchmarking:** 4× Cortex-A53 @ 1.4 GHz, 2 GB RAM, eMMC storage, Android 8.1, 720×1280 display. All performance targets are measured here, not on a flagship.

Not requesting the `INTERNET` permission is a deliberate product decision. It is the strongest possible privacy claim, it removes an entire class of security concerns, and it makes the offline-first requirement structurally impossible to violate.

## 6. Feature scope

### 6.1 Income management — P0

Record earnings from multiple named sources across arbitrary periods.

- Create an income entry: amount, source, date, optional note
- Typing a new source name creates that source automatically and reuses it thereafter
- Source names are unique, case- and whitespace-insensitive ("Salary" = "salary " = "Salary")
- The same source accepts unlimited entries in the same month (two farming sales in June are two entries, not one)
- Each source is classified **Stable** or **Variable** at creation, defaulting to Variable
- Sources can be archived, never deleted once entries exist
- Filter income by one or many sources, and by date range
- Totals for: selected month, selected year, arbitrary custom range
- Per-source breakdown with share-of-total percentage
- 12-month income trend

**Rationale for the Stable/Variable split:** it enables the single most useful metric for this user — the percentage of monthly expenses covered by stable income alone. That number tells them how exposed they are to a bad farming season.

### 6.2 Budget categories — P0

A two-level category tree with monthly spending limits.

- Three system root categories seeded at install: **Fixed Expenses**, **Variable Expenses**, **Unpredictable Expenses**
- System roots are renameable but not deletable
- User may create additional roots
- One level of subcategories per root (House Rent under Fixed, Grocery under Variable, New Clothes under Unpredictable)
- Depth is capped at two levels
- Create, rename, reorder, and archive categories
- Archived categories disappear from entry pickers but remain fully visible in historical reports
- Subcategories inherit `nature` (fixed / variable / unpredictable) from their root and cannot override it
- Monthly limit set per subcategory; a root's limit is the computed sum of its children
- "Copy budgets from last month" in one action
- Budget progress shown live as amount spent, limit, remaining, and percentage
- Alerts at 80% and 100% of a subcategory limit

**Two-level cap rationale:** arbitrary nesting sounds flexible but forces recursive rollups into every query and report, costs measurable time on low-end hardware, and delivers no benefit at personal-finance scale.

**Archive-not-delete rationale:** deleting a category orphans its historical transactions and silently rewrites last year's reports. Archiving preserves the ledger's integrity.

**Unpredictable Expenses is a buffer, not a plan.** Under-spending it is a win, not an unused allocation. It therefore gets a distinct visual treatment and is excluded from "under budget" nagging.

### 6.3 Daily expenses — P0

- Quick-add flow: amount → category → save
- Date defaults to today; account and category default to last used
- Only leaf subcategories are selectable, guaranteeing clean rollups
- Optional note and payment method (Cash, bKash, Nagad, Bank, Card)
- Edit and delete, with full downstream recalculation including past months
- Negative amounts permitted to represent refunds
- Ledger view filterable by date range, root, subcategory, and payment method
- Search by note text or amount

### 6.4 Dashboard and analytics — P0

One screen answering the questions that change behaviour.

| Metric | Definition | Why it matters |
|---|---|---|
| **Safe to spend today** | remaining variable + unpredictable budget ÷ days left in month | Converts a monthly abstraction into a decision at the shop counter |
| **Net position** | income received − expenses recorded, this month | The headline health number |
| **Savings rate** | (income − expense) ÷ income | Best single long-term indicator |
| **Burn-rate projection** | per category: current pace × days in month, vs limit | Warns on day 12, not day 30 |
| **Category delta** | this month vs trailing 3-month average, sorted by largest increase | Surfaces the *change*, which is the only actionable part |
| **Stable coverage** | stable income ÷ total expenses | Exposure to a bad season |
| **Fixed vs variable split** | share of spend by nature | Shows how much is actually controllable |
| **Top 5 largest expenses** | this month, descending | One purchase often explains a whole bad month |

Charts in v1: a single 6-month expense trend line with a budget reference line, and a 12-month income bar series. Both drawn with the platform canvas — no charting library.

### 6.5 Recurring transactions — P1

Templates for rent, internet, subscriptions, and regular salary. On the due date the app creates a *pending* entry requiring one-tap confirmation. Auto-posting without confirmation is available per-rule but off by default, because silently generated transactions that didn't actually happen destroy trust in the ledger faster than any other bug.

### 6.6 Data portability — P0

- Export the complete database to CSV (one file per entity) or JSON
- Import from a previously exported JSON file
- Export written via the system file picker to user-chosen storage

This is P0, not a nice-to-have. Users do not trust an app with their financial history until they have proof they can extract it. It is also the only backup mechanism in a no-server product.

### 6.7 Backup and restore — P1

- Nominate a folder once; Khata writes a dated backup there when it is opened, at most once a day, and only when something changed
- Keep the last several backups and delete older ones
- Optionally protect them with a passphrase
- Hand the newest one to another app — Drive, email, a chat app — with one tap
- Offer a restore on the first launch after an install, and from the recovery screen

§6.6 makes the *artifact* exist. This makes it exist without being remembered, which is a different requirement and the one that matters after a phone is lost. An export nobody runs is a backup nobody has.

Two limits are part of the design rather than gaps in it, and the interface says both out loud:

- **A phone left closed is not backed up.** There is no background service and no notification (05 §12), so backups happen when the app is opened. Promising "every day" would be promising something the app cannot deliver.
- **Getting a copy off the device is the user's tap.** Khata has no `INTERNET` permission and will not acquire one (FR-APP-01); "send a copy" hands the file to an app that already has one. Cloud backup of Khata's own remains P2, and `04 §11` records what it would cost.

## 7. Feature priority

| Priority | Scope |
|---|---|
| **P0 — MVP** | Income entry + sources, category tree, budgets, expense entry, ledger, dashboard, export/import |
| **P1 — Fast follow** | Recurring rules, app PIN lock, category reorder, budget copy templates, dark theme, automatic backup and restore |
| **P2 — Later** | SMS parsing, cloud backup, multi-device sync, receipt attachments, net worth |

## 8. Release plan

| Milestone | Content | Exit criterion |
|---|---|---|
| M1 | Schema, expense quick-add, ledger | Author uses it daily for one week |
| M2 | Category tree, budgets, alerts | Budgets reconcile against ledger |
| M3 | Income module | Yearly totals match manual calculation |
| M4 | Dashboard analytics | Dashboard renders in ≤ 300 ms with 5 years seeded data |
| M5 | Export/import, recurring, polish | Round-trip export→wipe→import loses nothing |

Ship M1 to yourself before building M2. Two weeks of genuine daily use will reveal more about what is missing than any amount of further specification.

## 9. Open decisions

Three questions change the database schema and must be settled before implementation:

1. **Canonical reporting date.** Money earned in June but received in July belongs to which month? One field must be chosen as canonical for all reports. *Recommendation: `earned_on`, with `received_on` deferred to v2 — a single date field halves the entry friction.*
2. **Refund modelling.** *Recommendation: negative amounts on the existing expense entity, not a separate refund type.*
3. **Account balances.** Does the user want running balances per payment method, or only categorised flows? *Recommendation: defer balances to v2 — they require opening-balance reconciliation and a transfer transaction type, which is significant scope for limited MVP value.*

## 10. Success criteria

The MVP is successful if, ninety days after first install, the author is still logging expenses on the same day they occur. No other metric matters at this stage.
