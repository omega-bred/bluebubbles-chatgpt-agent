package io.breland.bbagent.server.agent.persistence.website;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebsiteAccountSenderLinkRepository
    extends JpaRepository<WebsiteAccountSenderLinkEntity, String> {
  List<WebsiteAccountSenderLinkEntity> findAllByAccountSubjectOrderByCreatedAtDesc(
      String accountSubject);

  Optional<WebsiteAccountSenderLinkEntity>
      findByAccountSubjectAndCoderAccountBaseAndGcalAccountBase(
          String accountSubject, String coderAccountBase, String gcalAccountBase);

  Optional<WebsiteAccountSenderLinkEntity> findByLinkIdAndAccountSubject(
      String linkId, String accountSubject);
}
