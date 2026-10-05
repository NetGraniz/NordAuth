package dev.nordfjell.auth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MessageFormatTest {
    @Test
    void displaysCommandArgumentsAsAngleBrackets() {
        var component = MiniMessage.miniMessage().deserialize(
            "<gold>Please log in with <white>/login \\<password></white>.</gold>");

        assertEquals("Please log in with /login <password>.",
            PlainTextComponentSerializer.plainText().serialize(component));
    }

    @Test
    void welcomeLinkOpensTheConfiguredWebsite() {
        var component = MiniMessage.miniMessage().deserialize(
            "<aqua>Website: <click:open_url:https://example.com><white><underlined>"
                + "https://example.com</underlined></white></click></aqua>");

        ClickEvent event = findClickEvent(component);
        assertNotNull(event);
        assertEquals(ClickEvent.Action.OPEN_URL, event.action());
        assertEquals(ClickEvent.openUrl("https://example.com"), event);
    }

    private static ClickEvent findClickEvent(Component component) {
        if (component.clickEvent() != null) return component.clickEvent();
        for (Component child : component.children()) {
            ClickEvent event = findClickEvent(child);
            if (event != null) return event;
        }
        return null;
    }
}
