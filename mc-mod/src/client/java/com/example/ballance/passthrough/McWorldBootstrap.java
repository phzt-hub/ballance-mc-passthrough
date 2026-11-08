package com.example.ballance.passthrough;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 把客户端送进「虚空世界」，并对这个世界做一次性布景（milestone M2a）。
 *
 * <p>生成器是超平坦的 the_void 预设（一层空气 + the_void 生物群系，没有结构、没有湖），
 * 也就是「进去以后脚下什么都没有」，和菜单里的 单人游戏 → 世界类型 → 超平坦 →
 * 预设 the_void 是同一个东西，只是不用手点。
 *
 * <p><b>2026-11-07 起每个关卡一个全新的世界</b>：不再复用一个叫 {@code passthrough} 的存档，
 * 每次「从菜单进关卡」都由 {@link LevelSession} 请求这里删掉旧档、用新 id 重新建一个
 * （{@code passthrough}、{@code passthrough-2}、……），所以上一个关卡搭的东西不会出现在
 * 下一个关卡里。存档名带序号而不是原地删除重建，是因为删除必须等旧的服务端线程真的停下来，
 * 而「新建一个不同 id 的世界」不需要那个时序：新世界加载完，旧目录再删就绝不会被写回。
 * 启动时也会先清掉上一次会话留下的所有 {@code passthrough*}，所以关掉重开同样是干净的。
 *
 * <p>为什么不用 {@code --quickPlaySingleplayer}：loom 把程序参数写进 argfile，
 * Gradle 的 {@code --args} 到不了游戏（2026-11-05 实测，日志里是 "Completely ignored
 * arguments"）。所以走 {@link net.minecraft.client.gui.screens.worldselection.WorldOpenFlows}
 * 的同一条路，和「创建世界」界面按下确定之后做的一样。
 *
 * <p>环境变量：
 * <ul>
 *   <li>{@code BALLANCE_MC_WORLD=<存档 id>}：不建虚空世界，直接打开这个已有存档
 *       （老行为，做对照实验用；这个存档不会被布景、也不会去天空、更不会被删）；</li>
 *   <li>{@code BALLANCE_MC_VOID_WORLD=<id>}：虚空世界的存档名基名，默认 {@code passthrough}；</li>
 *   <li>{@code BALLANCE_MC_REUSE_WORLD=1}：启动时复用已有的 {@code <id>} 存档，不删旧档
 *       （调画面时省一次加载；每关换新世界的逻辑仍然生效）；</li>
 *   <li>{@code BALLANCE_MC_VOID=0}：整个自动开世界关掉，客户端停在标题界面。</li>
 * </ul>
 */
public final class McWorldBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-world");

    private static final String EXPLICIT_WORLD = System.getenv("BALLANCE_MC_WORLD");
    private static final String BASE_WORLD = readWorldId();
    private static final boolean ENABLED = !"0".equals(System.getenv("BALLANCE_MC_VOID"));
    private static final boolean REUSE_WORLD = envFlag("BALLANCE_MC_REUSE_WORLD");

    /** 标题界面起来、资源加载完再动手；6 秒是 2026-11-05 实测能稳定进世界的值。 */
    private static final long DELAY_NANOS = 6_000_000_000L;
    private static final long STARTED = System.nanoTime();

    private static boolean attempted;
    private static boolean inWorldHandled;

    /** 这次会话是不是由我们自己打开的虚空世界（决定要不要布景 / 去天空 / 换关时重建）。 */
    private static boolean passthroughWorld;

    /** 当前正在玩的存档 id，由 {@link #createFreshWorld} 记下，换关时用它来删旧档。 */
    private static String currentWorldId;

    private McWorldBootstrap() {
    }

    private static String readWorldId() {
        final String value = System.getenv("BALLANCE_MC_VOID_WORLD");
        if (value == null || value.isBlank()) {
            return "passthrough";
        }
        return value.trim();
    }

    private static boolean envFlag(String name) {
        final String value = System.getenv(name);
        return value != null && !value.isBlank()
                && !value.equals("0") && !value.equalsIgnoreCase("off") && !value.equalsIgnoreCase("false");
    }

    public static boolean isPassthroughWorld() {
        return passthroughWorld;
    }

    /** The save id the current world was created with, or null when it is not ours. */
    public static String currentWorldId() {
        return currentWorldId;
    }

    public static void onClientTick(Minecraft client) {
        if (client.level != null) {
            onInWorld(client);
            return;
        }
        // No world: the next one needs dressing too. This is what makes a level's own
        // freshly created world get the game rules, the flying player and the slot reset.
        inWorldHandled = false;
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
            LOGGER.info("opening world \"{}\" (BALLANCE_MC_WORLD, never recreated)", EXPLICIT_WORLD);
            client.createWorldOpenFlows().openWorld(EXPLICIT_WORLD,
                    () -> LOGGER.warn("world \"{}\" could not be opened", EXPLICIT_WORLD));
            return;
        }
        if (REUSE_WORLD && hasLevelData(client, BASE_WORLD)) {
            LOGGER.info("opening void world \"{}\" (BALLANCE_MC_REUSE_WORLD)", BASE_WORLD);
            passthroughWorld = true;
            currentWorldId = BASE_WORLD;
            PassthroughScene.markPassthroughWorld();
            client.createWorldOpenFlows().openWorld(BASE_WORLD, () -> {
                LOGGER.warn("void world \"{}\" could not be opened", BASE_WORLD);
                passthroughWorld = false;
            });
            return;
        }
        // The session before this one left its last instance behind under an un-timestamped id.
        // Since 2026-11-08 that is user data (the user asked for every instance to be kept),
        // so it is archived rather than deleted -- and archiving frees the id for reuse.
        final int archived = archiveLeftovers(client);
        if (archived > 0) {
            LOGGER.info("archived {} instance(s) left by an earlier session", archived);
        }
        createFreshWorld(client);
    }

    /**
     * Creates one more void world, with an id nothing else is using, and returns that id.
     *
     * <p>Called on startup and again for every level entered from Ballance's menu
     * ({@link LevelSession}). The world is a fresh save with a new random seed; the previous one
     * is deleted by the caller once this one is up, so the two never share a directory.
     */
    public static String createFreshWorld(Minecraft client) {
        final String id = nextWorldId(client);
        removeEmptyLeftover(client, id);
        LOGGER.info("creating fresh void world \"{}\"", id);
        passthroughWorld = true;
        currentWorldId = id;
        PassthroughScene.markPassthroughWorld();
        client.createWorldOpenFlows().createFreshLevel(
                id,
                new LevelSettings(id, GameType.CREATIVE,
                        LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                new WorldOptions(WorldOptions.randomSeed(), false, false),
                McWorldBootstrap::voidDimensions,
                null);
        return id;
    }

    private static String nextWorldId(Minecraft client) {
        final LevelStorageSource source = client.getLevelSource();
        for (int n = 1; n <= 999; n++) {
            final String id = n == 1 ? BASE_WORLD : BASE_WORLD + "-" + n;
            final Path path = source.getLevelPath(id);
            // A directory with no level.dat in it holds no world (a half-unpacked template used to
            // leave one behind), so its id is free and gets rewritten rather than skipped.
            if (!Files.exists(path) || !Files.isRegularFile(path.resolve("level.dat"))) {
                return id;
            }
        }
        return BASE_WORLD + "-" + System.currentTimeMillis();
    }

    /** True for the save ids this mod owns: {@code <base>} and {@code <base>-<digits>}. */
    public static boolean isOwnWorld(String id) {
        if (id == null) {
            return false;
        }
        if (id.equals(BASE_WORLD)) {
            return true;
        }
        if (!id.startsWith(BASE_WORLD + "-")) {
            return false;
        }
        final String tail = id.substring(BASE_WORLD.length() + 1);
        return !tail.isEmpty() && tail.chars().allMatch(Character::isDigit);
    }

    /** Deletes every {@code passthrough*} save. Only called with no world loaded. */
    public static int deleteOldWorlds(Minecraft client) {
        final Path saves = client.getLevelSource().getLevelPath(BASE_WORLD).getParent();
        if (saves == null || !Files.isDirectory(saves)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> entries = Files.list(saves)) {
            for (Path path : entries.toList()) {
                if (Files.isDirectory(path) && isOwnWorld(path.getFileName().toString())) {
                    deleteRecursively(path);
                    ++removed;
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("could not list the saves in {}: {}", saves, exception.toString());
        }
        return removed;
    }

    /**
     * Copies the pre-baked world for {@code levelId} into a new save and opens it.
     *
     * <p>The templates are the 13 levels written once with {@code BALLANCE_MC_BAKE} and shipped
     * inside the mod (tools/voxel/bake-levels.sh). A level start then only has to unpack one of
     * them: the world is complete from the first frame, which is what the user asked for --
     * "预先处理 13 个关卡，变成世界存在存档文件夹里".
     *
     * @return true when a template was used; false when the caller gets a plain void world and
     *         has to place the blocks itself
     */
    public static boolean openLevelWorld(Minecraft client, int levelId) {
        final String template = String.format("Level_%02d", levelId);
        if (levelId < 1 || !hasTemplate(template)) {
            LOGGER.warn("no baked world for level {}; a plain void world is used instead", levelId);
            createFreshWorld(client);
            return false;
        }
        final String id = nextWorldId(client);
        try {
            final int files = installTemplate(client, template, id);
            LOGGER.info("opening the baked world for {} as \"{}\" ({} files)", template, id, files);
        } catch (IOException | RuntimeException error) {
            LOGGER.error("could not unpack the baked world {}: {} -- a plain void world is used instead",
                    template, error.toString(), error);
            createFreshWorld(client);
            return false;
        }
        passthroughWorld = true;
        currentWorldId = id;
        PassthroughScene.markPassthroughWorld();
        client.createWorldOpenFlows().openWorld(id, () -> {
            LOGGER.warn("baked world \"{}\" could not be opened", id);
            passthroughWorld = false;
        });
        return true;
    }

    /** True when this build carries a baked world for the level. */
    public static boolean hasTemplate(int levelId) {
        return levelId >= 1 && hasTemplate(String.format("Level_%02d", levelId));
    }

    private static boolean hasTemplate(String name) {
        if (ownJarEntry("assets/ballance/templates/" + name + ".zip") != null) {
            return true;
        }
        return McWorldBootstrap.class.getResource("/assets/ballance/templates/" + name + ".zip") != null;
    }

    /**
     * The mod's own file when it was loaded from a jar, null in a development run (the classes
     * come from a directory there, and only the class loader knows about the resources).
     */
    private static Path ownJar() {
        try {
            final java.security.CodeSource code = McWorldBootstrap.class.getProtectionDomain().getCodeSource();
            if (code == null || code.getLocation() == null) {
                return null;
            }
            final Path self = java.nio.file.Paths.get(code.getLocation().toURI());
            return Files.isRegularFile(self) ? self : null;
        } catch (Throwable throwable) {
            LOGGER.warn("could not locate the mod file: {}", throwable.toString());
            return null;
        }
    }

    private static java.util.zip.ZipEntry ownJarEntry(String entry) {
        final Path self = ownJar();
        if (self == null) {
            return null;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(self.toFile())) {
            return zip.getEntry(entry);
        } catch (IOException exception) {
            LOGGER.warn("could not read the mod file {}: {}", self, exception.toString());
            return null;
        }
    }

    /**
     * Unpacks one template zip into {@code saves/<id>}, through a staging directory.
     *
     * <p>2026-11-08, first packaged run: the log said {@code (0 files)} and the world then failed
     * to load with {@code NoSuchFileException: level.dat}. The class loader had handed back a
     * stream with no entries in it and nothing threw, so the save the mod left behind was an
     * empty directory -- and {@code openWorld} went straight into it. Three changes, all of them
     * about "fail where it can be seen":
     * <ul>
     *   <li>the bytes are copied out of the mod's own jar file first ({@link #ownJar()}); the
     *       class loader is only the fallback for a development run;</li>
     *   <li>the zip is read with {@link java.util.zip.ZipFile} instead of a stream, so a template
     *       that is not a zip says so;</li>
     *   <li>everything is unpacked into a staging directory and only moved into {@code saves/}
     *       once {@code level.dat} is really there.</li>
     * </ul>
     */
    private static int installTemplate(Minecraft client, String name, String id) throws IOException {
        final Path saves = client.getLevelSource().getLevelPath(id).getParent();
        final Path target = client.getLevelSource().getLevelPath(id);
        final Path work = saves.getParent().resolve(".bmc-template-work");
        Files.createDirectories(work);
        final Path stash = work.resolve(name + ".zip");
        final Path staging = work.resolve(name);
        deleteRecursively(staging);
        Files.createDirectories(staging);

        final long bytes = stashTemplate(name, stash);
        if (bytes < 4) {
            throw new IOException("the template for " + name + " is empty (" + bytes + " bytes)");
        }
        int files;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(stash.toFile())) {
            files = unzipTemplate(zip, staging);
        }
        if (!Files.isRegularFile(staging.resolve("level.dat"))) {
            throw new IOException("the template for " + name + " has no level.dat ("
                    + files + " files unpacked out of " + bytes + " bytes)");
        }
        if (Files.exists(target)) {
            deleteRecursively(target);
        }
        Files.move(staging, target);
        Files.deleteIfExists(stash);
        return files;
    }

    /** Copies the template zip out of the mod into {@code stash}; returns the byte count. */
    private static long stashTemplate(String name, Path stash) throws IOException {
        final String entry = "assets/ballance/templates/" + name + ".zip";
        final Path self = ownJar();
        if (self != null) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(self.toFile())) {
                final java.util.zip.ZipEntry found = zip.getEntry(entry);
                if (found != null) {
                    try (java.io.InputStream in = zip.getInputStream(found)) {
                        final long bytes = Files.copy(in, stash,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("template {} read from the mod file {} ({} bytes)",
                                name, self.getFileName(), bytes);
                        return bytes;
                    }
                }
                LOGGER.warn("the mod file {} has no {}", self.getFileName(), entry);
            }
        }
        try (java.io.InputStream in = McWorldBootstrap.class.getResourceAsStream("/" + entry)) {
            if (in == null) {
                throw new IOException("the mod carries no " + entry);
            }
            final long bytes = Files.copy(in, stash, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("template {} read through the class loader ({} bytes, {})",
                    name, bytes, in.getClass().getName());
            return bytes;
        }
    }

    /** Unpacks every file of the template into {@code dir} and returns how many were written. */
    private static int unzipTemplate(java.util.zip.ZipFile zip, Path dir) throws IOException {
        int files = 0;
        int refused = 0;
        String firstRefused = null;
        // Both sides of the containment test are normalised, not just the entry's target
        // (2026-11-08). On this box the save path arrives with a "." component
        // ("J:\bmc-project\runtime\.\mc\saves"); Path.normalize() drops that from the
        // entry's target but keeps it in dir, so all 30 entries looked as if they escaped the
        // directory: nothing unpacked, there was no level.dat, and the level quietly fell back
        // to a plain void world.
        final Path root = dir.toAbsolutePath().normalize();
        final java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            final java.util.zip.ZipEntry entry = entries.nextElement();
            final Path target = root.resolve(entry.getName()).normalize();
            if (!target.startsWith(root)) {
                if (firstRefused == null) {
                    firstRefused = entry.getName() + " -> " + target;
                }
                ++refused;
                continue;
            }
            if (entry.isDirectory()) {
                Files.createDirectories(target);
                continue;
            }
            Files.createDirectories(target.getParent());
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ++files;
        }
        if (refused > 0) {
            LOGGER.warn("refused {} template entr{} with an absolute or parent-relative path"
                    + " (root {}, first {})",
                    refused, refused == 1 ? "y" : "ies", root, firstRefused);
        }
        return files;
    }

    /**
     * Renames one of our saves out of the way instead of deleting it (2026-11-08).
     *
     * <p>The user asked for every instance to be kept: a level start archives the world it
     * replaces as {@code <id>-<yyyyMMdd-HHmmss>} -- a different id, so the cleanup helpers here
     * never touch it again. The log says how many are kept now, and complains past twenty.
     */
    public static boolean archiveWorld(Minecraft client, String id) {
        if (id == null || id.isBlank() || id.equals(currentWorldId)) {
            return false;
        }
        final Path path = client.getLevelSource().getLevelPath(id);
        if (!Files.isDirectory(path)) {
            return false;
        }
        final String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        final Path target = path.resolveSibling(id + "-" + stamp);
        try {
            Files.move(path, target);
        } catch (IOException exception) {
            LOGGER.warn("could not archive \"{}\": {}", id, exception.toString());
            return false;
        }
        final int kept = countInstances(client);
        LOGGER.info("archived the last instance as \"{}\" ({} instances kept{})", target.getFileName(),
                kept, kept > 20 ? " -- over 20, consider cleaning up" : "");
        return true;
    }

    /** Archives every un-timestamped instance of ours. Only called with no world loaded. */
    public static int archiveLeftovers(Minecraft client) {
        final Path saves = client.getLevelSource().getLevelPath(BASE_WORLD).getParent();
        if (saves == null || !Files.isDirectory(saves)) {
            return 0;
        }
        int archived = 0;
        try (Stream<Path> entries = Files.list(saves)) {
            for (Path path : entries.toList()) {
                final String name = path.getFileName().toString();
                if (!Files.isDirectory(path) || !isOwnWorld(name) || name.equals(currentWorldId)) {
                    continue;
                }
                if (!Files.isRegularFile(path.resolve("level.dat"))) {
                    removeEmptyLeftover(client, name);
                    continue;
                }
                if (archiveWorld(client, name)) {
                    ++archived;
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("could not list the saves in {}: {}", saves, exception.toString());
        }
        return archived;
    }

    /** How many instance saves (current + archived) this mod owns. */
    public static int countInstances(Minecraft client) {
        final Path saves = client.getLevelSource().getLevelPath(BASE_WORLD).getParent();
        if (saves == null || !Files.isDirectory(saves)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> entries = Files.list(saves)) {
            for (Path path : entries.toList()) {
                final String name = path.getFileName().toString();
                if (Files.isDirectory(path) && (isOwnWorld(name) || name.startsWith(BASE_WORLD + "-"))) {
                    ++count;
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("could not list the saves in {}: {}", saves, exception.toString());
        }
        return count;
    }

    /** Deletes one save directory. Refuses the id of the world being played right now. */
    public static boolean deleteWorld(Minecraft client, String id) {
        if (id == null || id.isBlank() || !isOwnWorld(id)) {
            return false;
        }
        if (id.equals(currentWorldId)) {
            LOGGER.warn("refusing to delete the save that is being played (\"{}\")", id);
            return false;
        }
        final Path path = client.getLevelSource().getLevelPath(id);
        if (!Files.isDirectory(path)) {
            return false;
        }
        try {
            deleteRecursively(path);
            LOGGER.info("deleted the previous void save \"{}\"", id);
            return true;
        } catch (IOException exception) {
            LOGGER.warn("could not delete the void save \"{}\": {}", id, exception.toString());
            return false;
        }
    }

    /**
     * Drops a save directory that holds no world: {@code openWorld} on one of those is what put
     * the "加载世界失败 / NoSuchFileException: level.dat" screen in front of the user on
     * 2026-11-08, so the leftovers are removed before they can be opened.
     */
    private static void removeEmptyLeftover(Minecraft client, String id) {
        final Path path = client.getLevelSource().getLevelPath(id);
        if (!Files.isDirectory(path) || Files.isRegularFile(path.resolve("level.dat"))) {
            return;
        }
        try {
            deleteRecursively(path);
            LOGGER.info("removed the empty leftover \"{}\" (no level.dat)", id);
        } catch (IOException exception) {
            LOGGER.warn("could not remove the empty leftover \"{}\": {}", id, exception.toString());
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path each : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(each);
            }
        }
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
        if (passthroughWorld && client.getSingleplayerServer() == null) {
            // The integrated server is one tick behind the client level; dressing the world
            // without it would leave the ball standing in the void on newly created worlds.
            return;
        }
        inWorldHandled = true;
        // The selected hotbar slot is player data and survives a restart, so whoever scrolled
        // to an empty slot last session comes back with an empty hand -- and the first right
        // click then does nothing at all, which reads as "placing is broken". Reset it on both
        // sides: dressScene resets the server copy, and that one is never pushed to the client.
        client.player.getInventory().setSelectedSlot(0);
        LOGGER.info("in world: dimension={} pos=({}, {}, {}) gameType={} passthrough={} save={}",
                client.level.dimension().identifier(),
                String.format("%.1f", client.player.getX()),
                String.format("%.1f", client.player.getY()),
                String.format("%.1f", client.player.getZ()),
                client.player.gameMode(), passthroughWorld, currentWorldId);
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
            // The selected slot is player data, so it survives a restart: whoever scrolled to
            // an empty slot last time comes back with an empty hand, and the first right click
            // then does nothing at all. Start on the first slot every time.
            player.getInventory().setSelectedSlot(0);
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
