package com.example.ballance.passthrough;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 启动时把客户端送进「虚空世界」，并对这个世界做一次性布景（milestone M2a）。
 *
 * <p>世界 id 默认 {@code passthrough}：第一次运行**新建**，之后每次**直接打开**。
 * 生成器是超平坦的 the_void 预设（一层空气 + the_void 生物群系，没有结构、没有湖），
 * 也就是「进去以后脚下什么都没有」，和菜单里的 单人游戏 → 世界类型 → 超平坦 →
 * 预设 the_void 是同一个东西，只是不用手点。
 *
 * <p>为什么不用 {@code --quickPlaySingleplayer}：loom 把程序参数写进 argfile，
 * Gradle 的 {@code --args} 到不了游戏（2026-11-05 实测，日志里是 "Completely ignored
 * arguments"）。所以走 {@link net.minecraft.client.gui.screens.worldselection.WorldOpenFlows}
 * 的同一条路，和「创建世界」界面按下确定之后做的一样。
 *
 * <p>环境变量：
 * <ul>
 *   <li>{@code BALLANCE_MC_WORLD=<存档 id>}：不建虚空世界，直接打开这个已有存档
 *       （老行为，做对照实验用；这个存档不会被布景、也不会去天空）；</li>
 *   <li>{@code BALLANCE_MC_VOID_WORLD=<id>}：虚空世界的存档名，默认 {@code passthrough}；</li>
 *   <li>{@code BALLANCE_MC_VOID=0}：整个自动开世界关掉，客户端停在标题界面。</li>
 * </ul>
 */
public final class McWorldBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-world");

    private static final String EXPLICIT_WORLD = System.getenv("BALLANCE_MC_WORLD");
    private static final String VOID_WORLD = readWorldId();
    private static final boolean ENABLED = !"0".equals(System.getenv("BALLANCE_MC_VOID"));

    /** 标题界面起来、资源加载完再动手；6 秒是 2026-11-05 实测能稳定进世界的值。 */
    private static final long DELAY_NANOS = 6_000_000_000L;
    private static final long STARTED = System.nanoTime();

    private static boolean attempted;
    private static boolean inWorldHandled;

    /** 这次会话是不是由我们自己打开的虚空世界（决定要不要布景 / 去天空）。 */
    private static boolean passthroughWorld;

    private McWorldBootstrap() {
    }

    private static String readWorldId() {
        final String value = System.getenv("BALLANCE_MC_VOID_WORLD");
        if (value == null || value.isBlank()) {
            return "passthrough";
        }
        return value.trim();
    }

    public static void onClientTick(Minecraft client) {
        if (client.level != null) {
            onInWorld(client);
            return;
        }
        if (!ENABLED || attempted) {
            return;
        }
        if (System.nanoTime() - STARTED < DELAY_NANOS) {
            return;
        }
        attempted = true;
        try {
            openWorld(client);
        } catch (Throwable throwable) {
            LOGGER.error("could not open a world on startup", throwable);
        }
    }

    private static void openWorld(Minecraft client) {
        if (EXPLICIT_WORLD != null && !EXPLICIT_WORLD.isBlank()) {
            LOGGER.info("opening world \"{}\" (BALLANCE_MC_WORLD)", EXPLICIT_WORLD);
            client.createWorldOpenFlows().openWorld(EXPLICIT_WORLD,
                    () -> LOGGER.warn("world \"{}\" could not be opened", EXPLICIT_WORLD));
            return;
        }
        if (hasLevelData(client, VOID_WORLD)) {
            LOGGER.info("opening void world \"{}\"", VOID_WORLD);
            passthroughWorld = true;
            PassthroughScene.markPassthroughWorld();
            client.createWorldOpenFlows().openWorld(VOID_WORLD, () -> {
                LOGGER.warn("void world \"{}\" could not be opened", VOID_WORLD);
                passthroughWorld = false;
            });
            return;
        }
        LOGGER.info("creating void world \"{}\"", VOID_WORLD);
        passthroughWorld = true;
        PassthroughScene.markPassthroughWorld();
        client.createWorldOpenFlows().createFreshLevel(
                VOID_WORLD,
                new LevelSettings(VOID_WORLD, GameType.CREATIVE,
                        LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                new WorldOptions(WorldOptions.randomSeed(), false, false),
                McWorldBootstrap::voidDimensions,
                null);
    }

    /**
     * 存档目录里有 level.dat 才算「能打开」。{@code LevelStorageSource.levelExists} 只判断
     * 目录在不在，而 2026-11-05 那次失败留下的正是一个只有 session.lock 的空目录
     * （打开它时 WorldOpenFlows 静默落到 RecoverWorldDataScreen，日志里一个字都没有）。
     */
    private static boolean hasLevelData(Minecraft client, String worldId) {
        final LevelStorageSource source = client.getLevelSource();
        if (!source.levelExists(worldId)) {
            return false;
        }
        final Path levelData = source.getLevelPath(worldId).resolve("level.dat");
        if (Files.isRegularFile(levelData)) {
            return true;
        }
        LOGGER.warn("save \"{}\" exists but has no level.dat; it will be recreated", worldId);
        return false;
    }

    /**
     * 虚空维度：超平坦 the_void 预设（一层空气、the_void 生物群系、无结构无湖），
     * 只替换主世界，下界和末地保持原样。
     */
    private static WorldDimensions voidDimensions(HolderLookup.Provider registries) {
        final HolderLookup.RegistryLookup<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        final Holder<Biome> biome = biomes.getOrThrow(Biomes.THE_VOID);
        final FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(
                java.util.Optional.empty(), biome, List.of())
                .withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.AIR)),
                        java.util.Optional.empty(), biome);
        final ChunkGenerator generator = new FlatLevelSource(flat);
        // 这个 provider 只带「数据包世界生成注册表」，没有 LEVEL_STEM（那个由
        // createFreshLevel 自己在 bake 时才补），所以维度集合不能用
        // lookupOrThrow(Registries.LEVEL_STEM) 去拼——2026-11-06 就是这么撞上
        // "Registry minecraft:dimension not found" 的。用原版预设生成三维度再换主世界。
        return WorldPresets.createNormalWorldDimensions(registries)
                .replaceOverworldGenerator(registries, generator);
    }

    /** 进世界后的第一次 tick：打一行现场，然后（只在虚空世界里）做一次布景。 */
    private static void onInWorld(Minecraft client) {
        if (inWorldHandled || client.player == null) {
            return;
        }
        inWorldHandled = true;
        LOGGER.info("in world: dimension={} pos=({}, {}, {}) gameType={} passthrough={}",
                client.level.dimension().identifier(),
                String.format("%.1f", client.player.getX()),
                String.format("%.1f", client.player.getY()),
                String.format("%.1f", client.player.getZ()),
                client.player.gameMode(), passthroughWorld);
        if (!passthroughWorld) {
            return;
        }
        try {
            dressScene(client);
        } catch (Throwable throwable) {
            LOGGER.error("scene setup failed", throwable);
        }
    }

    /**
     * 一次性布景：时间冻住、不下雨、不刷怪、创造模式并且**开着飞行**。
     *
     * <p>飞行是必须的：虚空世界里脚下什么都没有，站着就会掉下去；掉到世界底部会死，
     * 死了以后客户端停在「You Died」界面上（2026-11-05 那次就是这么卡住的）。
     */
    private static void dressScene(Minecraft client) {
        final IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("no integrated server yet; scene rules left alone");
            return;
        }
        final GameRules rules = server.getGameRules();
        setRule(server, rules, GameRules.ADVANCE_TIME, false);
        setRule(server, rules, GameRules.ADVANCE_WEATHER, false);
        setRule(server, rules, GameRules.SPAWN_MOBS, false);
        setRule(server, rules, GameRules.SPAWN_MONSTERS, false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            // 虚空里出生点在 y=-64 附近（FlatLevelSource.getSpawnHeight 取世界底部），
            // 掉下去就再也回不来；顺手放到 M1 锚点 (0,100,0)，和 BALLANCE_MC_ANCHOR 对齐。
            player.teleportTo(0.5, 100.0, 0.5);
            LOGGER.info("scene: player {} set to creative + flying at 0,100,0", player.getName().getString());
        }
    }

    private static void setRule(IntegratedServer server, GameRules rules,
                                net.minecraft.world.level.gamerules.GameRule<Boolean> rule, boolean value) {
        try {
            rules.set(rule, value, server);
        } catch (Throwable throwable) {
            LOGGER.warn("could not set game rule {}", rule, throwable);
        }
    }
}
