package ua.vtc.rozklad.notification.substitution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionMessageRepository extends JpaRepository<SubstitutionMessage, UUID> {

    List<SubstitutionMessage> findByChangeId(UUID changeId);

    boolean existsByChatIdAndMessageId(long chatId, long messageId);
}
