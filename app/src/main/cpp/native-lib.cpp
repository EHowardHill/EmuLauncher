#include <jni.h>
#include <string>
#include <android/log.h>
#include <dlfcn.h>
#include <vector>
#include <cstring>
#include <android/bitmap.h>
#include "libretro.h"

#define TAG "EmuLauncher"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#define RETRO_ENVIRONMENT_GET_PIXEL_FORMAT 10

// --- Globals ---
void *coreHandle = nullptr;
uint16_t g_joypad_mask = 0;

// Video buffer setup
static uint16_t *g_video_buffer = nullptr;
static unsigned g_video_width = 0;
static unsigned g_video_height = 0;

// Audio buffer setup
static std::vector<int16_t> g_audio_buffer;

// --- Function Pointers ---
void (*ptr_retro_init)(void);
void (*ptr_retro_deinit)(void);
bool (*ptr_retro_load_game)(struct retro_game_info *info);
void (*ptr_retro_unload_game)(void);
void (*ptr_retro_run)(void);
void (*ptr_retro_set_environment)(retro_environment_t);
void (*ptr_retro_set_video_refresh)(retro_video_refresh_t);
void (*ptr_retro_set_audio_sample)(retro_audio_sample_t);
void (*ptr_retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
void (*ptr_retro_set_input_poll)(retro_input_poll_t);
void (*ptr_retro_set_input_state)(retro_input_state_t);
// FIXED: Added this missing declaration
void (*ptr_retro_get_system_av_info)(struct retro_system_av_info *info);

// --- Callbacks ---

void video_refresh_cb(const void *data, unsigned width, unsigned height, size_t pitch)
{
    if (!data) return;

    if (width != g_video_width || height != g_video_height)
    {
        g_video_width = width;
        g_video_height = height;
        if (g_video_buffer) free(g_video_buffer);
        // RGB565 is 2 bytes per pixel
        g_video_buffer = (uint16_t *)malloc(height * width * sizeof(uint16_t));
    }

    const uint8_t *src = (const uint8_t *)data;
    uint16_t *dst = g_video_buffer;

    // Pitch is in bytes, width is in pixels
    for (unsigned y = 0; y < height; y++)
    {
        memcpy(dst, src, width * sizeof(uint16_t));
        src += pitch;
        dst += width;
    }
}

void audio_sample_cb(int16_t left, int16_t right)
{
    g_audio_buffer.push_back(left);
    g_audio_buffer.push_back(right);
}

size_t audio_sample_batch_cb(const int16_t *data, size_t frames)
{
    g_audio_buffer.insert(g_audio_buffer.end(), data, data + (frames * 2));
    return frames;
}

void input_poll_cb() { /* No-op */ }

int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id)
{
    if (port == 0 && device == RETRO_DEVICE_JOYPAD)
    {
        if (g_joypad_mask & (1 << id)) return 1;
    }
    return 0;
}

bool environment_cb(unsigned cmd, void *data)
{
    if (cmd == RETRO_ENVIRONMENT_GET_PIXEL_FORMAT)
    {
        enum retro_pixel_format *fmt = (enum retro_pixel_format *)data;
        *fmt = RETRO_PIXEL_FORMAT_RGB565;
        return true;
    }
    return false;
}

// --- JNI Implementation (Updated to EmulatorActivity) ---

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cinemint_emulauncher_EmulatorActivity_loadCore(JNIEnv *env, jobject /* this */, jstring corePath)
{
    const char *path = env->GetStringUTFChars(corePath, 0);

    LOGD("Loading Core: %s", path);
    coreHandle = dlopen(path, RTLD_LAZY);

    if (!coreHandle)
    {
        LOGD("Failed to load core: %s", dlerror());
        env->ReleaseStringUTFChars(corePath, path);
        return false;
    }

    // FIXED: dlsym ONLY after dlopen
    ptr_retro_init = (void (*)(void))dlsym(coreHandle, "retro_init");
    ptr_retro_deinit = (void (*)(void))dlsym(coreHandle, "retro_deinit");
    ptr_retro_load_game = (bool (*)(struct retro_game_info *))dlsym(coreHandle, "retro_load_game");
    ptr_retro_unload_game = (void (*)(void))dlsym(coreHandle, "retro_unload_game");
    ptr_retro_run = (void (*)(void))dlsym(coreHandle, "retro_run");
    ptr_retro_set_environment = (void (*)(retro_environment_t))dlsym(coreHandle, "retro_set_environment");
    ptr_retro_set_video_refresh = (void (*)(retro_video_refresh_t))dlsym(coreHandle, "retro_set_video_refresh");
    ptr_retro_set_audio_sample = (void (*)(retro_audio_sample_t))dlsym(coreHandle, "retro_set_audio_sample");
    ptr_retro_set_audio_sample_batch = (void (*)(retro_audio_sample_batch_t))dlsym(coreHandle, "retro_set_audio_sample_batch");
    ptr_retro_set_input_poll = (void (*)(retro_input_poll_t))dlsym(coreHandle, "retro_set_input_poll");
    ptr_retro_set_input_state = (void (*)(retro_input_state_t))dlsym(coreHandle, "retro_set_input_state");
    ptr_retro_get_system_av_info = (void (*)(struct retro_system_av_info *))dlsym(coreHandle, "retro_get_system_av_info");

    // Setup Callbacks
    if (ptr_retro_set_environment) ptr_retro_set_environment(environment_cb);
    if (ptr_retro_set_video_refresh) ptr_retro_set_video_refresh(video_refresh_cb);
    if (ptr_retro_set_audio_sample) ptr_retro_set_audio_sample(audio_sample_cb);
    if (ptr_retro_set_audio_sample_batch) ptr_retro_set_audio_sample_batch(audio_sample_batch_cb);
    if (ptr_retro_set_input_poll) ptr_retro_set_input_poll(input_poll_cb);
    if (ptr_retro_set_input_state) ptr_retro_set_input_state(input_state_cb);

    if (ptr_retro_init) ptr_retro_init();

    g_audio_buffer.reserve(4096);

    env->ReleaseStringUTFChars(corePath, path);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cinemint_emulauncher_EmulatorActivity_loadGame(JNIEnv *env, jobject /* this */, jstring gamePath)
{
    const char *path = env->GetStringUTFChars(gamePath, 0);
    struct retro_game_info game = {0};
    game.path = path;

    bool success = false;
    if (ptr_retro_load_game)
    {
        success = ptr_retro_load_game(&game);
    }

    env->ReleaseStringUTFChars(gamePath, path);
    return success;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cinemint_emulauncher_EmulatorActivity_setInputState(JNIEnv *env, jobject /* this */, jint buttonId, jboolean pressed)
{
    if (pressed)
        g_joypad_mask |= (1 << buttonId);
    else
        g_joypad_mask &= ~(1 << buttonId);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cinemint_emulauncher_EmulatorActivity_runFrame(JNIEnv *env, jobject /* this */, jobject bitmap, jshortArray audioArray)
{
    if (!ptr_retro_run) return 0;

    // 1. Run Emulation
    ptr_retro_run();

    // 2. Video Handling
    if (g_video_buffer)
    {
        void *bitmapPixels;
        // Lock the Android Bitmap to write C++ pixels to it
        if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) >= 0)
        {
            memcpy(bitmapPixels, g_video_buffer, g_video_width * g_video_height * 2);
            AndroidBitmap_unlockPixels(env, bitmap);
        }
    }

    // 3. Audio Handling
    size_t samples = g_audio_buffer.size();
    if (samples > 0)
    {
        jsize arrayLen = env->GetArrayLength(audioArray);
        if (samples > arrayLen) samples = arrayLen;

        env->SetShortArrayRegion(audioArray, 0, samples, g_audio_buffer.data());
        g_audio_buffer.clear();
        return (jint)samples;
    }

    return 0;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_cinemint_emulauncher_EmulatorActivity_getCoreSampleRate(JNIEnv *env, jobject /* this */)
{
    if (ptr_retro_get_system_av_info)
    {
        struct retro_system_av_info av_info = {0};
        ptr_retro_get_system_av_info(&av_info);
        return av_info.timing.sample_rate;
    }
    return 44100.0;
}