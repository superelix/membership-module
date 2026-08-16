package com.application.membershipmodule.idempotency.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.application.membershipmodule.idempotency.domain.IdempotencyRecord;
import com.application.membershipmodule.idempotency.repository.IdempotencyRecordRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/lld/06-concurrency-and-transactions.md §3. Scoped to {@code POST /subscriptions} and
 * {@code POST /checkout} only (ADR-005) — callers check-then-replay before doing business work,
 * and store the response in the same {@code @Transactional} method as the business write, so a
 * sequential retry (MP-AC-029) always replays byte-identical output with no second side effect.
 *
 * <p><b>Known Day-1 limitation</b>: a genuinely simultaneous double-submit of the same
 * {@code (memberId, endpoint, key)} racing on the unique constraint is not retried-to-lookup here
 * (ADR-005 §3.1 point 3's full "loser retries the lookup" behavior). For {@code POST /subscriptions}
 * this is not load-bearing — the {@code activeMemberKey} unique constraint on {@code Subscription}
 * independently guarantees at most one row regardless. For {@code POST /checkout} a truly
 * simultaneous double-submit with an identical key could still race past this check-then-act
 * sequence; documented here as an accepted gap rather than silently left unstated.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public <T> Optional<T> findReplay(UUID memberId, String endpoint, String idempotencyKey, Class<T> type) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return repository.findByMemberIdAndEndpointAndIdempotencyKey(memberId, endpoint, idempotencyKey)
                .map(record -> deserialize(record.getResponseBodyJson(), type));
    }

    public void store(UUID memberId, String endpoint, String idempotencyKey, int status, Object body) {
        if (idempotencyKey == null) {
            return;
        }
        String json = serialize(body);
        repository.save(new IdempotencyRecord(memberId, endpoint, idempotencyKey, status, json, Instant.now(clock)));
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt idempotency record body", e);
        }
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize idempotency response body", e);
        }
    }
}
