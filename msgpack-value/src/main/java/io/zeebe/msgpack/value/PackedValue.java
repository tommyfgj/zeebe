/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.msgpack.value;

import io.zeebe.msgpack.spec.MsgPackReader;
import io.zeebe.msgpack.spec.MsgPackWriter;
import java.util.Objects;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public class PackedValue extends BaseValue {
  private final DirectBuffer buffer = new UnsafeBuffer(0, 0);
  private int length;

  public PackedValue() {}

  public PackedValue(DirectBuffer defaultValue, int offset, int length) {
    wrap(defaultValue, offset, length);
  }

  public void wrap(DirectBuffer buff, int offset, int length) {
    this.buffer.wrap(buff, offset, length);
    this.length = length;
  }

  public DirectBuffer getValue() {
    return buffer;
  }

  @Override
  public void reset() {
    buffer.wrap(0, 0);
    length = 0;
  }

  @Override
  public void writeJSON(StringBuilder builder) {
    builder.append("[packed value (length=");
    builder.append(length);
    builder.append(")]");
  }

  @Override
  public void write(MsgPackWriter writer) {
    writer.writeRaw(buffer);
  }

  @Override
  public void read(MsgPackReader reader) {
    final DirectBuffer buffer = reader.getBuffer();
    final int offset = reader.getOffset();
    reader.skipValue();
    final int lenght = reader.getOffset() - offset;

    wrap(buffer, offset, lenght);
  }

  @Override
  public int getEncodedLength() {
    return length;
  }

  @Override
  public int hashCode() {
    return Objects.hash(buffer, length);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PackedValue)) {
      return false;
    }

    final PackedValue that = (PackedValue) o;
    return length == that.length && Objects.equals(buffer, that.buffer);
  }
}
