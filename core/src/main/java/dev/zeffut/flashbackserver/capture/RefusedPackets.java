package dev.zeffut.flashbackserver.capture;

import java.util.Set;

/**
 * The clientbound PLAY packets the Flashback client refuses to replay.
 *
 * <p>Flashback's {@code ReplayGamePacketHandler} implements {@code ClientGamePacketListener}, so it
 * has a method for every clientbound packet — but 54 of those methods are nothing but
 * {@code throw new UnsupportedPacketException(packet)}, several with the real body still present
 * and commented out. A recording containing any of them does not degrade gracefully: the exception
 * kills the client's integrated replay server, and the replay fails to open or play at all.
 *
 * <p>A server-side recorder that copies the outbound stream verbatim therefore produces a file the
 * client cannot use. Measured on one short recording from this plugin: 3,976 game packets, ~32% of
 * them refused types — 1,118 of those {@code ClientboundMoveEntityPacket}. That single number is
 * why {@code move_entities} exists (see
 * {@link dev.zeffut.flashbackserver.record.EntityPositionTracker}): dropping the vanilla movement
 * packets drops all movement, so the recorder has to re-express it in the form Flashback reads.
 *
 * <p>Matching is on class name only, so core needs no NMS types. It is a denylist rather than an
 * allowlist because these 54 names are stable across the 1.21.x range this plugin supports, while
 * the ~82 accepted packets churn every version — an over-tight allowlist would silently drop
 * working content.
 *
 * <p>Derived from {@code Moulberry/Flashback} at Flashback 0.39.7. Re-derive when bumping the
 * supported client version: a packet whose handler gains a real body should come off this list, or
 * its content is being thrown away for nothing.
 */
public final class RefusedPackets {

    private RefusedPackets() {}

    private static final Set<String> REFUSED = Set.of(
            "ClientboundAwardStatsPacket",
            "ClientboundBlockChangedAckPacket",
            "ClientboundChunkBatchFinishedPacket",
            "ClientboundChunkBatchStartPacket",
            "ClientboundCommandSuggestionsPacket",
            "ClientboundCommandsPacket",
            "ClientboundContainerClosePacket",
            "ClientboundContainerSetContentPacket",
            "ClientboundContainerSetDataPacket",
            "ClientboundCookieRequestPacket",
            "ClientboundCooldownPacket",
            "ClientboundCustomChatCompletionsPacket",
            "ClientboundCustomPayloadPacket",
            "ClientboundCustomReportDetailsPacket",
            "ClientboundDebugSamplePacket",
            "ClientboundDeleteChatPacket",
            "ClientboundDisconnectPacket",
            "ClientboundForgetLevelChunkPacket",
            "ClientboundKeepAlivePacket",
            "ClientboundMerchantOffersPacket",
            "ClientboundMountScreenOpenPacket",
            "ClientboundMoveEntityPacket",
            "ClientboundMoveMinecartPacket",
            "ClientboundOpenBookPacket",
            "ClientboundOpenScreenPacket",
            "ClientboundOpenSignEditorPacket",
            "ClientboundPingPacket",
            "ClientboundPlaceGhostRecipePacket",
            "ClientboundPlayerAbilitiesPacket",
            "ClientboundPlayerChatPacket",
            "ClientboundPlayerCombatEndPacket",
            "ClientboundPlayerCombatEnterPacket",
            "ClientboundPlayerCombatKillPacket",
            "ClientboundPlayerPositionPacket",
            "ClientboundPongResponsePacket",
            "ClientboundRecipeBookAddPacket",
            "ClientboundRecipeBookRemovePacket",
            "ClientboundRecipeBookSettingsPacket",
            "ClientboundSelectAdvancementsTabPacket",
            "ClientboundServerLinksPacket",
            "ClientboundSetCameraPacket",
            "ClientboundSetChunkCacheCenterPacket",
            "ClientboundSetChunkCacheRadiusPacket",
            "ClientboundSetCursorItemPacket",
            "ClientboundSetSimulationDistancePacket",
            "ClientboundStartConfigurationPacket",
            "ClientboundStoreCookiePacket",
            "ClientboundTagQueryPacket",
            "ClientboundTestInstanceBlockStatus",
            "ClientboundTickingStatePacket",
            "ClientboundTickingStepPacket",
            "ClientboundTransferPacket",
            "ClientboundUpdateAdvancementsPacket",
            "ClientboundUpdateRecipesPacket",

            // Not in ReplayGamePacketHandler — pure protocol framing with no content, emitted by
            // the pipeline's bundler around a ClientboundBundlePacket's sub-packets. The adapter's
            // expand() already drops it; listed here so that a pipeline whose bundler sits on the
            // other side of the encoder cannot leak one into the stream.
            "BundleDelimiterPacket",
            "ClientboundBundleDelimiterPacket");

    /**
     * Whether a packet of this class must be kept out of a recording.
     *
     * @param className the packet's binary class name ({@code Class#getName()}), inner classes
     *     included — {@code ClientboundMoveEntityPacket} only ever travels as one of its
     *     {@code $Pos} / {@code $PosRot} / {@code $Rot} subclasses, so the enclosing class is
     *     matched too. Using the binary name rather than {@code getSimpleName()} matters: the
     *     simple name of those subclasses is just {@code "Pos"}, which would collide with any
     *     other inner class of the same name.
     */
    public static boolean isRefused(String className) {
        String tail = className.substring(className.lastIndexOf('.') + 1);
        if (REFUSED.contains(tail)) return true;
        int inner = tail.indexOf('$');
        return inner > 0 && REFUSED.contains(tail.substring(0, inner));
    }

    /** The number of entries in the denylist (54 refused packets + 2 framing names). */
    public static int count() {
        return REFUSED.size();
    }
}
