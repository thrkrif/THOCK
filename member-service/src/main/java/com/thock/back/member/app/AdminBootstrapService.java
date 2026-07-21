package com.thock.back.member.app;

import com.thock.back.global.eventPublisher.EventPublisher;
import com.thock.back.member.domain.entity.Credential;
import com.thock.back.member.domain.entity.Member;
import com.thock.back.member.out.CredentialRepository;
import com.thock.back.member.out.MemberRepository;
import com.thock.back.shared.member.event.MemberJoinedEvent;
import com.thock.back.shared.member.event.MemberModifiedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrapService implements ApplicationRunner {
    private final MemberRepository memberRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final EventPublisher eventPublisher;

    @Value("${admin.bootstrap.enabled:false}")
    private boolean enabled;

    @Value("${admin.bootstrap.email:}")
    private String email;

    @Value("${admin.bootstrap.name:시스템 관리자}")
    private String name;

    @Value("${admin.bootstrap.password:}")
    private String password;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (email == null || email.isBlank() || password == null || password.length() < 8) {
            log.error("Admin bootstrap is enabled but ADMIN_EMAIL/ADMIN_PASSWORD is missing or invalid.");
            return;
        }

        Member member = memberRepository.findByEmail(email.trim()).orElse(null);
        if (member == null) {
            member = Member.signUp(email.trim(), name);
            member.promoteToAdmin();
            Member savedMember = memberRepository.save(member);
            credentialRepository.save(Credential.create(savedMember.getId(), passwordEncoder.encode(password)));
            eventPublisher.publish(new MemberJoinedEvent(savedMember.toDto()));
            log.info("Admin bootstrap member created. email={}", email);
            return;
        }

        if (member.getRole() != com.thock.back.shared.member.domain.MemberRole.ADMIN) {
            member.promoteToAdmin();
            eventPublisher.publish(new MemberModifiedEvent(member.toDto()));
        }
        Member ensuredMember = member;
        credentialRepository.findByMemberId(ensuredMember.getId())
                .ifPresentOrElse(
                        credential -> credential.changePassword(passwordEncoder.encode(password)),
                        () -> credentialRepository.save(Credential.create(ensuredMember.getId(), passwordEncoder.encode(password)))
                );
        log.info("Admin bootstrap member ensured. email={}", email);
    }
}
