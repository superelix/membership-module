package com.application.membershipmodule.member.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.application.membershipmodule.member.domain.Member;

public interface MemberRepository extends JpaRepository<Member, UUID> {
    Optional<Member> findByExternalUserId(String externalUserId);
}
