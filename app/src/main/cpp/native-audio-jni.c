/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

#include <assert.h>
#include <jni.h>
#include <string.h>
#include <semaphore.h>
#include <pthread.h>

#include <android/log.h>

// for native audio
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

// engine interfaces
static SLObjectItf engineObject = NULL;
static SLEngineItf engineEngine;

// output mix interfaces
static SLObjectItf outputMixObject = NULL;

// buffer queue player interfaces
static SLObjectItf bqPlayerObject = NULL;
static SLPlayItf bqPlayerPlay;
static SLAndroidSimpleBufferQueueItf bqPlayerBufferQueue;
static SLVolumeItf bqPlayerVolume;

static jboolean isCopy;

static sem_t waiter;
static uint8_t mBufCount;
static int mBufSize;
static uint8_t *buf;
static uint8_t lastIndex = UINT8_MAX;

void Java_amirz_pcaudio_MainActivity_playAudio(JNIEnv* env, jclass clazz, jfloatArray data, size_t count) {
    SLresult result;
    int index = (lastIndex + 1) % mBufCount;
    uint8_t *useBuf = buf + index * mBufSize;

    jfloat *floats = (*env)->GetFloatArrayElements(env, data, &isCopy);
    memcpy(useBuf, floats, count);

    // Only enqueue when buffer slot is free
    if (sem_trywait(&waiter) == 0) {
        result = (*bqPlayerBufferQueue)->Enqueue(bqPlayerBufferQueue, useBuf, count);
        assert(SL_RESULT_SUCCESS == result);
        lastIndex = index;
    } else {
        // No place free, drop buffer
        __android_log_print(ANDROID_LOG_DEBUG, "native-audio-jni", "frame drop on buffer #%i", index);
    }

    (*env)->ReleaseFloatArrayElements(env, data, floats, JNI_ABORT);
}

void bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void *context) {
    // Release one lock
    sem_post(&waiter);
}

// create buffer queue audio player
void Java_amirz_pcaudio_MainActivity_start(JNIEnv* env, jclass clazz, int sampleRate, int bufCount, int bufSize) {
    SLresult result;

    // create engine
    result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);
    assert(SL_RESULT_SUCCESS == result);

    // realize the engine
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);

    // get the engine interface, which is needed in order to create other objects
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    assert(SL_RESULT_SUCCESS == result);

    // create output mix, with environmental reverb specified as a non-required interface
    const SLInterfaceID ids[0] = {};
    const SLboolean req[0] = {};
    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, ids, req);
    assert(SL_RESULT_SUCCESS == result);

    // realize the output mix
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);

    // configure audio source
    SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, bufCount};

    SLAndroidDataFormat_PCM_EX format_pcm;
    format_pcm.formatType = SL_ANDROID_DATAFORMAT_PCM_EX;
    format_pcm.numChannels = 2;
    format_pcm.sampleRate = (SLuint32) sampleRate * 1000;
    format_pcm.bitsPerSample = 32;
    format_pcm.containerSize = 32;
    format_pcm.channelMask = SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT;
    format_pcm.endianness = SL_BYTEORDER_LITTLEENDIAN;
    format_pcm.representation = SL_ANDROID_PCM_REPRESENTATION_FLOAT;

    SLDataSource audioSrc = {&loc_bufq, &format_pcm};

    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, outputMixObject};
    SLDataSink audioSnk = {&loc_outmix, NULL};

    const SLInterfaceID ids2[3] = {SL_IID_BUFFERQUEUE, SL_IID_VOLUME};
    const SLboolean req2[3] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};

    result = (*engineEngine)->CreateAudioPlayer(engineEngine, &bqPlayerObject, &audioSrc, &audioSnk, 2, ids2, req2);
    assert(SL_RESULT_SUCCESS == result);

    // realize the player
    result = (*bqPlayerObject)->Realize(bqPlayerObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);

    // get the play interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_PLAY, &bqPlayerPlay);
    assert(SL_RESULT_SUCCESS == result);

    // get the buffer queue interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_BUFFERQUEUE, &bqPlayerBufferQueue);
    assert(SL_RESULT_SUCCESS == result);

    // register callback on the buffer queue
    result = (*bqPlayerBufferQueue)->RegisterCallback(bqPlayerBufferQueue, bqPlayerCallback, NULL);
    assert(SL_RESULT_SUCCESS == result);

    // get the volume interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_VOLUME, &bqPlayerVolume);
    assert(SL_RESULT_SUCCESS == result);

    // set the player's state to playing
    result = (*bqPlayerPlay)->SetPlayState(bqPlayerPlay, SL_PLAYSTATE_PLAYING);
    assert(SL_RESULT_SUCCESS == result);

    sem_init(&waiter, 0, bufCount);

    buf = malloc(bufCount * bufSize);
    mBufCount = bufCount;
    mBufSize = bufSize;

    // set top priority for this thread
    struct sched_param param;
    int policy = 0;
    pthread_attr_t attr;

    pthread_t thId = pthread_self();
    pthread_attr_init(&attr);
    pthread_attr_getschedpolicy(&attr, &policy);
    pthread_attr_getschedparam(&attr, &param);
    param.sched_priority = sched_get_priority_max(policy);
    pthread_setschedparam(thId, policy, &param);
    pthread_attr_destroy(&attr);
}

// shut down the native audio system
void Java_amirz_pcaudio_MainActivity_shutdown(JNIEnv* env, jclass clazz) {
    sem_destroy(&waiter);
    free(buf);

    // destroy buffer queue audio player object, and invalidate all associated interfaces
    if (bqPlayerObject != NULL) {
        (*bqPlayerObject)->Destroy(bqPlayerObject);
        bqPlayerObject = NULL;
        bqPlayerPlay = NULL;
        bqPlayerBufferQueue = NULL;
        bqPlayerVolume = NULL;
    }

    // destroy output mix object, and invalidate all associated interfaces
    if (outputMixObject != NULL) {
        (*outputMixObject)->Destroy(outputMixObject);
        outputMixObject = NULL;
    }

    // destroy engine object, and invalidate all associated interfaces
    if (engineObject != NULL) {
        (*engineObject)->Destroy(engineObject);
        engineObject = NULL;
        engineEngine = NULL;
    }
}