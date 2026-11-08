package ua.vtc.rozklad.importer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.config.AiProperties;

class AiExtractionServiceTest {

    private static final UUID JOB = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    private final FakeAiAdapter fake = new FakeAiAdapter();
    private final AiUsageService usage = mock(AiUsageService.class);
    private AiExtractionService service;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties("", "fake", null, "claude-haiku-5-5", "claude-sonnet-5-5",
                new BigDecimal("2.00"), Duration.ofSeconds(60), 2, "low", "medium", 16000,
                Map.of(), new AiProperties.ModelPrice(BigDecimal.TEN, BigDecimal.TEN, null, null, null));
        when(usage.record(any(), any(), anyLong(), anyLong(), any(), any()))
                .thenAnswer(call -> new AiCallInfo(call.getArgument(1), call.getArgument(2), call.getArgument(3),
                        new BigDecimal("0.010000")));
        service = new AiExtractionService(fake, usage, properties, JsonMapper.builder().build());
    }

    @Test
    void visionCallUsesVisionModelSchemaAndRecordsUsage() {
        fake.reply(AiPurpose.ORDER_DOCUMENT, "{\"number\":35,\"kind\":\"ORDER\",\"rows\":[],\"notes\":[],\"warnings\":[]}",
                1500, 400);

        AiResult<AiExtractions.OrderExtraction> result = service.extractOrder(
                List.of(new AiDocument("image/jpeg", image("jpg", 40, 30))), JOB);

        assertThat(result.value().number()).isEqualTo(35);
        assertThat(result.call().inputTokens()).isEqualTo(1500);
        AiRequest request = fake.requests().getFirst();
        assertThat(request.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(request.effort()).isEqualTo("medium");
        assertThat(request.schema()).containsEntry("additionalProperties", false).containsKey("properties");
        assertThat(request.system()).contains("не вигадуй");
        verify(usage).record(AiPurpose.ORDER_DOCUMENT, FakeAiAdapter.MODEL, 1500, 400, AiUsageRecord.Outcome.SUCCESS, JOB);
    }

    @Test
    void textCallUsesTextModelAndPutsTheMessageInsideTags() {
        fake.reply(AiPurpose.ABSENCE_TEXT, """
                {"dateFrom":"2026-11-09","dateTo":"2026-11-09","pairs":null,"reasonCategory":"SICK_LEAVE",
                 "confidence":0.9,"clarification":null}""", 300, 60);

        var result = service.parseAbsence("у понеділок на лікарняному", LocalDate.of(2026, 11, 7));

        assertThat(result.value().dateFrom()).isEqualTo(LocalDate.of(2026, 11, 9));
        AiRequest request = fake.requests().getFirst();
        assertThat(request.model()).isEqualTo("claude-haiku-5-5");
        assertThat(request.effort()).isEqualTo("low");
        assertThat(request.documents()).isEmpty();
        assertThat(request.instruction()).contains("<message>\nу понеділок на лікарняному\n</message>").contains("2026-11-07");
    }

    @Test
    void refusalIsRecordedAndReported() {
        fake.reply(AiPurpose.ORDER_DOCUMENT, new AiResponse("", "claude-sonnet-5-5", 1200, 0, AiResponse.Stop.REFUSED));

        assertThatThrownBy(() -> service.extractOrder(List.of(new AiDocument("image/png", image("png", 40, 30))), JOB))
                .isInstanceOf(AiExtractionException.class)
                .hasMessageContaining("відмовилася");
        verify(usage).record(AiPurpose.ORDER_DOCUMENT, "claude-sonnet-5-5", 1200, 0, AiUsageRecord.Outcome.REFUSED, JOB);
    }

    @Test
    void cutOffAndInvalidJsonAreFailuresWithUsage() {
        fake.reply(AiPurpose.CURRICULUM, new AiResponse("{\"semester\":7,\"rows\":[", "m", 9000, 16000,
                AiResponse.Stop.TRUNCATED));
        fake.reply(AiPurpose.CURRICULUM, "це не json", 9000, 100);
        List<AiDocument> pdf = List.of(new AiDocument(AiDocument.PDF, pdf()));

        assertThatThrownBy(() -> service.extractCurriculum(pdf, null, 7, JOB)).hasMessageContaining("обрізано");
        assertThatThrownBy(() -> service.extractCurriculum(pdf, null, 7, JOB)).hasMessageContaining("неочікуваному форматі");
        verify(usage).record(eq(AiPurpose.CURRICULUM), eq("m"), eq(9000L), eq(16000L), eq(AiUsageRecord.Outcome.TRUNCATED), eq(JOB));
        verify(usage).record(eq(AiPurpose.CURRICULUM), any(), eq(9000L), eq(100L), eq(AiUsageRecord.Outcome.INVALID), eq(JOB));
    }

    @Test
    void exhaustedBudgetStopsBeforeTheCall() {
        doThrow(new AiBudgetExceededException(new BigDecimal("2.10"), new BigDecimal("2.00"))).when(usage).ensureWithinBudget();

        assertThatThrownBy(() -> service.extractProgramHours("текст", JOB)).isInstanceOf(AiBudgetExceededException.class)
                .hasMessageContaining("$2.1").hasMessageContaining("$2");
        assertThat(fake.requests()).isEmpty();
        verify(usage, never()).record(any(), any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void disabledAdapterIsReportedAsUnavailable() {
        AiExtractionService disabled = new AiExtractionService(new DisabledAiAdapter(), usage, new AiProperties("",
                "anthropic", null, "t", "v", BigDecimal.ONE, Duration.ofSeconds(1), 0, "low", "low", 2048, Map.of(),
                new AiProperties.ModelPrice(BigDecimal.ONE, BigDecimal.ONE, null, null, null)), JsonMapper.builder().build());

        assertThat(disabled.available()).isFalse();
        assertThatThrownBy(disabled::ensureCanCall).isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void largePhotosAreDownscaledToJpegBelowTheApiLimit() throws Exception {
        // плавний градієнт із дрібним шумом: PNG великий, а JPEG після зменшення — компактний, як у фото
        BufferedImage image = new BufferedImage(3200, 2400, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int red = Math.min(255, x * 240 / image.getWidth() + random.nextInt(16));
                int green = Math.min(255, y * 240 / image.getHeight() + random.nextInt(16));
                int blue = 120 + random.nextInt(16);
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        assertThat(png.size()).isGreaterThan(AiDocumentPreparer.MAX_IMAGE_BYTES);

        AiDocument prepared = AiDocumentPreparer.prepare(new AiDocument("image/png", png.toByteArray()));

        assertThat(prepared.mediaType()).isEqualTo("image/jpeg");
        assertThat(prepared.size()).isLessThanOrEqualTo(AiDocumentPreparer.MAX_IMAGE_BYTES);
        BufferedImage scaled = ImageIO.read(new java.io.ByteArrayInputStream(prepared.content()));
        assertThat(Math.max(scaled.getWidth(), scaled.getHeight())).isEqualTo(2000);
    }

    /** r3-ai-imports-04: the API refuses a side over 8000 px even under 5 MB. */
    @Test
    void imagesOverTheApiSideLimitAreDownscaledEvenWhenSmallInBytes() throws Exception {
        byte[] tall = image("jpg", 600, 8400);
        assertThat(tall.length).isLessThan(AiDocumentPreparer.MAX_IMAGE_BYTES);

        AiDocument prepared = AiDocumentPreparer.prepare(new AiDocument("image/jpeg", tall));

        BufferedImage scaled = ImageIO.read(new java.io.ByteArrayInputStream(prepared.content()));
        assertThat(scaled.getHeight()).isEqualTo(AiDocumentPreparer.TARGET_SIDE);
        assertThat(scaled.getWidth()).isEqualTo(143);
    }

    @Test
    void imagesWithinTheLimitsGoUnchanged() throws Exception {
        byte[] photo = image("jpg", 1200, 900);
        assertThat(AiDocumentPreparer.prepare(new AiDocument("image/jpeg", photo)).content()).isEqualTo(photo);
    }

    /** r3-ai-imports-07: a damaged file fails before the paid call, with a plain reason. */
    @Test
    void damagedImageOrFakePdfFailsBeforeTheCall() {
        byte[] jpegHeader = java.util.Arrays.copyOf(image("jpg", 40, 30), 20);
        byte[] damagedJpeg = new byte[3000];
        new java.util.Random(3).nextBytes(damagedJpeg);
        System.arraycopy(jpegHeader, 0, damagedJpeg, 0, jpegHeader.length);
        byte[] fakePdf = new byte[3000];
        new java.util.Random(4).nextBytes(fakePdf);
        System.arraycopy("%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, fakePdf, 0, 8);

        assertThatThrownBy(() -> service.extractOrder(List.of(new AiDocument("image/jpeg", damagedJpeg)), JOB))
                .isInstanceOf(AiExtractionException.class).hasMessage(AiDocumentPreparer.DAMAGED_IMAGE);
        assertThatThrownBy(() -> service.extractCurriculum(List.of(new AiDocument(AiDocument.PDF, fakePdf)), null, 7, JOB))
                .isInstanceOf(AiExtractionException.class).hasMessage(AiDocumentPreparer.DAMAGED_PDF);
        assertThat(fake.requests()).isEmpty();
        verify(usage, never()).record(any(), any(), anyLong(), anyLong(), any(), any());
    }

    static byte[] image(String format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(java.awt.Color.BLACK);
        graphics.drawLine(0, 0, width - 1, height - 1);
        graphics.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static byte[] pdf() {
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
