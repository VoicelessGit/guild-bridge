package com.guildbridge.supervisor;

import com.google.gson.JsonObject;
import java.util.Map;

/** Where in-game commands get their data. HypixelApi in the bot; a fake in tests. */
interface PvData {
    boolean hasKey();

    HypixelApi.Player player(String name);

    JsonObject profiles(String uuid);

    JsonObject garden(String profileId);

    JsonObject museum(String profileId);

    JsonObject bazaar();

    JsonObject election();

    JsonObject firesales();

    JsonObject collections();

    JsonObject items();

    Map<String, Double> prices();
}
