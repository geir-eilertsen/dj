package dj.domain;

import dj.domain.model.ConcertProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConcertProvidersTest {

    private final InMemoryConcertStore choices = new InMemoryConcertStore();
    private final FakeConcertSource worldwide = new FakeConcertSource("worldwide", true);
    private final FakeConcertSource regional = new FakeConcertSource("regional", false);
    private final ConcertProvider keyless = new ConcertProvider("keyless", "Keyless", "", true);

    private ConcertProviders providers() {
        return new ConcertProviders(choices).add(worldwide).addUnavailable(keyless, "needs a key").add(regional);
    }

    @Test
    void asksTheOnesOnByDefaultUntilTheListenerChooses() {
        ConcertProviders providers = providers();
        assertEquals(List.of(worldwide), providers.enabled());

        providers.choose(Set.of("regional"));

        assertEquals(List.of(regional), providers.enabled());
        assertEquals(Map.of("worldwide", false, "regional", true), choices.choices());
    }

    @Test
    void listsEveryProviderInPriorityOrderWithUnavailableOnesOff() {
        List<ConcertProviders.Status> all = providers().all();

        assertEquals(List.of("worldwide", "keyless", "regional"), all.stream().map(s -> s.provider().id()).toList());
        ConcertProviders.Status keylessStatus = all.get(1);
        assertFalse(keylessStatus.enabled());
        assertFalse(keylessStatus.available());
        assertEquals("needs a key", keylessStatus.unavailable());
    }

    @Test
    void keepsTheChoiceForAProviderThatIsUnavailableForNow() {
        providers().choose(Set.of("keyless"));
        providers().choose(Set.of("worldwide"));
        ConcertProviders withKey = new ConcertProviders(choices).add(new FakeConcertSource("keyless", false));
        assertEquals(1, withKey.enabled().size());
    }

    @Test
    void rejectsUnknownAndDuplicateProviders() {
        assertThrows(IllegalArgumentException.class, () -> providers().choose(Set.of("bandsintown")));
        assertThrows(IllegalArgumentException.class, () -> providers().add(new FakeConcertSource("worldwide", true)));
    }
}
