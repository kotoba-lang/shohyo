# kotoba-shohyo 諸表

**A trial balance folded into 貸借対照表 and 損益計算書.**

諸表 is the set of financial statements — read the name as *the statements*.
Not `kotoba-lang/kessai` (決済, settlement): one is what you report, the
other is money moving.

## What it refuses to do

**It does not guess what an account is.**

`bookkeeping.trial-balance` says of itself that it proves arithmetic, not
classification: an account is whatever string an entry named. Turning
`"cash"` into an asset because it is spelled that way would put
classification back where nobody declared it. So a **chart is an argument**,
and an account missing from it is named rather than dropped.

That is the whole design:

> A balance sheet that omits an account still balances. It is wrong, it looks
> right, and nothing about the output says which.

```clojure
(shohyo/statements chart (assoc balances ["mystery" "JPY"] {:balance 42}))
;; => {:shohyo/coverage :incomplete
;;     :shohyo/unclassified ["mystery"]
;;     :shohyo/by-currency {...}}   ; the numbers are still there
(shohyo/complete? …) ;; => false
```

`:incomplete` still carries the figures — refusing to show anything helps
nobody — but a caller that renders without reading `:shohyo/coverage` is
rendering a statement short by known accounts.

## Where it sits

```text
bookkeeping.posting ─┐
kakeibo.ledger      ─┼─▶ kotoba.banking ─▶ trial balance ─▶ kotoba.shohyo
                     ┘      (postings)        (balances)      (BS / PL)
```

Balances arrive as plain `{[account currency] {:balance n}}` — what
`bookkeeping.trial-balance/balances` produces — so this folds a trial balance
from anything, and `banking` stays the double-entry contract rather than
becoming a reporting engine. Zero dependencies.

## Boundary with kanjō 勘定

`cloud-itonami/kanjo` reads statements a listed company **disclosed** (EDGAR /
EDINET XBRL) and normalizes JP-GAAP, US-GAAP and IFRS onto one canonical
vocabulary. This library goes the other way: it **produces** a statement from
your own ledger.

They meet at that vocabulary. A chart may tag an account with a `:concept`,
and the accepted concepts are exactly kanjō's 16 — so an internally-produced
statement and an externally-read filing are comparable line for line.

The vocabulary is **vendored** (`resources/kanjo-canonical-concepts.edn`,
from kanjo @ `9834c1ff`) rather than depended on: kanjō is an actor and this
is a library, and a library depending on an actor has the arrow backwards.
The copy is what makes the alignment machine-checked instead of a claim in
prose, and the workspace already runs a `verify-vendored-copies` detector
over copies that name their upstream.

`concept-ok?` returns **nil**, not false, when the vocabulary cannot be
loaded — *this concept is unknown* and *nobody could check* are different
answers.

## Sign convention

Balances arrive debit-positive, as a trial balance produces. Assets and
expenses are debit-normal and appear as given; liabilities, equity and
revenue are credit-normal and are presented negated, so a statement reads the
way an accountant expects. The raw figure stays on each line as `:balance`,
so nothing is lost.

## The accounting equation is checked, not assumed

資産 = 負債 + 純資産 + 当期純利益, per currency. A fold that quietly
disagreed with the identity would still print two tidy columns.
`out-of-balance` names the currency and the difference, because a boolean is
not enough for whoever has to fix it.

Currencies never mix. Netting them would hide, one layer up, exactly the bug
this plane already shipped once at the entry level — a cross-currency journal
entry that "balanced" because nobody compared currencies.

`complete?` requires **both** full classification and the equation, and is
false for an empty ledger: an empty balance sheet balances trivially and says
nothing. Conservative in the same way as `worklaw/compliant?` and
`taxlaw/supported?`.

## 会社計算規則 — the JP statement structure, read from the regulation

`kotoba.shohyo` is jurisdiction-neutral: five account types and the
accounting equation. `kotoba.shohyo.jp` adds what a Japanese statement
actually looks like, and none of it is convention —— 会社計算規則
(418M60000010013, revision `..._20250331_507M60000010014`, retrieved
2026-08-18 from the e-Gov law API) states it, and `jp/provisions` quotes
every article implemented.

| | |
|---|---|
| 第七十三条 | 貸借対照表 = 資産 / 負債 / 純資産 |
| 第七十四条 | 資産の部 = 流動資産 / 固定資産 / 繰延資産; 固定資産 **contains** 有形 / 無形 / 投資その他 |
| 第八十八条 | 損益計算書 = the seven 区分, in the article's order |
| 第八十九〜九十二条 | the 段階利益 ladder: 売上総 → 営業 → 経常 → 税引前 |

### The rule a naive implementation gets wrong

第八十九条第二項, repeated identically in 第九十条, 第九十一条 and 第九十二条:

> 前項の規定にかかわらず、売上総損益金額が零未満である場合には、零から
> 売上総損益金額を減じて得た額を売上総損失金額として表示しなければならない。

A negative 売上総損益金額 is **not** a negative 売上総利益金額. It is a
**positive 売上総損失金額** — a different label and a sign flip. Printing
`-500` under 売上総利益 is the obvious implementation and is not what the
article says, so each rung returns a `:label`, not only a number. The signed
figure survives in `:shohyo.jp/concepts`, keyed by kanjō's names, so nothing
is lost for a caller that wants to compare with a filing.

Zero is a profit, not a loss: 零未満 is the condition, and zero is not below
zero.

**A missing section is not zero.** An absent 売上原価 is an unstated cost,
and treating it as zero would report a gross profit equal to sales, so the
ladder answers `:not-declared` and names every section it lacks.

Measured, all seven mutations red: present a loss as a negative profit (7),
relabel without flipping the sign (2), treat zero as a loss (1), compute the
ladder with sections missing (1 — **by NPE rather than by assertion, which is
weaker evidence and is recorded as such**), add 営業外費用 instead of
subtracting it (2), let a section contradict its article's account type (1),
flatten 固定資産's three subdivisions into peers (2).

## IAS 1 — the IFRS statement structure, and what could and could not be read

`kotoba.shohyo.ifrs` does for IAS 1 *Presentation of Financial Statements*
what `kotoba.shohyo.jp` does for 会社計算規則. **The core stays neutral and
knows about neither**: the module is the caller's choice.

### What was read

IFRS standards are copyrighted and behind registration, so the honest
expectation was that none of this could be quoted. Both of these turned out
to be public, and both were fetched on 2026-08-18:

| | |
|---|---|
| IFRS Foundation, IAS 1 (issued 2021, Part A), PDF | HTTP **200**, 246 388 bytes, no DRM |
| Commission Regulation (EC) No 1126/2008, consolidated at 2023-01-01, CELEX `02008R1126-20230101`, via CELLAR | HTTP **200**, 11 362 077 bytes |

They agree word for word on every paragraph quoted, which is why
`provisions` marks all eight `:read-from-source`: IAS 1.31, 1.32, 1.54, 1.56,
1.57, 1.60, 1.61, 1.82.

Three things were fetched and **not** used, and `catalog-verification`
records each with what it returned, because a citation nobody attempted and
one that failed look identical afterwards:

- the human-facing `eur-lex.europa.eu/legal-content/…` URL — **HTTP 202,
  empty body**. That is an async renderer saying *not this way*, not *no such
  text*; the CELLAR resource URL served the same act in full.
- `02008R1126-20240101` — **404**. 2023-01-01 is the consolidation that exists.
- `32008R1126`, the **un**consolidated 1126/2008 — **HTTP 200**, read, and
  rejected. It carries IAS 1 as revised in *2003*, whose paragraph 54 is a
  sentence about financial institutions and whose balance-sheet list is
  paragraph 68. Quoting IAS 1.54 from it would have been the wrong text under
  the right number.

### What was not read, and what that costs

IAS 1.54 and 1.82 fix the scope of particular items by pointing at other
standards — IFRS 17, IFRS 9 §5.5, IFRS 5, IAS 12, IAS 41 — and none of those
were read. Neither were IAS 1.66–76, the criteria that decide whether an
asset actually *is* current.

taxlaw's rule applies and is enforced in the code path, not in prose:
`requirable?` refuses to let any item whose scope is `:reachable-not-read`
make a statement conformant. A chart may still declare one — dropping it
silently is the bug this library exists to refuse — and the answer is then
`:scope-not-read`, naming the item.

`unread-provisions` prints the whole list, because a reader who cannot see
what was unread has to take the conformant answer on trust.

### The rule a naive implementation gets wrong

IAS 1.54 lists twenty items, so the obvious implementation is a checklist.
The standard says twice that it is not one:

> **57** This Standard does not prescribe the order or format in which an
> entity presents items. Paragraph 54 simply lists items that are
> sufficiently different in nature or function to warrant separate
> presentation …
>
> **31** … An entity need not provide a specific disclosure required by an
> IFRS if the information resulting from that disclosure is not material.
> This is the case even if the IFRS contains a list of specific requirements
> or describes them as minimum requirements.

A checklist reports every entity on earth non-conformant for having no
biological assets. **Materiality is the entity's judgement, not this
library's**: an item is required when the chart declares an account for it,
and missing when no line in that currency's statement carries it. Missing
items are **named** with their paragraph letter, never counted.

### The second rule a naive implementation gets wrong

IAS 1.60 lets an entity present in order of liquidity instead of
current/non-current. The obvious reading is that this turns the whole
section off. IAS 1.61 opens by saying it does not:

> **61 Whichever method of presentation is adopted**, an entity shall
> disclose the amount expected to be recovered or settled after more than
> twelve months for each asset and liability line item that combines
> amounts …

So the twelve-month disclosure is checked under **both** methods, and only
the per-line classification is switched off by the exception. And an
*undeclared* method is not a pass: defaulting to `:liquidity` would switch a
check off by default, and defaulting to the other would invent the entity's
choice.

### Three answers, one of which is a pass

| `:shohyo.ifrs/coverage` | |
|---|---|
| `:unread` | no paragraph is `:read-from-source` — nothing may be asserted |
| `:not-declared` | no account declares an `:ias1/item` |
| `:checked` | answered per currency, and **still need not be conformant** |

`presented?` is false for all but the last, the way `shohyo/complete?`,
`taxlaw/supported?` and `worklaw/compliant?` are — the convenient boolean
gives the conservative answer, not the flattering one. `:unread` and
`:not-declared` carry no `:shohyo.ifrs/conformant?` key at all, so a caller
reaching past the boolean gets nil.

Also read and enforced: **IAS 1.56** (deferred tax is never current) and the
item/type conflicts that would put a payable in the asset half. And **IAS
1.82(e)** and **(f)–(i)** are kept in the table marked `:deleted` rather than
omitted — *the Board removed this* and *we never implemented this* are
different facts, and a list that drops them cannot say which.

**IAS 1.32 (offsetting) is quoted and enforces nothing**, recorded as such in
`catalog-verification`. Offsetting happens upstream of a trial balance and
leaves no trace in the data that arrives. A cited article that silently backs
no check reads exactly like one that does.

### The one thing the core learned

`statements` now carries each chart entry onto its line as `:declared`. The
core still knows five account types and an equation and no jurisdiction — it
forwards whatever the caller declared and lets a module recognise its own
key, so this is an extension point rather than a hook for one framework.

It has to travel on the **line**, not be re-joined from the chart: the
statement is per currency and the chart is not. An account declared for a
required item but carrying no balance in USD is absent from the USD statement
while still present in the chart, and a module that asked the chart would
call that statement whole.

## Maturity

| | |
|---|---|
| Role | capability |
| Tests | 52 tests / 311 assertions green (`clojure -M:test`) |
| Dependencies | none |
| Mutations | 14 + 7 + 24 applied, all red |

Measured, every mutation red: dropping unclassified accounts silently (3),
returning an empty unclassified list (2), letting a typo'd account type
through (3), not checking the equation (3), aggregating across currencies
(17), answering `false` when the vocabulary is unloadable (1), presenting
credit-normal accounts unnegated (9).

> A first run of that harness printed `0 failures` seven times **without
> applying a single mutation** — a shell function that did not pass its
> arguments through. The mutation step is only worth anything if you can see
> that it ran; `applied` is now printed per mutation.

### Mutations — `kotoba.shohyo.ifrs` only

`nbb tools/check-mutations.cljs` then `nbb tools/mutate.cljs`. **This table
covers only the IFRS module and the one line it added to the core.** The
fourteen and seven mutations recorded above for the core and for
`kotoba.shohyo.jp` are separate runs; a green table here says nothing about
them.

Measured, 24 applied, **24 red, 0 survivors**. That was not the first run.
The first was **5 survivors and 1 unmeasured**, and every one was a real
finding:

| survivor | what it exposed |
|---|---|
| `:unread-paragraph-is-requirable` | every item in the table is `:read-from-source`, so `requirable?`'s read clause had never been exercised. Now downgraded under `with-redefs`. |
| `:liabilities-need-no-classification` | the test named *asset-or-liability* only ever dropped an **asset**'s classification. Now both, and both together. |
| `:not-declared-swallows-unverifiable` | a chart declaring **only** unread-scope items reported `:not-declared`, hiding the finding. *You tagged nothing* and *you tagged something nobody can verify* are different answers. |
| `:unread-list-counts-instead-of-naming` | the assertion was that `:affects` is non-empty, and `"1"` is non-empty — the same *counted, not named* failure this library refuses everywhere else. Written deliberately without looking at the tests, and it landed. |
| `:no-currency-is-a-pass` | **not a test gap.** The `(seq verdicts)` guard it targeted was unreachable — coverage is `:checked` only when a line declared an item, and lines live inside `by-currency`. The dead guard is gone and the id now targets the live invariant, that *every* currency must conform. |
| `:undeclared-method-passes` (unmeasured) | the replacement inserted a second `:holds?` into one map literal, so the file stopped **reading**. A mutation that breaks the reader demonstrates the reader. Re-aimed at a token that parses. |

Apache-2.0.
