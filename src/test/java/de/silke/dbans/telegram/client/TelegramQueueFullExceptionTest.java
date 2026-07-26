package de.silke.dbans.telegram.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramQueueFullExceptionTest {

    @Test
    void exposesChatIdAndCapacity() {
        TelegramQueueFullException exception = new TelegramQueueFullException("123", 5);

        assertThat(exception.chatId()).isEqualTo("123");
        assertThat(exception.capacity()).isEqualTo(5);
        assertThat(exception.getMessage()).contains("123", "5");
    }

    @Test
    void rejectsNullChatId() {
        assertThatThrownBy(() -> new TelegramQueueFullException(null, 5))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new TelegramQueueFullException("123", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelegramQueueFullException("123", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isNotAShutdownOrCancellationSignal() {
        TelegramQueueFullException exception = new TelegramQueueFullException("123", 5);

        assertThat(exception).isNotInstanceOf(TelegramClientShuttingDownException.class);
        assertThat(exception).isNotInstanceOf(CancellationException.class);
    }
}