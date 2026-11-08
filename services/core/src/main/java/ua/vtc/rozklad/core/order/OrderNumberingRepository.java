package ua.vtc.rozklad.core.order;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderNumberingRepository extends JpaRepository<OrderNumbering, UUID> {
}
