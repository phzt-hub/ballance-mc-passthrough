package com.example.ballance.passthrough;

import net.minecraft.client.Minecraft;

/**
 * 渲染侧的「场景化」开关：把 Minecraft 的一帧变成一张**只有方块和实体**的透明图层。
 *
 * <p>三处一起关，缺一不可：
 * <ul>
 *   <li>天空（{@code LevelRenderer.addSkyPass}：天穹、太阳、月亮、星星）；</li>
 *   <li>云（{@code CloudRenderer}，不走天空 pass，是独立的一条）；</li>
 *   <li>天气（{@code WeatherEffectRenderer}）。</li>
 * </ul>
 *
 * <p>清屏色不用管：{@code LevelRenderer.render} 本来就用 {@code new Vector4f(fogColor.x,
 * fogColor.y, fogColor.z, 0.0F)} 清屏，alpha 已经是 0（26.3 实测，见 docs/mc-void-world.md）。
 * 也就是说「背景透明」的 alpha 一直是 0，挡住它的是天空那几个不透明 pass。
 *
 * <p>开关（环境变量 {@code BALLANCE_MC_SCENE}）：
 * <ul>
 *   <li>不设 / {@code auto}：只在本 mod 自己打开的虚空世界里生效，自己开的世界不受影响；</li>
 *   <li>{@code 1}：强制生效（任何世界都去天空），做对照实验用；</li>
 *   <li>{@code 0}：全关。</li>
 * </ul>
 */
public final class PassthroughScene {
    private static final int MODE_OFF = 0;
    private static final int MODE_FORCED = 1;
    private static final int MODE_AUTO = 2;

    private static final int MODE = readMode();

    /** 由 {@link McWorldBootstrap} 在自己打开虚空世界时置位。 */
    private static volatile boolean passthroughWorld;
    private static boolean announced;

    private PassthroughScene() {
    }

    private static int readMode() {
        final String value = System.getenv("BALLANCE_MC_SCENE");
        if (value == null || value.isBlank()) {
            return MODE_AUTO;
        }
        String text = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.equals("0") || text.equals("off") || text.equals("false") || text.equals("no")) {
            return MODE_OFF;
        }
        if (text.equals("1") || text.equals("on") || text.equals("true") || text.equals("yes")) {
            return MODE_FORCED;
        }
        return MODE_AUTO;
    }

    static void markPassthroughWorld() {
        passthroughWorld = true;
    }

    static boolean isPassthroughWorld() {
        return passthroughWorld;
    }

    /**
     * 渲染线程每帧问一次：这一帧要不要去掉天空/云/天气。
     */
    public static boolean hideSky() {
        if (MODE == MODE_OFF) {
            return false;
        }
        if (MODE == MODE_FORCED) {
            return true;
        }
        if (!passthroughWorld) {
            return false;
        }
        if (Minecraft.getInstance().level == null) {
            return false;
        }
        if (!announced) {
            announced = true;
            org.slf4j.LoggerFactory.getLogger("ballance-passthrough-scene")
                    .info("scene mode: sky, clouds and weather hidden (BALLANCE_MC_SCENE=auto, passthrough world)");
        }
        return true;
    }
}
