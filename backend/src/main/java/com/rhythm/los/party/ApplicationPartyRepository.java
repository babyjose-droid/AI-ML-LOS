package com.rhythm.los.party;

public interface ApplicationPartyRepository extends org.springframework.data.jpa.repository.JpaRepository<ApplicationParty, Long> {
    java.util.List<ApplicationParty> findByApplicationIdOrderByIdAsc(Long applicationId);
    java.util.Optional<ApplicationParty> findByInviteHash(String inviteHash);
    @org.springframework.data.jpa.repository.Query("select count(p) from ApplicationParty p, com.rhythm.los.application.LoanApplication a where p.applicationId = a.id and p.pan = ?1 and p.role = 'GUARANTOR' and p.status <> 'REMOVED' and a.appState not in ('REJECTED','WITHDRAWN') and a.id <> ?2")
    long countOpenGuaranteesByPan(String pan, Long excludingAppId);
}
