package com.application.membershipmodule.member.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.service.MemberService;
import com.application.membershipmodule.member.web.dto.ChooseCohortRequest;
import com.application.membershipmodule.member.web.dto.MemberCohortResponse;

import jakarta.validation.Valid;

/**
 * Member self-service (distinct from an admin API — see docs/hld/README.md §3's "Admin surface"
 * row addendum). {@code cohortCode} is a member-chosen self-service attribute here, not an
 * admin-assigned one; the PRD's original MP-API-15 ("admin assigns a member to a cohort") is a
 * different, still-unbuilt capability, not superseded by this endpoint.
 */
@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @PostMapping("/me/cohort")
    public MemberCohortResponse chooseCohort(
            @RequestHeader("X-Member-Id") String externalMemberId,
            @Valid @RequestBody ChooseCohortRequest request) {
        Member member = memberService.resolveOrCreate(externalMemberId);
        return memberService.chooseCohort(member, request.cohortCode());
    }
}
