package dev.zeffut.flashbackserver.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class FlashbackMeta {
    private static final Gson GSON = new GsonBuilder().create();

    @SerializedName("uuid") public String uuid = UUID.randomUUID().toString();
    @SerializedName("name") public String name = "";
    @SerializedName("version_string") public String versionString = "";
    @SerializedName("world_name") public String worldName = "";
    @SerializedName("data_version") public int dataVersion;
    @SerializedName("protocol_version") public int protocolVersion;
    @SerializedName("total_ticks") public int totalTicks;
    @SerializedName("chunks") public Map<String, ChunkMeta> chunks = new LinkedHashMap<>();

    public String toJson() { return GSON.toJson(this); }
    public static FlashbackMeta fromJson(String json) { return GSON.fromJson(json, FlashbackMeta.class); }
}
