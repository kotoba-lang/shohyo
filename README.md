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

## Maturity

| | |
|---|---|
| Role | capability |
| Tests | 24 tests / 99 assertions green (`clojure -M:test`) |
| Dependencies | none |
| Mutations | 14 applied, 14 red |

Measured, every mutation red: dropping unclassified accounts silently (3),
returning an empty unclassified list (2), letting a typo'd account type
through (3), not checking the equation (3), aggregating across currencies
(17), answering `false` when the vocabulary is unloadable (1), presenting
credit-normal accounts unnegated (9).

> A first run of that harness printed `0 failures` seven times **without
> applying a single mutation** — a shell function that did not pass its
> arguments through. The mutation step is only worth anything if you can see
> that it ran; `applied` is now printed per mutation.

Apache-2.0.
