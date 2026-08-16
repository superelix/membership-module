# LLD-03 — Benefit Policy Engine

Implements PRD `03-benefits-and-perks.md` (`MP-BEN-*`) and the abstraction spec in PRD 03 §4,
which the architect review calls "the right shape" and "a clean, textbook answer." This document
turns that prose into interfaces and closes the same testability gap as LLD-02 §6 for the benefit
side.

## 1. The Benefit Abstraction

> **N4 fix (second review pass)**: two changes from the first-pass sketch, both required for the
> extensibility-proof test in §5 to actually compile (mirrors the LLD-02 §1 fix, same underlying
> defect). (1) The registry is keyed by `String`, not the `BenefitType` enum — `BenefitType` is
> kept only as a shipped-type catalog for seed data/admin DTOs. (2) `BenefitConfig` is no longer a
> `sealed interface` with a fixed `permits` list parsed by a central switch-based parser — each
> `BenefitPolicy` now owns parsing its own config from the raw `paramsJson` string. A `sealed`
> config hierarchy has exactly the same "closed to test code" problem as a closed enum: a test
> cannot add a new `permits` case from outside the file that declares the sealed interface. Making
> `BenefitConfig` an open marker interface removes that problem entirely — a test-only policy can
> define and return its own private config type with no production-code edit.

```java
public enum BenefitType { FREE_DELIVERY, PERCENTAGE_DISCOUNT, EXCLUSIVE_DEALS_ACCESS, PRIORITY_SUPPORT /* shipped-type catalog only — NOT the registry key, see note above */ }

public interface BenefitPolicy {
    String supportedType();                        // e.g. BenefitType.FREE_DELIVERY.name() for
                                                     // shipped types; an arbitrary string for a
                                                     // test-only or future out-of-tree type
    BenefitConfig parseConfig(String paramsJson);   // the policy owns its own config shape and
                                                     // parsing — no central sealed hierarchy/switch
    boolean isApplicable(BenefitConfig config, BenefitContext context);
    BenefitEffect apply(BenefitConfig config, BenefitContext context);
}

// BenefitConfig: open marker interface (not sealed) — each policy defines its own implementation
// privately; a test-only policy can add its own without touching this file
public interface BenefitConfig {}
public record FreeDeliveryConfig(BigDecimal minOrderValue, List<String> eligibleCategoryFilter) implements BenefitConfig {}
public record PercentageDiscountConfig(BigDecimal percentage, List<String> categoryFilter, BigDecimal maxDiscountAmount) implements BenefitConfig {}

public record BenefitContext(UUID memberId, Optional<Tier> tier, Optional<CheckoutCart> cart) {}
// tier absent = non-member checkout (MP-CHK-EDGE-03, see §2 — resolves N2)
// cart absent = "no order context" (e.g. resolving entitlements outside checkout, PRD 03 §4 pt.2)

// BenefitEffect: sealed hierarchy, deliberately kept closed — see §6's discussion of why a new
// *effect shape* (as opposed to a new *policy*) is the one place adding a benefit type still
// requires a small touch, and why that's an accepted, explicitly-scoped exception, not a violation
// of "pure addition." Existing effect shapes are reusable by any policy/type via the `source`
// string field, which is what keeps them usable from test code too (§5 Test 1).
public sealed interface BenefitEffect permits DeliveryFeeWaiver, LineItemDiscount, EntitlementFlag {}
public record DeliveryFeeWaiver(String source) implements BenefitEffect {}
public record LineItemDiscount(String source, UUID lineItemId, BigDecimal amount) implements BenefitEffect {}
public record EntitlementFlag(String source, Map<String, Object> metadata) implements BenefitEffect {}
```
`source` on every `BenefitEffect` variant is a `String` (the same `supportedType()` value, not the
`BenefitType` enum) for the same reason as `TierCriterionEvaluator` in LLD-02 — it lets a test-only
policy produce a real, renderable effect without needing a `BenefitType` constant to exist.

### Registry (identical idiom to LLD-02, deliberately — one pattern, two applications)

```java
@Component
public class BenefitPolicyRegistry {
    private final Map<String, BenefitPolicy> policies;
    public BenefitPolicyRegistry(List<BenefitPolicy> beans) {
        this.policies = beans.stream().collect(toMap(BenefitPolicy::supportedType, identity()));
    }
    public Optional<BenefitPolicy> find(String type) {
        return Optional.ofNullable(policies.get(type)); // absent, not throw — MP-BEN-EDGE-07
    }
}
```
Unlike `TierCriterionEvaluatorRegistry.get` (which throws — criteria are admin-validated at write
time against this same registry, so an unresolvable type should never reach evaluation),
`BenefitPolicyRegistry.find` returns `Optional` and `BenefitResolutionService` **skips** an
unresolvable `benefitType` with a logged warning rather than throwing — this is the literal
mechanism behind MP-BEN-EDGE-07 ("a missing policy implementation must degrade gracefully... never
500 the whole checkout").

### Concrete policies

| Class | `supportedType()` | Ships | `isApplicable` | `apply` |
|---|---|---|---|---|
| `FreeDeliveryPolicy` | `BenefitType.FREE_DELIVERY.name()` | Day-1 | `cart.subtotal() >= config.minOrderValue()` (`>=`, MP-CHK-EDGE-04) | `DeliveryFeeWaiver` |
| `PercentageDiscountPolicy` | `BenefitType.PERCENTAGE_DISCOUNT.name()` | Day-1 | at least one line item matches `categoryFilter` (or `ALL`) | one `LineItemDiscount` per matching line, `min(line.total * pct, remaining cap)` — see §4 for the order-level cap algorithm |
| `ExclusiveDealsAccessPolicy` | `BenefitType.EXCLUSIVE_DEALS_ACCESS.name()` | Increment 1 | always applicable if the tier has it (governs `Deal` visibility, not checkout totals) | `EntitlementFlag` used by the `GET /deals` filter, not by checkout math |
| `PrioritySupportPolicy` | `BenefitType.PRIORITY_SUPPORT.name()` | Increment 1 | always applicable if the tier has it | `EntitlementFlag{slaHours}` |

Adding a 5th benefit type (Increment 1+): new `BenefitType` enum value (catalog convenience only),
new private `BenefitConfig` implementation owned by the new policy, new `BenefitPolicy implements`
class registered as `@Component`. **Zero changes** to `BenefitResolutionService`, checkout total
computation, or any existing policy — see §5 for the proof test.

## 2. `BenefitResolutionService` — the Single Checkout-Facing Entry Point

> **N2 fix (second review pass)**: the first-pass sketch dereferenced `context.tier().id()` with
> no null check. `docs/lld/07-checkout-integration.md` §2 explicitly documents calling this method
> with an absent tier for non-member checkout (`MP-CHK-EDGE-03`/`MP-AC-045`) — as written, that call
> threw `NullPointerException` on the very first line, every time, which the `@RestControllerAdvice`
> would turn into an unhandled `500` for the exact case the PRD is most emphatic about degrading
> gracefully instead. `BenefitContext.tier()` is now `Optional<Tier>` (§1), and this method's first
> action is an explicit, named branch for the absent case — not an implicit null-check buried in a
> `Optional`/ternary one-liner, so it reads as a deliberate design decision, not a defensive
> afterthought:

```java
@Service
public class BenefitResolutionService {
    List<BenefitEffect> resolveApplicable(UUID memberId, BenefitContext context) {
        if (context.tier().isEmpty()) {
            // Non-member checkout (no ACTIVE/in-period subscription) or any other caller with no
            // resolvable tier — MP-CHK-EDGE-03: "no benefits" is genuinely just an empty list, not
            // a special code path or an error. See 07-checkout-integration.md §2/§3 for the
            // corresponding checkout-orchestrator-side gating logic (N3 fix) that decides when
            // context.tier() is empty vs. present.
            return List.of();
        }
        Tier tier = context.tier().get();
        List<TierBenefit> active = tierBenefitRepository.findActiveByTierId(tier.id(), clock.instant());
        List<BenefitEffect> effects = new ArrayList<>();
        for (TierBenefit tb : active) {
            Optional<BenefitPolicy> policy = registry.find(tb.benefitType());
            if (policy.isEmpty()) { log.warn("unresolvable benefitType {}", tb.benefitType()); continue; } // MP-BEN-EDGE-07
            BenefitConfig config = policy.get().parseConfig(tb.paramsJson());
            if (policy.get().isApplicable(config, context)) {
                effects.add(policy.get().apply(config, context));
            }
        }
        return effects;
    }
}
```
This does not know or care how many `BenefitType`s exist — it iterates whatever `TierBenefit` rows
are active and delegates entirely to the registry, exactly matching PRD 03 §4 pt.4's requirement.
The empty-tier branch above is the *only* place `resolveApplicable` treats "no tier" specially;
everything below it is unchanged and unconditional, so a member with a real tier but zero active
`TierBenefit` rows also naturally falls through to an empty `effects` list via the normal loop, not
via this branch — "no benefits" is one outcome reachable by two different, equally unremarkable
paths, not a special member-vs-non-member fork in the business logic.

## 3. Where `resolveApplicable` Is Called, and What Happens to the Result

- **`startCheckout`** (Day-1): called once with `context.cart = Optional.of(cart)`; the resulting
  `List<BenefitEffect>` is Jackson-serialized into `Order.benefitSnapshotJson` — this *is* the
  snapshot (LLD-01 §3, MP-CHK-EDGE-01). Never re-called for the same order afterward.
- **Entitlement query** (Increment 1, e.g. `GET /subscriptions/me` priority-support flag,
  `GET /deals`): called with `context.cart = Optional.empty()`; only `EntitlementFlag` effects are
  meaningful in this mode (a `PercentageDiscountPolicy` with an empty cart naturally produces no
  `LineItemDiscount`s since there are no lines to match — degrades gracefully, no special-casing).

## 4. Discount Cap Algorithm (MP-CHK-EDGE-05 — proportional trim)

```
function applyPercentageDiscount(config, cart):
    matching = cart.items.filter(item -> categoryMatches(item.category, config.categoryFilter))
    rawDiscounts = matching.map(item -> item.lineTotal * config.percentage / 100)
    rawTotal = sum(rawDiscounts)
    if config.maxDiscountAmount is null or rawTotal <= config.maxDiscountAmount:
        return zip(matching, rawDiscounts) as LineItemDiscount[]   // no capping needed
    scale = config.maxDiscountAmount / rawTotal
    return zip(matching, rawDiscounts.map(d -> round(d * scale, 2))) as LineItemDiscount[]
    // proportional trim, not "zero out the last line processed" — order-independent, deterministic
```
This directly implements MP-AC-021's requirement (₹900+₹900 over a ₹1,000 cap → ₹500+₹500, not
₹1,000+₹0), and is order-independent by construction since it scales every contributing line by
the same factor rather than processing lines sequentially and truncating.

## 5. Making the Abstraction's Value Checkable (mirrors LLD-02 §6)

### Test 1 — `BenefitResolutionServiceExtensibilityTest` (behavioral)

> **N4 fix (second review pass)**: the first-pass version registered a policy for a "fictitious
> `BenefitType`," which doesn't compile for the same reason as LLD-02's Test 1 — `BenefitType` was
> a closed enum and `BenefitConfig` was a closed `sealed` hierarchy, neither extendable from test
> code. §1's fix (string-keyed registry, open `BenefitConfig` marker interface, policy-owned
> parsing) removes both obstacles.

```java
@TestConfiguration
class BenefitExtensibilityTestConfig {
    static final String TEST_ONLY_FLAT_CREDIT = "TEST_ONLY_FLAT_CREDIT"; // arbitrary string,
        // not a BenefitType constant — proves the registry doesn't require one

    record TestOnlyConfig(BigDecimal creditAmount) implements BenefitConfig {} // local to the
        // test module — BenefitConfig is an open marker interface, so this needs no change to
        // production code, unlike the old sealed-hierarchy version

    @Bean BenefitPolicy flatCreditPolicy() {
        return new BenefitPolicy() {
            public String supportedType() { return TEST_ONLY_FLAT_CREDIT; }
            public BenefitConfig parseConfig(String json) { return new TestOnlyConfig(new BigDecimal("50.00")); }
            public boolean isApplicable(BenefitConfig c, BenefitContext ctx) { return true; }
            public BenefitEffect apply(BenefitConfig c, BenefitContext ctx) {
                return new EntitlementFlag(TEST_ONLY_FLAT_CREDIT, Map.of("creditAmount", ((TestOnlyConfig) c).creditAmount()));
            }
        };
    }
}
```
The test attaches a `TierBenefit` with `benefitType = "TEST_ONLY_FLAT_CREDIT"` to a scratch tier
and asserts `resolveApplicable` returns an `EntitlementFlag` with that `source` — again, no source
change to `BenefitResolutionService`, `BenefitType`, or `BenefitConfig`, only a new
`@TestConfiguration` bean and a locally-scoped test record. This compiles and runs today.
Reverting to a hardcoded `switch (benefitType) { case FREE_DELIVERY -> ...; }` inside
`BenefitResolutionService` makes this test fail immediately, since the switch has no case for
`"TEST_ONLY_FLAT_CREDIT"`.

### Test 2 — `BenefitResolutionServiceArchitectureTest` (structural, ArchUnit)
Same shape as LLD-02 §6 Test 2: `BenefitResolutionService` must not directly depend on any
concrete `BenefitPolicy` implementation class, only the interface and the registry.

### Test 3 — MP-BEN-EDGE-07 regression guard (explicitly named per Finding 9)
The architect review's Finding 9 flags MP-BEN-EDGE-07 (unresolvable benefit type must degrade
gracefully) as having zero `MP-AC-*` coverage despite being cheap to test. This design adds it
explicitly as an Increment-1 test obligation: seed a `TierBenefit` row with a `benefitType` string
that has no registered policy (simulating "policy removed from codebase, row still exists") and
assert `resolveApplicable` returns the remaining valid effects with the bad one skipped and a
warning logged — never a thrown exception, never a `500` from the checkout endpoint that calls it.

## 6. Extensibility Example — Referral Bonus, Not Birthday Bonus (resolves Finding 10)

PRD 08 §7 uses "birthday bonus" as its new-benefit-type example, but that needs
`Member.dateOfBirth`, which doesn't exist in the schema — undercutting the row's own "nothing else
touched" claim. This design uses **referral bonus** instead as the canonical "how to add a 6th
benefit type" walkthrough, since it's additive under the current schema (keyed off existing
`Member`/order data, no new columns):
1. Add `BenefitType.REFERRAL_BONUS` (catalog convenience only — seed data/admin DTOs).
2. Add a private `ReferralBonusConfig(BigDecimal creditAmount) implements BenefitConfig` and
   `ReferralBonusPolicy implements BenefitPolicy` — the policy's own `parseConfig` deserializes its
   `paramsJson` into `ReferralBonusConfig` (no central parser to touch, per §1's N4 fix);
   `isApplicable` checks whatever referral-tracking field/table already exists (or is added
   independently of this abstraction), `apply` returns a `LineItemDiscount`-shaped or a new
   `AccountCredit` effect type if the shape genuinely differs from existing effects.
3. Register as `@Component`. Nothing in `BenefitResolutionService`, checkout total computation, or
   `FreeDeliveryPolicy`/`PercentageDiscountPolicy` changes.
If a genuinely new effect shape is needed (e.g., "account credit" rather than "line discount" or
"delivery waiver"), add one more `permits` case to the `BenefitEffect` sealed interface and one
`case` to whatever exhaustive `switch` renders effects into the checkout response DTO
(`05-api-layer.md` §2) — this is the one place new effect *shapes* (not new benefit *types*) do
require a touch, and it's called out here explicitly so it isn't mistaken for a violation of the
"pure addition" claim: adding a new *policy* of an *existing* effect shape (the common case) is a
pure addition; adding a wholly new effect *shape* (rare — none of the Day-1/Increment-1 benefit
types need one) is not, by the nature of a sealed/exhaustive type.
