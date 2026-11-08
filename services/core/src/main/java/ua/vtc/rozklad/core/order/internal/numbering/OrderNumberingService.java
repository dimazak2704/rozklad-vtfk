package ua.vtc.rozklad.core.order.internal.numbering;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.order.OrderDocumentRepository;
import ua.vtc.rozklad.core.order.OrderNumbering;
import ua.vtc.rozklad.core.order.OrderNumberingRepository;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/**
 * Numbering of orders per semester (DOMAIN §8): the next number is the last one + 1, but never below
 * the semester's first number (default 1). The first number can only be set above the numbers already
 * used, so it never produces a duplicate or goes back.
 */
@Service
@Transactional
public class OrderNumberingService {

    static final int DEFAULT_FIRST_NUMBER = 1;

    private final OrderNumberingRepository numberings;
    private final OrderDocumentRepository orders;
    private final SemesterRepository semesters;

    public OrderNumberingService(OrderNumberingRepository numberings, OrderDocumentRepository orders,
                                 SemesterRepository semesters) {
        this.numberings = numberings;
        this.orders = orders;
        this.semesters = semesters;
    }

    /** The number the next publication takes; call under {@link OrderDocumentRepository#lockNumbering}. */
    public int nextNumber(UUID semesterId) {
        return Math.max(orders.findMaxNumber(semesterId) + 1, firstNumber(semesterId));
    }

    @Transactional(readOnly = true)
    public OrderNumberingDto get(UUID semesterId) {
        requireSemester(semesterId);
        return dto(semesterId);
    }

    /**
     * @throws NotFoundException     unknown semester (404)
     * @throws BusinessRuleException the number is already used or passed in the semester (422)
     */
    public OrderNumberingDto setFirstNumber(UUID semesterId, int firstNumber, String actor) {
        requireSemester(semesterId);
        orders.lockNumbering(semesterId);
        int last = orders.findMaxNumber(semesterId);
        if (last >= firstNumber) {
            throw new BusinessRuleException("ORDER_NUMBER_USED", ("Номер %d уже пройдено: останнє розпорядження "
                    + "семестру має №%d. Оберіть номер, більший за %d.").formatted(firstNumber, last, last));
        }
        numberings.findById(semesterId).ifPresentOrElse(
                numbering -> numbering.update(firstNumber, actor),
                () -> numberings.save(new OrderNumbering(semesterId, firstNumber, actor)));
        return dto(semesterId);
    }

    private OrderNumberingDto dto(UUID semesterId) {
        int last = orders.findMaxNumber(semesterId);
        return new OrderNumberingDto(semesterId, firstNumber(semesterId), last == 0 ? null : last,
                nextNumber(semesterId));
    }

    private int firstNumber(UUID semesterId) {
        return numberings.findById(semesterId).map(OrderNumbering::getFirstNumber).orElse(DEFAULT_FIRST_NUMBER);
    }

    private void requireSemester(UUID semesterId) {
        if (!semesters.existsById(semesterId)) {
            throw NotFoundException.of("Семестр", semesterId);
        }
    }

    /** {@code GET/PUT /api/admin/orders/numbering}; {@code lastNumber} is {@code null} before the first order. */
    public record OrderNumberingDto(UUID semesterId, int firstNumber, Integer lastNumber, int nextNumber) {
    }
}
