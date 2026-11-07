package ua.vtc.rozklad.notification.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.contracts.events.OrderPublished;
import ua.vtc.rozklad.notification.events.EventHandlers;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Photo;
import ua.vtc.rozklad.notification.support.IntegrationTest;
import ua.vtc.rozklad.notification.support.Order35;

/** order.published → only own rows to each group chat and teacher, summary to admins, public documents. */
class OrderDistributionIT extends IntegrationTest {

    private static final long CHAT_4KI1 = -100411;
    private static final long CHAT_4KI2 = -100412;
    private static final long CHAT_2KI3 = -100999;
    private static final long NEPYIVODA = 2001;
    private static final long KRENTSIN = 2002;

    @Autowired
    private EventHandlers handlers;
    @Autowired
    private JsonMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void chats() {
        owner();
        groupChatOf(CHAT_4KI1, CoreFixtures.GROUP_4KI1, "4КІ1");
        groupChatOf(CHAT_4KI2, CoreFixtures.GROUP_4KI2, "4КІ2");
        groupChatOf(CHAT_2KI3, CoreFixtures.GROUP_2KI3, "2КІ3");
        teacherChat(NEPYIVODA, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
        teacherChat(KRENTSIN, CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.");
    }

    @Test
    void everyChatGetsOnlyItsRowsAndThePicture() throws Exception {
        handlers.handle(new OrderPublished(EventEnvelope.create(Clock.systemUTC()), Order35.payload()));
        worker.drain();

        assertThat(telegram.lastSentTo(CHAT_4KI1).text()).startsWith("📢 <b>4КІ1</b> · зміни на понеділок, 19 жовтня")
                .contains("<b>НДКС · Непийвода</b>").doesNotContain("ОПЦЗ");
        assertThat(telegram.lastSentTo(CHAT_4KI2).text()).contains("<b>ОПЦЗ · Ганза</b>").doesNotContain("НДКС");
        assertThat(telegram.sentTo(CHAT_2KI3)).isEmpty();
        assertThat(telegram.photosTo(CHAT_2KI3)).isEmpty();

        Photo picture = telegram.photosTo(CHAT_4KI1).getFirst();
        assertThat(picture.caption()).isEqualTo("Розпорядження №35 на 19.10.2026");
        assertThat(picture.fileName()).isEqualTo("rozporiadzhennia-35-2026-10-19.png");
        assertThat(picture.png()).startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        // текст із рядками групи йде перед зображенням
        assertThat(telegram.lastSentTo(CHAT_4KI1).messageId()).isLessThan(picture.messageId());

        assertThat(telegram.lastSentTo(NEPYIVODA).text()).startsWith("📢 <b>Ваші зміни</b> на понеділок, 19 жовтня")
                .contains("<b>4КІ1, 2 пара</b>");
        assertThat(telegram.lastSentTo(KRENTSIN).text()).contains("<b>4КІ1, 2 пара</b>", "<b>4КІ2, 4 пара</b>");

        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("📄 <b>Опубліковано розпорядження №35</b>")
                .contains("Надіслано: 2 чатам груп, 2 викладачам")
                .contains("⚠️ Без Telegram: Крисак Л.");
        assertThat(telegram.photosTo(OWNER_CHAT)).hasSize(1);
        // одна конвертація на всіх: решта бере зображення з кешу
        assertThat(pdfConverter.conversions()).isEqualTo(1);

        mockMvc.perform(get("/api/notify/deliveries").param("orderId", Order35.ORDER_ID.toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.items[0].status").value("SENT"));
    }

    @Test
    void theSameEventTwiceIsHandledOnce() {
        OrderPublished event = new OrderPublished(EventEnvelope.create(Clock.systemUTC()), Order35.payload());
        handlers.handle(event);
        handlers.handle(event);
        worker.drain();
        assertThat(telegram.sentTo(CHAT_4KI1)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from outbound_message", Integer.class)).isEqualTo(8);
    }

    @Test
    void publishedOrderDocumentsArePublicAndCached() throws Exception {
        handlers.handle(new OrderPublished(EventEnvelope.create(Clock.systemUTC()), Order35.payload()));
        String base = "/api/documents/orders/" + Order35.ORDER_ID;

        byte[] docx = mockMvc.perform(get(base + ".docx"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("attachment; filename=\"rozporiadzhennia-35-2026-10-19.docx\"")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertThat(document.getParagraphs().getFirst().getText()).isEqualTo("РОЗПОРЯДЖЕННЯ №35");
        }

        String etag = mockMvc.perform(get(base + ".png"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=300")))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(get(base + ".png").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
        mockMvc.perform(get(base + ".pdf")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE));
        assertThat(pdfConverter.conversions()).isEqualTo(1);

        mockMvc.perform(get("/api/documents/orders/" + UUID.randomUUID() + ".pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Розпорядження не знайдено або ще не опубліковано."));
        mockMvc.perform(get(base + ".txt")).andExpect(status().isNotFound());
    }

    @Test
    void draftsAreRenderedOnlyForAdmins() throws Exception {
        String draft = json.writeValueAsString(Order35.payload(OrderStatus.DRAFT, null));
        mockMvc.perform(post("/api/documents/orders/render").param("format", "docx").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("rozporiadzhennia-chernetka-2026-10-19.docx")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(post("/api/documents/orders/render").param("format", "png").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE));
        mockMvc.perform(post("/api/documents/orders/render").param("format", "bmp").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("format"));
        mockMvc.perform(post("/api/documents/orders/render").with(teacher("teacher-token"))
                        .contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/documents/orders/render").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"number\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutLibreOfficeOrdersGoOutAsTextAndPdfIsUnavailable() throws Exception {
        pdfConverter.setAvailable(false);
        handlers.handle(new OrderPublished(EventEnvelope.create(Clock.systemUTC()), Order35.payload()));
        worker.drain();
        assertThat(telegram.sentTo(CHAT_4KI1)).hasSize(1);
        assertThat(telegram.photosTo(CHAT_4KI1)).isEmpty();
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).contains("Зображення не надсилалося");

        mockMvc.perform(get("/api/documents/orders/" + Order35.ORDER_ID + ".pdf"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Конвертер PDF недоступний"));
        mockMvc.perform(get("/api/documents/orders/" + Order35.ORDER_ID + ".docx")).andExpect(status().isOk());
    }

    @Test
    void amendmentNamesItsMainOrder() throws Exception {
        handlers.handle(new OrderPublished(EventEnvelope.create(Clock.systemUTC()), Order35.payload()));
        UUID amendmentId = UUID.randomUUID();
        OrderPayload amendment = new OrderPayload(amendmentId, 36, Order35.DATE, OrderKind.AMENDMENT,
                Order35.ORDER_ID, OrderStatus.PUBLISHED, null, "ПОНЕДІЛОК", "ЗНАМЕННИК",
                List.of(Order35.row("1КІ1", "3", OrderRowType.REPLACEMENT, "Матем.", "Вихристюк", "Відп.", "Фізика",
                        "Бевз", "212", null)), List.of(), List.of(), "Заступник директора з НР", "Микола ПОЛІЩУК",
                "I семестр 2026/2027", List.of(), List.of());
        handlers.handle(new OrderPublished(EventEnvelope.create(Clock.systemUTC()), amendment));

        byte[] docx = mockMvc.perform(get("/api/documents/orders/" + amendmentId + ".docx"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("dopovnennia-36-2026-10-19.docx")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertThat(document.getParagraphs()).extracting(XWPFParagraph::getText)
                    .startsWith("ДОПОВНЕННЯ №36", "до розпорядження №35");
        }
    }

    @Test
    void draftEventsAreNeverPublished() throws Exception {
        handlers.handle(new OrderPublished(EventEnvelope.create(Clock.systemUTC()),
                Order35.payload(OrderStatus.DRAFT, null)));
        worker.drain();
        assertThat(telegram.messageCount()).isZero();
        mockMvc.perform(get("/api/documents/orders/" + Order35.ORDER_ID + ".docx")).andExpect(status().isNotFound());
    }
}
