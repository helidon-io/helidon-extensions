/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.brotli;

/**
 * Node in a Huffman tree.
 */
class HuffmanNode {
    private long totalCount;
    private int indexLeft;
    private int indexRightOrValue;

    HuffmanNode() {
        this(-1, 0, 0);
    }

    HuffmanNode(int indexLeft, int indexRightOrValue, long totalCount) {
        this.indexLeft = indexLeft;
        this.indexRightOrValue = indexRightOrValue;
        this.totalCount = totalCount;
    }

    void initNode(long size, int right) {
        totalCount(size);
        indexLeft(-1);
        indexRightOrValue(right);
    }

    long totalCount() {
        return this.totalCount;
    }

    void totalCount(long size) {
        this.totalCount = size;
    }

    int indexLeft() {
        return this.indexLeft;
    }

    void indexLeft(int left) {
        this.indexLeft = left;
    }

    int indexRightOrValue() {
        return this.indexRightOrValue;
    }

    void indexRightOrValue(int right) {
        this.indexRightOrValue = right;
    }
}
