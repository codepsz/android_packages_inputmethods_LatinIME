/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.latin;

import android.util.Log;

import com.android.inputmethod.latin.makedict.BinaryDictInputOutput;
import com.android.inputmethod.latin.makedict.BinaryDictInputOutput.FusionDictionaryBufferInterface;
import com.android.inputmethod.latin.makedict.FormatSpec.FormatOptions;
import com.android.inputmethod.latin.makedict.FusionDictionary;
import com.android.inputmethod.latin.makedict.FusionDictionary.Node;
import com.android.inputmethod.latin.makedict.PendingAttribute;
import com.android.inputmethod.latin.makedict.UnsupportedFormatException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads and writes Binary files for a UserHistoryDictionary.
 *
 * All the methods in this class are static.
 */
public class UserHistoryDictIOUtils {
    private static final String TAG = UserHistoryDictIOUtils.class.getSimpleName();
    private static final boolean DEBUG = false;

    public interface OnAddWordListener {
        public void setUnigram(final String word, final String shortcutTarget, final int frequency);
        public void setBigram(final String word1, final String word2, final int frequency);
    }

    public interface BigramDictionaryInterface {
        public int getFrequency(final String word1, final String word2);
    }

    public static final class ByteArrayWrapper implements FusionDictionaryBufferInterface {
        private byte[] mBuffer;
        private int mPosition;

        public ByteArrayWrapper(final byte[] buffer) {
            mBuffer = buffer;
            mPosition = 0;
        }

        @Override
        public int readUnsignedByte() {
            return ((int)mBuffer[mPosition++]) & 0xFF;
        }

        @Override
        public int readUnsignedShort() {
            final int retval = readUnsignedByte();
            return (retval << 8) + readUnsignedByte();
        }

        @Override
        public int readUnsignedInt24() {
            final int retval = readUnsignedShort();
            return (retval << 8) + readUnsignedByte();
        }

        @Override
        public int readInt() {
            final int retval = readUnsignedShort();
            return (retval << 16) + readUnsignedShort();
        }

        @Override
        public int position() {
            return mPosition;
        }

        @Override
        public void position(int position) {
            mPosition = position;
        }

        @Override
        public void put(final byte b) {
            mBuffer[mPosition++] = b;
        }

        @Override
        public int limit() {
            return mBuffer.length;
        }
    }

    /**
     * Writes dictionary to file.
     */
    public static void writeDictionaryBinary(final OutputStream destination,
            final BigramDictionaryInterface dict, final UserHistoryDictionaryBigramList bigrams,
            final FormatOptions formatOptions) {

        final FusionDictionary fusionDict = constructFusionDictionary(dict, bigrams);

        try {
            BinaryDictInputOutput.writeDictionaryBinary(destination, fusionDict, formatOptions);
        } catch (IOException e) {
            Log.e(TAG, "IO exception while writing file: " + e);
        } catch (UnsupportedFormatException e) {
            Log.e(TAG, "Unsupported fomat: " + e);
        }
    }

    /**
     * Constructs a new FusionDictionary from BigramDictionaryInterface.
     */
    /* packages for test */ static FusionDictionary constructFusionDictionary(
            final BigramDictionaryInterface dict, final UserHistoryDictionaryBigramList bigrams) {

        final FusionDictionary fusionDict = new FusionDictionary(new Node(),
                new FusionDictionary.DictionaryOptions(
                        new HashMap<String,String>(), false, false));

        for (final String word1 : bigrams.keySet()) {
            final HashMap<String, Byte> word1Bigrams = bigrams.getBigrams(word1);
            for (final String word2 : word1Bigrams.keySet()) {
                final int freq = dict.getFrequency(word1, word2);

                if (DEBUG) {
                    if (word1 == null) {
                        Log.d(TAG, "add unigram: " + word2 + "," + Integer.toString(freq));
                    } else {
                        Log.d(TAG, "add bigram: " + word1
                                + "," + word2 + "," + Integer.toString(freq));
                    }
                }

                if (word1 == null) { // unigram
                    fusionDict.add(word2, freq, null, false /* isNotAWord */);
                } else { // bigram
                    fusionDict.setBigram(word1, word2, freq);
                }
                bigrams.updateBigram(word1, word2, (byte)freq);
            }
        }

        return fusionDict;
    }

    /**
     * Reads dictionary from file.
     */
    public static void readDictionaryBinary(final FusionDictionaryBufferInterface buffer,
            final OnAddWordListener dict) {
        final Map<Integer, String> unigrams = CollectionUtils.newTreeMap();
        final Map<Integer, Integer> frequencies = CollectionUtils.newTreeMap();
        final Map<Integer, ArrayList<PendingAttribute>> bigrams = CollectionUtils.newTreeMap();

        try {
            BinaryDictInputOutput.readUnigramsAndBigramsBinary(buffer, unigrams, frequencies,
                    bigrams);
            addWordsFromWordMap(unigrams, frequencies, bigrams, dict);
        } catch (IOException e) {
            Log.e(TAG, "IO exception while reading file: " + e);
        } catch (UnsupportedFormatException e) {
            Log.e(TAG, "Unsupported format: " + e);
        }
    }

    /**
     * Adds all unigrams and bigrams in maps to OnAddWordListener.
     */
    /* package for test */ static void addWordsFromWordMap(final Map<Integer, String> unigrams,
            final Map<Integer, Integer> frequencies,
            final Map<Integer, ArrayList<PendingAttribute>> bigrams, final OnAddWordListener to) {

        for (Map.Entry<Integer, String> entry : unigrams.entrySet()) {
            final String word1 = entry.getValue();
            final int unigramFrequency = frequencies.get(entry.getKey());
            to.setUnigram(word1, null, unigramFrequency);

            final ArrayList<PendingAttribute> attrList = bigrams.get(entry.getKey());

            if (attrList != null) {
                for (final PendingAttribute attr : attrList) {
                    to.setBigram(word1, unigrams.get(attr.mAddress),
                            BinaryDictInputOutput.reconstructBigramFrequency(unigramFrequency,
                                    attr.mFrequency));
                }
            }
        }

    }
}