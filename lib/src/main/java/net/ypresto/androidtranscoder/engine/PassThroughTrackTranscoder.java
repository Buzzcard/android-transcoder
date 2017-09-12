/*
 * Copyright (C) 2014 Yuya Tanaka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ypresto.androidtranscoder.engine;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PassThroughTrackTranscoder implements TrackTranscoder {
    private final MediaExtractor mExtractor;
    private final int mTrackIndex;
    private final QueuedMuxer mMuxer;
    private final QueuedMuxer.SampleType mSampleType;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private int mBufferSize;
    private ByteBuffer mBuffer;
    private boolean mHasWrittenEOS;
    private boolean mIsInputEOS;
    private MediaFormat mActualOutputFormat;
    private long mWrittenPresentationTimeUs;
    private long mMaxVideoDuration;

    public PassThroughTrackTranscoder(MediaExtractor extractor, int trackIndex,
                                      QueuedMuxer muxer, QueuedMuxer.SampleType sampleType, long maxVideoDuration) {
        mExtractor = extractor;
        mTrackIndex = trackIndex;
        mMuxer = muxer;
        mSampleType = sampleType;
        mMaxVideoDuration = maxVideoDuration;

        if (trackIndex < 0) {
            // Exclude the track.
            mMuxer.setOutputFormat(mSampleType, null);
            mIsInputEOS = true;
            mWrittenPresentationTimeUs = 0;
            return;
        }
        mActualOutputFormat = mExtractor.getTrackFormat(mTrackIndex);
        mMuxer.setOutputFormat(mSampleType, mActualOutputFormat);
        mBufferSize = mActualOutputFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        mBuffer = ByteBuffer.allocateDirect(mBufferSize).order(ByteOrder.nativeOrder());
    }

    @Override
    public void setup() {
    }

    @Override
    public MediaFormat getDeterminedFormat() {
        return mActualOutputFormat;
    }

    @SuppressLint("Assert")
    @Override
    public boolean stepPipeline() {
        if (mIsInputEOS) return false;

        int trackIndex = mExtractor.getSampleTrackIndex();

        boolean hasReachedMaxVideoDuration = mMaxVideoDuration > 0 && mWrittenPresentationTimeUs > mMaxVideoDuration;
        if (trackIndex < 0) mIsInputEOS = true;

        if (!mHasWrittenEOS && (hasReachedMaxVideoDuration || mIsInputEOS)) {
            mBuffer.clear();

            mBufferInfo.set(0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            mMuxer.writeSampleData(mSampleType, mBuffer, mBufferInfo);
            mHasWrittenEOS = true;
            return true;
        }

        if (trackIndex != mTrackIndex) return false;

        mBuffer.clear();
        int sampleSize = mExtractor.readSampleData(mBuffer, 0);
        assert sampleSize <= mBufferSize;
        boolean isKeyFrame = (mExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
        int flags = isKeyFrame ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0;

        if (!mHasWrittenEOS) {
            mBufferInfo.set(0, sampleSize, mExtractor.getSampleTime(), flags);
            mMuxer.writeSampleData(mSampleType, mBuffer, mBufferInfo);
        }

        mWrittenPresentationTimeUs = mBufferInfo.presentationTimeUs;
        mExtractor.advance();
        return true;
    }

    @Override
    public long getWrittenPresentationTimeUs() {
        return mWrittenPresentationTimeUs;
    }

    @Override
    public boolean isFinished() {
        return mIsInputEOS || mHasWrittenEOS;
    }

    @Override
    public void release() {
    }
}