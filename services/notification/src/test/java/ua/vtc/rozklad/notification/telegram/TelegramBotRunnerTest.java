package ua.vtc.rozklad.notification.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.telegram.TelegramApi.BotCommandSpec;

/** Command menus: what Telegram shows after «/» and what it accepts in {@code setMyCommands}. */
class TelegramBotRunnerTest {

    @Test
    void groupMenuOffersTheMorningScheduleSwitchesAndTheStatus() {
        assertThat(TelegramBotRunner.GROUP_COMMANDS).extracting(BotCommandSpec::command)
                .containsExactly("today", "tomorrow", "status", "rozklad_on", "rozklad_off", "bind", "help");
        assertThat(TelegramBotRunner.GROUP_COMMANDS).contains(
                new BotCommandSpec("rozklad_on", "Щоранку надсилати розклад групи"),
                new BotCommandSpec("rozklad_off", "Не надсилати ранковий розклад"));
    }

    @Test
    void everyCommandIsValidForTelegram() {
        for (List<BotCommandSpec> menu : List.of(TelegramBotRunner.PRIVATE_COMMANDS, TelegramBotRunner.GROUP_COMMANDS)) {
            assertThat(menu).allSatisfy(spec -> {
                // Bot API: 1–32 символи a-z, 0-9, _; опис 1–256 символів
                assertThat(spec.command()).matches("[a-z0-9_]{1,32}");
                assertThat(spec.description()).hasSizeBetween(1, 256);
            });
        }
    }
}
