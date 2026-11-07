package ua.vtc.rozklad.core.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesRfc9562Version7Ids() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void idsAreUniqueAndCarryCurrentTimestamp() {
        long before = System.currentTimeMillis();
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }
        long after = System.currentTimeMillis();

        assertThat(ids).hasSize(10_000);
        long timestamp = ids.iterator().next().getMostSignificantBits() >>> 16;
        assertThat(timestamp).isBetween(before, after);
    }

    @Test
    void laterIdsSortAfterEarlierOnes() throws InterruptedException {
        UUID first = Ids.newId();
        Thread.sleep(2);
        UUID second = Ids.newId();

        assertThat(second).isGreaterThan(first);
    }
}
