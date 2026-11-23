package com.seatlock.assistant.ai;

/** Which product feature made an LLM call (tag on metrics, column in {@code ai_requests}). */
public enum AiFeature {
    /** Admin connectivity check (POST /api/v1/admin/ai/ping). */
    PING,
    /** Free-text seat request → SeatIntent (W7·D3). */
    SEAT_FINDER,
    /** Explains the RefundPolicyEngine's decision (W7·D5). */
    REFUND_ASSISTANT
}
