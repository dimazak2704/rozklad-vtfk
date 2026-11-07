package ua.vtc.rozklad.notification.support;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import ua.vtc.rozklad.notification.core.ImporterClient;

/** importer stand-in: answers with the prepared parse result or fails. */
public class FakeImporterClient implements ImporterClient {

    public record Request(String text, UUID teacherId, LocalDate today) {
    }

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile AbsenceParse answer;
    private volatile RuntimeException failure;

    public void reset() {
        requests.clear();
        answer = null;
        failure = null;
    }

    public void answer(AbsenceParse value) {
        this.answer = value;
    }

    public void fail(RuntimeException value) {
        this.failure = value;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public AbsenceParse parseAbsence(String text, UUID teacherId, LocalDate today) {
        requests.add(new Request(text, teacherId, today));
        if (failure != null) {
            throw failure;
        }
        return answer;
    }
}
