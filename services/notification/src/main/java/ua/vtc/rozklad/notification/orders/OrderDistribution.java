package ua.vtc.rozklad.notification.orders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.contracts.events.OrderPayload.AffectedTeacher;
import ua.vtc.rozklad.contracts.events.OrderPayload.Item;
import ua.vtc.rozklad.contracts.events.OrderPublished;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundDraft;
import ua.vtc.rozklad.notification.delivery.Outbox;
import ua.vtc.rozklad.notification.delivery.Recipient;
import ua.vtc.rozklad.notification.documents.DocumentPrerenderer;
import ua.vtc.rozklad.notification.documents.OrderDocuments;
import ua.vtc.rozklad.notification.documents.PublishedOrders;
import ua.vtc.rozklad.notification.text.WebLinks;

/**
 * {@code order.published}: stores the order for public documents and queues its distribution —
 * each bound group chat gets only its rows plus the order picture, each affected teacher with
 * Telegram gets his rows, admin chats get a summary with the picture.
 */
@Service
public class OrderDistribution {

    private static final Logger log = LoggerFactory.getLogger(OrderDistribution.class);

    private final PublishedOrders publishedOrders;
    private final OrderDocuments documents;
    private final DocumentPrerenderer prerenderer;
    private final ChatBindings bindings;
    private final Outbox outbox;
    private final CoreQueries queries;
    private final WebLinks links;

    public OrderDistribution(PublishedOrders publishedOrders, OrderDocuments documents,
                             DocumentPrerenderer prerenderer, ChatBindings bindings, Outbox outbox,
                             CoreQueries queries, WebLinks links) {
        this.publishedOrders = publishedOrders;
        this.documents = documents;
        this.prerenderer = prerenderer;
        this.bindings = bindings;
        this.outbox = outbox;
        this.queries = queries;
        this.links = links;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onPublished(OrderPublished event) {
        OrderPayload order = event.order();
        if (order.status() != OrderStatus.PUBLISHED) {
            log.warn("order.published {} carries order {} in status {}: ignored", event.eventId(), order.id(),
                    order.status());
            return;
        }
        publishedOrders.store(order, event.eventId());
        queries.invalidateSchedules();
        boolean picture = documents.canRenderPictures();
        String pictureRef = OrderDocuments.orderPngRef(order.id());

        int groupChats = 0;
        Map<UUID, String> codes = new LinkedHashMap<>();
        order.affectedGroups().forEach(group -> codes.put(group.groupId(), group.code()));
        for (ChatBinding chat : bindings.groupChatsFor(codes.keySet())) {
            String code = codes.getOrDefault(chat.getGroupId(), chat.getGroupCode());
            List<Item> rows = OrderRows.forGroup(order, code);
            if (rows.isEmpty()) {
                continue;
            }
            Recipient recipient = Recipient.of(chat);
            outbox.enqueue(OutboundDraft.text(recipient, DeliveryPurpose.ORDER_GROUP,
                            OrderTexts.forGroup(order, code, rows))
                    .forOrder(order.id()).forEvent(event.eventId(), "rows"));
            if (picture) {
                outbox.enqueue(OutboundDraft.photo(recipient, DeliveryPurpose.ORDER_GROUP, pictureRef,
                                OrderTexts.pictureCaption(order))
                        .forOrder(order.id()).forEvent(event.eventId(), "picture"));
            }
            groupChats++;
        }

        int teachers = 0;
        List<String> withoutTelegram = new ArrayList<>();
        for (AffectedTeacher teacher : order.affectedTeachers()) {
            Optional<ChatBinding> chat = bindings.teacherChat(teacher.teacherId());
            List<Item> rows = OrderRows.forTeacher(order, teacher.name());
            if (chat.isEmpty()) {
                withoutTelegram.add(teacher.name());
                continue;
            }
            if (rows.isEmpty()) {
                log.warn("Order {}: no rows match affected teacher {} by name", order.id(), teacher.teacherId());
                continue;
            }
            outbox.enqueue(OutboundDraft.text(Recipient.of(chat.get()), DeliveryPurpose.ORDER_TEACHER,
                            OrderTexts.forTeacher(order, rows))
                    .forOrder(order.id()).forEvent(event.eventId(), "teacher"));
            teachers++;
        }

        String summary = OrderTexts.adminSummary(order, groupChats, teachers, withoutTelegram, picture,
                links.changes(order.date()));
        List<ChatBinding> adminChats = bindings.adminChats();
        for (ChatBinding admin : adminChats) {
            Recipient recipient = Recipient.of(admin);
            outbox.enqueue(OutboundDraft.text(recipient, DeliveryPurpose.ORDER_ADMIN, summary)
                    .forOrder(order.id()).forEvent(event.eventId(), "summary"));
            if (picture) {
                outbox.enqueue(OutboundDraft.photo(recipient, DeliveryPurpose.ORDER_ADMIN, pictureRef,
                                OrderTexts.pictureCaption(order))
                        .forOrder(order.id()).forEvent(event.eventId(), "picture"));
            }
        }
        if (picture) {
            prerenderer.pngAfterCommit(order.id());
        } else {
            log.warn("Order {} is distributed without the picture: PDF converter is not available", order.id());
        }
        log.info("Order {} (№{}) queued for {} group chat(s), {} teacher(s), {} admin chat(s)", order.id(),
                order.number(), groupChats, teachers, adminChats.size());
    }
}
