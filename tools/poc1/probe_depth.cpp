// poc1/probe_depth.cpp — does SDL3 GPU hand back a DEPTH texture on this box?
// Standalone, no shaders: renders nothing, only clears depth to known values and
// downloads them. Answers three questions at once:
//   A. can we open a GPU device + swapchain under Xvfb :99
//   B. can SDL_DownloadFromGPUTexture read a depth-stencil texture
//   C. what exact bytes come back, so the PNG writer knows the layout
#include <SDL3/SDL.h>
#include <stdio.h>
#include <string.h>

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL  %s  (%s)\n", msg, SDL_GetError()); failures++; } else { printf("ok    %s\n", msg); } } while (0)

static void stats(const char *tag, const Uint8 *data, Uint32 w, Uint32 h, Uint32 pitch) {
    Uint32 xs[5] = {0, w / 4, w / 2, w - 1, w / 2};
    Uint32 ys[5] = {0, h / 4, h / 2, h - 1, 0};
    printf("  %s\n", tag);
    for (int i = 0; i < 5; i++) {
        const Uint8 *p = data + (size_t)ys[i] * pitch + (size_t)xs[i] * 4;
        Uint32 v; memcpy(&v, p, 4);
        printf("    (%4u,%4u) = %02x %02x %02x %02x  u32=0x%08x\n",
               xs[i], ys[i], p[0], p[1], p[2], p[3], v);
    }
}

static bool run_depth_case(SDL_GPUDevice *dev, SDL_Window *win, SDL_GPUTextureFormat fmt,
                           const char *name) {
    Uint32 W = 64, H = 48;
    printf("\n=== case %s ===\n", name);
    if (!SDL_GPUTextureSupportsFormat(dev, fmt, SDL_GPU_TEXTURETYPE_2D, SDL_GPU_TEXTUREUSAGE_DEPTH_STENCIL_TARGET)) {
        printf("SKIP  format not supported as depth target\n");
        return false;
    }
    printf("ok    format supported as depth target\n");
    printf("info  ... and as depth target + sampler: %s\n",
           SDL_GPUTextureSupportsFormat(dev, fmt, SDL_GPU_TEXTURETYPE_2D,
               (SDL_GPUTextureUsageFlags)(SDL_GPU_TEXTUREUSAGE_DEPTH_STENCIL_TARGET | SDL_GPU_TEXTUREUSAGE_SAMPLER)) ? "yes" : "no");

    SDL_GPUTextureCreateInfo tci; memset(&tci, 0, sizeof(tci));
    tci.type = SDL_GPU_TEXTURETYPE_2D;
    tci.format = fmt;
    tci.usage = (SDL_GPUTextureUsageFlags)(SDL_GPU_TEXTUREUSAGE_DEPTH_STENCIL_TARGET | SDL_GPU_TEXTUREUSAGE_SAMPLER);
    tci.width = W; tci.height = H; tci.layer_count_or_depth = 1; tci.num_levels = 1;
    tci.sample_count = SDL_GPU_SAMPLECOUNT_1;
    SDL_GPUTexture *depth = SDL_CreateGPUTexture(dev, &tci);
    if (!depth) { printf("FAIL  create depth texture (%s)\n", SDL_GetError()); return false; }
    printf("ok    create depth texture\n");

    const float clears[3] = {1.0f, 0.75f, 0.25f};
    bool any = false;
    for (int i = 0; i < 3; i++) {
        SDL_GPUCommandBuffer *cmd = SDL_AcquireGPUCommandBuffer(dev);
        if (!cmd) { printf("FAIL  acquire cmd (%s)\n", SDL_GetError()); break; }
        SDL_GPUTexture *swap = NULL; Uint32 sw = 0, sh = 0;
        bool haveSwap = SDL_WaitAndAcquireGPUSwapchainTexture(cmd, win, &swap, &sw, &sh);
        if (!haveSwap) {
            printf("warn  no swapchain texture: %s\n", SDL_GetError());
            SDL_CancelGPUCommandBuffer(cmd);
            break;
        }
        SDL_GPUColorTargetInfo cti; memset(&cti, 0, sizeof(cti));
        cti.texture = swap;
        cti.clear_color.r = 0; cti.clear_color.g = 0; cti.clear_color.b = 0; cti.clear_color.a = 1;
        cti.load_op = SDL_GPU_LOADOP_CLEAR; cti.store_op = SDL_GPU_STOREOP_STORE;
        SDL_GPUDepthStencilTargetInfo dti; memset(&dti, 0, sizeof(dti));
        dti.texture = depth; dti.clear_depth = clears[i];
        dti.load_op = SDL_GPU_LOADOP_CLEAR; dti.store_op = SDL_GPU_STOREOP_STORE;
        dti.stencil_load_op = SDL_GPU_LOADOP_CLEAR; dti.stencil_store_op = SDL_GPU_STOREOP_DONT_CARE;
        dti.cycle = true;
        SDL_GPURenderPass *rp = SDL_BeginGPURenderPass(cmd, &cti, 1, &dti);
        if (!rp) { printf("FAIL  begin render pass (%s)\n", SDL_GetError()); SDL_CancelGPUCommandBuffer(cmd); break; }
        SDL_EndGPURenderPass(rp);

        const Uint32 needed = W * H * 4;
        SDL_GPUTransferBufferCreateInfo tbci; memset(&tbci, 0, sizeof(tbci));
        tbci.usage = SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD; tbci.size = needed;
        SDL_GPUTransferBuffer *tb = SDL_CreateGPUTransferBuffer(dev, &tbci);
        if (!tb) { printf("FAIL  create transfer buffer (%s)\n", SDL_GetError()); SDL_CancelGPUCommandBuffer(cmd); break; }
        SDL_GPUCopyPass *cp = SDL_BeginGPUCopyPass(cmd);
        SDL_GPUTextureRegion reg; memset(&reg, 0, sizeof(reg));
        reg.texture = depth; reg.w = W; reg.h = H; reg.d = 1;
        SDL_GPUTextureTransferInfo dst; memset(&dst, 0, sizeof(dst));
        dst.transfer_buffer = tb; dst.offset = 0;
        dst.pixels_per_row = W; dst.rows_per_layer = H;
        SDL_DownloadFromGPUTexture(cp, &reg, &dst);
        SDL_EndGPUCopyPass(cp);
        SDL_GPUFence *fence = SDL_SubmitGPUCommandBufferAndAcquireFence(cmd);
        if (!fence) { printf("FAIL  submit (%s)\n", SDL_GetError()); SDL_ReleaseGPUTransferBuffer(dev, tb); break; }
        SDL_WaitForGPUFences(dev, true, &fence, 1);
        SDL_ReleaseGPUFence(dev, fence);
        void *mapped = SDL_MapGPUTransferBuffer(dev, tb, false);
        if (!mapped) { printf("FAIL  map (%s)\n", SDL_GetError()); SDL_ReleaseGPUTransferBuffer(dev, tb); break; }
        char path[256]; SDL_snprintf(path, sizeof(path), "depth_%s_clear%.2f.bin", name, clears[i]);
        FILE *f = fopen(path, "wb"); if (f) { fwrite(mapped, 1, needed, f); fclose(f); }
        char tag[160]; SDL_snprintf(tag, sizeof(tag), "clear=%.2f -> %s", (double)clears[i], path);
        stats(tag, (const Uint8 *)mapped, W, H, W * 4);
        SDL_UnmapGPUTransferBuffer(dev, tb);
        SDL_ReleaseGPUTransferBuffer(dev, tb);
        any = true;
    }
    SDL_ReleaseGPUTexture(dev, depth);
    return any;
}

int main(void) {
    bool ok = SDL_Init(SDL_INIT_VIDEO);
    printf("init video: %s %s\n", ok ? "ok" : "FAIL", ok ? "" : SDL_GetError());
    if (!ok) return 1;
    printf("video driver: %s\n", SDL_GetCurrentVideoDriver());

    SDL_Window *win = SDL_CreateWindow("poc1-depth-probe", 320, 240, 0);
    CHECK(win != NULL, "create window");
    if (!win) return 1;

    SDL_GPUDevice *dev = SDL_CreateGPUDevice(
        SDL_GPU_SHADERFORMAT_SPIRV | SDL_GPU_SHADERFORMAT_DXIL | SDL_GPU_SHADERFORMAT_MSL, true, NULL);
    CHECK(dev != NULL, "create GPU device");
    if (!dev) return 1;
    printf("gpu driver: %s\n", SDL_GetGPUDeviceDriver(dev));
    CHECK(SDL_ClaimWindowForGPUDevice(dev, win), "claim window for GPU device");

    run_depth_case(dev, win, SDL_GPU_TEXTUREFORMAT_D24_UNORM_S8_UINT, "d24s8");
    printf("\n=== blit depth -> rgba8 attempt ===\n");
    run_depth_case(dev, win, SDL_GPU_TEXTUREFORMAT_D32_FLOAT, "d32f");

    SDL_ReleaseWindowFromGPUDevice(dev, win);
    SDL_DestroyGPUDevice(dev);
    SDL_DestroyWindow(win);
    SDL_Quit();
    printf("\n%d failure(s)\n", failures);
    return failures ? 2 : 0;
}
