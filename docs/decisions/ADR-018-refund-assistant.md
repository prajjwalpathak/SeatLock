# ADR-018: Refund Assistant: engine facts, retrieved policy, model explains, amounts guarded

- **Status:** Accepted (W7·D5)
- **Context:** Users ask "Can I cancel? How much do I get back?". The answer involves money and deadlines, which
  must be exactly what `RefundPolicyEngine` and the Cancel button will do. A language model is good at explaining,
  bad at arithmetic and prone to inventing policy.

## Decision
1. **Facts in code** (`RefundFactsService`): from the booking and the payment module's quote (the numbers the
   Cancel button uses) → situation (UNPAID, PAID_CANCELLABLE, PAID_CLOSED, EXPIRED, CANCELLED, REFUNDED), paid
   amount, refund now, the next tier (asked from the same engine just after the current tier ends), deadlines.
   Amounts formatted in code (Indian grouping), times in IST.
2. **Grounding:** the 3 nearest policy sections (ADR-017) go into the prompt with the facts; the model may only
   copy facts, never compute. User text stays the user message (300 chars) and is treated as data.
3. **Guard** (`AnswerGuard`, wired as the gateway's validation hook): every ₹/Rs/INR/"rupees" amount must be one the
   engine computed for this booking, every percentage one of this booking's or the policy's tiers, length ≤ 900.
   Any violation → `INVALID_OUTPUT` (audited) → template.
4. **Template** (`RefundTemplate`) per situation from the same facts: shown when AI is off, unavailable or
   rejected; always correct. The response says which one the user sees (`source: AI | TEMPLATE`) plus the excerpts.
5. **Read-only:** the assistant never cancels; the endpoint is `POST /bookings/{id}/assistant` (owner only → 404,
   `ai` rate limit, user quota via the gateway).

## Consequences
- A correct model answer can still be rejected if it mentions a number not in the facts (e.g. "the 24-hour rule"
  is fine, "₹0" for a closed booking is not unless 0 is an amount we computed); the template covers it.
- No answer cache: facts change with time (tiers) and booking state.
