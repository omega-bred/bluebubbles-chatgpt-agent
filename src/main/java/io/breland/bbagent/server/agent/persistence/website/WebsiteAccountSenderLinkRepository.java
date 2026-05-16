package io.breland.bbagent.server.agent.persistence.website;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebsiteAccountSenderLinkRepository
    extends JpaRepository<WebsiteAccountSenderLinkEntity, String> {
  List<WebsiteAccountSenderLinkEntity> findAllByAccountSubjectOrderByCreatedAtDesc(
      String accountSubject);

  List<WebsiteAccountSenderLinkEntity> findAllByAccountBaseOrderByCreatedAtDesc(String accountBase);

  List<WebsiteAccountSenderLinkEntity> findAllByAccountBaseInOrderByCreatedAtDesc(
      Collection<String> accountBases);

  List<WebsiteAccountSenderLinkEntity>
      findAllByAccountBaseAndCoderAccountBaseAndGcalAccountBaseOrderByCreatedAtDesc(
          String accountBase, String coderAccountBase, String gcalAccountBase);

  List<WebsiteAccountSenderLinkEntity>
      findAllByAccountBaseInAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
          Collection<String> accountBases,
          Collection<String> coderAccountBases,
          Collection<String> gcalAccountBases);

  Optional<WebsiteAccountSenderLinkEntity>
      findByAccountSubjectAndCoderAccountBaseAndGcalAccountBase(
          String accountSubject, String coderAccountBase, String gcalAccountBase);

  List<WebsiteAccountSenderLinkEntity>
      findAllByAccountSubjectAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
          String accountSubject,
          Collection<String> coderAccountBases,
          Collection<String> gcalAccountBases);

  Optional<WebsiteAccountSenderLinkEntity> findByLinkIdAndAccountSubject(
      String linkId, String accountSubject);
}
