package com.osservatore;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Il "regista" dell'Osservatore: tiene il conto di ogni giocatore, decide quando
 * far succedere qualcosa e reagisce a chat, morti, sonno e minerali.
 *
 * La paura cresce con il tempo: più resti nel mondo (e più muori), più gli eventi
 * diventano pesanti. Si passa da semplici sussurri (stadio 0) fino agli spaventi
 * veri e propri (stadio 3).
 */
final class Director {

    /** Gli eventi che si possono lanciare a mano con /osservatore evento <nome>. */
    static final List<String> EVENT_IDS = List.of(
            "sussurro", "passi", "porta", "creeper", "tuono", "respiro",
            "falso_ingresso", "ombra", "buio", "coordinate", "disco11", "jumpscare");

    /** Un evento casuale: da quale stadio compare e quanto è probabile. */
    private record Option(String id, int minStage, int weight) {}

    private static final List<Option> OPTIONS = List.of(
            new Option("sussurro", 0, 30),
            new Option("passi", 1, 14),
            new Option("porta", 1, 10),
            new Option("creeper", 1, 8),
            new Option("tuono", 1, 8),
            new Option("respiro", 1, 10),
            new Option("falso_ingresso", 2, 10),
            new Option("ombra", 2, 14),
            new Option("buio", 2, 10),
            new Option("coordinate", 2, 8),
            new Option("disco11", 3, 3),
            new Option("jumpscare", 3, 10));

    /** Quello che ricordiamo di ogni giocatore connesso. */
    private static final class PlayerState {
        final UUID id;
        final long joined;
        long nextEvent;
        long lastReply = -1000;
        long lastMine = -100_000;
        long lastJumpscare = -1_000_000;
        long lastSeen;
        int deaths;
        String lastEvent = "";

        // la figura lontana ("ombra")
        boolean stalker;
        ServerLevel stalkerLevel;
        double sx, sy, sz;
        long stalkerBorn;
        int lookTicks;
        int faceCounter;

        PlayerState(UUID id, long now) {
            this.id = id;
            this.joined = now;
            this.lastSeen = now;
        }
    }

    private final Config cfg;
    private final Scheduler sched = new Scheduler();
    private final Random rnd = new Random();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private volatile MinecraftServer server;
    private long tick;
    private boolean orphansCleaned;

    Director(Config cfg) {
        this.cfg = cfg;
    }

    // =====================================================================
    // Ciclo principale
    // =====================================================================

    void onTick(MinecraftServer srv) {
        this.server = srv;
        tick++;
        sched.tick();

        if (!orphansCleaned) {
            orphansCleaned = true;
            Fx.killAllStalkers(srv); // figure rimaste da una partita chiusa male
        }

        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            PlayerState st = state(p.getUUID());
            st.lastSeen = tick;

            if (st.stalker && (tick + p.getId()) % 4 == 0) {
                stalkerTick(p, st);
            }
            if (cfg.intensity <= 0 || !p.isAlive() || p.isSpectator()) {
                continue;
            }
            if (tick >= st.nextEvent) {
                st.nextEvent = tick + nextDelay();
                randomEvent(p, st, false);
            }
        }

        if (tick % 200 == 0) {
            forgetPlayersWhoLeft();
        }
    }

    void onServerStopping(MinecraftServer srv) {
        for (PlayerState st : states.values()) {
            removeStalker(st, null);
        }
        sched.clear();
    }

    private PlayerState state(UUID id) {
        return states.computeIfAbsent(id, this::newState);
    }

    private PlayerState newState(UUID id) {
        PlayerState st = new PlayerState(id, tick);
        st.nextEvent = tick + 20L * 90 + rnd.nextInt(20 * 60); // il primo evento arriva dopo 90-150 secondi
        scheduleWelcome(id);
        return st;
    }

    private void forgetPlayersWhoLeft() {
        for (PlayerState st : states.values()) {
            if (tick - st.lastSeen > 100) {
                removeStalker(st, null);
                states.remove(st.id);
            }
        }
    }

    private int nextDelay() {
        int lo = Math.max(5, cfg.minSeconds);
        int hi = Math.max(lo + 1, cfg.maxSeconds);
        double factor = switch (cfg.intensity) {
            case 1 -> 2.0;
            case 3 -> 0.4;
            default -> 1.0;
        };
        return (int) ((lo + rnd.nextInt(hi - lo)) * 20 * factor);
    }

    /** 0 = sussurri, 1 = rumori, 2 = presenza, 3 = spaventi. Sale col tempo e con le morti. */
    private int stage(PlayerState st) {
        long minutes = (tick - st.joined) / 1200L;
        int s = (int) (minutes / 6) + st.deaths / 2 + (cfg.intensity - 2);
        return Math.max(0, Math.min(3, s));
    }

    // =====================================================================
    // Utilità
    // =====================================================================

    private ServerPlayer online(UUID id) {
        MinecraftServer s = server;
        return s == null ? null : s.getPlayerList().getPlayer(id);
    }

    /** Esegue {@code action} fra {@code ticks} tick, se il giocatore è ancora connesso. */
    private void later(UUID id, int ticks, Consumer<ServerPlayer> action) {
        sched.after(ticks, () -> {
            ServerPlayer p = online(id);
            if (p != null) {
                action.accept(p);
            }
        });
    }

    /** Aspetta che il giocatore sia di nuovo vivo (schermata di morte) e poi agisce. */
    private void afterRespawn(UUID id, int attempt, int extraDelay, Consumer<ServerPlayer> action) {
        sched.after(20, () -> {
            ServerPlayer p = online(id);
            if (p == null) {
                return;
            }
            if (p.isAlive()) {
                later(id, extraDelay, action);
            } else if (attempt < 40) {
                afterRespawn(id, attempt + 1, extraDelay, action);
            }
        });
    }

    private <T> T pick(T[] pool) {
        return pool[rnd.nextInt(pool.length)];
    }

    /** Sceglie una frase che si può "compilare" adesso (es. se serve l'oggetto in mano, che ci sia). */
    private String template(String[] pool, ServerPlayer p) {
        for (int i = 0; i < 8; i++) {
            String t = pick(pool);
            if (Lines.fill(t, p) != null) {
                return t;
            }
        }
        return null;
    }

    /** L'Osservatore parla nella chat del giocatore. */
    void say(ServerPlayer p, String template, boolean loud) {
        if (template == null) {
            return;
        }
        String text = Lines.fill(template, p);
        if (text == null) {
            return;
        }
        p.sendSystemMessage(Voice.speech(cfg, text, loud));
    }

    private void snd(ServerPlayer p, String id, double dx, double dy, double dz, float vol, float pitch) {
        Fx.sound(server, p, id, dx, dy, dz, vol, pitch);
    }

    /** Direzione "dietro" al giocatore (in base a dove guarda). */
    private static double behindX(ServerPlayer p) {
        return Math.sin(Math.toRadians(p.getYRot()));
    }

    private static double behindZ(ServerPlayer p) {
        return -Math.cos(Math.toRadians(p.getYRot()));
    }

    private void shake(UUID id, int times) {
        for (int i = 0; i < times; i++) {
            later(id, i * 2, Fx::hurtTilt);
        }
    }

    // =====================================================================
    // Benvenuto
    // =====================================================================

    private void scheduleWelcome(UUID id) {
        if (cfg.intensity <= 0) {
            return;
        }
        String[] seq = pick(Lines.WELCOME);
        int t = 20 * 7 + rnd.nextInt(60);
        for (String line : seq) {
            int when = t;
            later(id, when, p -> say(p, line, false));
            t += 50 + rnd.nextInt(40);
        }
    }

    // =====================================================================
    // Scelta degli eventi
    // =====================================================================

    /** Un evento casuale, scelto in base allo stadio di paura (o a qualunque stadio se {@code ignoreStage}). */
    private void randomEvent(ServerPlayer p, PlayerState st, boolean ignoreStage) {
        int stage = ignoreStage ? 3 : stage(st);
        boolean outdoors = ((ServerLevel) p.level()).canSeeSky(p.blockPosition());

        List<Option> pool = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (Option o : OPTIONS) {
            if (o.minStage() > stage || o.id().equals(st.lastEvent) || !allowed(o.id(), st)) {
                continue;
            }
            int w = o.weight();
            if (!outdoors && (o.id().equals("respiro") || o.id().equals("buio") || o.id().equals("passi"))) {
                w *= 2; // sottoterra o al chiuso fanno più effetto
            }
            if (outdoors && (o.id().equals("tuono") || o.id().equals("ombra"))) {
                w *= 2;
            }
            pool.add(o);
            weights.add(w);
            total += w;
        }
        if (pool.isEmpty()) {
            return;
        }

        int roll = rnd.nextInt(total);
        for (int i = 0; i < pool.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) {
                if (!trigger(p, pool.get(i).id())) {
                    trigger(p, "sussurro");
                }
                return;
            }
        }
    }

    private boolean allowed(String id, PlayerState st) {
        return switch (id) {
            case "jumpscare" -> cfg.jumpscare && tick - st.lastJumpscare > 20L * 60 * 8;
            case "ombra" -> cfg.stalker && !st.stalker;
            case "falso_ingresso" -> cfg.fakeJoin;
            default -> true;
        };
    }

    /** Per il comando /osservatore evento (senza nome): un evento a caso, subito. */
    void randomNow(ServerPlayer p) {
        randomEvent(p, state(p.getUUID()), true);
    }

    /** Lancia un evento preciso. Restituisce false se non è stato possibile (es. nessun posto per l'ombra). */
    boolean trigger(ServerPlayer p, String id) {
        if (server == null) {
            return false;
        }
        PlayerState st = state(p.getUUID());
        boolean ok = switch (id) {
            case "sussurro" -> { sussurro(p, st); yield true; }
            case "passi" -> { passi(p); yield true; }
            case "porta" -> { porta(p); yield true; }
            case "creeper" -> { creeper(p); yield true; }
            case "tuono" -> { tuono(p); yield true; }
            case "respiro" -> { respiro(p); yield true; }
            case "falso_ingresso" -> { falsoIngresso(p); yield true; }
            case "ombra" -> ombra(p, st);
            case "buio" -> { buio(p); yield true; }
            case "coordinate" -> { coordinate(p); yield true; }
            case "disco11" -> { disco11(p); yield true; }
            case "jumpscare" -> { jumpscare(p, st); yield true; }
            default -> false;
        };
        if (ok) {
            st.lastEvent = id;
        }
        return ok;
    }

    // =====================================================================
    // Gli eventi
    // =====================================================================

    /** Una frase nella chat, preceduta da un rumore lontano. */
    private void sussurro(ServerPlayer p, PlayerState st) {
        int stage = stage(st);
        String[] pool = Lines.AMBIENT_1;
        if (stage >= 3) {
            pool = Lines.AMBIENT_3;
        } else if (stage >= 2 && rnd.nextInt(3) != 0) {
            pool = Lines.AMBIENT_2;
        }
        String line = template(pool, p);
        if (line == null) {
            return;
        }
        boolean loud = pool == Lines.AMBIENT_3;
        snd(p, "minecraft:ambient.cave", 0, 0, 0, 0.8f, 0.7f + rnd.nextFloat() * 0.4f);
        later(p.getUUID(), 30, q -> say(q, line, loud));
    }

    /** Passi che si avvicinano da dietro. */
    private void passi(ServerPlayer p) {
        UUID id = p.getUUID();
        String step = stepSound(p);
        int steps = 5 + rnd.nextInt(4);
        int t = 20;
        for (int i = 0; i < steps; i++) {
            double dist = 10.0 - i * (7.5 / steps);
            double side = (rnd.nextDouble() - 0.5) * 2.0;
            float pitch = 0.85f + rnd.nextFloat() * 0.3f;
            later(id, t, q -> {
                double bx = behindX(q);
                double bz = behindZ(q);
                snd(q, step, bx * dist - bz * side, 0, bz * dist + bx * side, 1.0f, pitch);
            });
            t += 9 + rnd.nextInt(4);
        }
        if (rnd.nextInt(100) < 45) {
            String line = template(Lines.BEHIND, p);
            later(id, t + 35, q -> say(q, line, false));
        }
    }

    private String stepSound(ServerPlayer p) {
        ServerLevel level = (ServerLevel) p.level();
        if (!level.canSeeSky(p.blockPosition())) {
            return p.getY() < 0 ? "minecraft:block.deepslate.step" : "minecraft:block.stone.step";
        }
        String[] outdoor = {"minecraft:block.grass.step", "minecraft:block.gravel.step", "minecraft:block.sand.step"};
        return pick(outdoor);
    }

    /** Una porta (o un baule) che si apre e si richiude da qualche parte vicino. */
    private void porta(ServerPlayer p) {
        String[][] pairs = {
                {"minecraft:block.wooden_door.open", "minecraft:block.wooden_door.close"},
                {"minecraft:block.iron_door.open", "minecraft:block.iron_door.close"},
                {"minecraft:block.chest.open", "minecraft:block.chest.close"},
                {"minecraft:block.wooden_trapdoor.open", "minecraft:block.wooden_trapdoor.close"}};
        String[] pair = pick(pairs);
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = 4 + rnd.nextDouble() * 4;
        double x = p.getX() + Math.cos(angle) * dist;
        double y = p.getY();
        double z = p.getZ() + Math.sin(angle) * dist;
        Fx.soundAt(server, p, pair[0], x, y, z, 1.0f, 0.9f);
        later(p.getUUID(), 25 + rnd.nextInt(30), q -> Fx.soundAt(server, q, pair[1], x, y, z, 1.0f, 0.9f));
    }

    /** Il sibilo di un creeper dietro di te... che poi non esplode. */
    private void creeper(ServerPlayer p) {
        snd(p, "minecraft:entity.creeper.primed", behindX(p) * 3, 0, behindZ(p) * 3, 1.0f, 1.0f);
        if (rnd.nextBoolean()) {
            String line = template(Lines.CREEPER, p);
            later(p.getUUID(), 40, q -> say(q, line, false));
        }
    }

    /** Un tuono lontanissimo, anche se il cielo è sereno. */
    private void tuono(ServerPlayer p) {
        UUID id = p.getUUID();
        double angle = rnd.nextDouble() * Math.PI * 2;
        snd(p, "minecraft:entity.lightning_bolt.thunder", Math.cos(angle) * 40, 30, Math.sin(angle) * 40,
                6.0f, 0.7f + rnd.nextFloat() * 0.2f);
        later(id, 70, q -> snd(q, "minecraft:ambient.cave", 0, 0, 0, 1.0f, 0.6f));
        if (rnd.nextInt(100) < 50) {
            String line = template(Lines.THUNDER, p);
            later(id, 100, q -> say(q, line, false));
        }
    }

    /** Battito del cuore che accelera e vista che si restringe. */
    private void respiro(ServerPlayer p) {
        UUID id = p.getUUID();
        Fx.effect(server, p, "darkness", 12, 0);
        Fx.actionBar(p, Voice.narration("Senti il cuore battere nelle orecchie."));
        for (int i = 0; i < 7; i++) {
            later(id, i * 18, q -> snd(q, "minecraft:entity.warden.heartbeat", 0, 0, 0, 1.3f, 1.0f));
        }
        String line = template(Lines.BREATH, p);
        later(id, 7 * 18 + 30, q -> say(q, line, false));
    }

    /** "Osservatore è entrato nel gioco"... poi parla... poi "ha lasciato il gioco". */
    private void falsoIngresso(ServerPlayer p) {
        UUID id = p.getUUID();
        p.sendSystemMessage(Voice.fakeJoined(cfg));
        String line = template(Lines.FAKE_JOIN, p);
        later(id, 50 + rnd.nextInt(30), q -> say(q, line, false));
        later(id, 170 + rnd.nextInt(60), q -> q.sendSystemMessage(Voice.fakeLeft(cfg)));
    }

    /** Il buio si chiude attorno al giocatore. */
    private void buio(ServerPlayer p) {
        UUID id = p.getUUID();
        Fx.effect(server, p, "darkness", 14, 0);
        snd(p, "minecraft:ambient.cave", 0, 0, 0, 1.0f, 0.7f);
        later(id, 45, q -> Fx.effect(server, q, "blindness", 2, 0));
        later(id, 100, q -> snd(q, "minecraft:entity.warden.nearby_close", 0, 0, 0, 0.8f, 1.0f));
        String line = template(Lines.DARK, p);
        later(id, 70, q -> say(q, line, false));
    }

    /** L'entità ti dice dove sei. Anime di sculk attorno e un sussulto. */
    private void coordinate(ServerPlayer p) {
        UUID id = p.getUUID();
        String line = template(Lines.COORDS, p);
        Fx.particle(server, p, "minecraft:sculk_soul", 1.0, 2.5, 25);
        snd(p, "minecraft:entity.warden.sniff", 0, 0, 0, 1.0f, 0.8f);
        later(id, 40, q -> {
            say(q, line, false);
            Fx.hurtTilt(q);
        });
        later(id, 46, Fx::hurtTilt);
    }

    /** Il disco "11": la traccia più inquietante del gioco. */
    private void disco11(ServerPlayer p) {
        Fx.soundSrc(server, p, "minecraft:music_disc.11", "record", 0, 0, 0, 1.0f, 1.0f);
    }

    /** Lo spavento vero: urlo, schermo che trema, titolo, buio improvviso. */
    private void jumpscare(ServerPlayer p, PlayerState st) {
        UUID id = p.getUUID();
        st.lastJumpscare = tick;
        snd(p, "minecraft:entity.ghast.scream", 0, 0, 0, 1.5f, 0.6f);
        snd(p, "minecraft:entity.enderman.scream", behindX(p) * 2, 0, behindZ(p) * 2, 1.2f, 0.5f);
        Fx.title(p, Voice.glitch("DIETRO DI TE", ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                Voice.narration("Non girarti."), 0, 25, 15);
        Fx.effect(server, p, "blindness", 2, 0);
        Fx.effect(server, p, "darkness", 8, 0);
        Fx.effect(server, p, "nausea", 5, 0);
        Fx.particle(server, p, "minecraft:soul", 1.0, 1.5, 50);
        shake(id, 8);
        String line = template(Lines.AFTER_SCARE, p);
        later(id, 70, q -> say(q, line, false));
    }

    // =====================================================================
    // L'ombra: una figura lontana che sparisce quando la guardi
    // =====================================================================

    private boolean ombra(ServerPlayer p, PlayerState st) {
        if (st.stalker || server == null) {
            return false;
        }
        ServerLevel level = (ServerLevel) p.level();

        // direzione "davanti" al giocatore, poi ci mettiamo dietro o di lato (fuori dal campo visivo)
        double yaw = Math.toRadians(p.getYRot());
        double forwardAngle = Math.atan2(Math.cos(yaw), -Math.sin(yaw));
        double angle = forwardAngle + Math.PI + (rnd.nextDouble() - 0.5) * Math.PI * 0.9;

        BlockPos spot = findSpot(level, p, angle, 16, 26);
        if (spot == null) {
            return false;
        }
        double sx = spot.getX() + 0.5;
        double sy = spot.getY();
        double sz = spot.getZ() + 0.5;
        double face = Math.toDegrees(Math.atan2(-(p.getX() - sx), p.getZ() - sz));

        Fx.summonStalker(server, p, sx, sy, sz, face);
        st.stalker = true;
        st.stalkerLevel = level;
        st.sx = sx;
        st.sy = sy;
        st.sz = sz;
        st.stalkerBorn = tick;
        st.lookTicks = 0;
        st.faceCounter = 0;
        snd(p, "minecraft:ambient.cave", 0, 0, 0, 0.6f, 0.6f);
        return true;
    }

    /** Cerca un punto libero (3 blocchi d'aria sopra un pavimento solido) a quella distanza e direzione. */
    private BlockPos findSpot(ServerLevel level, ServerPlayer p, double angle, double minDist, double maxDist) {
        int py = (int) Math.floor(p.getY());
        for (int attempt = 0; attempt < 30; attempt++) {
            double a = angle + (rnd.nextDouble() - 0.5) * 0.6;
            double d = minDist + rnd.nextDouble() * (maxDist - minDist);
            int x = (int) Math.floor(p.getX() + Math.cos(a) * d);
            int z = (int) Math.floor(p.getZ() + Math.sin(a) * d);
            for (int k = 0; k <= 14; k++) {
                int dy = (k % 2 == 0) ? k / 2 : -(k + 1) / 2; // 0, -1, 1, -2, 2... (prima i punti più vicini alla quota del giocatore)
                BlockPos feet = new BlockPos(x, py + dy, z);
                if (!level.hasChunkAt(feet)) {
                    break;
                }
                if (!level.getBlockState(feet).isAir()
                        || !level.getBlockState(feet.above()).isAir()
                        || !level.getBlockState(feet.above(2)).isAir()) {
                    continue;
                }
                BlockPos floor = feet.below();
                if (level.getBlockState(floor).isAir() || !level.getFluidState(floor).isEmpty()) {
                    continue;
                }
                return feet;
            }
        }
        return null;
    }

    private void stalkerTick(ServerPlayer p, PlayerState st) {
        if (p.level() != st.stalkerLevel) {
            vanish(p, st, false); // il giocatore ha cambiato dimensione
            return;
        }
        double dx = st.sx - p.getX();
        double dz = st.sz - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toRadians(p.getYRot());
        double facing = dist < 0.01 ? 1.0 : (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / dist;
        boolean looking = facing > 0.97 && dist < 70 && Math.abs(st.sy - p.getY()) < 14;
        st.lookTicks = looking ? st.lookTicks + 4 : Math.max(0, st.lookTicks - 2);
        long age = tick - st.stalkerBorn;

        if (dist < 7 || st.lookTicks >= 16) {
            vanish(p, st, true);          // l'hai guardata (o ti sei avvicinato troppo)
        } else if (age > 20 * 45) {
            vanish(p, st, false);         // nessuno l'ha vista: se ne va da sola
        } else if (st.faceCounter++ % 3 == 0) {
            Fx.faceStalker(server, p, st.sx, st.sy, st.sz); // ti fissa
        }
    }

    private void vanish(ServerPlayer p, PlayerState st, boolean seen) {
        double x = st.sx;
        double y = st.sy;
        double z = st.sz;
        boolean sameLevel = p.level() == st.stalkerLevel;
        removeStalker(st, p);
        if (!sameLevel) {
            return;
        }
        Fx.particleAt(server, p, "minecraft:large_smoke", x, y + 1.5, z, 0.4, 40);
        Fx.soundAt(server, p, "minecraft:entity.enderman.teleport", x, y, z, 1.0f, 0.5f);
        if (seen) {
            Fx.effect(server, p, "darkness", 5, 0);
            if (rnd.nextInt(100) < 70) {
                String line = template(Lines.SEEN, p);
                later(p.getUUID(), 25, q -> say(q, line, false));
            }
        }
    }

    /**
     * Toglie la figura dal mondo. Se il giocatore è nella sua stessa dimensione la fa sparire sul posto
     * (senza animazione di morte), altrimenti ripulisce tutte le dimensioni.
     */
    private void removeStalker(PlayerState st, ServerPlayer nearby) {
        if (!st.stalker) {
            return;
        }
        st.stalker = false;
        ServerLevel level = st.stalkerLevel;
        st.stalkerLevel = null;
        MinecraftServer s = server;
        if (s == null) {
            return;
        }
        if (nearby != null && nearby.level() == level) {
            Fx.banishStalker(s, nearby, st.sx, st.sy, st.sz);
        } else {
            Fx.killAllStalkers(s);
        }
    }

    /** Per /osservatore pulisci. Restituisce quante figure erano attive. */
    int clearStalkers() {
        int n = 0;
        for (PlayerState st : states.values()) {
            if (st.stalker) {
                removeStalker(st, null);
                n++;
            }
        }
        Fx.killAllStalkers(server);
        return n;
    }

    // =====================================================================
    // Reazioni a quello che fai
    // =====================================================================

    /** Hai scritto qualcosa nella chat: l'Osservatore ti risponde (dopo un po', come se scrivesse). */
    void onChat(UUID id, String raw) {
        if (cfg.intensity <= 0 || raw == null || raw.isBlank()) {
            return;
        }
        PlayerState st = states.get(id);
        ServerPlayer p = online(id);
        if (st == null || p == null || tick - st.lastReply < 30) {
            return;
        }

        List<String> replies = new ArrayList<>();
        String[] known = Lines.match(Lines.normalize(raw));
        if (known != null) {
            replies.add(template(known, p));
        } else if (raw.trim().endsWith("?") && rnd.nextInt(100) < 55) {
            replies.add(template(Lines.QUESTION, p));
        } else if (rnd.nextInt(100) < cfg.chatReplyChance) {
            if (rnd.nextInt(100) < 25) {
                replies.addAll(echo(raw));
            } else {
                replies.add(template(Lines.GENERIC, p));
            }
        }
        replies.removeIf(Objects::isNull);
        if (replies.isEmpty()) {
            return;
        }

        st.lastReply = tick;
        Fx.actionBar(p, Voice.narration("..."));
        int t = 30 + rnd.nextInt(50);
        for (String line : replies) {
            int when = t;
            later(id, when, q -> say(q, line, false));
            t += 45 + rnd.nextInt(30);
        }
    }

    /** Ripete quello che hai scritto: a volte con la voce "storta", a volte al contrario. */
    private List<String> echo(String raw) {
        String msg = raw.trim();
        if (msg.length() > 60) {
            msg = msg.substring(0, 60) + "…";
        }
        msg = msg.replace("{", "(").replace("}", ")");
        if (rnd.nextBoolean()) {
            return List.of(pick(Lines.ECHO).replace("{eco}", msg));
        }
        String reversed = new StringBuilder(msg).reverse().toString();
        return List.of(reversed, "...scusa. Da qui suona così.");
    }

    void onDeath(UUID id) {
        PlayerState st = states.get(id);
        if (st == null || cfg.intensity <= 0) {
            return;
        }
        st.deaths++;
        String[] pool = st.deaths >= 3 ? Lines.DEATH_MANY : Lines.DEATH;
        afterRespawn(id, 0, 60 + rnd.nextInt(60), q -> say(q, template(pool, q), false));
    }

    void onSleep(UUID id) {
        PlayerState st = states.get(id);
        if (st == null || cfg.intensity <= 0 || rnd.nextInt(100) >= 55) {
            return;
        }
        later(id, 40, q -> snd(q, "minecraft:ambient.cave", 0, 0, 0, 0.8f, 0.6f));
        later(id, 70 + rnd.nextInt(80), q -> say(q, template(Lines.SLEEP, q), false));
        if (stage(st) >= 2 && rnd.nextBoolean()) {
            later(id, 140, this::passi);
        }
    }

    /** {@code blockId} è tipo "minecraft:diamond_ore". */
    void onBlockBroken(UUID id, String blockId) {
        PlayerState st = states.get(id);
        if (st == null || cfg.intensity <= 0) {
            return;
        }
        String[] pool;
        if (blockId.contains("diamond_ore")) {
            pool = Lines.DIAMOND;
        } else if (blockId.contains("ancient_debris")) {
            pool = Lines.DEBRIS;
        } else if (blockId.contains("spawner")) {
            pool = Lines.SPAWNER;
        } else {
            return;
        }
        if (tick - st.lastMine < 20 * 90) {
            return; // non commentare ogni singolo blocco
        }
        st.lastMine = tick;
        String[] chosen = pool;
        later(id, 40 + rnd.nextInt(40), q -> say(q, template(chosen, q), false));
    }

    // =====================================================================
    // Per i comandi
    // =====================================================================

    String statusText() {
        String[] labels = {"spento", "lieve", "normale", "incubo"};
        return "L'Osservatore - intensità " + cfg.intensity + " (" + labels[cfg.intensity] + "). Eventi: "
                + String.join(", ", EVENT_IDS) + ".";
    }
}
