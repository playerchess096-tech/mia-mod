package com.osservatore;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * Effetti speciali.
 *
 * Suoni, particelle, effetti e creature passano dai normali comandi di Minecraft
 * (/playsound, /particle, /effect, /summon): cambiano pochissimo da una versione
 * all'altra, quindi la mod resta facile da aggiornare. Titoli, barra azione e
 * "scossa" della telecamera usano invece i pacchetti diretti al giocatore.
 *
 * I comandi sono eseguiti dal server in silenzio (nessun messaggio in chat).
 */
final class Fx {

    /** Etichetta delle figure lontane, per ritrovarle e rimuoverle. */
    static final String STALKER_TAG = "osservatore_stalker";

    private Fx() {}

    static String n(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Esegue un comando come "Server", senza output. */
    static void run(MinecraftServer server, String command) {
        if (server == null) {
            return;
        }
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        } catch (RuntimeException e) {
            OsservatoreMod.LOGGER.warn("Comando fallito: {} ({})", command, e.toString());
        }
    }

    /** Prefisso che esegue il resto del comando "come" il giocatore e dalla sua posizione. */
    private static String as(ServerPlayer p) {
        String u = p.getStringUUID();
        return "execute as " + u + " at " + u + " run ";
    }

    // ------------------------------------------------------------------ suoni

    /** Suono sentito solo da {@code p}, a una distanza (dx, dy, dz) da lui. */
    static void sound(MinecraftServer s, ServerPlayer p, String id, double dx, double dy, double dz, float vol, float pitch) {
        soundSrc(s, p, id, "master", dx, dy, dz, vol, pitch);
    }

    static void soundSrc(MinecraftServer s, ServerPlayer p, String id, String source,
                         double dx, double dy, double dz, float vol, float pitch) {
        String u = p.getStringUUID();
        run(s, as(p) + "playsound " + id + " " + source + " " + u
                + " ~" + n(dx) + " ~" + n(dy) + " ~" + n(dz) + " " + n(vol) + " " + n(pitch));
    }

    /** Suono in un punto fisso del mondo (coordinate assolute). */
    static void soundAt(MinecraftServer s, ServerPlayer p, String id, double x, double y, double z, float vol, float pitch) {
        run(s, "playsound " + id + " master " + p.getStringUUID()
                + " " + n(x) + " " + n(y) + " " + n(z) + " " + n(vol) + " " + n(pitch));
    }

    // -------------------------------------------------------------- particelle

    /** Nuvola di particelle attorno al giocatore. */
    static void particle(MinecraftServer s, ServerPlayer p, String id, double dy, double spread, int count) {
        run(s, as(p) + "particle " + id + " ~ ~" + n(dy) + " ~ "
                + n(spread) + " " + n(spread * 0.6) + " " + n(spread) + " 0.02 " + count + " force " + p.getStringUUID());
    }

    /** Nuvola di particelle in un punto fisso, visibile solo a {@code p}. */
    static void particleAt(MinecraftServer s, ServerPlayer p, String id, double x, double y, double z, double spread, int count) {
        run(s, as(p) + "particle " + id + " " + n(x) + " " + n(y) + " " + n(z) + " "
                + n(spread) + " " + n(spread * 1.5) + " " + n(spread) + " 0.02 " + count + " force " + p.getStringUUID());
    }

    // ----------------------------------------------------------------- effetti

    /** Effetto pozione senza icone/particelle. {@code id}: darkness, blindness, nausea... */
    static void effect(MinecraftServer s, ServerPlayer p, String id, int seconds, int amplifier) {
        run(s, "effect give " + p.getStringUUID() + " minecraft:" + id + " " + seconds + " " + amplifier + " true");
    }

    // ---------------------------------------------------------- figura lontana

    /** Crea l'Enderman immobile e muto (la "figura") nel punto indicato, rivolto verso {@code yaw}. */
    static void summonStalker(MinecraftServer s, ServerPlayer p, double x, double y, double z, double yaw) {
        run(s, as(p) + "summon minecraft:enderman " + n(x) + " " + n(y) + " " + n(z)
                + " {NoAI:1b,Silent:1b,Invulnerable:1b,Tags:[\"" + STALKER_TAG + "\"],Rotation:[" + n(yaw) + "f,0.0f]}");
    }

    /** Fa girare la figura verso il giocatore. */
    static void faceStalker(MinecraftServer s, ServerPlayer p, double x, double y, double z) {
        run(s, as(p) + "execute positioned " + n(x) + " " + n(y) + " " + n(z)
                + " as @e[tag=" + STALKER_TAG + ",distance=..4,limit=1] at @s run tp @s ~ ~ ~ facing entity "
                + p.getStringUUID() + " eyes");
    }

    /**
     * Fa sparire la figura sul posto: la sposta sotto il mondo, dove il gioco la elimina da solo.
     * Così non c'è nessuna animazione di morte e non servono funzioni "interne" di Minecraft.
     */
    static void banishStalker(MinecraftServer s, ServerPlayer p, double x, double y, double z) {
        run(s, as(p) + "execute positioned " + n(x) + " " + n(y) + " " + n(z)
                + " as @e[tag=" + STALKER_TAG + ",distance=..4] run tp @s ~ -3000 ~");
    }

    /** Toglie tutte le figure rimaste, nelle tre dimensioni di base (pulizia di sicurezza). */
    static void killAllStalkers(MinecraftServer s) {
        for (String dimension : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
            run(s, "execute in " + dimension + " run kill @e[tag=" + STALKER_TAG + "]");
        }
    }

    // ------------------------------------------------- pacchetti verso il client

    static void title(ServerPlayer p, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        if (subtitle != null) {
            p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    static void actionBar(ServerPlayer p, Component text) {
        p.connection.send(new ClientboundSetActionBarTextPacket(text));
    }

    /** Fa "sobbalzare" la telecamera come quando si prende un colpo, ma senza danno. */
    static void hurtTilt(ServerPlayer p) {
        p.connection.send(new ClientboundHurtAnimationPacket(p));
    }
}
