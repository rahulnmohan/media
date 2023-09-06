/*
 * Copyright (C) 2023 The Android Open Source Project
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
 */
package androidx.media3.extractor.ts;

import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static java.lang.Math.max;
import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.DtsUtil;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Parses a continuous DTS UHD byte stream and extracts individual samples. */
@UnstableApi
public final class DtsUhdReader implements ElementaryStreamReader {

  private static final int STATE_FINDING_SYNC = 0;
  private static final int STATE_FINDING_HEADER_SIZE = 1;
  private static final int STATE_READING_HEADER = 2;
  private static final int STATE_READING_SAMPLE = 3;

  /**
   * Maximum size of DTS UHD(DTS:X) frame header, in bytes. See ETSI TS 103 491 V1.2.1 (2019-05)
   * section 6.4.4.3.
   */
  private static final int FTOC_MAX_HEADER_SIZE = 5408;

  /**
   * Minimum possible size of DTS UHD(DTS:X) frame header, in bytes, that is required to parse and
   * extract header size information.
   */
  private static final int FTOC_MIN_HEADER_SIZE = 7;

  private final ParsableByteArray headerScratchBytes;
  @Nullable private final String language;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;

  private int state;
  private int bytesRead;

  /** Used to find the header. */
  private int syncBytes;

  // Used when parsing the header.
  private long sampleDurationUs;
  private @MonotonicNonNull Format format;
  private int sampleSize;
  private boolean isFtocSync;
  private int headerSizeToRead;

  // Used when reading the samples.
  private long timeUs;
  private int sampleRate;
  private int sampleCount; // frame duration

  /**
   * Constructs a new reader for DTS UHD elementary streams.
   *
   * @param language Track language.
   */
  public DtsUhdReader(@Nullable String language) {
    headerScratchBytes = new ParsableByteArray(new byte[FTOC_MAX_HEADER_SIZE]);
    state = STATE_FINDING_SYNC;
    timeUs = C.TIME_UNSET;
    sampleRate = 48000; // initialize to a non-zero sampling rate
    this.language = language;
  }

  @Override
  public void seek() {
    state = STATE_FINDING_SYNC;
    bytesRead = 0;
    syncBytes = 0;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if (pesTimeUs != C.TIME_UNSET) {
      timeUs = pesTimeUs;
    }
  }

  @Override
  public void consume(ParsableByteArray data) throws ParserException {
    checkStateNotNull(output); // Asserts that createTracks has been called.
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_FINDING_SYNC:
          if (skipToNextSync(data)) {
            state = STATE_FINDING_HEADER_SIZE;
          }
          break;
        case STATE_FINDING_HEADER_SIZE:
          // Read enough bytes to parse the header size information.
          if (continueRead(data, headerScratchBytes.getData(), FTOC_MIN_HEADER_SIZE)) {
            headerSizeToRead = DtsUtil.parseDtsUhdHeaderSize(headerScratchBytes.getData());
            // Already read more data than the actual header size. Setting target read length equal
            // to bytesRead.
            headerSizeToRead = max(bytesRead, headerSizeToRead);
            state = STATE_READING_HEADER;
          }
          break;
        case STATE_READING_HEADER:
          if (continueRead(data, headerScratchBytes.getData(), headerSizeToRead)) {
            parseHeader();
            headerScratchBytes.setPosition(0);
            output.sampleData(headerScratchBytes, headerSizeToRead);
            state = STATE_READING_SAMPLE;
          }
          break;
        case STATE_READING_SAMPLE:
          int bytesToRead = min(data.bytesLeft(), sampleSize - bytesRead);
          output.sampleData(data, bytesToRead);
          bytesRead += bytesToRead;
          if (bytesRead == sampleSize) {
            if (timeUs != C.TIME_UNSET) {
              output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null);
              timeUs += sampleDurationUs;
            }
            state = STATE_FINDING_SYNC;
          }
          break;
        default:
          // Never happens.
          throw new IllegalStateException();
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    // Do nothing.
  }

  /**
   * Continues a read from the provided {@code source} into a given {@code target}. It's assumed
   * that the data should be written into {@code target} starting from an offset of zero.
   *
   * @param source The source from which to read.
   * @param target The target into which data is to be read.
   * @param targetLength The target length of the read.
   * @return Whether the target length was reached.
   */
  private boolean continueRead(ParsableByteArray source, byte[] target, int targetLength) {
    int bytesToRead = min(source.bytesLeft(), targetLength - bytesRead);
    source.readBytes(target, bytesRead, bytesToRead);
    bytesRead += bytesToRead;
    return bytesRead == targetLength;
  }

  /**
   * Locates the next SYNC value in the buffer, advancing the position to the byte that immediately
   * follows it. If SYNC was not located, the position is advanced to the limit.
   *
   * @param pesBuffer The buffer whose position should be advanced.
   * @return Whether SYNC was found.
   */
  private boolean skipToNextSync(ParsableByteArray pesBuffer) {
    while (pesBuffer.bytesLeft() > 0) {
      syncBytes <<= 8;
      syncBytes |= pesBuffer.readUnsignedByte();
      isFtocSync = DtsUtil.isUhdFtocSyncWord(syncBytes);
      if (isFtocSync || DtsUtil.isUhdFtocNonSyncWord(syncBytes)) {
        byte[] headerData = headerScratchBytes.getData();
        headerData[0] = (byte) ((syncBytes >> 24) & 0xFF);
        headerData[1] = (byte) ((syncBytes >> 16) & 0xFF);
        headerData[2] = (byte) ((syncBytes >> 8) & 0xFF);
        headerData[3] = (byte) (syncBytes & 0xFF);
        bytesRead = 4;
        syncBytes = 0;
        return true;
      }
    }
    return false;
  }

  /** Parses the sample header. */
  @RequiresNonNull({"output"})
  private void parseHeader() throws ParserException {
    DtsUtil.DtsAudioFormat dtsAudioFormat = DtsUtil.parseDtsUhdFormat(headerScratchBytes.getData());
    if (isFtocSync) { // Format updates will happen only in FTOC sync frames.
      if (format == null
          || dtsAudioFormat.channelCount != format.channelCount
          || dtsAudioFormat.sampleRate != format.sampleRate
          || !Util.areEqual(dtsAudioFormat.mimeType, format.sampleMimeType)) {
        format =
            new Format.Builder()
                .setId(formatId)
                .setSampleMimeType(dtsAudioFormat.mimeType)
                .setChannelCount(dtsAudioFormat.channelCount)
                .setSampleRate(dtsAudioFormat.sampleRate)
                .setLanguage(language)
                .build();
        output.format(format);
      }
      // Update the sample rate and sample count information only in FTOC Sync frame.
      sampleRate = dtsAudioFormat.sampleRate;
      sampleCount = dtsAudioFormat.sampleCount; // Update the sample count only in FTOC Sync frame
    }
    sampleSize = dtsAudioFormat.frameSize;
    // In this class a sample is an access unit (frame in DTS), but the format's sample rate
    // specifies the number of PCM audio samples per second.
    sampleDurationUs =
        Ints.checkedCast(Util.scaleLargeTimestamp(C.MICROS_PER_SECOND, sampleCount, sampleRate));
  }
}
