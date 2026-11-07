package ua.vtc.rozklad.notification.documents;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublishedOrderRepository extends JpaRepository<PublishedOrder, UUID> {
}
