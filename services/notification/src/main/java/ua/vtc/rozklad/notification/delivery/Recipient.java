package ua.vtc.rozklad.notification.delivery;

import java.util.UUID;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.binding.ChatBinding;

/** Target chat of a push message with a snapshot of its binding for the delivery log. */
public record Recipient(long chatId, UUID bindingId, BindingType bindingType, String title) {

    public static Recipient of(ChatBinding binding) {
        return new Recipient(binding.getChatId(), binding.getId(), binding.getType(), binding.getTitle());
    }
}
