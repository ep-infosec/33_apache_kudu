// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.kudu.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.common.base.Objects;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

import org.apache.kudu.Schema;

/**
 * A Partition describes the set of rows that a Tablet is responsible for
 * serving. Each tablet is assigned a single Partition.<p>
 *
 * Partitions consist primarily of a start and end partition key. Every row with
 * a partition key that falls in a Tablet's Partition will be served by that
 * tablet.<p>
 *
 * In addition to the start and end partition keys, a Partition holds metadata
 * to determine if a scan can prune, or skip, a partition based on the scan's
 * start and end primary keys, and predicates.
 *
 * This class is new, and not considered stable or suitable for public use.
 */
@InterfaceAudience.LimitedPrivate("Impala")
@InterfaceStability.Unstable
public class Partition implements Comparable<Partition> {
  final byte[] partitionKeyStart;
  final byte[] partitionKeyEnd;

  final byte[] rangeKeyStart;
  final byte[] rangeKeyEnd;

  final List<Integer> hashBuckets;

  /**
   * Size of an encoded hash bucket component in a partition key.
   */
  private static final int ENCODED_BUCKET_SIZE = 4;

  /**
   * Creates a new partition with the provided start and end keys, and hash buckets.
   * @param partitionKeyStart the start partition key
   * @param partitionKeyEnd the end partition key
   * @param hashBuckets the partition hash buckets
   */
  public Partition(byte[] partitionKeyStart,
            byte[] partitionKeyEnd,
            List<Integer> hashBuckets) {
    this.partitionKeyStart = partitionKeyStart;
    this.partitionKeyEnd = partitionKeyEnd;
    this.hashBuckets = hashBuckets;
    this.rangeKeyStart = rangeKey(partitionKeyStart, hashBuckets.size());
    this.rangeKeyEnd = rangeKey(partitionKeyEnd, hashBuckets.size());
  }

  /**
   * Gets the start partition key.
   * @return the start partition key
   */
  public byte[] getPartitionKeyStart() {
    return partitionKeyStart;
  }

  /**
   * Gets the end partition key.
   * @return the end partition key
   */
  public byte[] getPartitionKeyEnd() {
    return partitionKeyEnd;
  }

  /**
   * Gets the start range key.
   * @return the start range key
   */
  public byte[] getRangeKeyStart() {
    return rangeKeyStart;
  }

  /**
   * Gets the decoded start range key.
   * @return the decoded start range key
   */
  public PartialRow getDecodedRangeKeyStart(KuduTable table) {
    Schema schema = table.getSchema();
    if (rangeKeyStart.length == 0) {
      return schema.newPartialRow();
    } else {
      PartitionSchema partitionSchema = table.getPartitionSchema();
      return KeyEncoder.decodeRangePartitionKey(schema, partitionSchema, rangeKeyStart);
    }
  }

  /**
   * Gets the end range key.
   * @return the end range key
   */
  public byte[] getRangeKeyEnd() {
    return rangeKeyEnd;
  }

  /**
   * Gets the decoded end range key.
   * @return the decoded end range key
   */
  public PartialRow getDecodedRangeKeyEnd(KuduTable table) {
    Schema schema = table.getSchema();
    if (rangeKeyEnd.length == 0) {
      return schema.newPartialRow();
    } else {
      PartitionSchema partitionSchema = table.getPartitionSchema();
      return KeyEncoder.decodeRangePartitionKey(schema, partitionSchema, rangeKeyEnd);
    }
  }

  /**
   * Gets the partition hash buckets.
   * @return the partition hash buckets
   */
  public List<Integer> getHashBuckets() {
    return hashBuckets;
  }

  /**
   * @return true if the partition is the absolute end partition
   */
  public boolean isEndPartition() {
    return partitionKeyEnd.length == 0;
  }

  /**
   * Equality only holds for partitions from the same table. Partition equality only takes into
   * account the partition keys, since there is a 1 to 1 correspondence between partition keys and
   * the hash buckets and range keys.
   *
   * @return the hash code
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Partition)) {
      return false;
    }
    Partition partition = (Partition) o;
    return Arrays.equals(partitionKeyStart, partition.partitionKeyStart) &&
        Arrays.equals(partitionKeyEnd, partition.partitionKeyEnd);
  }

  /**
   * The hash code only takes into account the partition keys, since there is a 1 to 1
   * correspondence between partition keys and the hash buckets and range keys.
   *
   * @return the hash code
   */
  @Override
  public int hashCode() {
    return Objects.hashCode(Arrays.hashCode(partitionKeyStart), Arrays.hashCode(partitionKeyEnd));
  }

  /**
   * Partition comparison is only reasonable when comparing partitions from the same table, and
   * since Kudu does not yet allow partition splitting, no two distinct partitions can have the
   * same start partition key. Accordingly, partitions are compared strictly by the start partition
   * key.
   *
   * @param other the other partition of the same table
   * @return the comparison of the partitions
   */
  @Override
  public int compareTo(Partition other) {
    return Bytes.memcmp(this.partitionKeyStart, other.partitionKeyStart);
  }

  /**
   * Returns the range key portion of a partition key given the number of buckets in the partition
   * schema.
   * @param partitionKey the partition key containing the range key
   * @param numHashBuckets the number of hash bucket components of the table
   * @return the range key
   */
  private static byte[] rangeKey(byte[] partitionKey, int numHashBuckets) {
    int bucketsLen = numHashBuckets * ENCODED_BUCKET_SIZE;
    if (partitionKey.length > bucketsLen) {
      return Arrays.copyOfRange(partitionKey, bucketsLen, partitionKey.length);
    } else {
      return AsyncKuduClient.EMPTY_ARRAY;
    }
  }

  @Override
  public String toString() {
    return String.format("[%s, %s)",
                         partitionKeyStart.length == 0 ? "<start>" : Bytes.hex(partitionKeyStart),
                         partitionKeyEnd.length == 0 ? "<end>" : Bytes.hex(partitionKeyEnd));
  }

  /**
   * Formats the range partition into a string suitable for debug printing.
   *
   * @param table that this partition belongs to
   * @param showHashInfo whether to output hash schema info per range
   * @return a string containing a formatted representation of the range partition
   */
  String formatRangePartition(KuduTable table, boolean showHashInfo) {
    Schema schema = table.getSchema();
    PartitionSchema partitionSchema = table.getPartitionSchema();
    PartitionSchema.RangeSchema rangeSchema = partitionSchema.getRangeSchema();

    if (rangeSchema.getColumnIds().isEmpty()) {
      return "";
    }
    if (rangeKeyStart.length == 0 && rangeKeyEnd.length == 0) {
      return "UNBOUNDED";
    }

    List<Integer> idxs = new ArrayList<>();
    for (int id : partitionSchema.getRangeSchema().getColumnIds()) {
      idxs.add(schema.getColumnIndex(id));
    }

    int numColumns = rangeSchema.getColumnIds().size();
    StringBuilder sb = new StringBuilder();

    if (rangeKeyEnd.length == 0) {
      sb.append("VALUES >= ");
      if (numColumns > 1) {
        sb.append('(');
      }
      KeyEncoder.decodeRangePartitionKey(schema, partitionSchema, rangeKeyStart)
                .appendShortDebugString(idxs, sb);
      if (numColumns > 1) {
        sb.append(')');
      }
    } else if (rangeKeyStart.length == 0) {
      sb.append("VALUES < ");
      if (numColumns > 1) {
        sb.append('(');
      }
      KeyEncoder.decodeRangePartitionKey(schema, partitionSchema, rangeKeyEnd)
                .appendShortDebugString(idxs, sb);
      if (numColumns > 1) {
        sb.append(')');
      }
    } else {
      PartialRow lowerBound =
          KeyEncoder.decodeRangePartitionKey(schema, partitionSchema, rangeKeyStart);
      PartialRow upperBound =
          KeyEncoder.decodeRangePartitionKey(schema, partitionSchema, rangeKeyEnd);

      if (PartialRow.isIncremented(lowerBound, upperBound, idxs)) {
        sb.append("VALUE = ");
        if (numColumns > 1) {
          sb.append('(');
        }
        lowerBound.appendShortDebugString(idxs, sb);
        if (numColumns > 1) {
          sb.append(')');
        }
      } else {
        if (numColumns > 1) {
          sb.append('(');
        }
        lowerBound.appendShortDebugString(idxs, sb);
        if (numColumns > 1) {
          sb.append(')');
        }
        sb.append(" <= VALUES < ");
        if (numColumns > 1) {
          sb.append('(');
        }
        upperBound.appendShortDebugString(idxs, sb);
        if (numColumns > 1) {
          sb.append(')');
        }
      }
    }

    if (showHashInfo) {
      List<PartitionSchema.HashBucketSchema> hashSchema =
          partitionSchema.getHashSchemaForRange(rangeKeyStart);
      for (PartitionSchema.HashBucketSchema hashDimension : hashSchema) {
        sb.append(" HASH(");
        boolean firstId = true;
        for (Integer id : hashDimension.getColumnIds()) {
          if (firstId) {
            firstId = false;
          } else {
            sb.append(',');
          }
          sb.append(schema.getColumnByIndex(schema.getColumnIndex(id)).getName());
        }
        sb.append(") PARTITIONS ");
        sb.append(hashDimension.getNumBuckets());
      }
    }
    return sb.toString();
  }
}
