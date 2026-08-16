package com.application.membershipmodule.member.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.repository.MemberRepository;

/**
 * Find-or-create resolution for the {@code X-Member-Id} header. No real authN exists
 * (docs/prd/06-api-contracts.md §0) so the first request from a given external id provisions the
 * {@link Member} row.
 */
@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final Clock clock;

    public MemberService(MemberRepository memberRepository, Clock clock) {
        this.memberRepository = memberRepository;
        this.clock = clock;
    }

    @Transactional
    public Member resolveOrCreate(String externalUserId) {
        return memberRepository.findByExternalUserId(externalUserId)
                .orElseGet(() -> memberRepository.save(new Member(externalUserId, Instant.now(clock))));
    }
}
