/*
 * Copyright 2017-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.camunda.zeebe.journal.file;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Sets;
import io.camunda.zeebe.journal.JournalException;
import io.camunda.zeebe.util.FileUtil;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.util.Set;
import org.agrona.IoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Log segment.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class JournalSegment implements AutoCloseable {

  private static final ByteOrder ENDIANNESS = ByteOrder.LITTLE_ENDIAN;
  private static final Logger LOG = LoggerFactory.getLogger(JournalSegment.class);

  private final JournalSegmentFile file;
  private final JournalSegmentDescriptor descriptor;
  private final JournalIndex index;
  private final MappedJournalSegmentWriter writer;
  private final Set<MappedJournalSegmentReader> readers = Sets.newConcurrentHashSet();
  private boolean open = true;
  private final MappedByteBuffer buffer;
  private boolean markedForDeletion = false;

  public JournalSegment(
      final JournalSegmentFile file,
      final JournalSegmentDescriptor descriptor,
      final MappedByteBuffer buffer,
      final long maxWrittenIndex,
      final JournalIndex index) {
    this.file = file;
    this.descriptor = descriptor;
    this.buffer = buffer;
    this.index = index;

    writer = createWriter(maxWrittenIndex);
  }

  /**
   * Returns the segment ID.
   *
   * @return The segment ID.
   */
  public long id() {
    return descriptor.id();
  }

  /**
   * Returns the segment's starting index.
   *
   * @return The segment's starting index.
   */
  public long index() {
    return descriptor.index();
  }

  /**
   * Returns the last index in the segment.
   *
   * @return The last index in the segment.
   */
  public long lastIndex() {
    return writer.getLastIndex();
  }

  /**
   * Returns the segment file.
   *
   * @return The segment file.
   */
  public JournalSegmentFile file() {
    return file;
  }

  /**
   * Returns the segment descriptor.
   *
   * @return The segment descriptor.
   */
  public JournalSegmentDescriptor descriptor() {
    return descriptor;
  }

  /**
   * Returns a boolean value indicating whether the segment is empty.
   *
   * @return Indicates whether the segment is empty.
   */
  public boolean isEmpty() {
    return length() == 0;
  }

  /**
   * Returns the segment length.
   *
   * @return The segment length.
   */
  public long length() {
    return writer.getNextIndex() - index();
  }

  /**
   * Returns the segment writer.
   *
   * @return The segment writer.
   */
  public MappedJournalSegmentWriter writer() {
    checkOpen();
    return writer;
  }

  /**
   * Creates a new segment reader.
   *
   * @return A new segment reader.
   */
  MappedJournalSegmentReader createReader() {
    checkOpen();
    return new MappedJournalSegmentReader(
        buffer.asReadOnlyBuffer().position(0).order(ENDIANNESS), this, index);
  }

  private MappedJournalSegmentWriter createWriter(final long lastWrittenIndex) {
    return new MappedJournalSegmentWriter(buffer, this, index, lastWrittenIndex);
  }

  /**
   * Removes the reader from this segment.
   *
   * @param reader the closed reader
   */
  void onReaderClosed(final MappedJournalSegmentReader reader) {
    readers.remove(reader);
    if (markedForDeletion && readers.isEmpty()) {
      safeDelete();
    }
  }

  /** Checks whether the segment is open. */
  private void checkOpen() {
    checkState(open, "Segment not open");
  }

  /**
   * Returns a boolean indicating whether the segment is open.
   *
   * @return indicates whether the segment is open
   */
  public boolean isOpen() {
    return open;
  }

  /** Closes the segment. */
  @Override
  public void close() {
    open = false;
    readers.forEach(MappedJournalSegmentReader::close);
    IoUtil.unmap(buffer);
  }

  /** Deletes the segment. */
  public void delete() {
    open = false;
    markForDeletion();
  }

  private void safeDelete() {
    if (!readers.isEmpty()) {
      throw new JournalException(
          String.format(
              "Cannot delete segment file. There are %d readers referring to this segment.",
              readers.size()));
    }
    try {
      IoUtil.unmap(buffer);
      Files.deleteIfExists(file.getFileMarkedForDeletion());
    } catch (final IOException e) {
      LOG.warn(
          "Could not delete segment {}. File to delete {}. This can lead to increased disk usage.",
          this,
          file.getFileMarkedForDeletion(),
          e);
    }
  }

  @Override
  public String toString() {
    return toStringHelper(this).add("id", id()).add("index", index()).toString();
  }

  private void markForDeletion() {
    if (markedForDeletion) {
      return;
    }
    writer.close();
    final var target = file.getFileMarkedForDeletion();
    try {
      FileUtil.moveDurably(file.file().toPath(), target);
    } catch (final IOException e) {
      throw new JournalException(e);
    }
    markedForDeletion = true;
  }
}
