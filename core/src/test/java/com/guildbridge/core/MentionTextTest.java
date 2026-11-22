package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MentionTextTest {
    @Test
    void pingsUseDisplayNames() {
        var users = Map.of("111111111111111111", "Moshe", "222222222222222222", "DiscordUser");
        assertEquals("hey @Moshe and @DiscordUser",
                MentionText.render("hey <@111111111111111111> and <@!222222222222222222>", users, Map.of(), Map.of()));
    }

    @Test
    void rolesChannelsAndEmoji() {
        assertEquals("@Staff see #general :pog: :wave:",
                MentionText.render("<@&5> see <#6> <:pog:777> <a:wave:888>",
                        Map.of(), Map.of("5", "Staff"), Map.of("6", "general")));
    }

    @Test
    void unknownIdsStayReadableAndPlainTextIsUntouched() {
        assertEquals("@unknown-user hi", MentionText.render("<@9> hi", Map.of(), Map.of(), Map.of()));
        assertEquals("price is $5 <3", MentionText.render("price is $5 <3", Map.of(), Map.of(), Map.of()));
    }
}
